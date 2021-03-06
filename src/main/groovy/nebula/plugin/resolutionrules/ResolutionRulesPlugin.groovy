/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package nebula.plugin.resolutionrules

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.joda.JodaModule
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ResolutionRulesPlugin implements Plugin<Project> {
    Logger logger = LoggerFactory.getLogger(ResolutionRulesPlugin)
    String configurationName = "resolutionRules"

    public void apply(Project project) {
        Configuration configuration = project.configurations.create(configurationName)
        project.afterEvaluate {
            Rules rules = rulesFromConfiguration(configuration)
            applyRules(rules, project)
        }
    }

    private Rules rulesFromConfiguration(Configuration configuration) {
        List<Rules> rules = new ArrayList<Rules>();
        ObjectMapper mapper = createMapper()
        Set<File> files = configuration.resolve()
        if (files.isEmpty()) {
            logger.warn("No resolution rules have been added to the '{}' configuration", configuration.name)
        }
        for (file in files) {
            if (file.name.endsWith(".json")) {
                ResolutionJsonValidator.validateJsonFile(file)
                logger.info("Using $file as a dependency rules source")
                rules.add(mapper.readValue(file, Rules))
            } else if (file.name.endsWith(".jar") || file.name.endsWith(".zip")) {
                logger.info("Using $file as a dependency rules source")
                ZipFile jar = new ZipFile(file)
                try {
                    Enumeration<? extends ZipEntry> entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement()
                        if (entry.name.endsWith(".json")) {
                            ResolutionJsonValidator.validateJsonStream(jar.getInputStream(entry))
                            rules.add(mapper.readValue(jar.getInputStream(entry), Rules))
                        }
                    }
                } finally {
                    jar.close()
                }
            } else {
                logger.error("Unsupported rules file extension for $file")
            }
        }
        return flattenRules(rules)
    }

    private static Rules flattenRules(Iterable<Rules> rules) {
        List<ReplaceRule> replace = rules.collectMany { it.replace }.flatten() as List<ReplaceRule>
        List<SubstituteRule> substitute = rules.collectMany { it.substitute }.flatten() as List<SubstituteRule>
        List<RejectRule> reject = rules.collectMany { it.reject }.flatten() as List<RejectRule>
        List<DenyRule> deny = rules.collectMany { it.deny }.flatten() as List<DenyRule>
        return new Rules(replace: replace, substitute: substitute, reject: reject, deny: deny)
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper()
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .registerModule(new JodaModule())
        return mapper
    }

    private void applyRules(Rules rules, Project project) {
        rules.projectRules().each { it.apply(project) }
        project.configurations.all({ configuration ->
            if (configuration.name == configurationName) {
                return
            }
            if (configuration.state != Configuration.State.UNRESOLVED) {
                logger.warn("Configuration '{}' has been resolved. Dependency resolution rules will not be applied", configuration.name)
                return
            }
            rules.configurationRules().each {
                it.apply(configuration)
            }
        })
    }
}
