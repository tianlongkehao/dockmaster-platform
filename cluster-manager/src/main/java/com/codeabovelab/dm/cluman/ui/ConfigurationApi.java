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

package com.codeabovelab.dm.cluman.ui;

import com.codeabovelab.dm.cluman.job.JobInstance;
import com.codeabovelab.dm.cluman.job.JobsManager;
import com.codeabovelab.dm.cluman.model.RootSource;
import com.codeabovelab.dm.cluman.reconfig.AppConfigService;
import com.codeabovelab.dm.cluman.source.DeployOptions;
import com.codeabovelab.dm.cluman.source.SourceService;
import com.codeabovelab.dm.cluman.ui.model.UiApplicationInfo;
import com.codeabovelab.dm.cluman.ui.model.UiJob;
import com.codeabovelab.dm.cluman.yaml.YamlUtils;
import com.codeabovelab.dm.common.utils.AppInfo;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;

/**
 */
@AllArgsConstructor(onConstructor = @__(@Autowired))
@RequestMapping(value = "/ui/api/", produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
@RestController
public class ConfigurationApi {

    private final AppConfigService appConfigService;
    private final SourceService sourceService;
    private final JobsManager jobsManager;

    @RequestMapping(path = "config", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> getConfig() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        // 'produces' in annotation does not work with stream
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cluman_config.json\"");
        return new ResponseEntity<>((os) -> {
            appConfigService.write(MimeTypeUtils.APPLICATION_JSON_VALUE, os);
        }, headers, HttpStatus.OK);
    }

    @RequestMapping(path = "config", method = RequestMethod.POST, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public void setConfig(InputStream is) throws IOException {
        appConfigService.read(MimeTypeUtils.APPLICATION_JSON_VALUE, is);
    }

    @RequestMapping(path = "source", method = RequestMethod.GET, produces = YamlUtils.MIME_TYPE_VALUE)
    public ResponseEntity<RootSource> getSource() throws IOException {
        RootSource source = sourceService.getRootSource();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cluman_source.yaml\"");
        return new ResponseEntity<>(source, headers, HttpStatus.OK);
    }

    @RequestMapping(path = "source", method = RequestMethod.POST, consumes = YamlUtils.MIME_TYPE_VALUE)
    public UiJob setSource(@RequestBody RootSource root,
                           DeployOptions.Builder options) throws Exception {
        JobInstance jobInstance = sourceService.setRootSource(root, options.build());
        return UiJob.toUi(jobInstance);
    }

    @RequestMapping(path = "source-upload", method = RequestMethod.POST, consumes = MimeTypeUtils.MULTIPART_FORM_DATA_VALUE)
    public UiJob uploadSource(@RequestPart("file") RootSource root,
                              DeployOptions.Builder options) throws Exception {
        JobInstance jobInstance = sourceService.setRootSource(root, options.build());
        return UiJob.toUi(jobInstance);
    }

    @RequestMapping(path = "version", method = RequestMethod.GET)
    public UiApplicationInfo getAppInfo() {
        try {
            return UiApplicationInfo.builder()
                    .version(AppInfo.getApplicationVersion())
                    .buildTime(AppInfo.getBuildTime()).build();

        } catch (IllegalArgumentException e) {
            // we expect error like IllegalArgumentException: Attribute 'dm-cluman-info-version' not found in MANIFEST.MF file(s) among 90 other attribute(s):
            // which appear anytime when we run app without jar file
            return UiApplicationInfo.builder()
              .version("1.0-CAN_NOT_FOUND_MANIFEST")
              .buildTime(OffsetDateTime.now()).build();
        }
    }
}
