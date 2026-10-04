package com.datagent.agentv2.context.hierarchical.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalStepSummaryDocument;
import com.datagent.agentv2.context.hierarchical.domain.StepSummaryCoverage;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalStepSummaryProtocolTest {
    private static final String HASH = "a".repeat(64);
    private static final String TEXT = "已完成读取，等待用户确认推送";
    private final HierarchicalStepSummaryProtocol protocol = new HierarchicalStepSummaryProtocol();
    private final ContextStepCursor end =
            new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 3);
    private final StepSummaryCoverage coverage =
            new StepSummaryCoverage(ContextStepCursor.ORIGIN, end, HASH);

    @Test
    void acceptsVerifiedCoverageAndRendersFacts() {
        String json = document(coverage, end);
        assertThat(protocol.render(protocol.validate(json, coverage))).contains(TEXT);
    }

    @Test
    void rejectsForgedCoverageAndEvidence() {
        var foreign = new ContextStepCursor(3, ContextStepCursor.PositionKind.MESSAGE_END, 0);
        assertThatThrownBy(() -> protocol.validate(document(coverage, foreign), coverage))
                .isInstanceOf(IllegalArgumentException.class);
        var forged = new StepSummaryCoverage(ContextStepCursor.ORIGIN, foreign, HASH);
        assertThatThrownBy(() -> protocol.validate(document(forged, end), coverage))
                .isInstanceOf(IllegalArgumentException.class);
        // 范围内但并不存在的 invocation 同样不能作为证据。
        var absent = new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 2);
        assertThatThrownBy(
                        () -> protocol.validate(document(coverage, absent), coverage, List.of(end)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String document(StepSummaryCoverage value, ContextStepCursor source) {
        return JsonMapper.builder()
                .build()
                .writeValueAsString(
                        new HierarchicalStepSummaryDocument(
                                3,
                                value,
                                List.of(
                                        new HierarchicalStepSummaryDocument.Entry(
                                                HierarchicalStepSummaryDocument.Kind.FACT,
                                                TEXT,
                                                List.of(source)))));
    }
}
