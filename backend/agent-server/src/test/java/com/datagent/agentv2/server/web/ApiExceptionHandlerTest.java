package com.datagent.agentv2.server.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import com.datagent.agentv2.server.context.RequestContextFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@WebMvcTest(controllers = ApiExceptionHandlerTest.FailureFixtureController.class)
@Import({ApiExceptionHandler.class, RequestContextFilter.class, PrefixedUuidIdGenerator.class})
@ContextConfiguration(classes = ApiExceptionHandlerTest.FailureFixtureController.class)
class ApiExceptionHandlerTest {

    @Autowired private MockMvc mvc;

    @Test
    void disconnectedAsyncClientDoesNotCreateAnotherHttpResponse() {
        new ApiExceptionHandler()
                .handleDisconnectedClient(
                        new AsyncRequestNotUsableException("client disconnected"));
    }

    @Test
    void unexpectedFailureReturnsRequestIdWithoutStackTrace() throws Exception {
        mvc.perform(get("/__test/failure").header("X-Request-Id", "request_test"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Request-Id", "request_test"))
                .andExpect(jsonPath("$.type").value("urn:data-agent:problem:internal-error"))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value("request_test"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void invalidInboundRequestIdIsReplaced() throws Exception {
        mvc.perform(get("/__test/failure").header("X-Request-Id", "invalid id"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Request-Id", matchesPattern("request_[a-f0-9]{32}")))
                .andExpect(jsonPath("$.requestId", matchesPattern("request_[a-f0-9]{32}")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "GIT_CONFLICT,409",
        "GIT_CHANGE_NOT_FOUND,404",
        "GIT_NOT_CONFIGURED,503",
        "GIT_LIMIT_EXCEEDED,413",
        "GIT_COMMAND_FAILED,503",
        "GIT_TIMEOUT,504"
    })
    void gitFailuresKeepStableCodesAndNeverReturnCommandOutput(String code, int expectedStatus)
            throws Exception {
        mvc.perform(get("/__test/git-failure").param("code", code))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "secret-stderr"))));
    }

    @RestController
    static class FailureFixtureController {

        @GetMapping("/__test/git-failure")
        void gitFail(@org.springframework.web.bind.annotation.RequestParam String code) {
            throw new com.datagent.agentv2.git.model.GitException(
                    code, "secret-stderr credential-value /server/private");
        }

        @GetMapping("/__test/failure")
        void fail() {
            throw new IllegalStateException("sensitive failure");
        }
    }
}
