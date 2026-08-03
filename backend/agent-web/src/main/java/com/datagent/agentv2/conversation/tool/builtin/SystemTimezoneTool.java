package com.datagent.agentv2.conversation.tool.builtin;

import com.datagent.agentv2.conversation.tool.AgentTool;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 向模型公开当前服务端 JVM 使用的系统时区。 */
@Component
@Order(100)
public final class SystemTimezoneTool implements AgentTool {

    /** 注册给模型的稳定工具名称。 */
    private static final String TOOL_NAME = "system_timezone";

    /** 帮助模型判断何时需要读取服务端时区的工具说明。 */
    private static final String TOOL_DESCRIPTION =
            "Return the timezone currently used by the Data Agent server.";

    /** 无参数工具使用的封闭 JSON Schema。 */
    private static final String PARAMETERS_SCHEMA =
            """
            {"type":"object","additionalProperties":false}
            """;

    /** 工具输出中时区标识字段的名称。 */
    private static final String KEY_ZONE_ID = "zoneId";

    /** 工具输出中本地化时区名称字段的名称。 */
    private static final String KEY_DISPLAY_NAME = "displayName";

    /** 工具输出中当前 UTC 偏移字段的名称。 */
    private static final String KEY_OFFSET = "offset";

    /** Java 对零偏移使用的短标识。 */
    private static final String OFFSET_UTC = "Z";

    /** 面向模型返回的标准零偏移文本。 */
    private static final String OFFSET_ZERO = "+00:00";

    /** 统一提供时区和取样时刻，测试可注入固定时钟。 */
    private final Clock clock;

    /** 用于创建输出节点并解析参数 Schema。 */
    private final ObjectMapper json;

    /** 使用 Spring 提供的 JSON 配置和 JVM 默认时区创建生产工具。 */
    @Autowired
    public SystemTimezoneTool(ObjectMapper json) {
        this(Clock.systemDefaultZone(), json);
    }

    /** 使用固定时钟创建可重复验证的工具实例。 */
    SystemTimezoneTool(Clock clock) {
        this(clock, JsonMapper.builder().build());
    }

    /** 保存工具运行所需的时钟和 JSON 组件。 */
    private SystemTimezoneTool(Clock clock, ObjectMapper json) {
        this.clock = clock;
        this.json = json;
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

    /** 读取当前服务端时区及该时刻对应的 UTC 偏移。 */
    @Override
    public JsonNode execute(JsonNode arguments, InvocationContext context) {
        ZoneId zoneId = clock.getZone();
        ZoneOffset offset = zoneId.getRules().getOffset(clock.instant());

        // 输出同时包含稳定 IANA 标识、可读名称和当前偏移，方便模型直接回答。
        ObjectNode result = json.createObjectNode();
        result.put(KEY_ZONE_ID, zoneId.getId());
        result.put(KEY_DISPLAY_NAME, zoneId.getDisplayName(TextStyle.FULL, Locale.getDefault()));
        result.put(KEY_OFFSET, normalizedOffset(offset));
        return result;
    }

    /** 将 Java 的零偏移标识规范化为更容易识别的 ISO-8601 文本。 */
    private static String normalizedOffset(ZoneOffset offset) {
        return OFFSET_UTC.equals(offset.getId()) ? OFFSET_ZERO : offset.getId();
    }
}
