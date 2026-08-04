package com.datagent.agentv2.server;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.auth.application.AuthenticationService;
import com.datagent.agentv2.auth.config.AuthProperties;
import com.datagent.agentv2.platform.id.IdGenerator;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import com.datagent.agentv2.security.WebSecurityConfiguration;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(controllers = AsyncDispatchSecurityTest.AsyncDispatchProbeController.class)
@Import({WebSecurityConfiguration.class, AsyncDispatchSecurityTest.SecurityDependencies.class})
@ContextConfiguration(classes = AsyncDispatchSecurityTest.AsyncDispatchProbeController.class)
class AsyncDispatchSecurityTest {

    private static final String PROBE_PATH = "/__test/async-dispatch";
    private static final String PROBE_RESPONSE = "ok";

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private FilterChainProxy securityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void configureMockMvcWithSecurityFilters() {
        // 将真实 SecurityFilterChain 接入测试请求，避免 WebMvc slice 漏掉安全过滤器。
        mvc =
                MockMvcBuilders.webAppContextSetup(applicationContext)
                        .addFilters(securityFilterChain)
                        .build();
    }

    @Test
    void requiresAuthenticationForTheInitialRequest() throws Exception {
        // 初始 REQUEST 仍必须完成登录鉴权，不能因 ASYNC 放行规则扩大匿名访问范围。
        mvc.perform(get(PROBE_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void permitsContainerAsyncRedispatchAfterTheInitialRequestWasAuthorized() throws Exception {
        // SSE 完成时的 ASYNC redispatch 不再携带最初请求线程的认证上下文。
        mvc.perform(
                        get(PROBE_PATH)
                                .with(
                                        request -> {
                                            request.setDispatcherType(DispatcherType.ASYNC);
                                            return request;
                                        }))
                .andExpect(status().isOk());
    }

    /** 提供安全配置构造 filter chain 所需、但本用例不会主动调用的依赖。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class SecurityDependencies {

        @Bean
        AuthenticationService authenticationService() {
            return mock(AuthenticationService.class);
        }

        @Bean
        AuthProperties authProperties() {
            return new AuthProperties();
        }

        @Bean
        IdGenerator idGenerator() {
            return new PrefixedUuidIdGenerator();
        }
    }

    /** 为匿名 ASYNC dispatcher 提供一个无业务依赖的探针端点。 */
    @RestController
    static class AsyncDispatchProbeController {

        @GetMapping(PROBE_PATH)
        String probe() {
            return PROBE_RESPONSE;
        }
    }
}
