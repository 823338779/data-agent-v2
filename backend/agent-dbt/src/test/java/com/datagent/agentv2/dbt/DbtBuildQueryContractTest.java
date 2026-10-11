package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** v3 是唯一活动协议，旧业务方法必须完全退出。 */
class DbtBuildQueryContractTest {
    @Test
    void onlyTypedV3ContractIsExposed() {
        assertThat(
                        Arrays.stream(DbtServiceClient.class.getMethods())
                                .map(java.lang.reflect.Method::getName))
                .contains("submitBuild", "getBuild", "currentDeployment", "submitQuery")
                .doesNotContain(
                        "project",
                        "submitValidation",
                        "branches",
                        "release",
                        "getOptions",
                        "queryStatus",
                        "queryResults");
    }
}
