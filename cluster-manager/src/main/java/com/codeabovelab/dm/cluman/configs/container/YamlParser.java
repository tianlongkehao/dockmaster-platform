/*
 * Copyright 2016 Code Above Lab LLC
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

package com.codeabovelab.dm.cluman.configs.container;

import com.codeabovelab.dm.cluman.model.ContainerSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Parser for compose yaml configs
 */
@Component
public class YamlParser extends AbstractParser {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigsFetcherGit.class);
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Override
    public void parse(String fileName, ContainerCreationContext context) {
        parse(fileName, context, ".yml");
    }

    @Override
    public void parse(File file, ContainerCreationContext context) {
        try {
            ContainerSource configuration = mapper.readValue(file, ContainerSource.class);
            context.addCreateContainerArg(configuration);
            LOG.info("Result of parsing {}", configuration);
        } catch (IOException e) {
            LOG.error("can't parse configuration", e);
        }
    }

    @Override
    public void parse(Map<String, Object> map, ContainerCreationContext context) {
        return;
    }

    @Override
    public void parse(Map<String, Object> map, ContainerSource arg) {
        return;
    }

}
