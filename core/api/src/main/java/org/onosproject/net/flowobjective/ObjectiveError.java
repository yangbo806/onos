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
package org.onosproject.net.flowobjective;

/**
 * Represents the set of errors possible when processing an objective.
 */
public enum ObjectiveError {

    /**
     * The driver processing this objective does not know how to process it.
     */
    UNSUPPORTED,

    /**
     * The flow installation for this objective failed.
     */
    FLOWINSTALLATIONFAILED,

    /**
     * THe group installation for this objective failed.
     */
    GROUPINSTALLATIONFAILED,

    /**
     * The group was reported as installed but is missing.
     */
    GROUPMISSING,

    /**
     * The device was not available to install objectives to.
     */
    DEVICEMISSING,

    /**
     * Incorrect Objective parameters passed in by the caller.
     */
    BADPARAMS,

    /**
     * An unknown error occurred.
     */
    UNKNOWN
}
