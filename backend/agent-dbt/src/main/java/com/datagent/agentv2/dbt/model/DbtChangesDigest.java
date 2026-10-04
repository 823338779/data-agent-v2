package com.datagent.agentv2.dbt.model;

import com.datagent.agentv2.dbt.model.DbtContracts.DraftChange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Python/Java 共用 changes-v1；UTF-8 无符号字节排序且不转换换行。 */
public final class DbtChangesDigest {
    private static final String HEADER = "dbt-changes-v1\n", ABSENT = "-", SHA256 = "SHA-256";
    private static final String HEADER_V2 = "dbt-changes-v2\n";

    private DbtChangesDigest() {}

    public static String sha256(String text) {
        return HexFormat.of().formatHex(hasher().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String digest(List<DraftChange> changes) {
        return digest(changes, HEADER);
    }

    public static String digestV2(List<DraftChange> changes) {
        return digest(changes, HEADER_V2);
    }

    private static String digest(List<DraftChange> changes, String header) {
        var digest = hasher();
        digest.update(header.getBytes(StandardCharsets.UTF_8));
        changes.stream()
                .sorted(
                        (a, b) ->
                                Arrays.compareUnsigned(
                                        a.path().getBytes(StandardCharsets.UTF_8),
                                        b.path().getBytes(StandardCharsets.UTF_8)))
                .forEach(
                        change -> {
                            String row =
                                    String.join(
                                                    "\0",
                                                    change.operation(),
                                                    change.path(),
                                                    change.expectedSha256() == null
                                                            ? ABSENT
                                                            : change.expectedSha256(),
                                                    change.content() == null
                                                            ? ABSENT
                                                            : sha256(change.content()))
                                            + "\n";
                            digest.update(row.getBytes(StandardCharsets.UTF_8));
                        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest hasher() {
        try {
            return MessageDigest.getInstance(SHA256);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
