package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CompactEvalCorpusLoaderTest {

    private static final String NATIVE_RESOURCE =
            "src/test/resources/compact-eval/smoke-corpus.json";
    private static final String LONG_MEM_EVAL_RESOURCE =
            "src/test/resources/compact-eval/longmemeval-sample.json";

    private final CompactEvalCorpusLoader loader = new CompactEvalCorpusLoader(new ObjectMapper());

    @Test
    void loadsNativeCorpusWithoutChangingTurnOrder() throws Exception {
        CompactEvalCorpus corpus = loader.load(Path.of(NATIVE_RESOURCE));

        assertEquals("built-in-smoke", corpus.source());
        assertEquals(1, corpus.scenarios().size());
        assertEquals(8, corpus.scenarios().getFirst().history().size());
        assertEquals("最终部署区域是什么？", corpus.scenarios().getFirst().probes().getFirst().question());
    }

    @Test
    void adaptsLongMemEvalWithoutLeakingAnswerAnnotations() throws Exception {
        CompactEvalCorpus corpus = loader.load(Path.of(LONG_MEM_EVAL_RESOURCE));

        CompactEvalCorpus.Scenario scenario = corpus.scenarios().getFirst();
        assertEquals("LongMemEval", corpus.source());
        assertEquals(2, scenario.history().size());
        assertFalse(scenario.history().getFirst().contains("has_answer"));
        assertEquals("GPS system", scenario.probes().getFirst().expectedAnswer());
    }

    @Test
    void rejectsUnknownCorpusShape() {
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.load(Path.of("src/test/resources/compact-eval/invalid-corpus.json")));
    }
}
