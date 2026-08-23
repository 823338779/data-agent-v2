package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactEvalCorpusLoaderTest {

    @TempDir Path tempDirectory;

    @Test
    void loadsNumericLongMemEvalAnswerWithoutLeakingAnnotations() throws Exception {
        Path corpus = tempDirectory.resolve("longmemeval.json.gz");
        String content =
                """
                [{
                  "question_id": "case-1",
                  "question_type": "temporal-reasoning",
                  "question_date": "2026/08/30",
                  "question": "How many?",
                  "answer": 42,
                  "haystack_session_ids": ["session-a"],
                  "answer_session_ids": ["session-a"],
                  "haystack_dates": ["2026/08/23"],
                  "haystack_sessions": [[
                    {"role": "user", "content": "The answer is 42.", "has_answer": true}
                  ]]
                }, {
                  "question_id": "case-2",
                  "question": "Which color?",
                  "answer": "green",
                  "haystack_dates": ["2026/08/24"],
                  "haystack_sessions": [[
                    {"role": "user", "content": "The color is green."}
                  ]]
                }]
                """;
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(corpus))) {
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        CompactEvalCorpus loaded = new CompactEvalCorpusLoader(new ObjectMapper()).load(corpus, 1);

        assertEquals(1, loaded.scenarios().size());
        CompactEvalCorpus.Scenario scenario = loaded.scenarios().getFirst();
        assertEquals("42", scenario.probes().getFirst().expectedAnswer());
        assertTrue(scenario.history().getFirst().contains("USER: The answer is 42."));
        assertFalse(scenario.history().getFirst().contains("has_answer"));
        assertEquals("LongMemEval", scenario.metadata().dataset());
        assertEquals("temporal-reasoning", scenario.metadata().questionType());
        assertEquals("2026-08-30", scenario.metadata().questionDate());
        CompactEvalCorpus.Evidence evidence = scenario.metadata().evidence().getFirst();
        assertEquals("session-a", evidence.sessionId());
        assertEquals(List.of(0), evidence.messageIndexes());
        assertEquals("2026-08-23", evidence.date());
        assertEquals(7L, evidence.memoryAgeDays());
        assertEquals("answer_session_ids", evidence.positionSource());
    }

    @Test
    void loadsCLongEvalConversationMemoryJsonLines() throws Exception {
        Path corpus = tempDirectory.resolve("small.jsonl");
        Files.writeString(
                corpus,
                """
                {"context":"以下是2023年04月27日的对话记录：一段需要记住的中文对话。\\n问题：","query":"4月27日记住了什么？","answer":"中文对话","id":"lcvmem-1","chatglm_length":12,"qwen_length":13,"internlm2_length":14}
                {"context":"第二段对话。问题：","query":"这是第几段？","answer":"第二段","id":"lcvmem-2"}
                """);
        Files.writeString(
                tempDirectory.resolve("long_conversation_memory_with_pos.jsonl"),
                """
                {"id":"lcvmem-1","context_length":771,"position_idx":391,"mean_pos":0.507}
                """);

        CompactEvalCorpus loaded = new CompactEvalCorpusLoader(new ObjectMapper()).load(corpus, 2);

        assertEquals("CLongEval/LCvMem/small", loaded.source());
        assertEquals(2, loaded.scenarios().size());
        CompactEvalCorpus.Scenario scenario = loaded.scenarios().getFirst();
        assertEquals("lcvmem-1", scenario.id());
        assertTrue(scenario.history().getFirst().contains("一段需要记住的中文对话。"));
        assertFalse(scenario.history().getFirst().endsWith("问题："));
        assertEquals("4月27日记住了什么？", scenario.probes().getFirst().question());
        assertEquals("中文对话", scenario.probes().getFirst().expectedAnswer());
        assertEquals("CLongEval", scenario.metadata().dataset());
        assertEquals("small", scenario.metadata().subset());
        assertEquals("2023-04-27", scenario.metadata().evidence().getFirst().date());
        assertEquals("middle", scenario.metadata().evidence().getFirst().positionPeriod());
        assertEquals(4, scenario.metadata().evidence().getFirst().positionSegment());
        assertEquals(391, scenario.metadata().evidence().getFirst().sourcePositionIndex());
        assertEquals(
                "dataset-index", scenario.metadata().evidence().getFirst().sourcePositionUnit());
        assertEquals(
                "official-position-file",
                scenario.metadata().evidence().getFirst().positionSource());
        assertEquals(771, scenario.metadata().contextLengths().get("official_context_length"));
        assertEquals(12, scenario.metadata().contextLengths().get("chatglm_length"));
        CompactEvalCorpus.Evidence fallback =
                loaded.scenarios().get(1).metadata().evidence().getFirst();
        assertEquals("answer-text-fallback", fallback.positionSource());
        assertEquals("character", fallback.sourcePositionUnit());
    }

    @Test
    void buildsDeterministicNeedleBenchChineseScenarioFromOfficialRawShape() throws Exception {
        Path corpus = Files.createDirectories(tempDirectory.resolve("needlebench-zh"));
        Files.writeString(
                corpus.resolve("multi_needle_reasoning_zh.json"),
                """
                [{
                  "id":"needle-1",
                  "question":"甲最后在哪里工作？",
                  "answer":"上海",
                  "type":"bridge",
                  "level":"medium",
                  "derivations":["甲加入了乙公司。","乙公司的办公地点在上海。"]
                }, {
                  "id":"needle-2",
                  "question":"不会加载的问题",
                  "answer":"不会加载",
                  "type":"comparison",
                  "level":"easy",
                  "derivations":["不会加载的事实。"]
                }]
                """);
        Files.writeString(
                corpus.resolve("zh_general.jsonl"),
                """
                {"text":"这是用于填充长上下文的中文背景文章。","title":"背景"}
                """);

        CompactEvalCorpus loaded =
                new CompactEvalCorpusLoader(new ObjectMapper()).load(corpus, 1, 300);

        assertEquals("NeedleBench/zh/300-characters", loaded.source());
        assertEquals(1, loaded.scenarios().size());
        CompactEvalCorpus.Scenario scenario = loaded.scenarios().getFirst();
        String history = String.join("", scenario.history());
        assertTrue(history.contains("甲加入了乙公司。"));
        assertTrue(history.contains("乙公司的办公地点在上海。"));
        assertEquals("甲最后在哪里工作？", scenario.probes().getFirst().question());
        assertEquals("上海", scenario.probes().getFirst().expectedAnswer());
        assertEquals("NeedleBench", scenario.metadata().dataset());
        assertEquals("bridge", scenario.metadata().reasoningType());
        assertEquals("medium", scenario.metadata().difficulty());
        assertEquals(2, scenario.metadata().evidence().size());
        assertEquals("甲加入了乙公司。", scenario.metadata().evidence().getFirst().text());
        assertEquals("character", scenario.metadata().evidence().getFirst().sourcePositionUnit());
        assertEquals(
                "generated-insertion", scenario.metadata().evidence().getFirst().positionSource());
        assertEquals("early", scenario.metadata().evidence().getFirst().positionPeriod());
        assertEquals("middle", scenario.metadata().evidence().getLast().positionPeriod());
        assertTrue(
                scenario.metadata().evidence().getFirst().relativePosition()
                        < scenario.metadata().evidence().getLast().relativePosition());
        try (Stream<String> chunks = scenario.history().stream()) {
            assertTrue(chunks.allMatch(chunk -> chunk.length() < 8000));
        }
    }
}
