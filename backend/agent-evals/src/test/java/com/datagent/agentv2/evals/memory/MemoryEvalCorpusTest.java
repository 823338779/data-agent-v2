package com.datagent.agentv2.evals.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 仅验证人工语义评测材料结构，不调用模型，也不输出语义准确率。 */
class MemoryEvalCorpusTest {
    @Test
    void allCasesHaveUniqueIdsCompleteContractsAndValidKinds() throws Exception {
        var json = new ObjectMapper();
        var ids = new HashSet<String>();
        try (var stream = getClass().getResourceAsStream("/memory-eval/cases.jsonl")) {
            assertNotNull(stream, "缺少个人记忆语料");
            for (String line :
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .filter(s -> !s.isBlank())
                            .toList()) {
                var row = json.readTree(line);
                for (String field :
                        List.of(
                                "id",
                                "messages",
                                "expectedActions",
                                "expectedKind",
                                "requiredConditions",
                                "forbiddenActions")) assertTrue(row.has(field), field);
                assertTrue(ids.add(row.path("id").asText()), "重复 ID");
                assertTrue(row.path("messages").isArray() && !row.path("messages").isEmpty());
                for (String field :
                        List.of(
                                "messages",
                                "expectedActions",
                                "requiredConditions",
                                "forbiddenActions")) {
                    assertTrue(row.path(field).isArray(), field);
                    for (var value : row.path(field))
                        assertTrue(value.isTextual() && !value.asText().isBlank(), field);
                }
                if (row.has("expectedKinds"))
                    for (var kind : row.path("expectedKinds"))
                        assertTrue(
                                Set.of("person_profile", "collaboration_preference")
                                        .contains(kind.asText()));
                assertTrue(
                        row.path("expectedKind").isNull()
                                || Set.of("person_profile", "collaboration_preference")
                                        .contains(row.path("expectedKind").asText()));
                for (String field : List.of("expectedActions", "forbiddenActions"))
                    for (var action : row.path(field))
                        assertTrue(
                                Set.of("add", "replace", "remove", "confirm")
                                        .contains(action.asText()));
                if (Set.of(
                                "semantic-duplicate",
                                "explicit-correction",
                                "ambiguous-forget",
                                "explicit-restore")
                        .contains(row.path("id").asText())) {
                    assertTrue(row.path("initialState").isObject());
                    assertTrue(row.path("evaluationNotes").isTextual());
                }
            }
        }
        assertEquals(
                Set.of(
                        "temporary-language",
                        "conditional-evidence",
                        "stable-role",
                        "language-profile",
                        "two-facts",
                        "weak-praise",
                        "semantic-duplicate",
                        "explicit-correction",
                        "ambiguous-forget",
                        "explicit-restore"),
                ids);
    }
}
