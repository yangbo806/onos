package org.onosproject.store.consistent.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.util.KryoNamespace;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.Leadership;
import org.onosproject.cluster.LeadershipEvent;
import org.onosproject.cluster.LeadershipEventListener;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.store.cluster.messaging.ClusterCommunicationService;
import org.onosproject.store.cluster.messaging.MessageSubject;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.ConsistentMapException;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.Versioned;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static org.onlab.util.Tools.groupedThreads;
import static org.slf4j.LoggerFactory.getLogger;
import static org.onosproject.cluster.ControllerNode.State.ACTIVE;
import static org.onosproject.cluster.ControllerNode.State.INACTIVE;

/**
 * Distributed Lock Manager implemented on top of ConsistentMap.
 * <p>
 * This implementation makes use of cluster manager's failure
 * detection capabilities to detect and purge stale locks.
 * TODO: Ensure lock safety and liveness.
 */
@Component(immediate = true, enabled = true)
@Service
public class DistributedLeadershipManager implements LeadershipService {

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterCommunicationService clusterCommunicator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected EventDeliveryService eventDispatcher;

    private static final MessageSubject LEADERSHIP_EVENT_MESSAGE_SUBJECT =
            new MessageSubject("distributed-leadership-manager-events");

    private final Logger log = getLogger(getClass());
    private ExecutorService messageHandlingExecutor;
    private ScheduledExecutorService retryLeaderLockExecutor;
    private ScheduledExecutorService deadLockDetectionExecutor;
    private ScheduledExecutorService leadershipStatusBroadcaster;

    private ConsistentMap<String, NodeId> leaderMap;
    private ConsistentMap<String, List<NodeId>> candidateMap;

    private ListenerRegistry<LeadershipEvent, LeadershipEventListener>
        listenerRegistry;
    private final Map<String, Leadership> leaderBoard = Maps.newConcurrentMap();
    private final Map<String, Leadership> candidateBoard = Maps.newConcurrentMap();
    private NodeId localNodeId;

    private Set<String> activeTopics = Sets.newConcurrentHashSet();

    private static final int ELECTION_JOIN_ATTEMPT_INTERVAL_SEC = 2;
    private static final int DELAY_BETWEEN_LEADER_LOCK_ATTEMPTS_SEC = 2;
    private static final int DEADLOCK_DETECTION_INTERVAL_SEC = 2;
    private static final int LEADERSHIP_STATUS_UPDATE_INTERVAL_SEC = 2;

    private static final int LEADER_CANDIDATE_POS = 0;

    private static final Serializer SERIALIZER = Serializer.using(
            new KryoNamespace.Builder().register(KryoNamespaces.API).build());

    @Activate
    public void activate() {
        leaderMap = storageService.<String, NodeId>consistentMapBuilder()
                .withName("onos-topic-leaders")
                .withSerializer(SERIALIZER)
                .withPartitionsDisabled().build();
        candidateMap = storageService.<String, List<NodeId>>consistentMapBuilder()
                .withName("onos-topic-candidates")
                .withSerializer(SERIALIZER)
                .withPartitionsDisabled().build();

        localNodeId = clusterService.getLocalNode().id();

        messageHandlingExecutor = Executors.newSingleThreadExecutor(
                groupedThreads("onos/store/leadership", "message-handler"));
        retryLeaderLockExecutor = Executors.newScheduledThreadPool(
                4, groupedThreads("onos/store/leadership", "election-thread-%d"));
        deadLockDetectionExecutor = Executors.newSingleThreadScheduledExecutor(
                groupedThreads("onos/store/leadership", "dead-lock-detector"));
        leadershipStatusBroadcaster = Executors.newSingleThreadScheduledExecutor(
                groupedThreads("onos/store/leadership", "peer-updater"));
        clusterCommunicator.addSubscriber(
                LEADERSHIP_EVENT_MESSAGE_SUBJECT,
                SERIALIZER::decode,
                this::onLeadershipEvent,
                messageHandlingExecutor);

        deadLockDetectionExecutor.scheduleWithFixedDelay(
                this::purgeStaleLocks, 0, DEADLOCK_DETECTION_INTERVAL_SEC, TimeUnit.SECONDS);
        leadershipStatusBroadcaster.scheduleWithFixedDelay(
                this::sendLeadershipStatus, 0, LEADERSHIP_STATUS_UPDATE_INTERVAL_SEC, TimeUnit.SECONDS);

        listenerRegistry = new ListenerRegistry<>();
        eventDispatcher.addSink(LeadershipEvent.class, listenerRegistry);

        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        leaderBoard.forEach((topic, leadership) -> {
            if (localNodeId.equals(leadership.leader())) {
                withdraw(topic);
            }
        });

        eventDispatcher.removeSink(LeadershipEvent.class);
        clusterCommunicator.removeSubscriber(LEADERSHIP_EVENT_MESSAGE_SUBJECT);

        messageHandlingExecutor.shutdown();
        retryLeaderLockExecutor.shutdown();
        deadLockDetectionExecutor.shutdown();
        leadershipStatusBroadcaster.shutdown();

        log.info("Stopped");
    }

    @Override
    public Map<String, Leadership> getLeaderBoard() {
        return ImmutableMap.copyOf(leaderBoard);
    }

    @Override
    public Map<String, List<NodeId>> getCandidates() {
        return Maps.toMap(candidateBoard.keySet(), this::getCandidates);
    }

    @Override
    public List<NodeId> getCandidates(String path) {
        Leadership current = candidateBoard.get(path);
        return current == null ? ImmutableList.of() : ImmutableList.copyOf(current.candidates());
    }

    @Override
    public NodeId getLeader(String path) {
        Leadership leadership = leaderBoard.get(path);
        return leadership != null ? leadership.leader() : null;
    }

    @Override
    public Leadership getLeadership(String path) {
        checkArgument(path != null);
        return leaderBoard.get(path);
    }

    @Override
    public Set<String> ownedTopics(NodeId nodeId) {
        checkArgument(nodeId != null);
        return leaderBoard.entrySet()
                    .stream()
                    .filter(entry -> nodeId.equals(entry.getValue().leader()))
                    .map(Entry::getKey)
                    .collect(Collectors.toSet());
    }

    @Override
    public CompletableFuture<Leadership> runForLeadership(String path) {
        log.debug("Running for leadership for topic: {}", path);
        CompletableFuture<Leadership> resultFuture = new CompletableFuture<>();
        doRunForLeadership(path, resultFuture);
        return resultFuture;
    }

    private void doRunForLeadership(String path, CompletableFuture<Leadership> future) {
        try {
            Versioned<List<NodeId>> candidates = candidateMap.get(path);
            if (candidates != null) {
                List<NodeId> candidateList = Lists.newArrayList(candidates.value());
                if (!candidateList.contains(localNodeId)) {
                    candidateList.add(localNodeId);
                    if (candidateMap.replace(path, candidates.version(), candidateList)) {
                        Versioned<List<NodeId>> newCandidates = candidateMap.get(path);
                        publish(new LeadershipEvent(
                                LeadershipEvent.Type.CANDIDATES_CHANGED,
                                new Leadership(path,
                                    newCandidates.value(),
                                    newCandidates.version(),
                                    newCandidates.creationTime())));
                    } else {
                        rerunForLeadership(path, future);
                        return;
                    }
                }
            } else {
                List<NodeId> candidateList = ImmutableList.of(localNodeId);
                if ((candidateMap.putIfAbsent(path, candidateList) == null)) {
                    Versioned<List<NodeId>> newCandidates = candidateMap.get(path);
                    publish(new LeadershipEvent(
                            LeadershipEvent.Type.CANDIDATES_CHANGED,
                            new Leadership(path,
                                newCandidates.value(),
                                newCandidates.version(),
                                newCandidates.creationTime())));
                } else {
                    rerunForLeadership(path, future);
                    return;
                }
            }
            log.debug("In the leadership race for topic {} with candidates {}", path, candidates);
            activeTopics.add(path);
            tryLeaderLock(path, future);
        } catch (ConsistentMapException e) {
            log.debug("Failed to enter topic leader race for {}. Retrying.", path, e);
            rerunForLeadership(path, future);
        }
    }

    @Override
    public CompletableFuture<Void> withdraw(String path) {
        activeTopics.remove(path);
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        doWithdraw(path, resultFuture);
        return resultFuture;
    }


    private void doWithdraw(String path, CompletableFuture<Void> future) {
        if (activeTopics.contains(path)) {
            future.completeExceptionally(new CancellationException(String.format("%s is now a active topic", path)));
        }
        try {
            Versioned<NodeId> leader = leaderMap.get(path);
            if (leader != null && Objects.equals(leader.value(), localNodeId)) {
                if (leaderMap.remove(path, leader.version())) {
                    log.info("Gave up leadership for {}", path);
                    future.complete(null);
                    publish(new LeadershipEvent(
                            LeadershipEvent.Type.LEADER_BOOTED,
                            new Leadership(path,
                                localNodeId,
                                leader.version(),
                                leader.creationTime())));
                }
            }
            // else we are not the current leader, can still be a candidate.
            Versioned<List<NodeId>> candidates = candidateMap.get(path);
            List<NodeId> candidateList = candidates != null
                    ? Lists.newArrayList(candidates.value())
                    : Lists.newArrayList();
            if (!candidateList.remove(localNodeId)) {
                future.complete(null);
                return;
            }
            if (candidateMap.replace(path, candidates.version(), candidateList)) {
                Versioned<List<NodeId>> newCandidates = candidateMap.get(path);
                future.complete(null);
                publish(new LeadershipEvent(
                                LeadershipEvent.Type.CANDIDATES_CHANGED,
                                new Leadership(path,
                                    newCandidates.value(),
                                    newCandidates.version(),
                                    newCandidates.creationTime())));
            } else {
                log.warn("Failed to withdraw from candidates list. Will retry");
                retryWithdraw(path, future);
            }
        } catch (Exception e) {
            log.debug("Failed to verify (and clear) any lock this node might be holding for {}", path, e);
            retryWithdraw(path, future);
        }
    }

    @Override
    public boolean stepdown(String path) {
        if (!activeTopics.contains(path) || !Objects.equals(localNodeId, getLeader(path))) {
            return false;
        }

        try {
            Versioned<NodeId> leader = leaderMap.get(path);
            if (leader != null && Objects.equals(leader.value(), localNodeId)) {
                if (leaderMap.remove(path, leader.version())) {
                    log.info("Stepped down from leadership for {}", path);
                    publish(new LeadershipEvent(
                            LeadershipEvent.Type.LEADER_BOOTED,
                            new Leadership(path,
                                localNodeId,
                                leader.version(),
                                leader.creationTime())));
                    retryLock(path, new CompletableFuture<>());
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Error executing stepdown for {}", path, e);
        }
        return false;
    }

    @Override
    public void addListener(LeadershipEventListener listener) {
        listenerRegistry.addListener(listener);
    }

    @Override
    public void removeListener(LeadershipEventListener listener) {
        listenerRegistry.removeListener(listener);
    }

    @Override
    public boolean makeTopCandidate(String path, NodeId nodeId) {
        Versioned<List<NodeId>> candidates = candidateMap.get(path);
        if (candidates == null || !candidates.value().contains(nodeId)) {
            return false;
        }
        List<NodeId> currentRoster = candidates.value();
        if (nodeId.equals(currentRoster.get(LEADER_CANDIDATE_POS))) {
            return true;
        }
        List<NodeId> newRoster = new ArrayList<>(currentRoster.size());
        newRoster.add(nodeId);
        currentRoster.stream().filter(id -> !nodeId.equals(id)).forEach(newRoster::add);
        boolean updated = candidateMap.replace(path, candidates.version(), newRoster);
        if (updated) {
            Versioned<List<NodeId>> newCandidates = candidateMap.get(path);
            publish(new LeadershipEvent(
                    LeadershipEvent.Type.CANDIDATES_CHANGED,
                    new Leadership(path,
                        newCandidates.value(),
                        newCandidates.version(),
                        newCandidates.creationTime())));
        }
        return updated;
    }

    private void tryLeaderLock(String path, CompletableFuture<Leadership> future) {
        if (!activeTopics.contains(path) || Objects.equals(localNodeId, getLeader(path))) {
            return;
        }
        try {
            Versioned<List<NodeId>> candidates = candidateMap.get(path);
            if (candidates != null) {
                List<NodeId> activeNodes = candidates.value()
                                  .stream()
                                  .filter(n -> clusterService.getState(n) == ACTIVE)
                                  .collect(Collectors.toList());
                if (localNodeId.equals(activeNodes.get(LEADER_CANDIDATE_POS))) {
                    leaderLockAttempt(path, candidates.value(), future);
                } else {
                    retryLock(path, future);
                }
            } else {
                throw new IllegalStateException("should not be here");
            }
        } catch (Exception e) {
            log.debug("Failed to fetch candidate information for {}", path, e);
            retryLock(path, future);
        }
    }

    private void leaderLockAttempt(String path, List<NodeId> candidates, CompletableFuture<Leadership> future) {
        try {
            Versioned<NodeId> currentLeader = leaderMap.get(path);
            if (currentLeader != null) {
                if (localNodeId.equals(currentLeader.value())) {
                    log.info("Already has leadership for {}", path);
                    // FIXME: candidates can get out of sync.
                    Leadership leadership = new Leadership(path,
                            localNodeId,
                            currentLeader.version(),
                            currentLeader.creationTime());
                    future.complete(leadership);
                    publish(new LeadershipEvent(
                            LeadershipEvent.Type.LEADER_ELECTED,
                            leadership));
                } else {
                    // someone else has leadership. will retry after sometime.
                    retryLock(path, future);
                }
            } else {
                if (leaderMap.putIfAbsent(path, localNodeId) == null) {
                    log.info("Assumed leadership for {}", path);
                    // do a get again to get the version (epoch)
                    Versioned<NodeId> newLeader = leaderMap.get(path);
                    // FIXME: candidates can get out of sync
                    Leadership leadership = new Leadership(path,
                            newLeader.value(),
                            newLeader.version(),
                            newLeader.creationTime());
                    future.complete(leadership);
                    publish(new LeadershipEvent(
                            LeadershipEvent.Type.LEADER_ELECTED,
                            leadership));
                } else {
                    // someone beat us to it.
                    retryLock(path, future);
                }
            }
        } catch (Exception e) {
            log.debug("Attempt to acquire leadership lock for topic {} failed", path, e);
            retryLock(path, future);
        }
    }

    private void publish(LeadershipEvent event) {
        onLeadershipEvent(event);
        clusterCommunicator.broadcast(event, LEADERSHIP_EVENT_MESSAGE_SUBJECT, SERIALIZER::encode);
    }

    private void onLeadershipEvent(LeadershipEvent leadershipEvent) {
        log.trace("Leadership Event: time = {} type = {} event = {}",
                leadershipEvent.time(), leadershipEvent.type(),
                leadershipEvent);

        Leadership leadershipUpdate = leadershipEvent.subject();
        LeadershipEvent.Type eventType = leadershipEvent.type();
        String topic = leadershipUpdate.topic();

        AtomicBoolean updateAccepted = new AtomicBoolean(false);
        if (eventType.equals(LeadershipEvent.Type.LEADER_ELECTED)) {
            leaderBoard.compute(topic, (k, currentLeadership) -> {
                if (currentLeadership == null || currentLeadership.epoch() < leadershipUpdate.epoch()) {
                    updateAccepted.set(true);
                    return leadershipUpdate;
                }
                return currentLeadership;
            });
        } else if (eventType.equals(LeadershipEvent.Type.LEADER_BOOTED)) {
            leaderBoard.compute(topic, (k, currentLeadership) -> {
                if (currentLeadership == null || currentLeadership.epoch() <= leadershipUpdate.epoch()) {
                    updateAccepted.set(true);
                    return null;
                }
                return currentLeadership;
            });
        } else if (eventType.equals(LeadershipEvent.Type.CANDIDATES_CHANGED)) {
            candidateBoard.compute(topic, (k, currentInfo) -> {
                if (currentInfo == null || currentInfo.epoch() < leadershipUpdate.epoch()) {
                    updateAccepted.set(true);
                    return leadershipUpdate;
                }
                return currentInfo;
            });
        } else {
            throw new IllegalStateException("Unknown event type.");
        }

        if (updateAccepted.get()) {
            eventDispatcher.post(leadershipEvent);
        }
    }

    private void rerunForLeadership(String path, CompletableFuture<Leadership> future) {
        retryLeaderLockExecutor.schedule(
                () -> doRunForLeadership(path, future),
                ELECTION_JOIN_ATTEMPT_INTERVAL_SEC,
                TimeUnit.SECONDS);
    }

    private void retryLock(String path, CompletableFuture<Leadership> future) {
        retryLeaderLockExecutor.schedule(
                () -> tryLeaderLock(path, future),
                DELAY_BETWEEN_LEADER_LOCK_ATTEMPTS_SEC,
                TimeUnit.SECONDS);
    }

    private void retryWithdraw(String path, CompletableFuture<Void> future) {
        retryLeaderLockExecutor.schedule(
                () -> doWithdraw(path, future),
                DELAY_BETWEEN_LEADER_LOCK_ATTEMPTS_SEC,
                TimeUnit.SECONDS);
    }

    private void purgeStaleLocks() {
        try {
            leaderMap.entrySet()
                .stream()
                .filter(e -> clusterService.getState(e.getValue().value()) == INACTIVE)
                .filter(e -> activeTopics.contains(e.getKey()))
                .forEach(entry -> {
                    String path = entry.getKey();
                    NodeId nodeId = entry.getValue().value();
                    long epoch = entry.getValue().version();
                    long creationTime = entry.getValue().creationTime();
                    try {
                        if (leaderMap.remove(path, epoch)) {
                            log.info("Purged stale lock held by {} for {}", nodeId, path);
                            publish(new LeadershipEvent(
                                    LeadershipEvent.Type.LEADER_BOOTED,
                                    new Leadership(path, nodeId, epoch, creationTime)));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to purge stale lock held by {} for {}", nodeId, path, e);
                    }
                });
        } catch (Exception e) {
            log.debug("Failed cleaning up stale locks", e);
        }
    }

    private void sendLeadershipStatus() {
        try {
            leaderBoard.forEach((path, leadership) -> {
                if (leadership.leader().equals(localNodeId)) {
                    LeadershipEvent event = new LeadershipEvent(LeadershipEvent.Type.LEADER_ELECTED, leadership);
                    clusterCommunicator.broadcast(event,
                            LEADERSHIP_EVENT_MESSAGE_SUBJECT,
                            SERIALIZER::encode);
                }
            });
            candidateBoard.forEach((path, leadership) -> {
                LeadershipEvent event = new LeadershipEvent(LeadershipEvent.Type.CANDIDATES_CHANGED, leadership);
                clusterCommunicator.broadcast(event,
                        LEADERSHIP_EVENT_MESSAGE_SUBJECT,
                        SERIALIZER::encode);
            });
        } catch (Exception e) {
            log.debug("Failed to send leadership updates", e);
        }
    }
}
