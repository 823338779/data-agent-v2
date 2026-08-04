package com.datagent.agentv2.conversation.model;

import java.util.Objects;

/** 模型供应商按原始顺序返回的强类型思考或回答增量。 */
public record ModelOutputDelta(
        /** 增量语义类型。 */
        Kind kind,
        /** 本次非空文本片段。 */
        String content,
        /** 模型供应商稳定标识。 */
        String modelProvider,
        /** 供应商模型名称。 */
        String modelName) {

    public ModelOutputDelta {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(modelProvider, "modelProvider");
        Objects.requireNonNull(modelName, "modelName");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("model output delta content must not be empty");
        }
    }

    /** 创建 thinking 增量。 */
    public static ModelOutputDelta thinking(String value, String provider, String model) {
        return new ModelOutputDelta(Kind.THINKING, value, provider, model);
    }

    /** 创建最终回答增量。 */
    public static ModelOutputDelta answer(String value, String provider, String model) {
        return new ModelOutputDelta(Kind.ANSWER, value, provider, model);
    }

    /** 模型输出片段的业务语义。 */
    public enum Kind {
        /** 不进入真实回答正文的模型思考过程。 */
        THINKING,

        /** 进入真实 assistant 正文的回答片段。 */
        ANSWER
    }
}
