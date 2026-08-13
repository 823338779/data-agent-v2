package com.datagent.agentv2.context.hierarchical.summary;

import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 根据持久化唯一身份的全部维度生成确定性 hierarchical node key。 */
public final class HierarchicalNodeKeyFactory {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String HASH_UNAVAILABLE = "SHA-256 is unavailable";
    private static final String IDENTITY_MISMATCH =
            "Generation request and identity belong to different sessions";

    /** 相同会话、来源种类、范围、协议与模型身份总是生成同一 key。 */
    public String create(GenerationRequest request, SummaryGenerationIdentity identity) {
        GenerationRequest checkedRequest = Objects.requireNonNull(request, "request");
        SummaryGenerationIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        if (!checkedRequest
                .conversationSessionId()
                .equals(checkedIdentity.conversationSessionId())) {
            throw new IllegalArgumentException(IDENTITY_MISMATCH);
        }

        // 节点 key 与数据库不可变 identity 对齐；固定宽度数字和长度前缀文本不存在分隔符歧义。
        GenerationRange range = checkedRequest.range();
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            updateText(digest, checkedIdentity.conversationSessionId());
            updateText(digest, checkedRequest.source().kind().name());
            updateInt(digest, range.level());
            updateLong(digest, range.startTurn());
            updateLong(digest, range.endTurn());
            updateLong(digest, range.startMessageSequence());
            updateLong(digest, range.endMessageSequence());
            updateInt(digest, checkedIdentity.schemaVersion());
            updateInt(digest, checkedIdentity.promptVersion());
            updateText(digest, checkedIdentity.modelIdentity().provider());
            updateText(digest, checkedIdentity.modelIdentity().model());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(HASH_UNAVAILABLE, failure);
        }
    }

    private static void updateText(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }
}
