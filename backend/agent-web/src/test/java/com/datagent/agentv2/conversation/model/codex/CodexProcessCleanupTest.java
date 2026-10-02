package com.datagent.agentv2.conversation.model.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CodexProcessCleanupTest {
    @Test
    void timeoutTerminatesDescendantsThatInheritTheOutputPipe() throws Exception {
        Process child =
                new ProcessBuilder(
                                javaExecutable(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                ParentWithInheritedPipe.class.getName())
                        .start();
        ProcessHandle descendant = null;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var pidLine =
                    executor.submit(
                            () ->
                                    new java.io.BufferedReader(
                                                    new java.io.InputStreamReader(
                                                            child.getErrorStream()))
                                            .readLine());
            descendant =
                    ProcessHandle.of(Long.parseLong(pidLine.get(3, TimeUnit.SECONDS)))
                            .orElseThrow();
            var constructor =
                    Class.forName(CodexAppServerClient.class.getName() + "$ProcessConnection")
                            .getDeclaredConstructor(Process.class, Path.class, boolean.class);
            constructor.setAccessible(true);
            var connection =
                    (CodexAppServerClient.Connection)
                            constructor.newInstance(child, Path.of("."), false);
            var properties = new CodexModelProperties();
            properties.setTimeout(Duration.ofMillis(200));
            var client =
                    new CodexAppServerClient(
                            properties, JsonMapper.builder().build(), () -> connection);
            executor.submit(
                            () ->
                                    assertThatThrownBy(
                                                    () ->
                                                            client.complete(
                                                                    "hello",
                                                                    List.of(),
                                                                    ignored -> {}))
                                            .isInstanceOf(ModelStreamInterruptedException.class))
                    .get(5, TimeUnit.SECONDS);
            assertThat(descendant.isAlive()).as("继承输出管道的后代也必须随本次调用退出").isFalse();
            assertThat(child.isAlive()).isFalse();
        } finally {
            if (descendant != null) {
                descendant.destroyForcibly();
            }
            child.destroyForcibly();
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationTerminatesAChildThatNeverReadsTheLargeToolRequest() throws Exception {
        Process child =
                new ProcessBuilder(
                                javaExecutable(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                NonReadingChild.class.getName())
                        .start();
        var constructor =
                Class.forName(CodexAppServerClient.class.getName() + "$ProcessConnection")
                        .getDeclaredConstructor(Process.class, Path.class, boolean.class);
        constructor.setAccessible(true);
        var connection =
                (CodexAppServerClient.Connection)
                        constructor.newInstance(child, Path.of("."), false);
        var properties = new CodexModelProperties();
        properties.setTimeout(Duration.ofMinutes(1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var client =
                new CodexAppServerClient(
                        properties,
                        JsonMapper.builder().build(),
                        () -> {
                            entered.countDown();
                            return connection;
                        });
        var worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        client.complete(
                                                "hello",
                                                List.of(
                                                        new com.datagent.agentv2.conversation.model
                                                                .api.ModelToolDefinition(
                                                                "large",
                                                                "x".repeat(2_000_000),
                                                                java.util.Map.of())),
                                                ignored -> {});
                                    } catch (Throwable caught) {
                                        failure.set(caught);
                                    } finally {
                                        exited.countDown();
                                    }
                                });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            assertThat(exited.await(4, TimeUnit.SECONDS)).as("取消必须释放阻塞读写管道").isTrue();
            assertThat(failure.get()).isInstanceOf(ModelStreamInterruptedException.class);
            assertThat(child.isAlive()).isFalse();
        } finally {
            child.destroyForcibly();
            worker.interrupt();
        }
    }

    @Test
    void timeoutTerminatesARealChildWhoseOutputPipeNeverCompletes() throws Exception {
        Process child =
                new ProcessBuilder(
                                javaExecutable(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                SilentChild.class.getName())
                        .start();
        var constructor =
                Class.forName(CodexAppServerClient.class.getName() + "$ProcessConnection")
                        .getDeclaredConstructor(Process.class, Path.class, boolean.class);
        constructor.setAccessible(true);
        var connection =
                (CodexAppServerClient.Connection)
                        constructor.newInstance(child, Path.of("."), false);
        var properties = new CodexModelProperties();
        properties.setTimeout(Duration.ofMillis(200));
        var client =
                new CodexAppServerClient(
                        properties, JsonMapper.builder().build(), () -> connection);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var invocation =
                    executor.submit(
                            () ->
                                    assertThatThrownBy(
                                                    () ->
                                                            client.complete(
                                                                    "hello",
                                                                    List.of(),
                                                                    ignored -> {}))
                                            .isInstanceOf(ModelStreamInterruptedException.class));
            invocation.get(4, TimeUnit.SECONDS);
            assertThat(child.isAlive()).isFalse();
        } finally {
            child.destroyForcibly();
            executor.shutdownNow();
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    public static class SilentChild {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000);
        }
    }

    public static class NonReadingChild {
        public static void main(String[] args) throws Exception {
            System.out.println("{\"id\":1,\"result\":{}}");
            System.out.flush();
            Thread.sleep(30_000);
        }
    }

    public static class ParentWithInheritedPipe {
        public static void main(String[] args) throws Exception {
            Process descendant =
                    new ProcessBuilder(
                                    javaExecutable(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    SilentChild.class.getName())
                            .inheritIO()
                            .start();
            System.err.println(descendant.pid());
            System.err.flush();
            Thread.sleep(30_000);
        }
    }
}
