package com.datagent.agentv2.dbt.model;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 传输分页之外的模型预算；仅删除完整行并从实际展示位置继续。 */
public final class DbtToolOutput {
    private static final int MAX_CHARS = 20000, MAX_ROWS = 200;
    private static final String ROWS = "rows",
            OFFSET = "offset",
            NEXT = "nextOffset",
            RETURNED = "returnedRows",
            TRUNCATED = "localTruncated";

    private DbtToolOutput() {}

    public static JsonNode bounded(JsonNode value, ObjectMapper json) throws ToolBusinessException {
        ObjectNode output = (ObjectNode) value.deepCopy();
        if (output.path(ROWS).isArray()) {
            ArrayNode rows = (ArrayNode) output.path(ROWS);
            while (rows.size() > MAX_ROWS || json.writeValueAsString(output).length() > MAX_CHARS) {
                if (rows.size() <= 1)
                    throw new ToolBusinessException("DBT_OUTPUT_TOO_LARGE", "单行或元数据超过模型预算，请减少查询字段");
                rows.remove(rows.size() - 1);
                output.put(NEXT, output.path(OFFSET).asInt() + rows.size())
                        .put(RETURNED, rows.size())
                        .put(TRUNCATED, true);
            }
        }
        if (json.writeValueAsString(output).length() > MAX_CHARS)
            throw new ToolBusinessException("DBT_OUTPUT_TOO_LARGE", "输出超过模型预算，请缩小检索或读取范围");
        return output;
    }
}
