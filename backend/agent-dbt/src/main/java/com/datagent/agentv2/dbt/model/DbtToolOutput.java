package com.datagent.agentv2.dbt.model;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 同步结果的模型预算；无结果分页接口，超过预算时明确失败，绝不丢弃行。 */
public final class DbtToolOutput {
    private static final int MAX_CHARS = 20000;

    private DbtToolOutput() {}

    public static JsonNode bounded(JsonNode value, ObjectMapper json) throws ToolBusinessException {
        // 服务不保存查询结果，因此不能裁剪后构造不可兑现的 nextOffset。
        if (json.writeValueAsString(value).length() > MAX_CHARS)
            throw new ToolBusinessException(
                    "DBT_OUTPUT_TOO_LARGE", "输出超过模型预算，请缩小查询 limit、字段或筛选范围后重新查询");
        return value;
    }
}
