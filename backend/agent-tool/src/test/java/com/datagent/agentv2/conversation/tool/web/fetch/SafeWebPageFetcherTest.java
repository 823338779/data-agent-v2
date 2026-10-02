package com.datagent.agentv2.conversation.tool.web.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SafeWebPageFetcherTest {

    @Test
    void sameHostRedirectCannotReusePreviousPublicDnsApproval() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        var policy =
                new PublicWebAddressPolicy(
                        host ->
                                new InetAddress[] {
                                    InetAddress.getByName(
                                            lookups.incrementAndGet() == 1
                                                    ? "93.184.216.34"
                                                    : "127.0.0.1")
                                });
        var transport =
                new StubTransport(
                        new WebFetchHttpResponse(
                                302, Map.of("Location", List.of("/after-rebinding")), new byte[0]));
        assertThatThrownBy(
                        () ->
                                new SafeWebPageFetcher(transport, policy, properties())
                                        .fetch(URI.create("https://rebound.example/start")))
                .isInstanceOfSatisfying(
                        WebFetchException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(WebFetchException.Kind.BLOCKED));
        assertThat(lookups).hasValue(2);
        assertThat(transport.requested)
                .containsExactly(URI.create("https://rebound.example/start"));
    }

    @Test
    void validatesEveryRedirectTargetBeforeRequestingIt() throws Exception {
        List<String> resolvedHosts = new ArrayList<>();
        PublicWebAddressPolicy policy =
                new PublicWebAddressPolicy(
                        host -> {
                            resolvedHosts.add(host);
                            return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                        });
        StubTransport transport =
                new StubTransport(
                        new WebFetchHttpResponse(
                                302,
                                Map.of("Location", List.of("https://target.example/page")),
                                new byte[0]),
                        new WebFetchHttpResponse(
                                200,
                                Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                                "<p>ok</p>".getBytes()));

        WebFetchPage result =
                new SafeWebPageFetcher(transport, policy, properties())
                        .fetch(URI.create("https://source.example/start"));

        assertThat(resolvedHosts).containsExactly("source.example", "target.example");
        assertThat(transport.requested)
                .containsExactly(
                        URI.create("https://source.example/start"),
                        URI.create("https://target.example/page"));
        assertThat(result.finalUri()).isEqualTo(URI.create("https://target.example/page"));
    }

    @Test
    void rejectsUnsupportedResponseBeforeReturningContent() {
        PublicWebAddressPolicy policy =
                new PublicWebAddressPolicy(
                        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        StubTransport transport =
                new StubTransport(
                        new WebFetchHttpResponse(
                                200,
                                Map.of("Content-Type", List.of("application/octet-stream")),
                                new byte[] {1}));

        assertThatThrownBy(
                        () ->
                                new SafeWebPageFetcher(transport, policy, properties())
                                        .fetch(URI.create("https://example.com/file")))
                .isInstanceOfSatisfying(
                        WebFetchException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(
                                                WebFetchException.Kind.UNSUPPORTED_CONTENT_TYPE));
    }

    private static WebFetchProperties properties() {
        return new WebFetchProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                3,
                65_536,
                12_000,
                "test");
    }

    private static final class StubTransport implements WebFetchTransport {
        private final ArrayDeque<WebFetchHttpResponse> responses;
        private final List<URI> requested = new ArrayList<>();

        private StubTransport(WebFetchHttpResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public WebFetchHttpResponse get(ValidatedWebAddress target) {
            requested.add(target.uri());
            return responses.removeFirst();
        }
    }
}
