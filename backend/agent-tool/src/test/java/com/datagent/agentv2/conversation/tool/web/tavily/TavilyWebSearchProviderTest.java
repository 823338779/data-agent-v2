package com.datagent.agentv2.conversation.tool.web.tavily;

import static com.datagent.agentv2.conversation.tool.web.WebSearchPropertiesTest.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.tool.web.SearchDepth;
import com.datagent.agentv2.conversation.tool.web.WebSearchException;
import com.datagent.agentv2.conversation.tool.web.WebSearchQuery;
import com.datagent.agentv2.conversation.tool.web.WebSearchResult;
import com.datagent.agentv2.conversation.tool.web.WebSearchTelemetryEvent;
import com.datagent.agentv2.conversation.tool.web.transport.WebSearchHttpRequest;
import com.datagent.agentv2.conversation.tool.web.transport.WebSearchHttpResponse;
import com.datagent.agentv2.conversation.tool.web.transport.WebSearchTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TavilyWebSearchProviderTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void sendsFixedTavilyRequestParsesResultsAndRecordsSafeTelemetry() throws Exception {
        FakeTransport transport =
                new FakeTransport(
                        response(
                                200,
                                """
                                {"results":[{"title":"Official docs","url":"https://example.com/docs","content":"Current documentation","score":0.84}],"request_id":"request-1"}
                                """));
        List<WebSearchTelemetryEvent> events = new ArrayList<>();
        TavilyWebSearchProvider provider =
                new TavilyWebSearchProvider(
                        transport,
                        properties("test-secret", 5, SearchDepth.BASIC),
                        json,
                        events::add);

        List<WebSearchResult> results =
                provider.search(new WebSearchQuery("java agent", 3, SearchDepth.BASIC));

        assertThat(transport.request().uri())
                .isEqualTo(URI.create("https://api.tavily.com/search"));
        assertThat(transport.request().headers().get("Authorization"))
                .isEqualTo("Bearer test-secret");
        JsonNode payload = json.readTree(transport.request().body());
        assertThat(payload.path("query").asString()).isEqualTo("java agent");
        assertThat(payload.path("max_results").asInt()).isEqualTo(3);
        assertThat(payload.path("search_depth").asString()).isEqualTo("basic");
        assertThat(payload.path("include_answer").asBoolean()).isFalse();
        assertThat(payload.path("include_raw_content").asBoolean()).isFalse();
        assertThat(payload.path("include_images").asBoolean()).isFalse();
        assertThat(results)
                .containsExactly(
                        new WebSearchResult(
                                "Official docs",
                                "https://example.com/docs",
                                "Current documentation",
                                0.84));
        assertThat(events)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.provider()).isEqualTo("tavily");
                            assertThat(event.httpStatus()).isEqualTo(200);
                            assertThat(event.resultCode()).isEqualTo("success");
                            assertThat(event.requestId()).isEqualTo("request-1");
                        });
        assertThat(transport.request().toString())
                .doesNotContain("test-secret")
                .contains("Authorization=***");
    }

    @Test
    void classifiesStatusAndProtocolFailures() {
        assertKind(401, "{}", WebSearchException.Kind.AUTHENTICATION);
        assertKind(429, "{}", WebSearchException.Kind.RATE_LIMITED);
        assertKind(500, "{}", WebSearchException.Kind.UNAVAILABLE);
        assertKind(200, "{}", WebSearchException.Kind.INVALID_RESPONSE);
        assertKind(200, "not-json", WebSearchException.Kind.INVALID_RESPONSE);
    }

    private void assertKind(int status, String body, WebSearchException.Kind expected) {
        TavilyWebSearchProvider provider =
                new TavilyWebSearchProvider(
                        new FakeTransport(response(status, body)),
                        properties("key", 5, SearchDepth.BASIC),
                        json,
                        ignored -> {});
        assertThatThrownBy(() -> provider.search(new WebSearchQuery("query", 1, SearchDepth.BASIC)))
                .isInstanceOf(WebSearchException.class)
                .satisfies(
                        failure ->
                                assertThat(((WebSearchException) failure).kind())
                                        .isEqualTo(expected));
    }

    private static WebSearchHttpResponse response(int status, String body) {
        return new WebSearchHttpResponse(status, Map.of(), body);
    }

    private static final class FakeTransport implements WebSearchTransport {

        private final WebSearchHttpResponse response;
        private WebSearchHttpRequest request;

        private FakeTransport(WebSearchHttpResponse response) {
            this.response = response;
        }

        @Override
        public WebSearchHttpResponse send(WebSearchHttpRequest request) {
            this.request = request;
            return response;
        }

        private WebSearchHttpRequest request() {
            return request;
        }
    }
}
