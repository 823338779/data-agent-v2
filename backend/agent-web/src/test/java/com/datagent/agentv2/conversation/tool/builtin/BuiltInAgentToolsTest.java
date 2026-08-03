package com.datagent.agentv2.conversation.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.AgentToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BuiltInAgentToolsTest {

    private static final String FIXED_INSTANT = "2026-08-03T13:00:00Z";
    private static final String FIXED_ZONE_ID = "Asia/Shanghai";
    private static final String SEARCH_QUERY = "OpenAI";
    private static final String SEARCH_RESPONSE =
            """
            {
              "query": {
                "search": [
                  {
                    "title": "OpenAI",
                    "snippet": "<span class=\\\"searchmatch\\\">OpenAI</span> 是人工智能公司 &quot;示例&quot;"
                  }
                ]
              }
            }
            """;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneId.of(FIXED_ZONE_ID));

    @Test
    void reportsTheSystemTimezoneUsedByTheServer() throws Exception {
        JsonNode result = new SystemTimezoneTool(clock).execute(json.createObjectNode(), context());

        assertThat(result.path("zoneId").asString()).isEqualTo(FIXED_ZONE_ID);
        assertThat(result.path("offset").asString()).isEqualTo("+08:00");
    }

    @Test
    void reportsCurrentInstantAndLocalTimeFromTheSameClock() throws Exception {
        JsonNode result = new CurrentTimeTool(clock).execute(json.createObjectNode(), context());

        assertThat(result.path("instant").asString()).isEqualTo(FIXED_INSTANT);
        assertThat(result.path("localDateTime").asString()).isEqualTo("2026-08-03T21:00");
        assertThat(result.path("zoneId").asString()).isEqualTo(FIXED_ZONE_ID);
        assertThat(result.path("offset").asString()).isEqualTo("+08:00");
    }

    @Test
    void searchesWikipediaAndReturnsPlainTextResults() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(
                        request -> {
                            assertThat(request.getURI().getHost()).isEqualTo("zh.wikipedia.org");
                            assertThat(request.getURI().getQuery())
                                    .contains(
                                            "action=query",
                                            "list=search",
                                            "srsearch=OpenAI",
                                            "srlimit=2");
                        })
                .andRespond(withSuccess(SEARCH_RESPONSE, MediaType.APPLICATION_JSON));
        WebSearchTool tool = new WebSearchTool(restClientBuilder, json);
        JsonNode arguments = json.createObjectNode().put("query", SEARCH_QUERY).put("limit", 2);

        JsonNode result = tool.execute(arguments, context());

        assertThat(result.path("query").asString()).isEqualTo(SEARCH_QUERY);
        assertThat(result.path("source").asString()).isEqualTo("zh.wikipedia.org");
        assertThat(result.path("results")).hasSize(1);
        assertThat(result.path("results").get(0).path("title").asString()).isEqualTo(SEARCH_QUERY);
        assertThat(result.path("results").get(0).path("snippet").asString())
                .isEqualTo("OpenAI 是人工智能公司 \"示例\"");
        assertThat(result.path("results").get(0).path("url").asString())
                .isEqualTo("https://zh.wikipedia.org/wiki/OpenAI");
        server.verify();
    }

    @Test
    void exposesAllBuiltInToolsToTheModelInRegistrationOrder() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        List<AgentTool> tools =
                List.of(
                        new SystemTimezoneTool(clock),
                        new CurrentTimeTool(clock),
                        new WebSearchTool(restClientBuilder, json));

        AgentToolRegistry registry = new AgentToolRegistry(tools);

        assertThat(registry.modelDefinitions())
                .extracting(definition -> definition.name())
                .containsExactly("system_timezone", "current_time", "web_search");
    }

    private static AgentTool.InvocationContext context() {
        return new AgentTool.InvocationContext(
                "tenant-test", "execution-test", 1, "call-test", null);
    }
}
