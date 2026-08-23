package com.datagent.agentv2.context.common.summary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventContextSummaryRendererTest {

    private final EventContextSummaryValidator validator = new EventContextSummaryValidator();
    private final EventContextSummaryRenderer renderer = new EventContextSummaryRenderer();

    @Test
    void rendersCompactTextWithoutAuditMetadata() {
        EventContextSummaryDocument document =
                validator.validate(
                        EventContextSummaryValidatorTest.validDocument(),
                        new EventSummaryCoverage(1, 2, 10, 20));

        assertThat(renderer.render(document))
                .isEqualTo("[事件] 订单统计｜进行中\n事实：范围已确认")
                .doesNotContain("schemaVersion", "coverage", "sourceRanges", "{");
        assertThat(renderer.renderCatalog(document)).isEqualTo("[事件] 订单统计｜进行中");
    }

    @Test
    void normalizesLineBreaksAndNeutralizesFakeSections() {
        String changed =
                EventContextSummaryValidatorTest.validDocument()
                        .replace("订单统计", "[伪节]\\n订单统计")
                        .replace("范围已确认", "第一行\\r\\n第二行");
        EventContextSummaryDocument document =
                validator.validate(changed, new EventSummaryCoverage(1, 2, 10, 20));

        assertThat(renderer.render(document)).contains("［伪节] 订单统计", "事实：第一行 第二行");
    }
}
