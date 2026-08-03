package com.datagent.agentv2.conversation.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.platform.context.TenantContext;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

class CodexAppServerClientTest {

    private static final String TENANT_ID = "tenant-codex-test";
    private static final String PROMPT = "conversation";
    private static final String FINAL_ANSWER = "answer";
    private static final String TOOL_NAME = "lookup";
    private static final String TOOL_CALL_ID = "call-1";
    private static final String LIVE_TEST_PROPERTY = "data.agent.codex.live.enabled";
    private static final String LIVE_EXPECTED_ANSWER = "CODEX_SUBSCRIPTION_OK";
    private static final String FINAL_PROTOCOL =
            """
            {"id":1,"result":{}}
            {"id":2,"result":{"thread":{"id":"thread-1"}}}
            {"id":3,"result":{"turn":{"id":"turn-1"}}}
            {"method":"item/started","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","id":"message-1","text":"","phase":"final_answer"}}}
            {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"message-1","delta":"answer"}}
            {"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","id":"message-1","text":"answer","phase":"final_answer"}}}
            {"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}
            """;
    private static final String TOOL_PROTOCOL =
            """
            {"id":1,"result":{}}
            {"id":2,"result":{"thread":{"id":"thread-1"}}}
            {"id":3,"result":{"turn":{"id":"turn-1"}}}
            {"id":"server-tool-1","method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","tool":"lookup","arguments":{"q":"x"}}}
            {"id":4,"result":{}}
            {"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"interrupted"}}}
            """;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void streamsOnlyFinalAnswerAndReturnsCompletedText() {
        InMemoryConnection connection = new InMemoryConnection(FINAL_PROTOCOL);
        CodexAppServerClient client = client(connection);
        List<String> deltas = new ArrayList<>();
        List<String> callbackTenants = new ArrayList<>();

        ModelInvocationResult result =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                client.complete(
                                        PROMPT,
                                        List.of(),
                                        delta -> {
                                            callbackTenants.add(TenantContext.requiredTenantId());
                                            deltas.add(delta);
                                        }));

        assertThat(result).isEqualTo(new ModelInvocationResult.FinalAnswer(FINAL_ANSWER));
        assertThat(deltas).containsExactly(FINAL_ANSWER);
        assertThat(callbackTenants).containsOnly(TENANT_ID);
        assertThat(connection.output())
                .contains(
                        "\"method\":\"initialize\"",
                        "\"method\":\"thread/start\"",
                        "\"sandbox\":\"read-only\"",
                        "\"method\":\"turn/start\"",
                        "\"text\":\"conversation\"");
    }

    @Test
    void convertsDynamicToolRequestAndInterruptsTemporaryTurn() {
        InMemoryConnection connection = new InMemoryConnection(TOOL_PROTOCOL);
        CodexAppServerClient client = client(connection);
        ModelToolDefinition tool =
                new ModelToolDefinition(
                        TOOL_NAME,
                        "Looks up a value",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("q", Map.of("type", "string")),
                                "required", List.of("q")));

        ModelInvocationResult result =
                TenantContext.call(
                        TENANT_ID, () -> client.complete(PROMPT, List.of(tool), ignored -> {}));

        assertThat(result).isInstanceOf(ModelInvocationResult.ToolCalls.class);
        List<ModelToolCall> calls = ((ModelInvocationResult.ToolCalls) result).calls();
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().toolCallId()).isEqualTo(TOOL_CALL_ID);
        assertThat(calls.getFirst().name()).isEqualTo(TOOL_NAME);
        assertThat(calls.getFirst().arguments().path("q").asString()).isEqualTo("x");
        assertThat(connection.output())
                .contains(
                        "\"dynamicTools\"",
                        "\"name\":\"lookup\"",
                        "\"id\":\"server-tool-1\"",
                        "\"success\":true",
                        "\"method\":\"turn/interrupt\"",
                        "\"threadId\":\"thread-1\"",
                        "\"turnId\":\"turn-1\"");
    }

    @Test
    @EnabledIfSystemProperty(named = LIVE_TEST_PROPERTY, matches = "true")
    void invokesTheLocallyAuthenticatedCodexSubscription() {
        CodexModelProperties properties = new CodexModelProperties();
        properties.setTimeout(Duration.ofMinutes(2));
        CodexAgentModelGateway gateway = new CodexAgentModelGateway(properties, json);

        ModelInvocationResult result =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                gateway.complete(
                                        List.of(
                                                ModelInputMessage.plain(
                                                        "user",
                                                        "Reply with exactly "
                                                                + LIVE_EXPECTED_ANSWER)),
                                        List.of(),
                                        ignored -> {}));

        assertThat(result).isInstanceOf(ModelInvocationResult.FinalAnswer.class);
        assertThat(((ModelInvocationResult.FinalAnswer) result).answer())
                .contains(LIVE_EXPECTED_ANSWER);
    }

    @Test
    @EnabledIfSystemProperty(named = LIVE_TEST_PROPERTY, matches = "true")
    void exposesRegisteredDynamicToolsToTheLocallyAuthenticatedCodexSubscription() {
        CodexModelProperties properties = new CodexModelProperties();
        properties.setTimeout(Duration.ofMinutes(2));
        CodexAgentModelGateway gateway = new CodexAgentModelGateway(properties, json);
        ModelToolDefinition tool =
                new ModelToolDefinition(
                        TOOL_NAME,
                        "Looks up a value",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("q", Map.of("type", "string")),
                                "required", List.of("q")));

        ModelInvocationResult result =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                gateway.complete(
                                        List.of(
                                                ModelInputMessage.plain(
                                                        "user",
                                                        "Call lookup once with q set to x.")),
                                        List.of(tool),
                                        ignored -> {}));

        assertThat(result).isInstanceOf(ModelInvocationResult.ToolCalls.class);
        assertThat(((ModelInvocationResult.ToolCalls) result).calls())
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.name()).isEqualTo(TOOL_NAME);
                            assertThat(call.arguments().path("q").asString()).isEqualTo("x");
                        });
    }

    /** 创建使用固定内存 JSONL 输入的客户端。 */
    private CodexAppServerClient client(InMemoryConnection connection) {
        CodexModelProperties properties = new CodexModelProperties();
        properties.setTimeout(Duration.ofSeconds(2));
        return new CodexAppServerClient(properties, json, () -> connection);
    }

    /** 测试用协议连接，保留客户端写出的每一条 JSONL 信封。 */
    private static final class InMemoryConnection implements CodexAppServerClient.Connection {
        private final BufferedReader reader;
        private final StringWriter output = new StringWriter();
        private final BufferedWriter writer = new BufferedWriter(output);

        private InMemoryConnection(String protocol) {
            this.reader = new BufferedReader(new StringReader(protocol));
        }

        @Override
        public BufferedReader reader() {
            return reader;
        }

        @Override
        public BufferedWriter writer() {
            return writer;
        }

        @Override
        public Path workingDirectory() {
            return Path.of(".").toAbsolutePath().normalize();
        }

        @Override
        public void close() throws IOException {
            writer.flush();
        }

        private String output() {
            return output.toString();
        }
    }
}
