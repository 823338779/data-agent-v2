package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.AgentV2Application;
import com.datagent.agentv2.conversation.model.api.*;
import com.datagent.agentv2.conversation.react.*;
import com.datagent.agentv2.server.context.RequestContextFilter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

/** 只替换外部模型，HTTP、执行器、工具、事务和策略均采用真实运行时；数据库只来自测试容器。 */
final class PersonalMemoryTestSupport implements AutoCloseable {
    final ConfigurableApplicationContext context;
    final MockMvc mvc;
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final StubGateway gateway;

    PersonalMemoryTestSupport(PostgreSQLContainer postgres, String strategy) {
        var application = new SpringApplication(AgentV2Application.class, StubConfiguration.class);
        var args =
                new ArrayList<>(
                        List.of(
                                "--server.port=0",
                                "--spring.main.banner-mode=off",
                                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                "--spring.datasource.username=" + postgres.getUsername(),
                                "--spring.datasource.password=" + postgres.getPassword(),
                                "--data-agent.runtime.scheduling-enabled=false",
                                "--data-agent.memory.scan-initial-delay=1h",
                                "--data-agent.memory.review-delay=0s"));
        if (strategy != null) args.add("--data-agent.context.strategy=" + strategy);
        context = application.run(args.toArray(String[]::new));
        mvc =
                MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                        .addFilters(context.getBean(RequestContextFilter.class))
                        .build();
        jdbc = context.getBean(JdbcTemplate.class);
        json = context.getBean(ObjectMapper.class);
        gateway = context.getBean(StubGateway.class);
    }

    String session() throws Exception {
        return body(mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"个人记忆验收\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("conversationSessionId")
                .asString();
    }

    String submit(String session, String content) throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/agent-sessions/{id}/messages", session)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of("content", content))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = body(response).path("execution").path("executionId").asString();
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select status from agent_execution where execution_id=?",
                                                        String.class,
                                                        id))
                                        .isEqualTo("done"));
        return id;
    }

    JsonNode body(String content) {
        return json.readTree(content);
    }

    JsonNode request(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int status)
            throws Exception {
        return body(
                mvc.perform(request)
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Override
    public void close() {
        context.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {
        @Bean
        @Primary
        StubGateway testGateway() {
            return new StubGateway();
        }
    }

    static final class StubGateway implements AgentModelGateway {
        final List<List<ModelInputMessage>> inputs = new CopyOnWriteArrayList<>();
        final List<List<ModelToolDefinition>> toolsSeen = new CopyOnWriteArrayList<>();

        @Override
        public ModelInvocationResult complete(
                List<ModelInputMessage> input,
                List<ModelToolDefinition> tools,
                Consumer<ModelOutputDelta> delta) {
            inputs.add(List.copyOf(input));
            toolsSeen.add(List.copyOf(tools));
            boolean requestAdd = input.stream().anyMatch(m -> m.content().equals("请记住我是一名后端工程师"));
            boolean alreadyCalled =
                    input.stream().anyMatch(m -> m.role() == ModelInputMessage.Role.TOOL);
            if (requestAdd && !alreadyCalled) {
                String metadata =
                        input.stream()
                                .map(ModelInputMessage::content)
                                .filter(c -> c.startsWith("[memory_evidence]"))
                                .findFirst()
                                .orElseThrow();
                String evidence =
                        metadata.substring(metadata.indexOf("ID：") + 3, metadata.indexOf("。"));
                var json = new ObjectMapper();
                return new ModelInvocationResult.ToolCalls(
                        List.of(
                                new ModelToolCall(
                                        "memory-add",
                                        "memory",
                                        json.valueToTree(
                                                Map.of(
                                                        "action",
                                                        "add",
                                                        "kind",
                                                        "person_profile",
                                                        "content",
                                                        "用户是一名后端工程师",
                                                        "write_reason",
                                                        "user_requested",
                                                        "evidence_message_ids",
                                                        List.of(evidence))))));
            }
            return new ModelInvocationResult.FinalAnswer("验收回答");
        }
    }
}
