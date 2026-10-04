package com.datagent.agentv2.context.hierarchical.domain;

import java.util.List;
import java.util.Objects;

/** V3 结构化记忆，所有条目均引用已冻结来源；不表示新的执行授权。 */
public record HierarchicalStepSummaryDocument(
        /** 独立协议版本，固定为 3。 */
        int schemaVersion,
        /** 服务端确定的连续覆盖，模型不得更改。 */
        StepSummaryCoverage coverage,
        /** 按类别保存事实、目标、约束与待办。 */
        List<Entry> entries,
        /** 必须原样回显服务端冻结的身份与授权事实。 */
        ContextExecutionFacts executionFacts) {
    public HierarchicalStepSummaryDocument(
            int schemaVersion, StepSummaryCoverage coverage, List<Entry> entries) {
        this(schemaVersion, coverage, entries, ContextExecutionFacts.empty());
    }

    public HierarchicalStepSummaryDocument {
        Objects.requireNonNull(executionFacts, "executionFacts");
        Objects.requireNonNull(coverage, "coverage");
        entries = List.copyOf(entries);
        if (schemaVersion != 3 || entries.isEmpty()) {
            throw new IllegalArgumentException("Invalid step summary document");
        }
    }

    /** 类别只描述来源内容，不改变任何业务状态。 */
    public enum Kind {
        GOAL,
        CONSTRAINT,
        EVENT,
        FACT,
        TODO,
        ERROR,
        RECOVERY
    }

    /** 一项可追溯的摘要内容。 */
    public record Entry(
            /** 条目语义分类。 */
            Kind kind,
            /** 模型生成的精简文本。 */
            String text,
            /** 实际完整步骤的结束游标，至少一项。 */
            List<ContextStepCursor> sources) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            sources = List.copyOf(sources);
            if (text == null || text.isBlank() || sources.isEmpty()) {
                throw new IllegalArgumentException("Invalid step summary entry");
            }
        }
    }
}
