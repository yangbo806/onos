/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.store.consistent.impl;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.*;

import org.onosproject.store.service.DatabaseUpdate;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.TransactionContext;
import org.onosproject.store.service.TransactionException;
import org.onosproject.store.service.TransactionalMap;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Default TransactionContext implementation.
 */
public class DefaultTransactionContext implements TransactionContext {
    private static final String TX_NOT_OPEN_ERROR = "Transaction Context is not open";

    @SuppressWarnings("rawtypes")
    private final Map<String, DefaultTransactionalMap> txMaps = Maps.newConcurrentMap();
    private boolean isOpen = false;
    private final Database database;
    private final long transactionId;

    public DefaultTransactionContext(Database database, long transactionId) {
        this.database = checkNotNull(database);
        this.transactionId = transactionId;
    }

    @Override
    public long transactionId() {
        return transactionId;
    }

    @Override
    public void begin() {
        checkState(!isOpen, "Transaction Context is already open");
        isOpen = true;
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> TransactionalMap<K, V> getTransactionalMap(String mapName,
            Serializer serializer) {
        checkState(isOpen, TX_NOT_OPEN_ERROR);
        checkNotNull(mapName);
        checkNotNull(serializer);
        return txMaps.computeIfAbsent(mapName, name -> new DefaultTransactionalMap<>(
                                name,
                                new DefaultConsistentMap<>(name, database, serializer, false),
                                this,
                                serializer));
    }

    @SuppressWarnings("unchecked")
    @Override
    public void commit() {
        checkState(isOpen, TX_NOT_OPEN_ERROR);
        try {
            List<DatabaseUpdate> updates = Lists.newLinkedList();
            txMaps.values()
                  .forEach(m -> { updates.addAll(m.prepareDatabaseUpdates()); });
            database.prepareAndCommit(new DefaultTransaction(transactionId, updates));
        } catch (Exception e) {
            abort();
            throw new TransactionException(e);
        } finally {
            isOpen = false;
        }
    }

    @Override
    public void abort() {
        checkState(isOpen, TX_NOT_OPEN_ERROR);
        txMaps.values().forEach(m -> m.rollback());
    }
}
