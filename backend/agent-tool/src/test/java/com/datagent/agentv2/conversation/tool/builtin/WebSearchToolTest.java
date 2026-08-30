package com.datagent.agentv2.conversation.tool.builtin;

import static com.datagent.agentv2.conversation.tool.web.WebSearchPropertiesTest.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.web.SearchDepth;
import com.datagent.agentv2.conversation.tool.web.WebSearchException;
import com.datagent.agentv2.conversation.tool.web.WebSearchProvider;
import com.datagent.agentv2.conversation.tool.web.WebSearchProviderRegistry;
import com.datagent.agentv2.conversation.tool.web.WebSearchQuery;
import com.datagent.agentv2.conversation.tool.web.WebSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WebSearchToolTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void invocationOverridesConfiguredLimitAndDepth() throws Exception {
        RecordingProvider provider =
                new RecordingProvider(
                        List.of(
                                new WebSearchResult(
                                        "Title", "https://example.com/a", "Snippet", 0.91)));
        WebSearchTool tool =
                new WebSearchTool(
                        new WebSearchProviderRegistry(List.of(provider)),
                        properties("api-key", 5, SearchDepth.BASIC),
                        json);

        JsonNode output =
                tool.execute(
                        json.readTree(
                                """
                                {"query":"latest java agent","limit":1,"search_depth":"advanced"}
                                """),
                        context());

        assertThat(provider.lastQuery())
                .isEqualTo(new WebSearchQuery("latest java agent", 1, SearchDepth.ADVANCED));
        assertThat(output.path("provider").asString()).isEqualTo("tavily");
        assertThat(output.path("results").size()).isEqualTo(1);
        assertThat(output.path("references").path(0).path("index").asInt()).isEqualTo(1);
    }

    @Test
    void filtersDirtyUrlsNormalizesFieldsAndKeepsReferenceIndexesAligned() throws Exception {
        String longSnippet = "字".repeat(501);
        RecordingProvider provider =
                new RecordingProvider(
                        List.of(
                                new WebSearchResult(null, "javascript:alert(1)", "ignored", 1.0),
                                new WebSearchResult(
                                        null, "https://example.com/a", "  hello\n world  ", null),
                                new WebSearchResult(
                                        "Long", "http://example.com/b", longSnippet, 0.5)));
        WebSearchTool tool = tool(provider, "api-key");

        JsonNode output =
                tool.execute(json.readTree("{\"query\":\"query\",\"limit\":2}"), context());

        assertThat(output.path("results").size()).isEqualTo(2);
        assertThat(output.path("results").path(0).path("index").asInt()).isEqualTo(1);
        assertThat(output.path("results").path(0).path("title").asString()).isEmpty();
        assertThat(output.path("results").path(0).path("snippet").asString())
                .isEqualTo("hello world");
        assertThat(output.path("results").path(0).path("score").isNull()).isTrue();
        assertThat(output.path("results").path(1).path("snippet").asString())
                .hasSize(501)
                .endsWith("…");
        assertThat(output.path("references").path(1).path("index").asInt()).isEqualTo(2);
    }

    @Test
    void validatesArgumentsBeforeProviderIo() {
        RecordingProvider provider = new RecordingProvider(List.of());
        WebSearchTool tool = tool(provider, "api-key");

        for (String arguments :
                List.of(
                        "{}",
                        "{\"query\":\"   \"}",
                        "{\"query\":\"x\",\"limit\":1.5}",
                        "{\"query\":\"x\",\"limit\":21}",
                        "{\"query\":\"x\",\"search_depth\":\"deep\"}")) {
            assertThatThrownBy(() -> tool.execute(json.readTree(arguments), context()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(provider.lastQuery()).isNull();
    }

    @Test
    void missingConfigurationFailsWithoutProviderIo() {
        RecordingProvider provider = new RecordingProvider(List.of());
        WebSearchTool tool = tool(provider, " ");

        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree("{\"query\":\"current news\"}"), context()))
                .isInstanceOf(ToolBusinessException.class)
                .satisfies(
                        failure ->
                                assertThat(((ToolBusinessException) failure).code())
                                        .isEqualTo("web_search_not_configured"));
        assertThat(provider.lastQuery()).isNull();
    }

    private WebSearchTool tool(RecordingProvider provider, String apiKey) {
        return new WebSearchTool(
                new WebSearchProviderRegistry(List.of(provider)),
                properties(apiKey, 5, SearchDepth.BASIC),
                json);
    }

    private static AgentTool.InvocationContext context() {
        return new AgentTool.InvocationContext("tenant", "execution", 1L, "call", "key");
    }

    private static final class RecordingProvider implements WebSearchProvider {

        private final List<WebSearchResult> results;
        private WebSearchQuery lastQuery;

        private RecordingProvider(List<WebSearchResult> results) {
            this.results = List.copyOf(results);
        }

        @Override
        public String id() {
            return "tavily";
        }

        @Override
        public List<WebSearchResult> search(WebSearchQuery query)
                throws WebSearchException, InterruptedException {
            lastQuery = query;
            return results;
        }

        private WebSearchQuery lastQuery() {
            return lastQuery;
        }
    }
}
