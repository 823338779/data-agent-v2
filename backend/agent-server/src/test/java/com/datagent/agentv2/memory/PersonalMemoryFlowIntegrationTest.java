package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.memory.review.MemoryReviewCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 两种真实策略分别走 execution→memory 工具→新会话→管理 HTTP→旧后台写拒绝。 */
class PersonalMemoryFlowIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"lsm", "hierarchical"})
    void memorySurvivesSessionsAndDeletionRejectsOldBackgroundResults(String strategy)
            throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (var app = new PersonalMemoryTestSupport(postgres, strategy)) {
                String session = app.session();
                String execution = app.submit(session, "请记住我是一名后端工程师");
                var memory = app.context.getBean(MemoryService.class);
                var saved = memory.snapshot().entries().getFirst();
                assertThat(saved.content()).isEqualTo("用户是一名后端工程师");
                assertThat(saved.source()).isEqualTo(MemorySource.USER_DIRECT);
                assertThat(app.gateway.toolsSeen.getFirst())
                        .extracting(t -> t.name())
                        .contains("memory");
                assertThat(app.gateway.inputs.get(1))
                        .anySatisfy(
                                m -> {
                                    assertThat(m.role()).isEqualTo(ModelInputMessage.Role.TOOL);
                                    assertThat(m.content()).contains("CREATED");
                                });
                app.submit(app.session(), "新会话测试");
                var input = app.gateway.inputs.getLast();
                var injected =
                        input.stream()
                                .filter(m -> m.content().contains("用户是一名后端工程师"))
                                .findFirst()
                                .orElseThrow();
                assertThat(injected.role()).isEqualTo(ModelInputMessage.Role.USER);
                assertThat(input.indexOf(injected)).isLessThan(input.size() - 1);
                assertThat(
                                app.jdbc.queryForObject(
                                        "select count(*) from conversation_message where content like '%[personal_memory%'",
                                        Integer.class))
                        .isZero();
                var metrics = app.context.getBean(MeterRegistry.class);
                assertThat(metrics.get("memory.injection.tokens").summary().count())
                        .isEqualTo(app.gateway.inputs.size());
                assertThat(metrics.get("memory.injection.tokens").summary().totalAmount())
                        .isPositive();

                var coordinator = app.context.getBean(MemoryReviewCoordinator.class);
                var claim = coordinator.claimDue("old-worker", 4).getFirst();
                long oldRevision = memory.snapshot().state().revision();
                app.mvc
                        .perform(
                                put("/api/user/memories/{id}", saved.memoryId())
                                        .header("Idempotency-Key", "edit")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"content\":\"用户是平台工程师\",\"expectedVersion\":1}"))
                        .andExpect(status().isOk());
                app.mvc
                        .perform(
                                delete("/api/user/memories/{id}", saved.memoryId())
                                        .param("expectedVersion", "2")
                                        .header("Idempotency-Key", "delete"))
                        .andExpect(status().isNoContent());
                String evidence =
                        app.jdbc.queryForObject(
                                "select conversation_message_id from conversation_message where execution_id=? and role='user'",
                                String.class,
                                execution);
                var oldResult =
                        memory.execute(
                                new MemoryWriteContext(
                                        MemoryWriteContext.Caller.BACKGROUND,
                                        "stale",
                                        null,
                                        claim.conversationId(),
                                        oldRevision,
                                        claim),
                                new MemoryCommand.Add(
                                        MemoryKind.PERSON_PROFILE,
                                        "用户是平台工程师",
                                        MemoryWriteReason.AGENT_INFERRED,
                                        List.of(evidence),
                                        false));
                assertThat(oldResult.status()).isEqualTo(MemoryStatus.CONFLICT);
                assertThat(memory.snapshot().entries()).isEmpty();
                assertThat(
                                app.jdbc.queryForObject(
                                        "select count(*) from personal_memory_evidence",
                                        Integer.class))
                        .isZero();
                app.submit(app.session(), "删除后新会话");
                assertThat(app.gateway.inputs.getLast())
                        .noneMatch(
                                m ->
                                        m.content().contains("用户是平台工程师")
                                                || m.content().contains("用户是一名后端工程师"));
            }
        }
    }
}
