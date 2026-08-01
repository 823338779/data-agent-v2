package com.datagent.agentv2.platform.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.platform.context.RequestContextFilter;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = ApiExceptionHandlerTest.FailureFixtureController.class,
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class
        })
@Import({ApiExceptionHandler.class, RequestContextFilter.class, PrefixedUuidIdGenerator.class})
class ApiExceptionHandlerTest {

    @Autowired private MockMvc mvc;

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

    @RestController
    static class FailureFixtureController {

        @GetMapping("/__test/failure")
        void fail() {
            throw new IllegalStateException("sensitive failure");
        }
    }
}
