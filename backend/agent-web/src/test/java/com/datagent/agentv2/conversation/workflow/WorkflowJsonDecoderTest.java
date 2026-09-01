package com.datagent.agentv2.conversation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.workflow.model.ExecutionRoute;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkflowJsonDecoderTest {

    private final WorkflowJsonDecoder decoder =
            new WorkflowJsonDecoder(JsonMapper.builder().build());

    @Test
    void decodesOneStrictRouteDocument() {
        assertThat(decoder.decodeRoute("{\"route\":\"DATA_ANALYSIS_WORKFLOW\"}"))
                .isEqualTo(ExecutionRoute.DATA_ANALYSIS_WORKFLOW);
    }

    @Test
    void rejectsMarkdownTrailingTextAndUnknownFields() {
        assertThatThrownBy(() -> decoder.decodeRoute("```json\n{\"route\":\"GENERAL_REACT\"}\n```"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decodeRoute("{\"route\":\"GENERAL_REACT\"} trailing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> decoder.decodeRoute("{\"route\":\"GENERAL_REACT\",\"next\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodesQueryPlanWithoutSqlOrGraphFields() {
        String document =
                """
                {
                  "analysisGoal":"分析销售额",
                  "tasks":[{
                    "taskId":"sales",
                    "purpose":"统计销售额",
                    "semanticResourceIds":["orders","paid_sales"],
                    "metrics":["paid_sales"],
                    "dimensions":[],
                    "filters":[],
                    "timeRange":null,
                    "orderBy":[],
                    "limit":100
                  }]
                }
                """;
        assertThat(decoder.decodeQueryPlan(document).tasks())
                .singleElement()
                .extracting(task -> task.taskId())
                .isEqualTo("sales");
    }
}
