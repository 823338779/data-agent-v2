package com.datagent.agentv2.conversation.tool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

public class WebSearchPropertiesTest {

    @Test
    void redactsApiKeyFromStringRepresentation() {
        WebSearchProperties properties = properties("top-secret", 5, SearchDepth.BASIC);

        assertThat(properties.toString()).contains("apiKey=***").doesNotContain("top-secret");
    }

    @Test
    void validatesProviderLimitAndTimeoutOrdering() {
        assertThatThrownBy(
                        () ->
                                new WebSearchProperties(
                                        "other",
                                        "key",
                                        5,
                                        SearchDepth.BASIC,
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(20),
                                        Duration.ofSeconds(25),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("key", 21, SearchDepth.BASIC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WebSearchProperties(
                                        "tavily",
                                        "key",
                                        5,
                                        SearchDepth.BASIC,
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(20),
                                        Duration.ofSeconds(20),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    public static WebSearchProperties properties(String apiKey, int limit, SearchDepth depth) {
        return new WebSearchProperties(
                "tavily",
                apiKey,
                limit,
                depth,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                Duration.ofSeconds(25),
                "");
    }
}
