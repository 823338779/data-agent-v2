package com.datagent.agentv2.conversation.tool.web.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApacheWebFetchTransportTest {
    private final ApacheWebFetchTransport transport = new ApacheWebFetchTransport(properties());

    @AfterEach
    void closeTransport() {
        transport.destroy();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 302})
    void requestDeadlineIncludesSlowResponseBody(int status) throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        exchange.sendResponseHeaders(status, 2);
                        exchange.getResponseBody().write('a');
                        exchange.getResponseBody().flush();
                        releaseBody.await(2, TimeUnit.SECONDS);
                        exchange.getResponseBody().write('b');
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {

            long started = System.nanoTime();
            assertThatThrownBy(
                            () ->
                                    transport.get(
                                            new ValidatedWebAddress(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + server.getAddress().getPort()
                                                                    + "/"),
                                                    List.of(InetAddress.getByName("127.0.0.1")))))
                    .isInstanceOfSatisfying(
                            WebFetchException.class,
                            failure ->
                                    assertThat(failure.kind())
                                            .isEqualTo(WebFetchException.Kind.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(1500));
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test
    void interruptCancelsInFlightBodyRead() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        exchange.sendResponseHeaders(200, 2);
                        exchange.getResponseBody().write('a');
                        exchange.getResponseBody().flush();
                        headersSent.countDown();
                        releaseBody.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        Thread caller =
                Thread.ofVirtual()
                        .unstarted(
                                () -> {
                                    try {
                                        transport.get(
                                                new ValidatedWebAddress(
                                                        URI.create(
                                                                "http://interrupt.invalid:"
                                                                        + server.getAddress()
                                                                                .getPort()
                                                                        + "/"),
                                                        List.of(
                                                                InetAddress.getByName(
                                                                        "127.0.0.1"))));
                                    } catch (Throwable caught) {
                                        failure.set(caught);
                                    }
                                });
        try {
            caller.start();
            assertThat(headersSent.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(1000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        } finally {
            releaseBody.countDown();
            caller.interrupt();
            caller.join(1000);
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsOversizedBodyWithOrWithoutDeclaredLength(boolean declaredLength) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        exchange.sendResponseHeaders(200, declaredLength ? 70_000 : 0);
                        exchange.getResponseBody().write(new byte[70_000]);
                    } catch (java.io.IOException closedByClient) {
                        // 客户端达到字节上限后主动断开连接，是本用例预期行为。
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var target =
                    new ValidatedWebAddress(
                            URI.create(
                                    "http://size.invalid:" + server.getAddress().getPort() + "/"),
                            List.of(InetAddress.getByName("127.0.0.1")));
            assertThatThrownBy(() -> transport.get(target))
                    .isInstanceOfSatisfying(
                            WebFetchException.class,
                            failure ->
                                    assertThat(failure.kind())
                                            .isEqualTo(WebFetchException.Kind.RESPONSE_TOO_LARGE));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectsOnlyToValidatedAddressWithoutResolvingOriginalHostAgain() throws Exception {
        AtomicReference<String> hostHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write(new byte[] {'o', 'k'});
                    exchange.close();
                });
        server.start();
        try {
            URI uri = URI.create("http://rebound.invalid:" + server.getAddress().getPort() + "/");
            var target = new ValidatedWebAddress(uri, List.of(InetAddress.getByName("127.0.0.1")));
            var response = transport.get(target);
            assertThat(response.body()).isEqualTo(new byte[] {'o', 'k'});
            assertThat(hostHeader.get())
                    .isEqualTo("rebound.invalid:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    private static WebFetchProperties properties() {
        return new WebFetchProperties(
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofSeconds(3),
                3,
                65_536,
                12_000,
                "test");
    }
}
