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
package org.onosproject.net.group;

import org.onosproject.core.GroupId;

/**
 * ONOS representation of group that is stored in the system.
 */
public interface Group extends GroupDescription {
    /**
     * State of the group object in ONOS.
     */
    enum GroupState {
        /**
         * Group create request is queued as group AUDIT is in progress.
         */
        WAITING_AUDIT_COMPLETE,
        /**
         * Group create request is processed by ONOS and not yet
         * received the confirmation from data plane.
         */
        PENDING_ADD,
        /**
         * Group is created in the data plane.
         */
        ADDED,
        /**
         * Group update request is processed by ONOS and not
         * received the confirmation from data plane post which
         * state moves to ADDED state.
         */
        PENDING_UPDATE,
        /**
         * Group delete request is processed by ONOS and not
         * received the confirmation from data plane.
         */
        PENDING_DELETE
    }

    /**
     * Returns group identifier associated with a group object.
     *
     * @return GroupId Group Identifier
     */
    GroupId id();

    /**
     * Returns current state of a group object.
     *
     * @return GroupState Group State
     */
    GroupState state();

    /**
     * Returns the number of milliseconds this group has been alive.
     *
     * @return number of millis
     */
    long life();

    /**
     * Returns the number of packets processed by this group.
     *
     * @return number of packets
     */
    long packets();

    /**
     * Returns the number of bytes processed by this group.
     *
     * @return number of bytes
     */
    long bytes();

    /**
     * Returns the number of flow rules or other groups reference this group.
     *
     * @return number of flow rules or other groups pointing to this group
     */
    long referenceCount();
}
