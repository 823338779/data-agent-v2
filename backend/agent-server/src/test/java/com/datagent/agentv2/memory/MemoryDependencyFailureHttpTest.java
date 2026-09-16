package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.controller.PersonalMemoryController;
import com.datagent.agentv2.server.web.ApiExceptionHandler;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** 真实 HTTP 与事务代理外边界；仅替换数据库事务管理器与事务内领域操作，确定性注入依赖故障。 */
class MemoryDependencyFailureHttpTest {
    private static final String PRIVATE_DETAIL = "private-sql-and-connection-detail";

    @Test
    void transactionAcquisitionFailureMapsAllManagementEntrypointsToUnavailable() throws Exception {
        var target = mock(MemoryService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any()))
                .thenThrow(new CannotCreateTransactionException(PRIVATE_DETAIL));
        var mvc = mvc(transactional(target, manager));
        for (var request : requests()) assertUnavailable(mvc, request);
        verifyNoInteractions(target);
    }

    static Stream<RuntimeException> connectionFailures() {
        return Stream.of(
                new DataAccessResourceFailureException(PRIVATE_DETAIL),
                new TransientDataAccessResourceException(PRIVATE_DETAIL),
                new org.springframework.jdbc.CannotGetJdbcConnectionException(PRIVATE_DETAIL),
                new TransactionSystemException(
                        PRIVATE_DETAIL, new SQLException(PRIVATE_DETAIL, "08006")),
                new TransactionSystemException(
                        PRIVATE_DETAIL,
                        new java.sql.SQLTransientConnectionException(PRIVATE_DETAIL)),
                new TransactionSystemException(
                        PRIVATE_DETAIL,
                        new java.sql.SQLNonTransientConnectionException(PRIVATE_DETAIL)),
                new TransactionSystemException(
                        PRIVATE_DETAIL, new java.sql.SQLRecoverableException(PRIVATE_DETAIL)));
    }

    @ParameterizedTest
    @MethodSource("connectionFailures")
    void connectionFailuresDoNotLeakOrBecomeInternalErrors(RuntimeException failure)
            throws Exception {
        var memory = mock(MemoryService.class);
        when(memory.snapshot()).thenThrow(failure);
        when(memory.execute(any(), any())).thenThrow(failure);
        when(memory.updateSettings(any(), any())).thenThrow(failure);
        for (var request : requests()) assertUnavailable(mvc(memory), request);
    }

    @Test
    void commitConnectionFailureNeverReturnsSuccessAndRetryKeepsTheOperationKey() throws Exception {
        var target = mock(MemoryService.class);
        var outcome = new MemoryOutcome(MemoryStatus.CREATED, "entry", 1L, 1, true, true, Map.of());
        when(target.execute(any(), any())).thenReturn(outcome);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(DefaultTransactionStatus.class));
        doThrow(
                        new TransactionSystemException(
                                PRIVATE_DETAIL, new SQLException(PRIVATE_DETAIL, "08006")))
                .doNothing()
                .when(manager)
                .commit(any());
        var mvc = mvc(transactional(target, manager));
        assertUnavailable(mvc, requests().get(2));
        mvc.perform(requests().get(2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memoryId").value("entry"));
        var contexts = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(target, times(2)).execute(contexts.capture(), any());
        assertThat(contexts.getAllValues())
                .extracting(MemoryWriteContext::requestId)
                .containsExactly("same-key", "same-key");
    }

    static Stream<RuntimeException> otherFailures() {
        return Stream.of(
                new IllegalStateException(PRIVATE_DETAIL),
                new DataIntegrityViolationException(PRIVATE_DETAIL),
                new TransactionSystemException(
                        PRIVATE_DETAIL, new SQLException(PRIVATE_DETAIL, "23505")),
                new TransactionSystemException(PRIVATE_DETAIL, new SQLException(PRIVATE_DETAIL)));
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void programmingAndNonConnectionFailuresKeepTheirOriginalBoundary(RuntimeException failure)
            throws Exception {
        var memory = mock(MemoryService.class);
        when(memory.snapshot()).thenThrow(failure);
        when(memory.execute(any(), any())).thenThrow(failure);
        mvc(memory)
                .perform(get("/api/user/memories"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        mvc(memory)
                .perform(requests().get(2))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new PersonalMemoryApplicationService(memory).settings())
                .isSameAs(failure);
    }

    @Test
    void domainRejectionRetainsItsBusinessCode() throws Exception {
        var memory = mock(MemoryService.class);
        when(memory.execute(any(), any()))
                .thenThrow(new MemoryException(MemoryStatus.CONFLICT, "记忆版本冲突"));
        mvc(memory)
                .perform(requests().get(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    private static MemoryService transactional(
            MemoryService target, PlatformTransactionManager manager) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new MatchAlwaysTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (MemoryService) proxy.getProxy();
    }

    private static MockMvc mvc(MemoryService memory) {
        return MockMvcBuilders.standaloneSetup(
                        new PersonalMemoryController(new PersonalMemoryApplicationService(memory)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static List<MockHttpServletRequestBuilder> requests() {
        return List.of(
                get("/api/user/memories"),
                get("/api/user/memories/settings"),
                post("/api/user/memories")
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"person_profile\",\"content\":\"测试正文\"}"),
                put("/api/user/memories/entry")
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"content\":\"测试正文\"}"),
                delete("/api/user/memories/entry")
                        .header("Idempotency-Key", "same-key")
                        .param("expectedVersion", "1"),
                put("/api/user/memories/settings")
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"autoLearningEnabled\":false}"));
    }

    private static void assertUnavailable(MockMvc mvc, MockHttpServletRequestBuilder request)
            throws Exception {
        mvc.perform(request)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(content().string(not(containsString(PRIVATE_DETAIL))));
    }
}
