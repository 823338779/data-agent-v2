package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.CARRIAGE_RETURN;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_HASH_ALGORITHM_UNAVAILABLE_SUFFIX;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_IDEMPOTENCY_KEY_BLANK;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.HASH_ALGORITHM_SHA_256;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.LINE_SEPARATOR;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.WINDOWS_LINE_SEPARATOR;

import com.datagent.agentv2.conversation.domain.ConversationSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public final class AgentExecutionSubmissionService {

    private final AgentExecutionSubmissionTransactionService submissionTransactions;

    public ConversationSession submit(
            String conversationSessionId, String content, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        String payloadHash = payloadHash(content);

        // 先提交创建事务；唯一约束冲突时，再通过独立事务读取并发请求的提交结果。
        try {
            return submissionTransactions.createOrReplay(
                    conversationSessionId, content, idempotencyKey, payloadHash);
        } catch (DataIntegrityViolationException conflict) {
            ConversationSession replay =
                    submissionTransactions.replayAfterConstraintConflict(
                            conversationSessionId, idempotencyKey, payloadHash);
            if (replay != null) {
                return replay;
            }
            throw conflict;
        }
    }

    static String payloadHash(String content) {
        String normalized =
                Normalizer.normalize(
                        content.replace(WINDOWS_LINE_SEPARATOR, LINE_SEPARATOR)
                                .replace(CARRIAGE_RETURN, LINE_SEPARATOR),
                        Normalizer.Form.NFC);
        try {
            byte[] digest =
                    MessageDigest.getInstance(HASH_ALGORITHM_SHA_256)
                            .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    HASH_ALGORITHM_SHA_256 + ERROR_HASH_ALGORITHM_UNAVAILABLE_SUFFIX, impossible);
        }
    }
}
