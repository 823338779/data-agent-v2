package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.dbt.model.DbtContracts;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 消费服务权威fixture，每个协议模型必须逐字段无损往返。 */
class DbtServiceContractIntegrationTest {
    static final JsonMapper JSON = new JsonMapper();

    static JsonNode fixture(String name) throws Exception {
        try (var input =
                DbtServiceContractIntegrationTest.class.getResourceAsStream("/contracts/v3.json")) {
            for (JsonNode sample : JSON.readTree(input).path("examples"))
                if (name.equals(sample.path("name").asString())) return sample.path("value");
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    void allServiceModelsRoundTripWithoutRenamingOrLosingPrecision() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/v3.json")) {
            var fixture = JSON.readTree(input);
            assertThat(fixture.path("protocolVersion").asString()).isEqualTo("build-deployment-v3");
            for (JsonNode example : fixture.path("examples")) {
                String name = example.path("model").asString();
                Class<?> model =
                        Arrays.stream(DbtContracts.class.getDeclaredClasses())
                                .filter(type -> type.getSimpleName().equals(name))
                                .findFirst()
                                .orElseThrow();
                JsonNode value = example.path("value");
                assertThat(JSON.readTree(JSON.writeValueAsString(JSON.treeToValue(value, model))))
                        .as(example.path("name").asString())
                        .isEqualTo(value);
            }
        }
    }

    @Test
    void timeAndDecimalStringsRemainOpaque() throws Exception {
        ResultPage result = JSON.treeToValue(fixture("precision-result"), ResultPage.class);
        assertThat(result.rows().getFirst().getFirst().asString())
                .isEqualTo("12345678901234567890.123456789");
        assertThat(result.rows().getFirst().get(1).isNull()).isTrue();
        assertThat(result.businessTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.normalizedTimeRange().path("startTime").asString())
                .isEqualTo("2026-10-01T00:00:00");
    }
}
