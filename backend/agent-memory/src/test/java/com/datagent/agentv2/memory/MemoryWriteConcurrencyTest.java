package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 每个线程从 Spring 代理进入独立事务，真实数据库行锁决定竞争结果。 */
@SpringBootTest(classes = MemoryIntegrationTestConfiguration.class)
class MemoryWriteConcurrencyTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired MemoryService memory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
    }

    @Test
    void concurrentInitializationAndSameContentProduceOneEntry() throws Exception {
        var results =
                race(
                        () -> memory.execute(ui("one"), add("中文")),
                        () -> memory.execute(ui("two"), add("中文！")));
        assertThat(results)
                .extracting(MemoryOutcome::status)
                .containsExactlyInAnyOrder(MemoryStatus.CREATED, MemoryStatus.ALREADY_EXISTS);
        assertThat(memory.snapshot().entries()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from personal_memory_state", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameRequestHasOnlyOneSideEffect() throws Exception {
        var results =
                race(
                        () -> memory.execute(ui("same"), add("中文")),
                        () -> memory.execute(ui("same"), add("中文")));
        assertThat(results.getFirst()).isEqualTo(results.getLast());
        assertThat(memory.snapshot().state().revision()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_operation", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentQuotaClaimsCannotOverspendRemainingCapacity() throws Exception {
        memory.execute(ui("seed"), add("a".repeat(799)));
        var results =
                race(
                        () -> memory.execute(ui("one"), add("甲")),
                        () -> memory.execute(ui("two"), add("乙")));
        assertThat(results)
                .extracting(MemoryOutcome::status)
                .containsExactlyInAnyOrder(MemoryStatus.CREATED, MemoryStatus.QUOTA_EXCEEDED);
        assertThat(memory.snapshot().quotas().get(MemoryKind.PERSON_PROFILE).used()).isEqualTo(800);
        assertThat(memory.snapshot().entries()).hasSize(2);
    }

    @Test
    void snapshotWaitsForEntireWriteCommitAndDoesNotRefreshConfirmation() throws Exception {
        memory.initialize();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var written = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var writer =
                    workers.submit(
                            () ->
                                    transactions.execute(
                                            status -> {
                                                var result = memory.execute(ui("write"), add("中文"));
                                                written.countDown();
                                                await(commit);
                                                return result;
                                            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var reader = workers.submit(memory::snapshot);
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> reader.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                commit.countDown();
            }
            var snapshot = reader.get(10, TimeUnit.SECONDS);
            assertThat(snapshot.state().revision())
                    .isEqualTo(writer.get(10, TimeUnit.SECONDS).revision());
            assertThat(snapshot.entries()).hasSize(1);
            assertThat(snapshot.quotas().get(MemoryKind.PERSON_PROFILE).used()).isEqualTo(2);
            assertThat(memory.snapshot()).isEqualTo(snapshot);
        }
    }

    private List<MemoryOutcome> race(Callable<MemoryOutcome> first, Callable<MemoryOutcome> second)
            throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var connections = ConcurrentHashMap.<Integer>newKeySet();
            var one = workers.submit(() -> inConnection(first, connections, ready, start));
            var two = workers.submit(() -> inConnection(second, connections, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            assertThat(connections).hasSize(2);
            return results;
        }
    }

    private MemoryOutcome inConnection(
            Callable<MemoryOutcome> action,
            java.util.Set<Integer> connections,
            CountDownLatch ready,
            CountDownLatch start) {
        // 屏障只同步发令，不互斥写入；两个独立事务先占有不同 PG 连接，所有竞争由真实行锁裁决。
        return transactions.execute(
                status -> {
                    connections.add(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    ready.countDown();
                    await(start);
                    try {
                        return action.call();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // 夹具局部构造，单独运行并发测试不会触发其他测试类的 PostgreSQL 静态初始化。
    private static MemoryWriteContext ui(String requestId) {
        return new MemoryWriteContext(
                MemoryWriteContext.Caller.UI, requestId, null, null, null, null);
    }

    private static MemoryCommand.Add add(String content) {
        return new MemoryCommand.Add(
                MemoryKind.PERSON_PROFILE,
                content,
                MemoryWriteReason.USER_REQUESTED,
                List.of(),
                false);
    }
}
