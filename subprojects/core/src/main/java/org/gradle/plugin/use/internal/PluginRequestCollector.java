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

package org.gradle.plugin.use.internal;

import org.gradle.api.Transformer;
import org.gradle.api.specs.Spec;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.exceptions.LocationAwareException;
import org.gradle.plugin.management.internal.DefaultPluginRequest;
import org.gradle.plugin.management.internal.DefaultPluginRequests;
import org.gradle.plugin.management.internal.InvalidPluginRequestException;
import org.gradle.plugin.management.internal.PluginRequestInternal;
import org.gradle.plugin.management.internal.PluginRequests;
import org.gradle.plugin.use.BinaryPluginDependencySpec;
import org.gradle.plugin.use.PluginDependenciesSpec;
import org.gradle.plugin.use.PluginId;
import org.gradle.plugin.use.ScriptPluginDependencySpec;
import org.gradle.util.CollectionUtils;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.gradle.util.CollectionUtils.collect;
import static org.gradle.util.CollectionUtils.filter;
import static org.gradle.util.CollectionUtils.groupBy;

/**
 * The real delegate of the plugins {} block.
 *
 * The {@link PluginUseScriptBlockMetadataExtractor} interacts with this type.
 */
public class PluginRequestCollector {

    private final ScriptSource scriptSource;

    public PluginRequestCollector(ScriptSource scriptSource) {
        this.scriptSource = scriptSource;
    }

    private static class DependencySpecImpl implements BinaryPluginDependencySpec, ScriptPluginDependencySpec {
        private final PluginId id;
        private String version;
        private String script;
        private boolean apply;
        private final int lineNumber;

        private DependencySpecImpl(String id, int lineNumber, String script) {
            this.id = id == null ? null : DefaultPluginId.of(id);
            this.apply = true;
            this.lineNumber = lineNumber;
            this.script = script;
        }

        @Override
        public BinaryPluginDependencySpec version(String version) {
            this.version = version;
            return this;
        }

        @Override
        public BinaryPluginDependencySpec apply(boolean apply) {
            this.apply = apply;
            return this;
        }
    }

    private final List<DependencySpecImpl> specs = new LinkedList<DependencySpecImpl>();

    public PluginDependenciesSpec createSpec(final int lineNumber) {
        return new PluginDependenciesSpec() {
            public BinaryPluginDependencySpec id(String id) {
                DependencySpecImpl spec = new DependencySpecImpl(id, lineNumber, null);
                specs.add(spec);
                return spec;
            }

            @Override
            public ScriptPluginDependencySpec script(String script) {
                DependencySpecImpl spec = new DependencySpecImpl(null, lineNumber, script);
                specs.add(spec);
                return spec;
            }
        };
    }

    public PluginRequests getPluginRequests() {
        return new DefaultPluginRequests(listPluginRequests());
    }

    public List<PluginRequestInternal> listPluginRequests() {
        List<PluginRequestInternal> pluginRequests = collect(specs, new Transformer<PluginRequestInternal, DependencySpecImpl>() {
            public PluginRequestInternal transform(DependencySpecImpl original) {
                return new DefaultPluginRequest(scriptSource, original.lineNumber, original.id, original.version, original.script, original.apply);
            }
        });

        // TODO:rbo Dedupe the deduplicating code

        // Check for duplicate scripts
        Map<String, Collection<PluginRequestInternal>> groupedByScript = groupBy(filter(pluginRequests, new Spec<PluginRequestInternal>() {
            @Override
            public boolean isSatisfiedBy(PluginRequestInternal pluginRequest) {
                return pluginRequest.getScript() != null;
            }
        }), new Transformer<String, PluginRequestInternal>() {
            @Override
            public String transform(PluginRequestInternal pluginRequest) {
                return pluginRequest.getScript();
            }
        });
        for (String key : groupedByScript.keySet()) {
            Collection<PluginRequestInternal> pluginRequestsForId = groupedByScript.get(key);
            if (pluginRequestsForId.size() > 1) {
                PluginRequestInternal first = pluginRequests.get(0);
                PluginRequestInternal second = pluginRequests.get(1);

                InvalidPluginRequestException exception = new InvalidPluginRequestException(second, "Script Plugin '" + key + "' was already requested at line " + first.getRequestingScriptLineNumber());
                throw new LocationAwareException(exception, second.getRequestingScriptDisplayName(), second.getRequestingScriptLineNumber());
            }
        }

        // Check for duplicate IDs
        Map<PluginId, Collection<PluginRequestInternal>> groupedById = groupBy(filter(pluginRequests, new Spec<PluginRequestInternal>() {
            @Override
            public boolean isSatisfiedBy(PluginRequestInternal pluginRequest) {
                return pluginRequest.getId() != null;
            }
        }), new Transformer<PluginId, PluginRequestInternal>() {
            public PluginId transform(PluginRequestInternal pluginRequest) {
                return pluginRequest.getId();
            }
        });
        for (PluginId key : groupedById.keySet()) {
            Collection<PluginRequestInternal> pluginRequestsForId = groupedById.get(key);
            if (pluginRequestsForId.size() > 1) {
                PluginRequestInternal first = pluginRequests.get(0);
                PluginRequestInternal second = pluginRequests.get(1);

                InvalidPluginRequestException exception = new InvalidPluginRequestException(second, "Plugin with id '" + key + "' was already requested at line " + first.getRequestingScriptLineNumber());
                throw new LocationAwareException(exception, second.getRequestingScriptDisplayName(), second.getRequestingScriptLineNumber());
            }
        }
        return pluginRequests;
    }

}
