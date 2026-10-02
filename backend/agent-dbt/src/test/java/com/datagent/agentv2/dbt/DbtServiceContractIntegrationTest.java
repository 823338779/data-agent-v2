package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.dbt.client.HttpDbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.tool.DbtAnalysisTool;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 独立构建消费与 Python 相同的版本化样例，并校验传输和数值精度。 */
class DbtServiceContractIntegrationTest {
    @Test
    void fixturesHaveMatchingHashesAndFlowThroughRealHttp() throws Exception {
        var json = new JsonMapper();
        var manifest = json.readTree(getClass().getResourceAsStream("/dbt-contract/manifest.json"));
        for (String name : manifest.path("sha256").propertyNames()) {
            try (var stream = getClass().getResourceAsStream("/dbt-contract/" + name)) {
                assertThat(
                                HexFormat.of()
                                        .formatHex(
                                                MessageDigest.getInstance("SHA-256")
                                                        .digest(stream.readAllBytes())))
                        .isEqualTo(manifest.path("sha256").path(name).asString());
            }
        }
        var fixture =
                json.readTree(getClass().getResourceAsStream("/dbt-contract/protocol-v1.json"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String key =
                            path.endsWith("/publication")
                                    ? "project"
                                    : path.endsWith("/status")
                                            ? "status"
                                            : path.endsWith("/results")
                                                    ? "page"
                                                    : path.contains("/validations/")
                                                            ? "validation"
                                                            : "options";
                    byte[] body = json.writeValueAsBytes(fixture.path(key));
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
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
                        json)) {
            var tool = new DbtAnalysisTool(DbtAnalysisTool.Kind.GET, client, json);
            var page =
                    tool.execute(
                            json.createObjectNode()
                                    .put(
                                            "queryId",
                                            fixture.path("page").path("queryId").asString()),
                            new InvocationContext("t", "e", 1, "call", "key"));
            assertThat(page).isEqualTo(fixture.path("page"));
            assertThat(page.path("rows").get(0).get(0).asString())
                    .isEqualTo("12345678901234567890.123456789");
            assertThat(client.getOptions(fixture.path("options").path("optionsJobId").asString()))
                    .isEqualTo(fixture.path("options"));
            assertThat(
                            client.getValidation(
                                    fixture.path("validation").path("validationId").asString()))
                    .isEqualTo(fixture.path("validation"));
        } finally {
            server.stop(0);
        }
    }
}
