/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.operations.trace;

import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.progress.BuildOperationDescriptor;
import org.gradle.internal.progress.BuildOperationListener;
import org.gradle.internal.progress.BuildOperationListenerManager;
import org.gradle.internal.progress.OperationFinishEvent;
import org.gradle.internal.progress.OperationStartEvent;

import java.util.ArrayList;
import java.util.List;

public class BuildOperationStore implements Stoppable{

    private final BuildOperationListenerManager listenerManager;
    private final BuildOperationListener listener;

    private List<StoredBuildOperation> storedEvents = new ArrayList<StoredBuildOperation>();

    public BuildOperationStore(BuildOperationListenerManager listenerManager){
        this.listenerManager = listenerManager;
        this.listener = new BuildOperationListener() {
            @Override
            public void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
                storedEvents.add(new StoredBuildOperation(buildOperation, startEvent));
            }

            @Override
            public void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent) {
                storedEvents.add(new StoredBuildOperation(buildOperation, finishEvent));

            }
        };
        this.listenerManager.addListener(listener);
    }

    public List<StoredBuildOperation> getStoredEvents() {
        return storedEvents;
    }

    @Override
    public void stop() {
        listenerManager.removeListener(listener);
    }

    public static class StoredBuildOperation {
        public final BuildOperationDescriptor buildOperation;
        public final Object event;

        public StoredBuildOperation(BuildOperationDescriptor buildOperation, Object event) {

            this.buildOperation = buildOperation;
            this.event = event;
        }
    }
}
