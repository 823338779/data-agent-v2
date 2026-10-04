package com.datagent.agentv2.conversation.process;

import com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference;
import com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference.Availability;
import com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference.Direction;
import com.datagent.agentv2.context.hierarchical.persistence.entity.ContextPayloadEntity;
import com.datagent.agentv2.context.hierarchical.persistence.repository.ContextPayloadRepository;
import com.datagent.agentv2.platform.context.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 在过程事务中先保存完整安全载荷，成功后才返回模型可使用的引用。 */
@RequiredArgsConstructor
public final class ContextPayloadArchive {
    private static final String DIGEST = "SHA-256";
    private final ContextPayloadRepository payloads;

    /** 单份脱敏正文的最大 UTF-8 字节数。 */
    private final int maxBytes;

    public ContextPayloadReference archive(
            String sessionId,
            String executionId,
            String processItemId,
            Direction direction,
            String safeContent) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Payload maximum must be positive");
        }
        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        String hash = hash(bytes);
        var facts = new java.util.TreeMap<String, String>();
        // 必须在数据库预览截断前取顶层协议字段；嵌套正文的同名键不具有状态语义。
        try {
            var output = JsonMapper.builder().build().readTree(safeContent);
            for (String field : ContextPayloadReference.OBSERVED_FIELDS) {
                var value = output.path(field);
                if (value.isValueNode() && !value.isNull()) {
                    facts.put(field, value.asString());
                }
            }
        } catch (tools.jackson.core.JacksonException plainText) {
            // 文本载荷仅归档正文，不推断业务身份。
        }
        if (bytes.length > maxBytes) {
            return new ContextPayloadReference(
                    null, Availability.OVERSIZE, bytes.length, hash, facts);
        }
        String tenant = TenantContext.requiredTenantId();
        String reference =
                hash(
                        JsonMapper.builder()
                                .build()
                                .writeValueAsBytes(
                                        List.of(
                                                tenant,
                                                sessionId,
                                                executionId,
                                                processItemId,
                                                direction.name(),
                                                hash)));
        // 失败直接交给现有执行异常边界，不能自动重跑已发生副作用的工具。
        payloads.insertOrSelect(
                ContextPayloadEntity.create(
                        reference,
                        tenant,
                        sessionId,
                        executionId,
                        processItemId,
                        direction.name(),
                        hash,
                        safeContent));
        return new ContextPayloadReference(
                reference, Availability.AVAILABLE, bytes.length, hash, facts);
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST).digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required digest algorithm is unavailable", impossible);
        }
    }
}
