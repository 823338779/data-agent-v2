package com.datagent.agentv2.development;

import com.datagent.agentv2.development.model.DevelopmentContracts.DevelopmentConfig;

import tools.jackson.databind.json.JsonMapper;

/** 两套完整环境配置，供冻结快照与恢复用例共享。 */
final class ExecutionFixtures {
    private ExecutionFixtures() {}

    static final String CONFIG =
"""
{"repository": "repo", "previewExecution": {"environmentId": "12345678-1234-4234-8234-000000000001", "configVersion": 1, "connection": {"type": "starrocks", "instanceId": "warehouse", "host": "localhost", "port": 9030, "path": null}, "credentialRef": "warehouse-test", "catalog": "default_catalog", "schema": "preview_metrics", "threads": 2, "businessTimezone": "Asia/Shanghai"}, "productionExecution": {"environmentId": "12345678-1234-4234-8234-000000000002", "configVersion": 1, "connection": {"type": "starrocks", "instanceId": "warehouse", "host": "localhost", "port": 9030, "path": null}, "credentialRef": "warehouse-test", "catalog": "default_catalog", "schema": "production_metrics", "threads": 2, "businessTimezone": "Asia/Shanghai"}, "version": 2}
""";

    static DevelopmentConfig config() {
        return new JsonMapper().readValue(CONFIG, DevelopmentConfig.class);
    }
}
