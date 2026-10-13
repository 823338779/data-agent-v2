package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.dbt.model.DbtContracts.BuildRequest;
import com.datagent.agentv2.dbt.model.DbtContracts.BuildView;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** 新快照请求响应必须完整往返，不再接受绑定字段作为执行信息。 */
class SnapshotContractTest {
    private static final String FIXTURE = "/contracts/dbt.json";
    private static final String EXAMPLES = "examples", VALUE = "value", MODEL = "model";
    private static final String REQUEST = "SnapshotBuildRequest", VIEW = "SnapshotBuildView";

    @Test
    void snapshotRequestAndResponseRoundTripWithoutLegacyFields() throws Exception {
        var json = new JsonMapper();
        try (var input = getClass().getResourceAsStream(FIXTURE)) {
            for (var example : json.readTree(input).path(EXAMPLES)) {
                var model = example.path(MODEL).asString();
                if (!REQUEST.equals(model) && !VIEW.equals(model)) continue;
                var value = example.path(VALUE);
                Class<?> type = REQUEST.equals(model) ? BuildRequest.class : BuildView.class;
                assertThat(json.readTree(json.writeValueAsString(json.treeToValue(value, type))))
                        .isEqualTo(value);
            }
        }
    }
}
