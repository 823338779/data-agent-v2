package com.datagent.agentv2.dbt;

import static com.datagent.agentv2.dbt.DbtServiceContractIntegrationTest.*;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.dbt.client.HttpDbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 本地真实HTTP验证执行快照路由、凭据、边界及无隐式重试。 */
class DbtServiceClientTest {
    static final UUID BUILD = UUID.fromString("12345678-1234-4234-8234-123456789abc");

    @Test
    void rejectsBuildWithoutExecutionSnapshot() throws Exception {
        // 成功响应也必须携带完整执行快照，不能将旧形态解释成有效构建。
        var body = (tools.jackson.databind.node.ObjectNode) fixture("failed-build").deepCopy();
        body.remove("execution");
        var server = server(exchange -> response(exchange, 200, body.toString()));
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            assertCode(() -> client.getBuild(BUILD), "DBT_PROTOCOL_INVALID");
        } finally {
            server.stop(0);
        }
    }

    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    static HttpServer server(Handler handler) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(
                "/",
                exchange -> {
                    try {
                        handler.handle(exchange);
                    } catch (Exception ignored) {
                        /* 取消响应时客户端已断开。 */
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        return server;
    }

    static HttpDbtServiceClient client(HttpServer server, Duration timeout, String token) {
        return new HttpDbtServiceClient(
                new DbtProperties(
                        true,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Duration.ofMillis(50),
                        timeout,
                        token),
                JSON);
    }

    static void response(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Test
    void queryUsesCurrentStatelessWireContract() throws Exception {
        var path = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server =
                server(
                        exchange -> {
                            path.set(exchange.getRequestURI().toString());
                            body.set(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            response(exchange, 200, fixture("precision-result").toString());
                        });
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            client.submitQuery(BUILD, JSON.treeToValue(fixture("query"), QueryRequest.class));
            assertThat(path.get()).isEqualTo("/query?build_id=" + BUILD);
            assertThat(JSON.readTree(body.get()).propertyNames())
                    .doesNotContain("idempotencyKey", "offset");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void buildRetryKeepsKeyAndOrigin() throws Exception {
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger accepted = new AtomicInteger();
        var server =
                server(
                        exchange -> {
                            bodies.add(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            if (accepted.incrementAndGet() == 1) {
                                exchange.close();
                                return;
                            }
                            response(exchange, 202, fixture("failed-build").toString());
                        });
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            var request = JSON.treeToValue(fixture("build"), BuildRequest.class);
            request =
                    new BuildRequest(
                            request.repository(),
                            request.branchName(),
                            request.commitSha(),
                            request.execution(),
                            request.deploymentPolicy(),
                            request.idempotencyKey(),
                            new Origin("session", "operation"));
            BuildRequest fixed = request;
            assertThatThrownBy(() -> client.submitBuild(fixed))
                    .isInstanceOf(ToolBusinessException.class);
            assertThat(accepted).hasValue(1);
            assertThat(client.submitBuild(fixed).buildId()).isEqualTo(BUILD);
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
            assertThat(JSON.readTree(bodies.getFirst()).path("execution").path("schema").asString())
                    .isEqualTo("analytics");
            assertThat(
                            JSON.readTree(bodies.getFirst())
                                    .path("origin")
                                    .path("operationId")
                                    .asString())
                    .isEqualTo("operation");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "explain", "preview", "dimension_values"})
    void synchronousQueryReturnsRowsInThePostResponse(String example) throws Exception {
        var request = JSON.treeToValue(fixture(example), QueryRequest.class);
        var page = (tools.jackson.databind.node.ObjectNode) fixture("precision-result").deepCopy();
        page.put("mode", request.mode().name());
        var wireBody = new AtomicReference<String>();
        var wirePath = new AtomicReference<String>();
        var server =
                server(
                        exchange -> {
                            wirePath.set(exchange.getRequestURI().toString());
                            wireBody.set(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            response(exchange, 200, page.toString());
                        });
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            var result = JSON.valueToTree(client.submitQuery(BUILD, request));
            assertThat(result.path("rows").size()).isEqualTo(1);
            assertThat(result.path("mode").asString()).isEqualTo(request.mode().name());
            assertThat(result.path("rows").get(0).get(0).asString())
                    .isEqualTo("12345678901234567890.123456789");
            assertThat(wirePath.get()).isEqualTo("/query?build_id=" + BUILD);
            assertThat(JSON.readTree(wireBody.get())).isEqualTo(fixture(example));
            assertThat(result.propertyNames()).doesNotContain("queryId", "state", "nextOffset");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void synchronousOptionsOnlySendMetricsAndReturnOptions() throws Exception {
        var body = new AtomicReference<String>();
        var server =
                server(
                        exchange -> {
                            body.set(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            response(
                                    exchange,
                                    200,
                                    fixture("available-dimensions-result").toString());
                        });
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            var options =
                    client.submitOptions(
                            BUILD, new AvailableDimensionsRequest(List.of("metric.revenue")));
            assertThat(options.buildId()).isEqualTo(BUILD);
            assertThat(options.dimensions()).isNotNull();
            assertThat(JSON.readTree(body.get()).propertyNames())
                    .containsExactly("metricResourceIds");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void synchronousQueryFailurePreservesStructuredErrorCode() throws Exception {
        var server = server(exchange -> response(exchange, 422, fixture("422").toString()));
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            assertCode(
                    () ->
                            client.submitQuery(
                                    BUILD, JSON.treeToValue(fixture("query"), QueryRequest.class)),
                    fixture("422").path("code").asString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void synchronousQueryRejectsAcceptedTaskWithoutQueryResult() throws Exception {
        // 模拟已退出的任务受理响应：即使状态成功，缺失结果页也不能作为同步成功返回。
        var incomplete =
                (tools.jackson.databind.node.ObjectNode) fixture("query-result").deepCopy();
        incomplete.remove("rows");
        var server = server(exchange -> response(exchange, 202, incomplete.toString()));
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            assertCode(
                    () ->
                            client.submitQuery(
                                    BUILD, JSON.treeToValue(fixture("query"), QueryRequest.class)),
                    "DBT_PROTOCOL_INVALID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writeRequestsUseServiceCredential() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> credentials = new ArrayList<>();
        var server =
                server(
                        exchange -> {
                            paths.add(exchange.getRequestURI().toString());
                            credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
                            String example =
                                    paths.getLast().startsWith("/query/available-dimensions?")
                                            ? "available-dimensions-result"
                                            : paths.getLast().startsWith("/query?")
                                                    ? "precision-result"
                                                    : "failed-build";
                            response(
                                    exchange,
                                    example.equals("failed-build") ? 202 : 200,
                                    fixture(example).toString());
                        });
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            client.submitBuild(JSON.treeToValue(fixture("build"), BuildRequest.class));
            client.submitOptions(
                    BUILD,
                    JSON.treeToValue(
                            fixture("available-dimensions-request"),
                            AvailableDimensionsRequest.class));
            client.submitQuery(BUILD, JSON.treeToValue(fixture("query"), QueryRequest.class));
            assertThat(paths)
                    .containsExactly(
                            "/builds",
                            "/query/available-dimensions?build_id=" + BUILD,
                            "/query?build_id=" + BUILD);
            assertThat(credentials)
                    .containsExactly("Bearer secret", "Bearer secret", "Bearer secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noCredentialRejectsWritesBeforeNetwork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var server = server(exchange -> calls.incrementAndGet());
        try (var client = client(server, Duration.ofSeconds(2), null)) {
            assertCode(
                    () ->
                            client.submitOptions(
                                    BUILD,
                                    JSON.treeToValue(
                                            fixture("available-dimensions-request"),
                                            AvailableDimensionsRequest.class)),
                    "DBT_SERVICE_NOT_CONFIGURED");
            assertThat(calls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "410,PHYSICAL_OBJECTS_REMOVED",
        "422,INVALID_DIMENSION_OPTION",
        "503,EXECUTION_ENVIRONMENT_UNAVAILABLE",
        "409,IDEMPOTENCY_CONFLICT"
    })
    void newEndpointFailureNeverCallsLegacyRoute(int status, String code) throws Exception {
        List<String> paths = new ArrayList<>();
        var server =
                server(
                        exchange -> {
                            paths.add(exchange.getRequestURI().toString());
                            response(
                                    exchange,
                                    status,
                                    "{\"error\":{\"code\":\""
                                            + code
                                            + "\",\"message\":\"private credential\"}}");
                        });
        try (var client = client(server, Duration.ofSeconds(2), "secret")) {
            assertCode(() -> client.getBuild(BUILD), code);
            assertThat(paths).containsExactly("/builds/" + BUILD);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectsNeverReceiveServiceCredential() throws Exception {
        AtomicInteger forwarded = new AtomicInteger();
        var other = server(exchange -> forwarded.incrementAndGet());
        var origin =
                server(
                        exchange -> {
                            exchange.getResponseHeaders()
                                    .add(
                                            "Location",
                                            "http://127.0.0.1:"
                                                    + other.getAddress().getPort()
                                                    + "/steal");
                            response(exchange, 307, "{}");
                        });
        try (var client = client(origin, Duration.ofSeconds(2), "secret")) {
            assertCode(
                    () ->
                            client.submitBuild(
                                    JSON.treeToValue(fixture("build"), BuildRequest.class)),
                    "DBT_UNAVAILABLE");
            assertThat(forwarded).hasValue(0);
        } finally {
            origin.stop(0);
            other.stop(0);
        }
    }

    @Test
    void malformedOversizedAndUnknownStatesFailClosed() throws Exception {
        for (String body :
                List.of(
                        "not json",
                        "x".repeat(8 * 1024 * 1024 + 1),
                        fixture("failed-build").toString().replace("FAILED", "UNKNOWN"))) {
            var server = server(exchange -> response(exchange, 200, body));
            try (var client = client(server, Duration.ofSeconds(2), null)) {
                assertCode(() -> client.getBuild(BUILD), "DBT_PROTOCOL_INVALID");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void requestDeadlineIncludesSlowResponseBody() throws Exception {
        var server =
                server(
                        exchange -> {
                            exchange.sendResponseHeaders(200, 0);
                            for (int i = 0; i < 100; i++) {
                                exchange.getResponseBody().write(' ');
                                exchange.getResponseBody().flush();
                                Thread.sleep(25);
                            }
                        });
        long start = System.nanoTime();
        try (var client = client(server, Duration.ofMillis(120), null)) {
            assertCode(() -> client.getBuild(BUILD), "DBT_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void typedReadRoutesPreserveFiltersAndResultMetadata() throws Exception {
        List<String> paths = new ArrayList<>();
        var server =
                server(
                        exchange -> {
                            String path = exchange.getRequestURI().getPath();
                            paths.add(exchange.getRequestURI().toString());
                            String name =
                                    path.endsWith("/catalog")
                                            ? "catalog"
                                            : path.endsWith("/source")
                                                    ? "source"
                                                    : path.endsWith("/lineage")
                                                            ? "lineage"
                                                            : path.endsWith("/native-details")
                                                                    ? "native-details"
                                                                    : path.contains("/resources/")
                                                                            ? "resource"
                                                                            : path.endsWith(
                                                                                            "/results")
                                                                                    ? "precision-result"
                                                                                    : path.endsWith(
                                                                                                    "/current")
                                                                                            ? "empty-target"
                                                                                            : "waiting-intent";
                            response(
                                    exchange,
                                    200,
                                    path.equals("/deployments")
                                            ? "{\"items\":["
                                                    + fixture(name)
                                                    + "],\"nextCursor\":null}"
                                            : fixture(name).toString());
                        });
        try (var client = client(server, Duration.ofSeconds(2), null)) {
            String repo = "https://git.example.com/metrics.git";
            client.currentDeployment(
                    new DeploymentKey(
                            repo,
                            UUID.fromString("12345678-1234-4234-8234-000000000001"),
                            "feature/revenue"));
            assertThat(
                            client.listDeployments(
                                            new DeploymentFilter(
                                                    repo, null, "feature/revenue", null, 50))
                                    .items())
                    .hasSize(1);
            assertThat(
                            client.catalog(
                                            BUILD,
                                            new CatalogFilter("收 入", "METRIC", "next+token", 50))
                                    .items())
                    .hasSize(5);
            client.resource(BUILD, "metric.revenue");
            client.resourceSource(BUILD, "metric.revenue");
            client.resourceLineage(BUILD, "metric.revenue");
            client.resourceNative(BUILD, "metric.revenue");
            assertThat(paths)
                    .anyMatch(path -> path.contains("branchName=feature%2Frevenue"))
                    .anyMatch(path -> path.contains("cursor=next%2Btoken"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseBelongingToAnotherBuildIsRejected() throws Exception {
        var server = server(exchange -> response(exchange, 200, fixture("catalog").toString()));
        try (var client = client(server, Duration.ofSeconds(2), null)) {
            assertCode(
                    () -> client.catalog(UUID.randomUUID(), new CatalogFilter("", null, null, 20)),
                    "DBT_PROTOCOL_INVALID");
        } finally {
            server.stop(0);
        }
    }

    static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        ToolBusinessException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(code);
                            assertThat(error.getMessage()).doesNotContain("private credential");
                        });
    }
}
