package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Agent 执行进度阶段的有限业务取值。 */
public enum ExecutionPhase {
    /** 本机执行已启动。 */
    RECEIVED,

    /** 本机执行正在确定模型输入消息边界。 */
    PREPARING_CONTEXT,

    /** 正在等待模型输出。 */
    CONNECTING_MODEL,

    /** 模型正在生成或已经生成回答正文。 */
    ANSWERING,

    /** 执行正在收敛最终消息和终态记录。 */
    PERSISTING,

    /** 执行及其流投影已经成功完成。 */
    COMPLETED;

    /** 返回数据库和接口契约使用的大写阶段名称。 */
    @JsonValue
    public String value() {
        return name();
    }

    /** 从数据库或 JSON 字符串严格解析执行阶段。 */
    @JsonCreator
    public static ExecutionPhase fromValue(String value) {
        // 只接受精确枚举名称，避免大小写归一化掩盖非法持久化数据。
        return Arrays.stream(values())
                .filter(candidate -> candidate.name().equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown execution phase: " + value));
    }
}
