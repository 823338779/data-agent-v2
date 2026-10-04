package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.application.DbtGitBranchRegistry;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.client.HttpDbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.git.model.GitException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 分支操作使用受控项目与部署凭据，能力不足时在写入前失败。 */
class DbtGitBranchRegistryTest {
    private static final String BRANCH = "be264ade-8043-4fca-b34a-76985d54b250";
    private static final String TOKEN = "local-test-branch-token";
    private static final String PROJECT = "sample";
    private static final String ROOT = ".";
    private static final String RESPONSE =
            "{\"projectId\":\"sample\",\"branchId\":\"" + BRANCH + "\"}";
    private static final String PREFIX = "/v2/projects/sample/branches";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String JSON = "application/json";
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String LOCAL = "http://127.0.0.1:";
    private final JsonMapper json = new JsonMapper();

    @Test
    void branchTransportPinsProjectAndAddsDeploymentCredential() throws Exception {
        var path = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                PREFIX,
                exchange -> {
                    path.set(exchange.getRequestURI().toString());
                    auth.set(exchange.getRequestHeaders().getFirst(AUTHORIZATION));
                    byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set(CONTENT_TYPE, JSON);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var properties =
                new DbtProperties(
                        true,
                        URI.create(LOCAL + server.getAddress().getPort()),
                        PROJECT,
                        ROOT,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        TOKEN);
        try (var client = new HttpDbtServiceClient(properties, json)) {
            assertThat(client.createBranch(json.createObjectNode()).path("branchId").asString())
                    .isEqualTo(BRANCH);
            assertThat(path.get()).isEqualTo(PREFIX);
            assertThat(auth.get()).isEqualTo(BEARER + TOKEN);
            client.deleteBranch(BRANCH, 2);
            assertThat(path.get()).isEqualTo(PREFIX + "/" + BRANCH + "?expectedVersion=2");
            assertThat(properties.toString()).doesNotContain(TOKEN);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingCapabilityRejectsBeforeRemoteMutation() throws Exception {
        var client = mock(DbtServiceClient.class);
        when(client.project()).thenReturn(json.readTree("{\"capabilities\":[]}"));
        var registry = new DbtGitBranchRegistry(client);
        assertThatThrownBy(() -> registry.create(json.createObjectNode()))
                .isInstanceOf(GitException.class);
        verify(client, never()).createBranch(any());
    }
}
