package com.datagent.agentv2.context.lsm.persistence.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies canonical, body-free compaction plan documents across process restarts. */
class LsmDocumentCodecTest {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String HASH_UNAVAILABLE = "SHA-256 is unavailable";
    private static final String TARGET_A = "planned-a";
    private static final String TARGET_B = "planned-b";

    @Test
    void canonicalizesMapKeysWhilePreservingPlannerListOrderAcrossRestart() {
        Map<String, String> reverseKeys = new LinkedHashMap<>();
        reverseKeys.put(TARGET_B, "key-b");
        reverseKeys.put(TARGET_A, "key-a");
        Map<String, String> forwardKeys = new LinkedHashMap<>();
        forwardKeys.put(TARGET_A, "key-a");
        forwardKeys.put(TARGET_B, "key-b");
        Map<String, String> reverseHashes = new LinkedHashMap<>();
        reverseHashes.put(TARGET_B, "hash-b");
        reverseHashes.put(TARGET_A, "hash-a");
        Map<String, String> forwardHashes = new LinkedHashMap<>();
        forwardHashes.put(TARGET_A, "hash-a");
        forwardHashes.put(TARGET_B, "hash-b");

        // Serialize equivalent inputs with opposite map insertion order.
        String firstDocument =
                new LsmDocumentCodec().writeCompactionPlan(plan(), reverseKeys, reverseHashes);
        String secondDocument =
                new LsmDocumentCodec().writeCompactionPlan(plan(), forwardKeys, forwardHashes);
        String restartedDocument = new LsmDocumentCodec().canonicalizeCompactionPlan(firstDocument);

        // Map keys are sorted, while the deliberately B-before-A operation list is unchanged.
        assertThat(firstDocument)
                .isEqualTo(secondDocument)
                .isEqualTo(restartedDocument)
                .contains("\"segmentKeys\":{\"planned-a\":\"key-a\",\"planned-b\":\"key-b\"}")
                .contains(
                        "\"sourceContentHashes\":{\"planned-a\":\"hash-a\",\"planned-b\":\"hash-b\"}");
        assertThat(firstDocument.indexOf("\"targetId\":\"planned-b\""))
                .isLessThan(firstDocument.indexOf("\"targetId\":\"planned-a\""));
        assertThat(sha256(firstDocument))
                .isEqualTo(sha256(secondDocument))
                .isEqualTo(sha256(restartedDocument));
    }

    private static LsmCompactionPlan plan() {
        LsmCompactionPlan.PlannedOperation operationB = operation(TARGET_B, 2L);
        LsmCompactionPlan.PlannedOperation operationA = operation(TARGET_A, 1L);
        LsmContextManifest manifest =
                new LsmContextManifest(
                        TARGET_B, List.of(TARGET_B, TARGET_A), null, null, null, null, 4L, 3, 2);
        return new LsmCompactionPlan(
                new LsmCompactionPlan.HotBoundary(null, null, 0, null),
                List.of(operationB, operationA),
                manifest,
                List.of(segment(TARGET_B, 2L), segment(TARGET_A, 1L)));
    }

    private static LsmCompactionPlan.PlannedOperation operation(String targetId, long turn) {
        return new LsmCompactionPlan.PlannedOperation(
                LsmCompactionPlan.OperationType.SEAL_L0,
                targetId,
                List.of(),
                LsmSummarySegment.SegmentKind.STANDARD,
                0,
                new LsmSummarySegment.Coverage(turn, turn),
                false,
                100);
    }

    private static LsmSummarySegment segment(String targetId, long turn) {
        return new LsmSummarySegment(
                targetId,
                LsmSummarySegment.SegmentKind.STANDARD,
                0,
                new LsmSummarySegment.Coverage(turn, turn),
                100,
                List.of("raw-" + turn),
                false);
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance(HASH_ALGORITHM)
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(HASH_UNAVAILABLE, failure);
        }
    }
}
