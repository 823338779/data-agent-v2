package com.datagent.agentv2.conversation.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.web.fetch.WebFetchException;
import com.datagent.agentv2.conversation.tool.web.fetch.WebFetchPage;
import com.datagent.agentv2.conversation.tool.web.fetch.WebFetchProperties;
import com.datagent.agentv2.conversation.tool.web.fetch.WebPageFetcher;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WebFetchToolTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void extractsReadableHtmlAndReportsFinalUrl() throws Exception {
        WebPageFetcher fetcher =
                ignored ->
                        new WebFetchPage(
                                URI.create("https://example.com/final"),
                                "text/html; charset=UTF-8",
                                """
                                <html><head><title> Example title </title><style>.x{}</style></head>
                                <body><h1>Heading</h1><p>Hello <b>world</b>.</p><script>secret()</script></body></html>
                                """
                                        .getBytes(StandardCharsets.UTF_8));
        WebFetchTool tool = new WebFetchTool(fetcher, properties(), json);

        JsonNode output =
                tool.execute(json.readTree("{\"url\":\"https://example.com/start\"}"), context());

        assertThat(output.path("finalUrl").asString()).isEqualTo("https://example.com/final");
        assertThat(output.path("title").asString()).isEqualTo("Example title");
        assertThat(output.path("text").asString())
                .contains("Heading", "Hello world")
                .doesNotContain("secret", ".x{}");
        assertThat(output.path("truncated").asBoolean()).isFalse();
    }

    @Test
    void validatesUrlAndMaxCharactersBeforeFetch() {
        CountingFetcher fetcher = new CountingFetcher();
        WebFetchTool tool = new WebFetchTool(fetcher, properties(), json);

        for (String arguments :
                new String[] {
                    "{}",
                    "{\"url\":\"file:///tmp/a\"}",
                    "{\"url\":\"https://example.com\",\"max_chars\":999}",
                    "{\"url\":\"https://example.com\",\"max_chars\":20001}"
                }) {
            assertThatThrownBy(() -> tool.execute(json.readTree(arguments), context()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(fetcher.calls).isZero();
    }

    @Test
    void mapsBlockedTargetToStableSafeFailure() {
        WebPageFetcher fetcher =
                ignored -> {
                    throw new WebFetchException(
                            WebFetchException.Kind.BLOCKED, "internal diagnostic");
                };
        WebFetchTool tool = new WebFetchTool(fetcher, properties(), json);

        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree("{\"url\":\"https://example.com\"}"),
                                        context()))
                .isInstanceOf(ToolBusinessException.class)
                .satisfies(
                        failure -> {
                            ToolBusinessException business = (ToolBusinessException) failure;
                            assertThat(business.code()).isEqualTo("web_fetch_blocked");
                            assertThat(business.safeMessage()).doesNotContain("diagnostic");
                        });
    }

    private static WebFetchProperties properties() {
        return new WebFetchProperties(
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                Duration.ofSeconds(25),
                3,
                1_048_576,
                12_000,
                "Data-Agent-V2/0.1 web_fetch");
    }

    private static AgentTool.InvocationContext context() {
        return new AgentTool.InvocationContext("tenant", "execution", 1L, "call", "key");
    }

    private static final class CountingFetcher implements WebPageFetcher {
        private int calls;

        @Override
        public WebFetchPage fetch(URI uri) {
            calls++;
            throw new AssertionError("must not fetch invalid arguments");
        }
    }
}
