package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.dbt.client.HttpDbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 对齐服务原生数组目录与验证回执，不把它们错误识别为普通对象详情。 */
class DbtBranchTransportTest {
    @Test
    void acceptsListAndReceiptShapesFromServiceContract() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String body =
                            path.endsWith("/branches")
                                    ? "[{\"branchId\":\"a\"}]"
                                    : "{\"validationId\":\"c35f7be0-e439-4c8d-ae21-c19d0ad59dc6\",\"state\":\"QUEUED\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var json = new ObjectMapper();
        var settings =
                new DbtProperties(
                        true,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "metrics",
                        ".",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        "test-token");
        try (var client = new HttpDbtServiceClient(settings, json)) {
            assertThat(client.branches().isArray()).isTrue();
            assertThat(
                            client.submitValidation("a", json.createObjectNode())
                                    .path("state")
                                    .asString())
                    .isEqualTo("QUEUED");
        } finally {
            server.stop(0);
        }
    }
}
