package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.conversation.context.ConversationContextStrategyResolver;
import com.datagent.agentv2.memory.review.*;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 真实应用默认配置与 HTTP 设置边界，包含 raw 缺少纯估算器的安全阻塞。 */
class PersonalMemoryConfigurationIntegrationTest {
    @Test
    void reducedQuotaKeepsManagementAndIncrementalDeletionAvailable() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (var app = new PersonalMemoryTestSupport(postgres, null)) {
                var ids = new java.util.ArrayList<String>();
                for (String text : new String[] {"住在杭州", "喜爱阅读", "早起工作"}) {
                    ids.add(
                            app.request(
                                            post("/api/user/memories")
                                                    .header("Idempotency-Key", text)
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(
                                                            app.json.writeValueAsString(
                                                                    java.util.Map.of(
                                                                            "kind",
                                                                            "person_profile",
                                                                            "content",
                                                                            text))),
                                            201)
                                    .path("memoryId")
                                    .asString());
                }
                var properties =
                        app.context.getBean(
                                com.datagent.agentv2.memory.config.MemoryProperties.class);
                properties.setPersonProfileLimit(5);
                var snapshot = app.context.getBean(MemoryService.class).snapshot();
                assertThat(snapshot.quotas().get(MemoryKind.PERSON_PROFILE).used()).isEqualTo(12);
                assertThat(snapshot.quotas().get(MemoryKind.PERSON_PROFILE).limit()).isEqualTo(5);
                app.mvc
                        .perform(get("/api/user/memories"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.quotas.person_profile.used").value(12))
                        .andExpect(jsonPath("$.quotas.person_profile.limit").value(5));
                app.mvc.perform(get("/api/user/memories/settings")).andExpect(status().isOk());
                app.mvc
                        .perform(
                                put("/api/user/memories/settings")
                                        .header("Idempotency-Key", "lowered-settings")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"expectedVersion\":0,\"autoLearningEnabled\":false}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.autoLearningEnabled").value(false));
                app.mvc
                        .perform(
                                post("/api/user/memories")
                                        .header("Idempotency-Key", "over-quota-add")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"kind\":\"person_profile\",\"content\":\"新增\"}"))
                        .andExpect(status().is(422))
                        .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
                app.mvc
                        .perform(
                                put("/api/user/memories/{id}", ids.getFirst())
                                        .header("Idempotency-Key", "over-quota-replace")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"expectedVersion\":1,\"content\":\"更长的新正文\"}"))
                        .andExpect(status().is(422))
                        .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
                // 第一次删除后仍然超额，必须已经提交，不能因构造结果配额而回滚。
                for (int i = 0; i < 2; i++) {
                    app.mvc
                            .perform(
                                    delete("/api/user/memories/{id}", ids.get(i))
                                            .param("expectedVersion", "1")
                                            .header("Idempotency-Key", "cleanup-" + i))
                            .andExpect(status().isNoContent());
                    app.mvc
                            .perform(get("/api/user/memories"))
                            .andExpect(status().isOk())
                            .andExpect(
                                    jsonPath("$.quotas.person_profile.used").value(i == 0 ? 8 : 4))
                            .andExpect(jsonPath("$.quotas.person_profile.limit").value(5));
                    assertThat(
                                    app.jdbc.queryForObject(
                                            "select count(*) from personal_memory where memory_id=?",
                                            Integer.class,
                                            ids.get(i)))
                            .isZero();
                }
            }
        }
    }

    @Test
    void applicationDefaultsAndManagementContracts() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (var app = new PersonalMemoryTestSupport(postgres, null)) {
                assertThat(
                                app.context
                                        .getBean(ConversationContextStrategyResolver.class)
                                        .selected()
                                        .strategyId())
                        .isEqualTo("hierarchical");
                var state = app.context.getBean(MemoryService.class).snapshot().state();
                assertThat(
                                state.readEnabled()
                                        && state.explicitWriteEnabled()
                                        && state.autoLearningEnabled())
                        .isTrue();
                for (String text : new String[] {"中文", "平台工程师", "喜欢简洁"})
                    app.mvc
                            .perform(
                                    post("/api/user/memories")
                                            .header("Idempotency-Key", text)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    app.json.writeValueAsString(
                                                            java.util.Map.of(
                                                                    "kind",
                                                                    "person_profile",
                                                                    "content",
                                                                    text))))
                            .andExpect(status().isCreated());
                var first =
                        app.request(
                                get("/api/user/memories")
                                        .param("kind", "person_profile")
                                        .param("limit", "2"),
                                200);
                var second =
                        app.request(
                                get("/api/user/memories")
                                        .param("kind", "person_profile")
                                        .param("limit", "2")
                                        .param(
                                                "cursor",
                                                first.at("/groups/person_profile/nextCursor")
                                                        .asString()),
                                200);
                assertThat(first.at("/groups/person_profile/entries").size()).isEqualTo(2);
                assertThat(second.at("/groups/person_profile/entries").size()).isEqualTo(1);
                assertThat(second.at("/groups/person_profile/entries/0/memoryId").asString())
                        .isNotIn(
                                first.at("/groups/person_profile/entries/0/memoryId").asString(),
                                first.at("/groups/person_profile/entries/1/memoryId").asString());
                assertThat(second.path("quotas")).isEqualTo(first.path("quotas"));
                app.mvc
                        .perform(
                                put("/api/user/memories/settings")
                                        .header("Idempotency-Key", "off")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"expectedVersion\":0,\"readEnabled\":false,\"explicitWriteEnabled\":false}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.settingsVersion").value(1));
                app.mvc
                        .perform(get("/api/user/memories"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.groups.person_profile.entries.length()").value(3));
                app.mvc
                        .perform(
                                put("/api/user/memories/settings")
                                        .header("Idempotency-Key", "on")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"expectedVersion\":1,\"explicitWriteEnabled\":true}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.explicitWriteEnabled").value(true));
                app.mvc
                        .perform(
                                delete("/api/user/memories/invalid")
                                        .param("expectedVersion", "abc")
                                        .header("Idempotency-Key", "invalid"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
                app.mvc
                        .perform(get("/api/user/memories").param("limit", "abc"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
                app.mvc
                        .perform(get("/api/user/memories").param("kind", "work_context"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
                // 既有项目路由也保持数字参数校验与业务404的区分，不能因新增全局映射变成500。
                app.mvc
                        .perform(
                                delete("/api/v1/agent-projects/missing")
                                        .param("expectedVersion", "abc"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
                app.mvc
                        .perform(get("/api/v1/agent-sessions/missing"))
                        .andExpect(status().isNotFound());
            }
        }
    }

    @Test
    void explicitlyConfiguredRawBlocksReviewWithoutCallingModel() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (var app = new PersonalMemoryTestSupport(postgres, "raw")) {
                app.submit(app.session(), "本轮普通对话");
                int calls = app.gateway.inputs.size();
                var claim =
                        app.context
                                .getBean(MemoryReviewCoordinator.class)
                                .claimDue("raw", 4)
                                .getFirst();
                app.context.getBean(MemoryReviewWorker.class).run(claim);
                var job = app.context.getBean(MemoryReviewJobService.class).find(claim.jobId());
                assertThat(job.status()).isEqualTo(MemoryReviewJob.Status.BLOCKED);
                assertThat(job.reviewedThroughSequence()).isEqualTo(claim.fromSequence());
                assertThat(app.gateway.inputs).hasSize(calls);
            }
        }
    }
}
