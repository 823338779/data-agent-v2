package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.client.HttpDbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.tool.DbtAnalysisTool;
import com.datagent.agentv2.git.model.GitWorkspaceContext;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DbtBranchQueryTest {
    @Test
    void missingPreviewFailsOnBranchRouteWithoutProductionFallback() throws Exception {
        var routes = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    routes.add(exchange.getRequestURI().getPath());
                    byte[] body =
                            "{\"detail\":{\"reason\":\"NO_ACTIVE_RELEASE\"}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(409, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var json = new ObjectMapper();
            var properties =
                    new DbtProperties(
                            true,
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            "metrics",
                            ".",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2));
            try (var client = new HttpDbtServiceClient(properties, json)) {
                var tool =
                        new DbtAnalysisTool(
                                DbtAnalysisTool.Kind.PROJECT,
                                client,
                                json,
                                context ->
                                        new GitWorkspaceContext(
                                                "workspace",
                                                "metrics",
                                                "branch-a",
                                                "refs/heads/dev"));
                assertThatThrownBy(
                                () ->
                                        tool.execute(
                                                json.createObjectNode(),
                                                new AgentTool.InvocationContext(
                                                        "tenant_local",
                                                        "execution",
                                                        1,
                                                        "call",
                                                        "key")))
                        .isInstanceOf(ToolBusinessException.class);
                assertThat(routes)
                        .containsExactly("/v2/projects/metrics/branches/branch-a/publication");
            }
        } finally {
            server.stop(0);
        }
    }
}
