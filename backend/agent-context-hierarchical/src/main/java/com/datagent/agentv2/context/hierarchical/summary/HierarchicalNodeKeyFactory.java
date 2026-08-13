package com.datagent.agentv2.context.hierarchical.summary;

import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 根据持久化唯一身份的全部维度生成确定性 hierarchical node key。 */
public final class HierarchicalNodeKeyFactory {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String FIELD_SEPARATOR = "\u001f";
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

        // 节点 key 与数据库不可变 identity 对齐，不携带来源正文或原始 reasoning。
        GenerationRange range = checkedRequest.range();
        String material =
                String.join(
                        FIELD_SEPARATOR,
                        checkedIdentity.conversationSessionId(),
                        checkedRequest.source().kind().name(),
                        Integer.toString(range.level()),
                        Long.toString(range.startTurn()),
                        Long.toString(range.endTurn()),
                        Long.toString(range.startMessageSequence()),
                        Long.toString(range.endMessageSequence()),
                        Integer.toString(checkedIdentity.schemaVersion()),
                        Integer.toString(checkedIdentity.promptVersion()),
                        checkedIdentity.modelIdentity().provider(),
                        checkedIdentity.modelIdentity().model());
        try {
            byte[] digest =
                    MessageDigest.getInstance(HASH_ALGORITHM)
                            .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(HASH_UNAVAILABLE, failure);
        }
    }
}
