package com.datagent.agentv2.conversation.tool.builtin;

import com.datagent.agentv2.conversation.tool.AgentTool;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 向模型公开当前服务端时间，避免模型依赖训练数据推测时间。 */
@Component
@Order(110)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CurrentTimeTool implements AgentTool {

    /** 注册给模型的稳定工具名称。 */
    private static final String TOOL_NAME = "current_time";

    /** 帮助模型判断何时需要读取当前时间的工具说明。 */
    private static final String TOOL_DESCRIPTION =
            "Return the current server time, timezone, and UTC offset.";

    /** 无参数工具使用的封闭 JSON Schema。 */
    private static final String PARAMETERS_SCHEMA =
            """
            {"type":"object","additionalProperties":false}
            """;

    /** 工具输出中绝对时间字段的名称。 */
    private static final String KEY_INSTANT = "instant";

    /** 工具输出中本地日期时间字段的名称。 */
    private static final String KEY_LOCAL_DATE_TIME = "localDateTime";

    /** 工具输出中时区标识字段的名称。 */
    private static final String KEY_ZONE_ID = "zoneId";

    /** 工具输出中 UTC 偏移字段的名称。 */
    private static final String KEY_OFFSET = "offset";

    /** Java 对零偏移使用的短标识。 */
    private static final String OFFSET_UTC = "Z";

    /** 面向模型返回的标准零偏移文本。 */
    private static final String OFFSET_ZERO = "+00:00";

    /** 提供当前时刻和服务端时区，测试可注入固定时钟。 */
    private final Clock clock;

    /** 用于创建输出节点并解析参数 Schema。 */
    private final ObjectMapper json;

    /** 使用 Spring 提供的 JSON 配置和 JVM 默认时区创建生产工具。 */
    @Autowired
    public CurrentTimeTool(ObjectMapper json) {
        this(Clock.systemDefaultZone(), json);
    }

    /** 使用固定时钟创建可重复验证的工具实例。 */
    CurrentTimeTool(Clock clock) {
        this(clock, JsonMapper.builder().build());
    }

    /** 返回模型协议中使用的稳定工具名称。 */
    @Override
    public String name() {
        return TOOL_NAME;
    }

    /** 返回面向模型的工具用途说明。 */
    @Override
    public String description() {
        return TOOL_DESCRIPTION;
    }

    /** 声明本工具不接受任何模型参数。 */
    @Override
    public JsonNode parametersSchema() {
        return json.readTree(PARAMETERS_SCHEMA);
    }

    /** 在同一个时钟快照上生成绝对时间、本地时间、时区和偏移。 */
    @Override
    public JsonNode execute(JsonNode arguments, InvocationContext context) {
        ZonedDateTime current = ZonedDateTime.now(clock);

        // 所有字段来自同一次取样，防止跨秒或跨日期边界产生自相矛盾的结果。
        ObjectNode result = json.createObjectNode();
        result.put(KEY_INSTANT, current.toInstant().toString());
        result.put(KEY_LOCAL_DATE_TIME, current.toLocalDateTime().toString());
        result.put(KEY_ZONE_ID, current.getZone().getId());
        result.put(KEY_OFFSET, normalizedOffset(current.getOffset().getId()));
        return result;
    }

    /** 将 Java 的零偏移标识规范化为更容易识别的 ISO-8601 文本。 */
    private static String normalizedOffset(String offset) {
        return OFFSET_UTC.equals(offset) ? OFFSET_ZERO : offset;
    }
}
