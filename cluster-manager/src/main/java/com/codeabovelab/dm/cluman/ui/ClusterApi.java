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

import com.codeabovelab.dm.cluman.cluster.docker.ClusterConfigImpl;
import com.codeabovelab.dm.cluman.cluster.docker.management.ApplicationService;
import com.codeabovelab.dm.cluman.cluster.docker.management.DockerService;
import com.codeabovelab.dm.cluman.cluster.docker.management.DockerUtils;
import com.codeabovelab.dm.cluman.cluster.docker.management.argument.GetContainersArg;
import com.codeabovelab.dm.cluman.cluster.registry.RegistryRepository;
import com.codeabovelab.dm.cluman.job.JobInstance;
import com.codeabovelab.dm.cluman.security.AclContext;
import com.codeabovelab.dm.cluman.security.AclContextFactory;
import com.codeabovelab.dm.cluman.security.SecuredType;
import com.codeabovelab.dm.cluman.source.DeployOptions;
import com.codeabovelab.dm.cluman.source.SourceService;
import com.codeabovelab.dm.cluman.ds.DockerServiceRegistry;
import com.codeabovelab.dm.cluman.ds.clusters.RealCluster;
import com.codeabovelab.dm.cluman.ds.clusters.SwarmNodesGroupConfig;
import com.codeabovelab.dm.cluman.ds.container.ContainerStorage;
import com.codeabovelab.dm.cluman.ds.nodes.NodeStorage;
import com.codeabovelab.dm.cluman.model.*;
import com.codeabovelab.dm.cluman.ui.model.*;
import com.codeabovelab.dm.cluman.validate.ExtendedAssert;
import com.codeabovelab.dm.cluman.yaml.YamlUtils;
import com.codeabovelab.dm.common.cache.DefineCache;
import com.codeabovelab.dm.common.cache.MessageBusCacheInvalidator;
import com.codeabovelab.dm.common.security.Authorities;
import com.codeabovelab.dm.common.utils.Sugar;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.annotation.Secured;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.*;

/**
 * Rest controller for UI
 */
@RestController
@Slf4j
@RequestMapping(value = "/ui/api", produces = APPLICATION_JSON_VALUE)
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ClusterApi {

    private final DockerServiceRegistry dockerServiceRegistry;
    private final RegistryRepository registryRepository;

    private final NodeStorage nodeRegistry;
    private final SourceService sourceService;
    private final DiscoveryStorage discoveryStorage;
    private final ApplicationService applicationService;
    private final ContainerStorage containerStorage;
    private final FilterApi filterApi;
    private final AclContextFactory aclContextFactory;

    @RequestMapping(value = "/clusters/", method = GET)
    public List<UiCluster> listClusters() {
        AclContext ac = aclContextFactory.getContext();
        Collection<NodesGroup> clusters = this.discoveryStorage.getClusters();
        List<UiCluster> ucs = clusters.stream().map(c -> this.toUi(ac, c)).collect(Collectors.toList());
        ucs.sort(Comparator.naturalOrder());
        return ucs;
    }

    private UiCluster toUi(AclContext ac, NodesGroup cluster) {
        UiCluster uc = new UiCluster();
        final String name = cluster.getName();
        uc.setName(name);
        uc.getTitle().accept(cluster.getTitle());
        uc.getDescription().accept(cluster.getDescription());
        uc.getFilter().accept(cluster.getImageFilter());
        uc.setFeatures(cluster.getFeatures());
        try {
            DockerServiceInfo info = cluster.getDocker().getInfo();
            uc.setContainers(new UiCluster.Entry(info.getContainers(), info.getOffContainers()));
            uc.setNodes(new UiCluster.Entry(info.getNodeCount(), info.getOffNodeCount()));
        } catch (AccessDeniedException e) {
            uc.setContainers(new UiCluster.Entry(0, 0));
            uc.setNodes(new UiCluster.Entry(0, 0));
            //nothing
        }
        try {
            Set<String> apps = uc.getApplications();
            List<Application> applications = applicationService.getApplications(name);
            applications.forEach(a -> apps.add(a.getName()));
        } catch (Exception e) {
            //nothing
        }
        UiPermission.inject(uc, ac, SecuredType.CLUSTER.id(name));
        return uc;
    }

    @RequestMapping(value = "/clusters/{cluster}/containers", method = GET)
    public ResponseEntity<Collection<UiContainer>> listContainers(@PathVariable("cluster") String cluster) {
        AclContext ac = aclContextFactory.getContext();
        List<UiContainer> list = new ArrayList<>();
        GetContainersArg arg = new GetContainersArg(true);
        NodesGroup nodesGroup = discoveryStorage.getCluster(cluster);
        if (nodesGroup == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        DockerService service = nodesGroup.getDocker();
        Map<String, String> apps = UiUtils.mapAppContainer(applicationService, nodesGroup);
        ExtendedAssert.notFound(service, "Service for " + cluster + " is null.");
        List<DockerContainer> containers = service.getContainers(arg);
        for (DockerContainer container : containers) {
            UiContainer uic = UiContainer.from(container);
            uic.enrich(discoveryStorage, containerStorage);
            uic.setApplication(apps.get(uic.getId()));
            UiPermission.inject(uic, ac, SecuredType.CONTAINER.id(uic.getId()));
            list.add(uic);
        }
        Collections.sort(list);
        return new ResponseEntity<>(list, HttpStatus.OK);
    }

    @RequestMapping(value = "/clusters/{cluster}/containers", method = PUT)
    public ResponseEntity<Collection<UiContainer>> filteredListContainers(@PathVariable("cluster") String cluster,
                                                                          @RequestBody UISearchQuery searchQuery) {
        ResponseEntity<Collection<UiContainer>> listResponseEntity = listContainers(cluster);
        Collection<UiContainer> body = listResponseEntity.getBody();
        Collection<UiContainer> uiContainers = filterApi.listNodes(body, searchQuery);
        return new ResponseEntity<>(uiContainers, HttpStatus.OK);

    }

    @RequestMapping(value = "/clusters/{cluster}/info", method = GET)
    @Cacheable("SwarmInfo")
    @DefineCache(
            expireAfterWrite = 120_000,
            invalidator = MessageBusCacheInvalidator.class,
            invalidatorArgs = {
                    MessageBusCacheInvalidator.BUS_KEY, NodeEvent.BUS
            }
    )
    public DockerServiceInfo info(@PathVariable("cluster") String cluster) {
        return dockerServiceRegistry.getService(cluster).getInfo();
    }

    @RequestMapping(value = "/clusters/{cluster}/nodes-detailed", method = GET)
    @Cacheable("UINode")
    @DefineCache(
            expireAfterWrite = 120_000,
            invalidator = MessageBusCacheInvalidator.class,
            invalidatorArgs = {
                    MessageBusCacheInvalidator.BUS_KEY, NodeEvent.BUS
            }
    )
    public List<NodeInfo> listNodesDetailed(@PathVariable("cluster") String cluster) {
        DockerServiceInfo info = dockerServiceRegistry.getService(cluster).getInfo();
        return info.getNodeList();
    }

    @RequestMapping(value = "/clusters/{cluster}/nodes", method = GET)
    public List<String> listNodes(@PathVariable("cluster") String cluster) {
        DockerServiceInfo info = dockerServiceRegistry.getService(cluster).getInfo();
        return DockerUtils.listNodes(info);
    }

    @ApiOperation("Add node to specified cluster. Node must be present in same environment wit cluster.")
    @RequestMapping(value = "/clusters/{cluster}/nodes/{node}", method = POST)
    public ResponseEntity<?> addNode(@PathVariable("cluster") String clusterId, @PathVariable("node") String node) {
        // we setup cluster
        NodesGroup cluster = discoveryStorage.getOrCreateCluster(clusterId, null);
        //and then attach node to it
        if (cluster.getFeatures().contains(NodesGroup.Feature.FORBID_NODE_ADDITION)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Cluster: " + clusterId + " does not allow addition of nodes.");
        }
        nodeRegistry.setNodeCluster(node, clusterId);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("Remove node from specified cluster. Also you can use 'all' cluster or any other - node will be correctly removed anyway.")
    @RequestMapping(value = "/clusters/{cluster}/nodes/{node}", method = DELETE)
    public ResponseEntity<?> removeNode(@PathVariable("cluster") String clusterId, @PathVariable("node") String node) {
        nodeRegistry.setNodeCluster(node, null);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @RequestMapping(value = "/clusters/{cluster}/registries", method = GET)
    public List<String> getRegistriesForCluster(@PathVariable("cluster") String cluster) {
        Collection<String> availableRegistries = registryRepository.getAvailableRegistries();
        DockerService service = dockerServiceRegistry.getService(cluster);
        List<String> registries = service.getClusterConfig().getRegistries();
        if (registries == null || registries.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> intersection = new ArrayList<>(availableRegistries);
        intersection.retainAll(registries);
        return intersection;
    }

    @RequestMapping(value = "/clusters/{cluster}/source", method = GET, produces = YamlUtils.MIME_TYPE_VALUE)
    public ResponseEntity<RootSource> getClusterSource(@PathVariable("cluster") String cluster) {
        RootSource root = sourceService.getClusterSource(cluster);
        ExtendedAssert.notFound(root, "Can not find cluster with name: " + cluster);
        HttpHeaders headers = new HttpHeaders();
        String confName = com.codeabovelab.dm.common.utils.StringUtils.retainForFileName(cluster);
        if (confName.isEmpty()) {
            confName = "config";
        }
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + confName + ".json\"");
        return new ResponseEntity<>(root, headers, HttpStatus.OK);
    }

    @RequestMapping(value = "/clusters/{cluster}/source", method = POST, consumes = YamlUtils.MIME_TYPE_VALUE)
    public UiJob setClusterSource(@PathVariable("cluster") String cluster,
                                  DeployOptions.Builder options,
                                  @RequestBody RootSource rootSource) {
        return setRootSrc(cluster, options, rootSource);
    }

    @RequestMapping(value = "/clusters/{cluster}/source-upload", method = POST, consumes = MimeTypeUtils.MULTIPART_FORM_DATA_VALUE)
    public UiJob uploadClusterSource(@PathVariable("cluster") String cluster,
                                  DeployOptions.Builder options,
                                  @RequestPart("file") RootSource rootSource) {
        return setRootSrc(cluster, options, rootSource);
    }

    private UiJob setRootSrc(String cluster, DeployOptions.Builder options, RootSource rootSource) {
        List<ClusterSource> clusters = rootSource.getClusters();
        if(clusters.isEmpty()) {
            throw new IllegalArgumentException("No clusters in source");
        }
        if(clusters.size() > 1) {
            throw new IllegalArgumentException("Too many clusters in source, accept only one.");
        }
        // update name of cluster, because name from path more priority than from source
        clusters.get(0).setName(cluster);
        JobInstance jobInstance = sourceService.setRootSource(rootSource, options.build());
        return UiJob.toUi(jobInstance);
    }

    @RequestMapping(value = "/clusters/{cluster}", method = DELETE)
    public void deleteCluster(@PathVariable("cluster") String cluster) {
        discoveryStorage.deleteCluster(cluster);
    }

    /**
     *
     * @param clusterName
     * @param clusterData
     */
    @Secured({Authorities.ADMIN_ROLE, SecuredType.CLUSTER_ADMIN})
    @RequestMapping(value = "/clusters/{cluster}", method = PUT)
    public void createCluster(@PathVariable("cluster") String clusterName, @RequestBody(required = false) UiClusterEditablePart clusterData) {
        SwarmNodesGroupConfig sgnc = new SwarmNodesGroupConfig();
        sgnc.setName(clusterName);
        ClusterConfigImpl.Builder ccib = ClusterConfigImpl.builder(RealCluster.getDefaultConfig(clusterName));
        if (clusterData != null) {
            ccib.merge(clusterData.getConfig());
        }
        sgnc.setConfig(ccib.build());
        NodesGroup cluster = discoveryStorage.getOrCreateGroup(sgnc);
        if (clusterData != null) {
            Sugar.setIfChanged(cluster::setTitle, clusterData.getTitle());
            Sugar.setIfChanged(cluster::setDescription, clusterData.getDescription());
            Sugar.setIfChanged(cluster::setImageFilter, clusterData.getFilter());
            // we cannot change strategy for created cluster, maybe we need restart swarm for it?
            // cluster.setStrategy(clusterData.getStrategy());
        }
        cluster.flush();
    }

}
