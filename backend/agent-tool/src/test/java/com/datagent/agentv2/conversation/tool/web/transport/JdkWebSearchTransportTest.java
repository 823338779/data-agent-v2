package com.datagent.agentv2.conversation.tool.web.transport;

import static com.datagent.agentv2.conversation.tool.web.WebSearchPropertiesTest.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.tool.web.SearchDepth;
import com.datagent.agentv2.conversation.tool.web.WebSearchProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkWebSearchTransportTest {

    @Test
    void postsBodyAndDoesNotFollowRedirects() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/search",
                exchange -> {
                    received.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    exchange.getResponseHeaders().add("Location", "/other");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            WebSearchHttpResponse response =
                    new JdkWebSearchTransport(properties("key", 5, SearchDepth.BASIC))
                            .send(
                                    new WebSearchHttpRequest(
                                            "POST",
                                            uri,
                                            Map.of("Content-Type", "application/json"),
                                            "{\"ok\":true}",
                                            Duration.ofSeconds(2)));

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(received.get()).isEqualTo("{\"ok\":true}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidExplicitProxy() {
        WebSearchProperties invalid =
                new WebSearchProperties(
                        "tavily",
                        "key",
                        5,
                        SearchDepth.BASIC,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        "localhost:8080");

        assertThatThrownBy(() -> new JdkWebSearchTransport(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
