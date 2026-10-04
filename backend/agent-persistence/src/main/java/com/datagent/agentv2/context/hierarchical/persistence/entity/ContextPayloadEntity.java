package com.datagent.agentv2.context.hierarchical.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.nio.charset.StandardCharsets;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 与会话同生命周期的脱敏载荷；原始敏感值禁止进入本类型。 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("conversation_context_payload")
public class ContextPayloadEntity {
    /** 服务端生成的不透明业务主键。 */
    @TableId private String reference;

    /** 固定租户作用域。 */
    private String tenantId;

    /** 归属会话。 */
    private String conversationSessionId;

    /** 原工具执行身份。 */
    private String executionId;

    /** thinking process 节点身份。 */
    private String processItemId;

    /** INPUT 或 OUTPUT 来源方向。 */
    private String direction;

    /** 原文摘要。 */
    private String sha256;

    /** 原文 UTF-8 字节数。 */
    private Long totalBytes;

    /** 已完成脱敏、未被预览截断的正文。 */
    private String content;

    public static ContextPayloadEntity create(
            String reference,
            String tenantId,
            String sessionId,
            String executionId,
            String processItemId,
            String direction,
            String sha256,
            String content) {
        var value = new ContextPayloadEntity();
        value.reference = reference;
        value.tenantId = tenantId;
        value.conversationSessionId = sessionId;
        value.executionId = executionId;
        value.processItemId = processItemId;
        value.direction = direction;
        value.sha256 = sha256;
        value.totalBytes = (long) content.getBytes(StandardCharsets.UTF_8).length;
        value.content = content;
        return value;
    }
}
