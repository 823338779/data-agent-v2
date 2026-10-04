package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.hierarchical.domain.ContextExecutionFacts;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.context.hierarchical.domain.StepSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalStepSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalStepSummaryProtocol;
import com.datagent.agentv2.conversation.model.codex.CodexContextSummaryModelAdapter;
import com.datagent.agentv2.conversation.model.codex.CodexModelProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 显式启用才调用真实摘要模型；仅使用合成脱敏样本，常规构建不产生模型费用。 */
@EnabledIfEnvironmentVariable(named = "RUN_REAL_CONTEXT_QUALITY", matches = "true")
class HierarchicalRealSummaryQualityTest {
    private static final String MODEL = "gpt-5.6-sol";
    private static final String GOAL = "完成 revenue 指标开发，使用本仓库分支；创建候选后等待用户明确确认。";
    private static final String CONSTRAINT = "已读取 Skill 原文：RULE_NO_PROD_PUSH 禁止直接推送生产。";
    private static final String FAILURE = "ERR_SQL_42：列名拼写错误，已定位尚未修复；下一步修改 SQL 并重新验证。";
    private static final String FILLER = "已读取示例字段说明，重复材料无需逐条保留。";
    private static final String HASH = "0".repeat(64);
    private static final String OUTPUT = "target/real-summary-quality.json";

    @Test
    void preservesGoalsConstraintsIdentifiersErrorsAndPendingConfirmation() throws Exception {
        var properties = new CodexModelProperties();
        properties.setModel(System.getenv().getOrDefault("DATA_AGENT_MODEL_CODEX_MODEL", MODEL));
        var json = JsonMapper.builder().build();
        var model = new CodexContextSummaryModelAdapter(properties, json);
        var protocol = new HierarchicalStepSummaryProtocol();
        var generator =
                new HierarchicalStepSummaryGenerator(
                        model, protocol, new ConversationTokenEstimator());
        var user = new ContextStepCursor(1, ContextStepCursor.PositionKind.MESSAGE_END, 0);
        var tool = new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 1);
        var groups =
                List.of(
                        new ContextStepGroup(
                                ContextStepCursor.ORIGIN,
                                user,
                                List.of(ContextMessage.user(GOAL)),
                                HASH,
                                true),
                        new ContextStepGroup(
                                user,
                                tool,
                                List.of(
                                        ContextMessage.assistant(
                                                CONSTRAINT + FAILURE + FILLER.repeat(120), null)),
                                HASH,
                                true));
        var coverage =
                new StepSummaryCoverage(
                        ContextStepCursor.ORIGIN,
                        tool,
                        HierarchicalStepSummaryGenerator.digestPrefix(groups, tool));
        var facts =
                new ContextExecutionFacts(
                        Map.of(
                                "branchId",
                                "branch-sample",
                                "workspaceId",
                                "workspace-sample",
                                "draftRevision",
                                "7",
                                "validationId",
                                "validation-sample",
                                "changeId",
                                "change-sample",
                                "queryId",
                                "query-sample",
                                "status",
                                "PREPARED",
                                "pushConfirmed",
                                "false"));
        var summary =
                generator.generate(null, groups, coverage, facts, 4096, 32768, 2048, () -> {});
        String rendered = protocol.render(summary);
        assertThat(summary.executionFacts()).isEqualTo(facts);
        assertThat(rendered).contains("revenue", "RULE_NO_PROD_PUSH", "ERR_SQL_42");
        assertThat(summary.entries()).anyMatch(entry -> entry.kind().name().equals("TODO"));
        Files.createDirectories(Path.of(OUTPUT).getParent());
        Files.writeString(Path.of(OUTPUT), json.writeValueAsString(summary));
    }
}
