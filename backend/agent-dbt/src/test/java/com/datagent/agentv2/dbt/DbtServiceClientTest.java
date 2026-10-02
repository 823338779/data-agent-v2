package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.dbt.client.HttpDbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** HTTP 边界用真实本地服务验证，不能向模型泄露服务异常正文。 */
class DbtServiceClientTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "404,DBT_NOT_FOUND",
        "409,DBT_CONFLICT",
        "410,DBT_RELEASE_GONE",
        "422,DBT_INVALID_SELECTION",
        "503,DBT_UNAVAILABLE"
    })
    void statusMatrix(int status, String code) throws Exception {
        response(
                status,
                "{\"detail\":{\"reason\":\"invalid_dimension_option\",\"field\":\"groupBy[0].optionId\",\"recovery\":\"reload_query_options\",\"message\":\"private\"}}",
                Duration.ofSeconds(2),
                0,
                code,
                true);
    }

    @Test
    void malformedAndOversizedBodiesAreRejected() throws Exception {
        response(200, "not json", Duration.ofSeconds(2), 0, "DBT_PROTOCOL_INVALID", false);
        response(
                200,
                "x".repeat(8 * 1024 * 1024 + 1),
                Duration.ofSeconds(2),
                0,
                "DBT_PROTOCOL_INVALID",
                false);
    }

    @Test
    void requestHasTotalDeadline() throws Exception {
        response(200, "{}", Duration.ofMillis(100), 500, "DBT_TIMEOUT", false);
    }

    private void response(
            int status, String body, Duration timeout, long delay, String code, boolean detail)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        Thread.sleep(delay);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var client =
                new HttpDbtServiceClient(
                        new DbtProperties(
                                true,
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                "sample",
                                ".",
                                Duration.ofMillis(50),
                                timeout),
                        new JsonMapper())) {
            assertThatThrownBy(client::project)
                    .isInstanceOfSatisfying(
                            ToolBusinessException.class,
                            error -> {
                                assertThat(error.code()).isEqualTo(code);
                                assertThat(error.getMessage()).doesNotContain("private");
                                if (detail)
                                    assertThat(error.getMessage())
                                            .contains(
                                                    "invalid_dimension_option",
                                                    "groupBy[0].optionId",
                                                    "reload_query_options");
                            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fixedProjectRoutesAndSafeErrorMapping() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    byte[] content =
                            "{\"detail\":{\"code\":\"release_gone\",\"message\":\"secret\"}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(410, content.length);
                    exchange.getResponseBody().write(content);
                    exchange.close();
                });
        server.start();
        try (var client =
                new HttpDbtServiceClient(
                        new DbtProperties(
                                true,
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                "sample",
                                ".",
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(20)),
                        new JsonMapper())) {
            assertThatThrownBy(client::project)
                    .isInstanceOfSatisfying(
                            ToolBusinessException.class,
                            error -> {
                                assertThat(error.code()).isEqualTo("DBT_RELEASE_GONE");
                                assertThat(error.getMessage()).doesNotContain("secret");
                            });
            assertThat(path.get()).isEqualTo("/v2/projects/sample/publication");
        } finally {
            server.stop(0);
        }
    }
}
