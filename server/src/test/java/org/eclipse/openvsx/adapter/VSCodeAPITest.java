/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.eclipse.openvsx.ExtensionValidator;
import org.eclipse.openvsx.MockTransactionTemplate;
import org.eclipse.openvsx.UserService;
import org.eclipse.openvsx.cache.CacheService;
import org.eclipse.openvsx.cache.LatestExtensionVersionCacheKeyGenerator;
import org.eclipse.openvsx.eclipse.EclipseService;
import org.eclipse.openvsx.entities.*;
import org.eclipse.openvsx.publish.ExtensionVersionIntegrityService;
import org.eclipse.openvsx.repositories.RepositoryService;
import org.eclipse.openvsx.search.ExtensionSearch;
import org.eclipse.openvsx.search.ISearchService;
import org.eclipse.openvsx.search.SearchUtilService;
import org.eclipse.openvsx.security.OAuth2UserServices;
import org.eclipse.openvsx.security.SecurityConfig;
import org.eclipse.openvsx.security.TokenService;
import org.eclipse.openvsx.storage.*;
import org.eclipse.openvsx.util.TargetPlatform;
import org.eclipse.openvsx.util.VersionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsImpl;
import org.springframework.data.elasticsearch.core.TotalHitsRelation;
import org.springframework.data.util.Streamable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.eclipse.openvsx.entities.FileResource.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VSCodeAPI.class)
@AutoConfigureWebClient
@MockBean({
    ClientRegistrationRepository.class, GoogleCloudStorageService.class, AzureBlobStorageService.class,
    AzureDownloadCountService.class, CacheService.class, UpstreamVSCodeService.class,
    VSCodeIdService.class, EntityManager.class, EclipseService.class, ExtensionValidator.class,
    SimpleMeterRegistry.class
})
class VSCodeAPITest {

    @MockBean
    EntityManager entityManager;

    @MockBean
    RepositoryService repositories;

    @MockBean
    SearchUtilService search;

    @MockBean
    ExtensionVersionIntegrityService integrityService;

    @Autowired
    MockMvc mockMvc;

    @Test
    void testSearch() throws Exception {
        var extension = mockSearch(true);
        mockExtensionVersions(extension, null, "universal");

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("search-yaml-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("search-yaml-response.json")));
    }

    @Test
    void testSearchMacOSXTarget() throws Exception {
        var targetPlatform = "darwin-x64";
        var extension = mockSearch(targetPlatform, true);
        mockExtensionVersions(extension, targetPlatform, targetPlatform);

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("search-yaml-query-darwin.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("search-yaml-response-darwin.json")));
    }

    @Test
    void testSearchExcludeBuiltInExtensions() throws Exception {
        var extension = mockSearch(null, "vscode", true);
        mockExtensionVersions(extension, null,"universal");

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("search-yaml-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("search-yaml-response-builtin-extensions.json")));
    }

    @Test
    void testSearchMultipleTargetsResponse() throws Exception {
        var extension = mockSearch(true);
        mockExtensionVersions(extension, null, "darwin-x64", "linux-x64", "alpine-arm64");

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("search-yaml-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("search-yaml-response-targets.json")));
    }

    @Test
    void testFindById() throws Exception {
        var extension = mockSearch(true);
        mockExtensionVersions(extension, null, "universal");

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("findid-yaml-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("findid-yaml-response.json")));
    }

    @Test
    void testFindByIdAlpineTarget() throws Exception {
        var targetPlatform = "alpine-arm64";
        var extension = mockSearch(targetPlatform, true);
        mockExtensionVersions(extension, targetPlatform, targetPlatform);

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("findid-yaml-query-alpine.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("findid-yaml-response-alpine.json")));
    }

    @Test
    void testFindByIdDuplicate() throws Exception {
        var extension = mockSearch(true);
        mockExtensionVersions(extension, null, "universal");

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("findid-yaml-duplicate-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("findid-yaml-response.json")));
    }

    @Test
    void testFindByIdInactive() throws Exception {
        mockSearch(false);
        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("findid-yaml-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("empty-response.json")));
    }

    @Test
    void testFindByName() throws Exception {
        var extension = mockSearch(true);
        mockExtensionVersions(extension, null, "universal");

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("findname-yaml-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("findname-yaml-response.json")));
    }

    @Test
    void testFindByNameLinuxTarget() throws Exception {
        var targetPlatform = "linux-x64";
        var extension = mockSearch(targetPlatform, true);
        mockExtensionVersions(extension, targetPlatform, targetPlatform);

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("findname-yaml-query-linux.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("findname-yaml-response-linux.json")));
    }

    @Test
    void testFindByNameDuplicate() throws Exception {
        var extension = mockSearch(true);
        mockExtensionVersions(extension, null,"universal");

        mockMvc.perform(post("/vscode/gallery/extensionquery")
                .content(file("findname-yaml-duplicate-query.json"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(file("findname-yaml-response.json")));
    }

    @Test
    void testAsset() throws Exception {
        mockExtensionVersion();

        var path = Path.of("/tmp", "redhat", "vscode-yaml", "0.5.2", "package.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"foo\":\"bar\"}");

        mockMvc.perform(get("/vscode/asset/{namespace}/{extensionName}/{version}/{assetType}",
                    "redhat", "vscode-yaml", "0.5.2", "Microsoft.VisualStudio.Code.Manifest"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("{\"foo\":\"bar\"}"))
                .andDo(result -> Files.delete(path));
    }

    @Test
    void testAssetMacOSX() throws Exception {
        var target = "darwin-arm64";
        mockExtensionVersion(target);

        var path = Path.of("/tmp", "redhat", "vscode-yaml", target, "0.5.2", "package.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"foo\":\"bar\",\"target\":\"darwin-arm64\"}");

        mockMvc.perform(get("/vscode/asset/{namespace}/{extensionName}/{version}/{assetType}?targetPlatform={target}",
                "redhat", "vscode-yaml", "0.5.2", "Microsoft.VisualStudio.Code.Manifest", target))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("{\"foo\":\"bar\",\"target\":\"darwin-arm64\"}"))
                .andDo(result -> Files.delete(path));
    }

    @Test
    void testAssetNotFound() throws Exception {
        mockExtensionVersion();
        Mockito.when(repositories.findFileByType("redhat", "vscode-yaml", "universal", "0.5.2", FileResource.MANIFEST))
                .thenReturn(null);
        mockMvc.perform(get("/vscode/asset/{namespace}/{extensionName}/{version}/{assetType}",
                    "redhat", "vscode-yaml", "0.5.2", "Microsoft.VisualStudio.Code.Manifest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testWebResourceAsset() throws Exception {
        var extVersion = mockExtensionVersion();

        var resource = new FileResource();
        resource.setExtension(extVersion);
        resource.setName("extension/img/logo.png");
        resource.setType(RESOURCE);
        resource.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findFileByTypeAndName("redhat", "vscode-yaml", TargetPlatform.NAME_UNIVERSAL,"0.5.2", FileResource.RESOURCE, "extension/img/logo.png")).thenReturn(resource);

        var path = Path.of("/tmp", "redhat", "vscode-yaml", "0.5.2", "extension", "img", "logo.png");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "logo.png");

        mockMvc.perform(get("/vscode/asset/{namespace}/{extensionName}/{version}/{assetType}",
                "redhat", "vscode-yaml", "0.5.2", "Microsoft.VisualStudio.Code.WebResources/extension/img/logo.png"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("logo.png"))
                .andDo(result -> Files.delete(path));
    }

    @Test
    void testNotWebResourceAsset() throws Exception {
        mockExtensionVersion();
        mockMvc.perform(get("/vscode/asset/{namespace}/{extensionName}/{version}/{assetType}",
                "redhat", "vscode-yaml", "0.5.2", "Microsoft.VisualStudio.Code.WebResources/img/logo.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testAssetExcludeBuiltInExtensions() throws Exception {
        mockMvc.perform(get("/vscode/asset/{namespace}/{extensionName}/{version}/{assetType}",
                "vscode", "vscode-yaml", "0.5.2", "Microsoft.VisualStudio.Code.Manifest"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Built-in extension namespace 'vscode' not allowed"));
    }

    @Test
    void testGetItem() throws Exception {
        var extension = mockExtension();
        extension.setActive(true);
        Mockito.when(repositories.findActiveExtension("vscode-yaml", "redhat")).thenReturn(extension);
        mockMvc.perform(get("/vscode/item?itemName={itemName}", "redhat.vscode-yaml"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/extension/redhat/vscode-yaml"));
    }

    @Test
    void testGetItemExcludeBuiltInExtensions() throws Exception {
        mockMvc.perform(get("/vscode/item?itemName={itemName}", "vscode.vscode-yaml"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Built-in extension namespace 'vscode' not allowed"));
    }

    @Test
    void testGetItemBadRequest() throws Exception {
        mockMvc.perform(get("/vscode/item?itemName={itemName}", "vscode-yaml"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Expecting an item of the form `{publisher}.{name}`"));
    }

    @Test
    void testBrowseNotFound() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1L);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);

        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension/img"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testBrowseExcludeBuiltInExtensions() throws Exception {
        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", "vscode", "bar", "1.3.4", "extension/img"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Built-in extension namespace 'vscode' not allowed"));
    }

    @Test
    void testBrowseTopDir() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1L);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);

        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var vsixManifest = new FileResource();
        vsixManifest.setExtension(extVersion);
        vsixManifest.setName("extension.vsixmanifest");
        vsixManifest.setType(RESOURCE);
        vsixManifest.setStorageType(STORAGE_LOCAL);

        var packageJson = new FileResource();
        packageJson.setExtension(extVersion);
        packageJson.setName("extension/package.json");
        packageJson.setType(RESOURCE);
        packageJson.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "")).thenReturn(List.of(vsixManifest, packageJson));

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}", namespaceName, extensionName, version))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension.vsixmanifest\",\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension/\"]"));
    }

    @Test
    void testBrowseVsixManifest() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1L);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);

        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var resource = new FileResource();
        resource.setExtension(extVersion);
        resource.setName("extension.vsixmanifest");
        resource.setType(RESOURCE);
        resource.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "extension.vsixmanifest")).thenReturn(List.of(resource));

        var path = Path.of("/tmp", namespaceName, extensionName, version, "extension.vsixmanifest");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<xml></xml>");

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension.vsixmanifest"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("<xml></xml>"))
                .andDo(result -> Files.delete(path));
    }

    @Test
    void testBrowseVsixManifestUniversal() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);

        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var resource = new FileResource();
        resource.setExtension(extVersion);
        resource.setName("extension.vsixmanifest");
        resource.setType(RESOURCE);
        resource.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "extension.vsixmanifest")).thenReturn(List.of(resource));

        var path = Path.of("/tmp", namespaceName, extensionName, version, "extension.vsixmanifest");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<xml></xml>");

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension.vsixmanifest"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("<xml></xml>"))
                .andDo(result -> Files.delete(path));
    }

    @Test
    void testBrowseVsixManifestWindows() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(2);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_WIN32_X64);
        extVersion.setExtension(extension);

        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var resource = new FileResource();
        resource.setExtension(extVersion);
        resource.setName("extension.vsixmanifest");
        resource.setType(RESOURCE);
        resource.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "extension.vsixmanifest")).thenReturn(List.of(resource));

        var path = Path.of("/tmp", namespaceName, extensionName, "win32-x64", version, "extension.vsixmanifest");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<xml></xml>");

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension.vsixmanifest"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("<xml></xml>"));
    }

    @Test
    void testBrowseExtensionDir() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1L);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);

        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var packageJson = new FileResource();
        packageJson.setExtension(extVersion);
        packageJson.setName("extension/package.json");
        packageJson.setType(RESOURCE);
        packageJson.setStorageType(STORAGE_LOCAL);

        var readme = new FileResource();
        readme.setExtension(extVersion);
        readme.setName("extension/README.md");
        readme.setType(RESOURCE);
        readme.setStorageType(STORAGE_LOCAL);

        var changelog = new FileResource();
        changelog.setExtension(extVersion);
        changelog.setName("extension/CHANGELOG.md");
        changelog.setType(RESOURCE);
        changelog.setStorageType(STORAGE_LOCAL);

        var license = new FileResource();
        license.setExtension(extVersion);
        license.setName("extension/LICENSE.txt");
        license.setType(RESOURCE);
        license.setStorageType(STORAGE_LOCAL);

        var icon = new FileResource();
        icon.setExtension(extVersion);
        icon.setName("extension/images/icon.png");
        icon.setType(RESOURCE);
        icon.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "extension")).thenReturn(List.of(packageJson, readme, changelog, license, icon));

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension/"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[" +
                        "\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension/package.json\"," +
                        "\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension/README.md\"," +
                        "\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension/CHANGELOG.md\"," +
                        "\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension/LICENSE.txt\"," +
                        "\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension/images/\"" +
                        "]"));
    }

    @Test
    void testBrowsePackageJson() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1L);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);
        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var resource = new FileResource();
        resource.setExtension(extVersion);
        resource.setName("extension/package.json");
        resource.setType(RESOURCE);
        resource.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "extension/package.json")).thenReturn(List.of(resource));

        var path = Path.of("/tmp", namespaceName, extensionName, version, "extension", "package.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"package\":\"json\"}");

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension/package.json"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("{\"package\":\"json\"}"))
                .andDo(result -> Files.delete(path));
    }

    @Test
    void testBrowseImagesDir() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1L);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);
        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var resource = new FileResource();
        resource.setExtension(extVersion);
        resource.setName("extension/images/icon128.png");
        resource.setType(RESOURCE);
        resource.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "extension/images")).thenReturn(List.of(resource));

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension/images/"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[\"http://localhost/vscode/unpkg/foo/bar/1.3.4/extension/images/icon128.png\"]"));
    }

    @Test
    void testBrowseIcon() throws Exception {
        var version = "1.3.4";
        var extensionName = "bar";
        var namespaceName = "foo";
        var namespace = new Namespace();
        namespace.setName(namespaceName);
        var extension = new Extension();
        extension.setId(0L);
        extension.setName(extensionName);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extVersion.setId(1L);
        extVersion.setVersion(version);
        extVersion.setTargetPlatform(TargetPlatform.NAME_UNIVERSAL);
        extVersion.setExtension(extension);
        Mockito.when(repositories.findActiveExtensionVersion(version, extensionName, namespaceName))
                .thenReturn(extVersion);

        var resource = new FileResource();
        resource.setExtension(extVersion);
        resource.setName("extension/images/icon128.png");
        resource.setType(RESOURCE);
        resource.setStorageType(STORAGE_LOCAL);

        Mockito.when(repositories.findResourceFileResources(extVersion, "extension/images/icon128.png")).thenReturn(List.of(resource));

        var path = Path.of("/tmp", namespaceName, extensionName, version, "extension", "images", "icon128.png");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "ICON128");

        mockMvc.perform(get("/vscode/unpkg/{namespaceName}/{extensionName}/{version}/{path}", namespaceName, extensionName, version, "extension/images/icon128.png"))
                .andExpect(request().asyncStarted())
                .andDo(MvcResult::getAsyncResult)
                .andExpect(status().isOk())
                .andExpect(content().string("ICON128"))
                .andDo(result -> Files.delete(path));
    }

    @Test
    void testDownload() throws Exception {
        mockExtensionVersion();
        mockMvc.perform(get("/vscode/gallery/publishers/{namespace}/vsextensions/{extension}/{version}/vspackage",
                "redhat", "vscode-yaml", "0.5.2"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost/vscode/asset/redhat/vscode-yaml/0.5.2/Microsoft.VisualStudio.Services.VSIXPackage"));
    }

    @Test
    void testDownloadMacOSX() throws Exception {
        mockExtensionVersion("darwin-arm64");
        mockMvc.perform(get("/vscode/gallery/publishers/{namespace}/vsextensions/{extension}/{version}/vspackage?targetPlatform={target}",
                "redhat", "vscode-yaml", "0.5.2", "darwin-arm64"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost/vscode/asset/redhat/vscode-yaml/0.5.2/Microsoft.VisualStudio.Services.VSIXPackage?targetPlatform=darwin-arm64"));
    }

    @Test
    void testDownloadExcludeBuiltInExtensions() throws Exception {
        mockMvc.perform(get("/vscode/gallery/publishers/{namespace}/vsextensions/{extension}/{version}/vspackage",
                "vscode", "vscode-yaml", "0.5.2"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Built-in extension namespace 'vscode' not allowed"));
    }

    // ---------- UTILITY ----------//
    private Extension mockSearch(boolean active) {
        return mockSearch(null, active);
    }

    private Extension mockSearch(String targetPlatform, boolean active) {
        return mockSearch(targetPlatform, null, active);
    }

    private Extension mockSearch(String targetPlatform, String namespaceName, boolean active) {
        var builtInExtensionNamespace = "vscode";
        var entry1 = new ExtensionSearch();
        entry1.setId(1);
        List<SearchHit<ExtensionSearch>> searchResults = !builtInExtensionNamespace.equals(namespaceName)
                ? Collections.singletonList(new SearchHit<>("0", "1", null, 1.0f, null, null, null, null, null, null, entry1))
                : Collections.emptyList();
        var searchHits = new SearchHitsImpl<>(searchResults.size(), TotalHitsRelation.EQUAL_TO, 1.0f, "1", null,
                searchResults, null, null);

        Mockito.when(integrityService.isEnabled())
                .thenReturn(true);
        Mockito.when(search.isEnabled())
                .thenReturn(true);
        var searchOptions = new ISearchService.Options("yaml", null, targetPlatform, 50, 0, "desc", "relevance", false, new String[]{builtInExtensionNamespace});
        Mockito.when(search.search(searchOptions))
                .thenReturn(searchHits);

        var extension = mockExtension();
        List<Extension> results = active ? List.of(extension) : Collections.emptyList();
        Mockito.when(repositories.findActiveExtensionsById(List.of(entry1.getId())))
                .thenReturn(results);

        var publicIds = Set.of(extension.getPublicId());
        Mockito.when(repositories.findActiveExtensionsByPublicId(publicIds, builtInExtensionNamespace))
                .thenReturn(results);

        var ids = List.of(extension.getId());
        Mockito.when(repositories.findActiveExtension(extension.getName(), extension.getNamespace().getName()))
                .thenReturn(extension);

        mockExtensionVersions(extension, targetPlatform, targetPlatform);
        return extension;
    }

    private Extension mockExtension() {
            var namespace = new Namespace();
            namespace.setId(2);
            namespace.setPublicId("test-2");
            namespace.setName("redhat");

            var extension = new Extension();
            extension.setId(1);
            extension.setPublicId("test-1");
            extension.setName("vscode-yaml");
            extension.setAverageRating(3.0);
            extension.setReviewCount(10L);
            extension.setDownloadCount(100);
            extension.setPublishedDate(LocalDateTime.parse("1999-12-01T09:00"));
            extension.setLastUpdatedDate(LocalDateTime.parse("2000-01-01T10:00"));
            extension.setNamespace(namespace);

            return extension;
    }

    private void mockExtensionVersions(Extension extension, String queryTargetPlatform, String... targetPlatforms) {
        var id = 2;
        var versions = new ArrayList<ExtensionVersion>(targetPlatforms.length);
        for(var targetPlatform : targetPlatforms) {
            versions.add(mockExtensionVersion(extension, id, targetPlatform));
            id++;
        }

        Mockito.when(repositories.findActiveExtensionVersions(Set.of(extension.getId()), queryTargetPlatform))
                .thenReturn(versions);

        mockFileResources(versions);
    }

    private ExtensionVersion mockExtensionVersion(Extension extension, long id, String targetPlatform) {
        var extVersion = new ExtensionVersion();
        extVersion.setId(id);
        extVersion.setVersion("0.5.2");
        extVersion.setTargetPlatform(targetPlatform);
        extVersion.setPreview(true);
        extVersion.setTimestamp(LocalDateTime.parse("2000-01-01T10:00"));
        extVersion.setDisplayName("YAML");
        extVersion.setDescription("YAML Language Support");
        extVersion.setEngines(List.of("vscode@^1.31.0"));
        extVersion.setRepository("https://github.com/redhat-developer/vscode-yaml");
        extVersion.setDependencies(Collections.emptyList());
        extVersion.setBundledExtensions(Collections.emptyList());
        extVersion.setLocalizedLanguages(Collections.emptyList());
        extVersion.setExtension(extension);

        var keyPair = new SignatureKeyPair();
        keyPair.setPublicId("123-456-789");
        extVersion.setSignatureKeyPair(keyPair);

        mockFileResources(List.of(extVersion));
        return extVersion;
    }

    private void mockFileResources(List<ExtensionVersion> extensionVersions) {
        var types = List.of(MANIFEST, README, LICENSE, ICON, DOWNLOAD, CHANGELOG, VSIXMANIFEST, DOWNLOAD_SIG);

        var files = new ArrayList<FileResource>();
        for(var extVersion : extensionVersions) {
            var id = extVersion.getId();
            files.add(mockFileResource(id * 100 + 5, extVersion, "redhat.vscode-yaml-0.5.2.vsix", DOWNLOAD));
            files.add(mockFileResource(id * 100 + 6, extVersion, "package.json", MANIFEST));
            files.add(mockFileResource(id * 100 + 7, extVersion, "README.md", README));
            files.add(mockFileResource(id * 100 + 8, extVersion, "CHANGELOG.md", CHANGELOG));
            files.add(mockFileResource(id * 100 + 9, extVersion, "LICENSE.txt", LICENSE));
            files.add(mockFileResource(id * 100 + 10, extVersion, "icon128.png", ICON));
            files.add(mockFileResource(id * 100 + 11, extVersion, "extension.vsixmanifest", VSIXMANIFEST));
            files.add(mockFileResource(id * 100 + 12, extVersion, "redhat.vscode-yaml-0.5.2.sigzip", DOWNLOAD_SIG));
        }

        var ids = extensionVersions.stream().map(ExtensionVersion::getId).collect(Collectors.toSet());
        Mockito.when(repositories.findFileResourcesByExtensionVersionIdAndType(ids, types))
                .thenReturn(files);
    }

    private FileResource mockFileResource(long id, ExtensionVersion extVersion, String name, String type) {
        var resource = new FileResource();
        resource.setId(id);
        resource.setExtension(extVersion);
        resource.setName(name);
        resource.setType(type);

        return resource;
    }

    private FileResource mockFileResource(long id, ExtensionVersion extVersion, String name, String type, String storageType) {
        var resource = mockFileResource(id, extVersion, name, type);
        resource.setStorageType(storageType);
        return resource;
    }

    private ExtensionVersion mockExtensionVersion() throws JsonProcessingException {
        return mockExtensionVersion(TargetPlatform.NAME_UNIVERSAL);
    }

    private ExtensionVersion mockExtensionVersion(String targetPlatform) throws JsonProcessingException {
        var namespace = new Namespace();
        namespace.setId(2);
        namespace.setPublicId("test-2");
        namespace.setName("redhat");
        var extension = new Extension();
        extension.setId(1);
        extension.setPublicId("test-1");
        extension.setName("vscode-yaml");
        extension.setActive(true);
        extension.setDownloadCount(100);
        extension.setAverageRating(3.0);
        extension.setNamespace(namespace);
        var extVersion = new ExtensionVersion();
        extension.getVersions().add(extVersion);
        extVersion.setExtension(extension);
        extVersion.setTargetPlatform(targetPlatform);
        extVersion.setVersion("0.5.2");
        extVersion.setPreRelease(true);
        extVersion.setTimestamp(LocalDateTime.parse("2000-01-01T10:00"));
        extVersion.setActive(true);
        extVersion.setDisplayName("YAML");
        extVersion.setDescription("YAML Language Support");
        extVersion.setRepository("https://github.com/redhat-developer/vscode-yaml");
        extVersion.setEngines(Lists.newArrayList("vscode@^1.31.0"));
        extVersion.setDependencies(Lists.newArrayList());
        extVersion.setBundledExtensions(Lists.newArrayList());
        Mockito.when(repositories.findExtensionByPublicId("test-1"))
                .thenReturn(extension);
        Mockito.when(repositories.findExtension("vscode-yaml", "redhat"))
                .thenReturn(extension);
        Mockito.when(repositories.findVersion("0.5.2", targetPlatform, "vscode-yaml", "redhat"))
                .thenReturn(extVersion);
        Mockito.when(repositories.findVersions(extension))
                .thenReturn(Streamable.of(extVersion));
        var extensionFile = new FileResource();
        extensionFile.setId(10L);
        extensionFile.setExtension(extVersion);
        extensionFile.setName("redhat.vscode-yaml-0.5.2.vsix");
        extensionFile.setType(FileResource.DOWNLOAD);
        extensionFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, extensionFile.getId())).thenReturn(extensionFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), DOWNLOAD))
                .thenReturn(extensionFile);

        var manifestFile = new FileResource();
        manifestFile.setId(11L);
        manifestFile.setExtension(extVersion);
        manifestFile.setName("package.json");
        manifestFile.setType(FileResource.MANIFEST);
        manifestFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, manifestFile.getId())).thenReturn(manifestFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), FileResource.MANIFEST))
                .thenReturn(manifestFile);
        var readmeFile = new FileResource();
        readmeFile.setExtension(extVersion);
        readmeFile.setName("README.md");
        readmeFile.setType(FileResource.README);
        readmeFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, readmeFile.getId())).thenReturn(readmeFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), README))
                .thenReturn(readmeFile);
        var changelogFile = new FileResource();
        changelogFile.setExtension(extVersion);
        changelogFile.setName("CHANGELOG.md");
        changelogFile.setType(FileResource.CHANGELOG);
        changelogFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, changelogFile.getId())).thenReturn(changelogFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), CHANGELOG))
                .thenReturn(changelogFile);
        var licenseFile = new FileResource();
        licenseFile.setExtension(extVersion);
        licenseFile.setName("LICENSE.txt");
        licenseFile.setType(FileResource.LICENSE);
        licenseFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, licenseFile.getId())).thenReturn(licenseFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), LICENSE))
                .thenReturn(licenseFile);
        var iconFile = new FileResource();
        iconFile.setExtension(extVersion);
        iconFile.setName("icon128.png");
        iconFile.setType(FileResource.ICON);
        iconFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, iconFile.getId())).thenReturn(iconFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), ICON))
                .thenReturn(iconFile);
        var vsixManifestFile = new FileResource();
        vsixManifestFile.setExtension(extVersion);
        vsixManifestFile.setName("extension.vsixmanifest");
        vsixManifestFile.setType(VSIXMANIFEST);
        vsixManifestFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, vsixManifestFile.getId())).thenReturn(vsixManifestFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), VSIXMANIFEST))
                .thenReturn(vsixManifestFile);
        var signatureFile = new FileResource();
        signatureFile.setExtension(extVersion);
        signatureFile.setName("redhat.vscode-yaml-0.5.2.sigzip");
        signatureFile.setType(FileResource.DOWNLOAD_SIG);
        signatureFile.setStorageType(FileResource.STORAGE_LOCAL);
        Mockito.when(entityManager.find(FileResource.class, signatureFile.getId())).thenReturn(signatureFile);
        Mockito.when(repositories.findFileByType(namespace.getName(), extension.getName(), targetPlatform, extVersion.getVersion(), DOWNLOAD_SIG))
                .thenReturn(signatureFile);
        Mockito.when(repositories.findFilesByType(anyCollection(), anyCollection())).thenAnswer(invocation -> {
            Collection<ExtensionVersion> extVersions = invocation.getArgument(0);
            var types = invocation.getArgument(1);
            var expectedTypes = Arrays.asList(FileResource.MANIFEST, FileResource.README, FileResource.LICENSE, FileResource.ICON, FileResource.DOWNLOAD, FileResource.CHANGELOG, VSIXMANIFEST, DOWNLOAD_SIG);
            return types.equals(expectedTypes) && extVersions.iterator().hasNext() && extVersion.equals(extVersions.iterator().next())
                    ? Streamable.of(manifestFile, readmeFile, licenseFile, iconFile, extensionFile, changelogFile, vsixManifestFile, signatureFile)
                    : Streamable.empty();
        });

        return extVersion;
    }

    private String file(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream(name)) {
            return CharStreams.toString(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @TestConfiguration
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean
        IExtensionQueryRequestHandler extensionQueryRequestHandler(LocalVSCodeService local, UpstreamVSCodeService upstream) {
            return new DefaultExtensionQueryRequestHandler(local, upstream);
        }

        @Bean
        TransactionTemplate transactionTemplate() {
            return new MockTransactionTemplate();
        }

        @Bean
        OAuth2UserServices oauth2UserServices(
                UserService users,
                TokenService tokens,
                RepositoryService repositories,
                EntityManager entityManager,
                EclipseService eclipse
        ) {
            return new OAuth2UserServices(users, tokens, repositories, entityManager, eclipse);
        }

        @Bean
        TokenService tokenService(
                TransactionTemplate transactions,
                EntityManager entityManager,
                ClientRegistrationRepository clientRegistrationRepository
        ) {
            return new TokenService(transactions, entityManager, clientRegistrationRepository);
        }

        @Bean
        LocalVSCodeService localVSCodeService(
                RepositoryService repositories,
                VersionService versions,
                SearchUtilService search,
                StorageUtilService storageUtil,
                ExtensionVersionIntegrityService integrityService
        ) {
            return new LocalVSCodeService(repositories, versions, search, storageUtil, integrityService);
        }

        @Bean
        UserService userService(
                EntityManager entityManager,
                RepositoryService repositories,
                StorageUtilService storageUtil,
                CacheService cache,
                ExtensionValidator validator
        ) {
            return new UserService(entityManager, repositories, storageUtil, cache, validator);
        }

        @Bean
        StorageUtilService storageUtilService(
                RepositoryService repositories,
                GoogleCloudStorageService googleStorage,
                AzureBlobStorageService azureStorage,
                LocalStorageService localStorage,
                AzureDownloadCountService azureDownloadCountService,
                SearchUtilService search,
                CacheService cache,
                EntityManager entityManager
        ) {
            return new StorageUtilService(
                    repositories,
                    googleStorage,
                    azureStorage,
                    localStorage,
                    azureDownloadCountService,
                    search,
                    cache,
                    entityManager
            );
        }

        @Bean
        LocalStorageService localStorage() {
            return new LocalStorageService();
        }

        @Bean
        VersionService getVersionService() {
            return new VersionService();
        }

        @Bean
        LatestExtensionVersionCacheKeyGenerator latestExtensionVersionCacheKeyGenerator() {
            return new LatestExtensionVersionCacheKeyGenerator();
        }
    }

}
