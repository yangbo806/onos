package org.onosproject.cli.net;

import java.util.Set;
import java.util.List;

import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.commands.Option;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.intent.IntentId;
import org.onosproject.net.resource.DefaultLinkResourceRequest;
import org.onosproject.net.resource.LinkResourceAllocations;
import org.onosproject.net.resource.LinkResourceRequest;
import org.onosproject.net.resource.LinkResourceService;
import org.onosproject.net.topology.PathService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;

import com.google.common.collect.Lists;

/**
 * Commands to test out LinkResourceManager directly.
 */
@Command(scope = "onos", name = "resource-request",
        description = "request or remove resources")
public class LinkResourceTestCommand extends AbstractShellCommand {

    // default is bandwidth.
    @Option(name = "-m", aliases = "--mpls", description = "MPLS resource",
            required = false, multiValued = false)
    private boolean isMPLS = false;

    @Option(name = "-o", aliases = "--optical", description = "Optical resource",
            required = false, multiValued = false)
    private boolean isOptical = false;

    @Option(name = "-d", aliases = "--delete", description = "Delete resource by intent ID",
            required = false, multiValued = false)
    private boolean remove = false;

    @Argument(index = 0, name = "srcString", description = "Link source",
            required = true, multiValued = false)
    String srcString = null;

    @Argument(index = 1, name = "dstString", description = "Link destination",
            required = true, multiValued = false)
    String dstString = null;

    @Argument(index = 2, name = "id", description = "Identifier",
            required = true, multiValued = false)
    int id;

    private LinkResourceService resService;
    private PathService pathService;

    private static final int BANDWIDTH = 1_000_000;

    @Override
    protected void execute() {
        resService = get(LinkResourceService.class);
        pathService = get(PathService.class);

        DeviceId src = DeviceId.deviceId(getDeviceId(srcString));
        DeviceId dst = DeviceId.deviceId(getDeviceId(dstString));
        IntentId intId = IntentId.valueOf(id);

        Set<Path> paths = pathService.getPaths(src, dst);

        if (paths == null || paths.isEmpty()) {
            print("No path between %s and %s", srcString, dstString);
            return;
        }

        if (remove) {
            LinkResourceAllocations lra = resService.getAllocations(intId);
            resService.releaseResources(lra);
            return;
        }

        for (Path p : paths) {
            List<Link> links = p.links();
            LinkResourceRequest.Builder request = null;
            if (isMPLS) {
                List<Link> nlinks = Lists.newArrayList();
                try {
                    nlinks.addAll(links.subList(1, links.size() - 2));
                    request = DefaultLinkResourceRequest.builder(intId, nlinks)
                            .addMplsRequest();
                } catch (IndexOutOfBoundsException e) {
                    log.warn("could not allocate MPLS path", e);
                    continue;
                }
            } else if (isOptical) {
                request = DefaultLinkResourceRequest.builder(intId, links)
                        .addLambdaRequest();
            } else {
                request = DefaultLinkResourceRequest.builder(intId, links)
                        .addBandwidthRequest(BANDWIDTH);
            }

            if (request != null) {
                LinkResourceRequest lrr = request.build();
                LinkResourceAllocations lra = resService.requestResources(lrr);
                if (lra != null) {
                    break;
                }
                print("Allocated:\n%s", lra);
            } else {
                log.info("nothing to request");
            }
        }
    }

    public String getDeviceId(String deviceString) {
        int slash = deviceString.indexOf('/');
        if (slash <= 0) {
            return "";
        }
        return deviceString.substring(0, slash);
    }

}
