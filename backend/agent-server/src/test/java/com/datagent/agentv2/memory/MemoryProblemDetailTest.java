package com.datagent.agentv2.memory;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import com.datagent.agentv2.server.context.RequestContextFilter;
import com.datagent.agentv2.server.web.ApiExceptionHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = MemoryProblemDetailTest.FailureFixtureController.class)
@Import({ApiExceptionHandler.class, RequestContextFilter.class, PrefixedUuidIdGenerator.class})
@ContextConfiguration(classes = MemoryProblemDetailTest.FailureFixtureController.class)
class MemoryProblemDetailTest {

    @Autowired private MockMvc mvc;

    @ParameterizedTest
    @CsvSource({
        "INVALID_REQUEST,400",
        "UNSAFE_CONTENT,400",
        "NOT_FOUND,404",
        "CONFLICT,409",
        "DUPLICATE_CONFLICT,409",
        "PROTECTED,409",
        "SUPPRESSED,409",
        "QUOTA_EXCEEDED,422",
        "LEARNING_DISABLED,409",
        "LEASE_LOST,409",
        "UNAVAILABLE,503"
    })
    void memoryFailuresUseStableProblemDetails(MemoryStatus code, int expectedStatus)
            throws Exception {
        mvc.perform(
                        get("/__test/memory-failure")
                                .param("code", code.name())
                                .header("X-Request-Id", "memory-request"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.type").value("urn:data-agent:problem:memory"))
                .andExpect(jsonPath("$.title").value("Memory operation failed"))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.requestId").value("memory-request"))
                .andExpect(content().string(not(containsString("secret-memory-content"))));
    }

    @org.junit.jupiter.api.Test
    void obsoleteKindAliasInQueryIsAValidationFailure() throws Exception {
        mvc.perform(get("/__test/memory-kind").param("kind", "work_context"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @RestController
    static class FailureFixtureController {
        @GetMapping("/__test/memory-failure")
        void fail(@RequestParam MemoryStatus code) {
            throw new MemoryException(code, "记忆操作未完成");
        }

        @GetMapping("/__test/memory-kind")
        void kind(@RequestParam MemoryKind kind) {}
    }
}
