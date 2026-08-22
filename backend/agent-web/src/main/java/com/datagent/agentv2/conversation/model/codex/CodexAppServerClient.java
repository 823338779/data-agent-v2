package com.datagent.agentv2.conversation.model.codex;

import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.model.api.ModelOutputDelta;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 使用 Codex App Server 的 JSONL 协议完成一次隔离的模型调用。 */
final class CodexAppServerClient {

    private static final int REQUEST_INITIALIZE = 1;
    private static final int REQUEST_THREAD_START = 2;
    private static final int REQUEST_TURN_START = 3;
    private static final int REQUEST_TURN_INTERRUPT = 4;

    private static final String ARG_APP_SERVER = "app-server";
    private static final String EMPTY_TEXT = "";
    private static final String TEMP_DIRECTORY_PREFIX = "data-agent-codex-";
    private static final String CLIENT_NAME = "data-agent-v2";
    private static final String CLIENT_TITLE = "Data Agent V2";
    private static final String CLIENT_VERSION = "1";
    private static final String SERVICE_NAME = "data-agent-v2";
    private static final String MODEL_PROVIDER = "codex";
    private static final String SANDBOX_READ_ONLY = "read-only";
    private static final String APPROVAL_NEVER = "never";
    private static final String CONTENT_TYPE_INPUT_TEXT = "inputText";
    private static final String INPUT_TYPE_TEXT = "text";
    private static final String TOOL_TYPE_FUNCTION = "function";
    private static final String ITEM_TYPE_AGENT_MESSAGE = "agentMessage";
    private static final String ITEM_TYPE_REASONING = "reasoning";
    private static final String PHASE_FINAL_ANSWER = "final_answer";
    private static final String STATUS_COMPLETED = "completed";
    private static final String TOOL_ACCEPTED_MESSAGE =
            "Tool call accepted by Data Agent V2; execution continues outside Codex.";
    private static final String BASE_INSTRUCTIONS =
            "You are the model backend for Data Agent V2. Answer from the supplied conversation "
                    + "history. Use only the provided dynamic function tools when external data is "
                    + "needed. Never use shell, file editing, filesystem search, web search, MCP, "
                    + "browser, collaboration, or other Codex built-in tools. Do not describe this "
                    + "adapter protocol to the user.";
    private static final String ERROR_INTERRUPTED =
            "Codex App Server ended before a complete model result";
    private static final String ERROR_TIMEOUT = "Codex App Server invocation timed out";
    private static final String ERROR_PROTOCOL = "Codex App Server returned an invalid response";
    private static final String ERROR_BUILT_IN_TOOL =
            "Codex attempted a built-in tool that is disabled for Data Agent V2: ";

    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_INITIALIZED = "initialized";
    private static final String METHOD_THREAD_START = "thread/start";
    private static final String METHOD_TURN_START = "turn/start";
    private static final String METHOD_TURN_INTERRUPT = "turn/interrupt";
    private static final String METHOD_DYNAMIC_TOOL_CALL = "item/tool/call";
    private static final String NOTIFICATION_REASONING_TEXT_DELTA = "item/reasoning/textDelta";
    private static final String NOTIFICATION_REASONING_SUMMARY_TEXT_DELTA =
            "item/reasoning/summaryTextDelta";
    private static final String NOTIFICATION_AGENT_MESSAGE_DELTA = "item/agentMessage/delta";
    private static final String METHOD_ITEM_STARTED = "item/started";
    private static final String METHOD_ITEM_COMPLETED = "item/completed";
    private static final String METHOD_TURN_COMPLETED = "turn/completed";

    private static final String KEY_ID = "id";
    private static final String KEY_METHOD = "method";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_RESULT = "result";
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_CLIENT_INFO = "clientInfo";
    private static final String KEY_NAME = "name";
    private static final String KEY_TITLE = "title";
    private static final String KEY_VERSION = "version";
    private static final String KEY_CAPABILITIES = "capabilities";
    private static final String KEY_EXPERIMENTAL_API = "experimentalApi";
    private static final String KEY_THREAD = "thread";
    private static final String KEY_THREAD_ID = "threadId";
    private static final String KEY_TURN = "turn";
    private static final String KEY_TURN_ID = "turnId";
    private static final String KEY_ITEM = "item";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_TYPE = "type";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_TEXT = "text";
    private static final String KEY_DELTA = "delta";
    private static final String KEY_STATUS = "status";
    private static final String KEY_TOOL = "tool";
    private static final String KEY_CALL_ID = "callId";
    private static final String KEY_ARGUMENTS = "arguments";
    private static final String KEY_CONTENT_ITEMS = "contentItems";
    private static final String KEY_SUCCESS = "success";
    private static final String KEY_CWD = "cwd";
    private static final String KEY_APPROVAL_POLICY = "approvalPolicy";
    private static final String KEY_SANDBOX = "sandbox";
    private static final String KEY_EPHEMERAL = "ephemeral";
    private static final String KEY_SERVICE_NAME = "serviceName";
    private static final String KEY_BASE_INSTRUCTIONS = "baseInstructions";
    private static final String KEY_MODEL = "model";
    private static final String KEY_DYNAMIC_TOOLS = "dynamicTools";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_INPUT_SCHEMA = "inputSchema";
    private static final String KEY_INPUT = "input";
    private static final String KEY_EFFORT = "effort";

    private static final Set<String> DISABLED_ITEM_TYPES =
            Set.of("commandExecution", "fileChange", "mcpToolCall", "webSearch", "collabToolCall");

    private final CodexModelProperties properties;
    private final ObjectMapper json;
    private final ConnectionFactory connectionFactory;

    /** 创建使用真实 Codex 子进程的客户端。 */
    CodexAppServerClient(CodexModelProperties properties, ObjectMapper json) {
        this(properties, json, () -> openProcess(properties));
    }

    /** 创建可注入协议连接的客户端，供 JSONL 协议测试使用。 */
    CodexAppServerClient(
            CodexModelProperties properties,
            ObjectMapper json,
            ConnectionFactory connectionFactory) {
        this.properties = properties;
        this.json = json;
        this.connectionFactory = connectionFactory;
    }

    /** 在超时边界内执行一个临时 Codex thread，并返回领域模型结果。 */
    ModelInvocationResult complete(
            String prompt,
            List<ModelToolDefinition> tools,
            Consumer<ModelOutputDelta> outputDeltaConsumer) {
        // 子进程阻塞读取放入虚拟线程；超时关闭连接会同步解除 readLine 阻塞。
        try (Connection connection = connectionFactory.open()) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            Future<ModelInvocationResult> invocation =
                    executor.submit(() -> exchange(connection, prompt, tools, outputDeltaConsumer));
            try {
                return invocation.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                invocation.cancel(true);
                executor.shutdownNow();
            }
        } catch (TimeoutException failure) {
            throw new ModelStreamInterruptedException(ERROR_TIMEOUT, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED, failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED, cause);
        } catch (IOException failure) {
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED, failure);
        }
    }

    /** 顺序处理 App Server 响应、通知和动态工具请求。 */
    private ModelInvocationResult exchange(
            Connection connection,
            String prompt,
            List<ModelToolDefinition> tools,
            Consumer<ModelOutputDelta> outputDeltaConsumer) {
        SessionState state = new SessionState();
        send(connection, initializeRequest());

        try {
            String line;
            while ((line = connection.reader().readLine()) != null) {
                JsonNode envelope = json.readTree(line);
                String method = text(envelope, KEY_METHOD);

                // App Server 主动发起的动态工具调用必须先响应，再中断本次临时 turn。
                if (METHOD_DYNAMIC_TOOL_CALL.equals(method)) {
                    handleDynamicToolCall(connection, envelope, state);
                    continue;
                }
                if (envelope.has(KEY_ID)) {
                    handleResponse(connection, envelope, prompt, tools, state);
                    continue;
                }
                ModelInvocationResult completed =
                        handleNotification(
                                connection,
                                method,
                                envelope.path(KEY_PARAMS),
                                state,
                                outputDeltaConsumer);
                if (completed != null) {
                    return completed;
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new ModelStreamInterruptedException(ERROR_PROTOCOL, failure);
        }
        throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
    }

    /** 根据客户端请求编号推进 initialize、thread/start 和 turn/start 状态机。 */
    private void handleResponse(
            Connection connection,
            JsonNode envelope,
            String prompt,
            List<ModelToolDefinition> tools,
            SessionState state) {
        if (envelope.hasNonNull(KEY_ERROR)) {
            throw new IllegalArgumentException(
                    ERROR_PROTOCOL + ": " + text(envelope.path(KEY_ERROR), KEY_MESSAGE));
        }
        int requestId = envelope.path(KEY_ID).asInt();
        if (requestId == REQUEST_INITIALIZE) {
            send(connection, notification(METHOD_INITIALIZED));
            send(connection, threadStartRequest(connection.workingDirectory(), tools));
        } else if (requestId == REQUEST_THREAD_START) {
            state.threadId = text(envelope.path(KEY_RESULT).path(KEY_THREAD), KEY_ID);
            send(connection, turnStartRequest(state.threadId, prompt));
        }
    }

    /** 处理可见回答增量、item 生命周期以及 turn 终态。 */
    private ModelInvocationResult handleNotification(
            Connection connection,
            String method,
            JsonNode params,
            SessionState state,
            Consumer<ModelOutputDelta> outputDeltaConsumer) {
        if (METHOD_ITEM_STARTED.equals(method) || METHOD_ITEM_COMPLETED.equals(method)) {
            handleItem(
                    params.path(KEY_ITEM),
                    state,
                    METHOD_ITEM_COMPLETED.equals(method),
                    outputDeltaConsumer);
            return null;
        }
        if (NOTIFICATION_REASONING_TEXT_DELTA.equals(method)) {
            handleReasoningDelta(params, state, outputDeltaConsumer, true);
            return null;
        }
        if (NOTIFICATION_REASONING_SUMMARY_TEXT_DELTA.equals(method)) {
            handleReasoningDelta(params, state, outputDeltaConsumer, false);
            return null;
        }
        if (NOTIFICATION_AGENT_MESSAGE_DELTA.equals(method)) {
            String itemId = text(params, KEY_ITEM_ID);
            String delta = text(params, KEY_DELTA);
            if (PHASE_FINAL_ANSWER.equals(state.itemPhases.get(itemId)) && !delta.isEmpty()) {
                state.streamedAnswer.append(delta);
                outputDeltaConsumer.accept(
                        ModelOutputDelta.answer(delta, MODEL_PROVIDER, properties.getModel()));
            }
            return null;
        }
        if (!METHOD_TURN_COMPLETED.equals(method)) {
            return null;
        }

        // 工具 turn 由外层 ReAct 执行器接管；回答 turn 只接受 Codex 的 completed 终态。
        if (!state.toolCalls.isEmpty()) {
            return new ModelInvocationResult.ToolCalls(state.toolCalls);
        }
        String status = text(params.path(KEY_TURN), KEY_STATUS);
        if (!STATUS_COMPLETED.equals(status)) {
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
        }
        String answer =
                state.completedAnswer.isBlank()
                        ? state.streamedAnswer.toString()
                        : state.completedAnswer;
        if (answer.isBlank()) {
            throw new ModelStreamInterruptedException(ERROR_PROTOCOL);
        }
        if (state.streamedAnswer.isEmpty()) {
            outputDeltaConsumer.accept(
                    ModelOutputDelta.answer(answer, MODEL_PROVIDER, properties.getModel()));
        }
        return new ModelInvocationResult.FinalAnswer(answer);
    }

    /** 记录 agentMessage 阶段和完整正文，并拒绝 Codex 内置工具 item。 */
    private void handleItem(
            JsonNode item,
            SessionState state,
            boolean completed,
            Consumer<ModelOutputDelta> outputDeltaConsumer) {
        String type = text(item, KEY_TYPE);
        if (DISABLED_ITEM_TYPES.contains(type)) {
            throw new ModelStreamInterruptedException(ERROR_BUILT_IN_TOOL + type);
        }
        if (ITEM_TYPE_REASONING.equals(type)) {
            if (completed) {
                finishReasoningItem(text(item, KEY_ID), state, outputDeltaConsumer);
            }
            return;
        }
        if (!ITEM_TYPE_AGENT_MESSAGE.equals(type)) {
            return;
        }
        String itemId = text(item, KEY_ID);
        String phase = text(item, KEY_PHASE);
        state.itemPhases.put(itemId, phase);
        if (completed && PHASE_FINAL_ANSWER.equals(phase)) {
            state.completedAnswer = text(item, KEY_TEXT);
        }
    }

    /** raw reasoning 到达后丢弃同 item 的 summary；summary 只缓冲到 item 完成。 */
    private void handleReasoningDelta(
            JsonNode params,
            SessionState state,
            Consumer<ModelOutputDelta> outputDeltaConsumer,
            boolean raw) {
        String itemId = text(params, KEY_ITEM_ID);
        String delta = text(params, KEY_DELTA);
        if (delta.isEmpty()) {
            return;
        }
        if (raw) {
            if (state.rawReasoningItems.add(itemId)) {
                state.reasoningSummaries.remove(itemId);
            }
            outputDeltaConsumer.accept(
                    ModelOutputDelta.thinking(delta, MODEL_PROVIDER, properties.getModel()));
            return;
        }
        if (!state.rawReasoningItems.contains(itemId)) {
            state.reasoningSummaries
                    .computeIfAbsent(itemId, ignored -> new StringBuilder())
                    .append(delta);
        }
    }

    /** reasoning item 完成时仅在没有 raw 文本的情况下输出一次累计 summary。 */
    private void finishReasoningItem(
            String itemId, SessionState state, Consumer<ModelOutputDelta> outputDeltaConsumer) {
        StringBuilder summary = state.reasoningSummaries.remove(itemId);
        boolean rawReceived = state.rawReasoningItems.remove(itemId);
        if (!rawReceived && summary != null && !summary.isEmpty()) {
            outputDeltaConsumer.accept(
                    ModelOutputDelta.thinking(
                            summary.toString(), MODEL_PROVIDER, properties.getModel()));
        }
    }

    /** 捕获一个领域工具调用，向 App Server 确认收件并请求中断临时 turn。 */
    private void handleDynamicToolCall(
            Connection connection, JsonNode envelope, SessionState state) {
        JsonNode params = envelope.path(KEY_PARAMS);
        JsonNode arguments = params.path(KEY_ARGUMENTS).deepCopy();
        state.toolCalls.add(
                new ModelToolCall(text(params, KEY_CALL_ID), text(params, KEY_TOOL), arguments));
        send(connection, dynamicToolResponse(envelope.path(KEY_ID)));
        if (!state.interruptRequested) {
            state.interruptRequested = true;
            send(
                    connection,
                    turnInterruptRequest(text(params, KEY_THREAD_ID), text(params, KEY_TURN_ID)));
        }
    }

    /** 构造 App Server 初始化请求并显式打开实验性动态工具协议。 */
    private ObjectNode initializeRequest() {
        ObjectNode params = json.createObjectNode();
        ObjectNode clientInfo = params.putObject(KEY_CLIENT_INFO);
        clientInfo.put(KEY_NAME, CLIENT_NAME);
        clientInfo.put(KEY_TITLE, CLIENT_TITLE);
        clientInfo.put(KEY_VERSION, CLIENT_VERSION);
        params.putObject(KEY_CAPABILITIES).put(KEY_EXPERIMENTAL_API, true);
        return request(REQUEST_INITIALIZE, METHOD_INITIALIZE, params);
    }

    /** 创建只读、无审批、无持久历史的临时 thread，并注册 V2 动态工具。 */
    private ObjectNode threadStartRequest(Path workingDirectory, List<ModelToolDefinition> tools) {
        ObjectNode params = json.createObjectNode();
        params.put(KEY_CWD, workingDirectory.toAbsolutePath().toString());
        params.put(KEY_APPROVAL_POLICY, APPROVAL_NEVER);
        params.put(KEY_SANDBOX, SANDBOX_READ_ONLY);
        params.put(KEY_EPHEMERAL, true);
        params.put(KEY_SERVICE_NAME, SERVICE_NAME);
        params.put(KEY_BASE_INSTRUCTIONS, BASE_INSTRUCTIONS);
        if (!properties.getModel().isBlank()) {
            params.put(KEY_MODEL, properties.getModel());
        }
        ArrayNode dynamicTools = params.putArray(KEY_DYNAMIC_TOOLS);
        for (ModelToolDefinition tool : tools) {
            ObjectNode definition = dynamicTools.addObject();
            definition.put(KEY_TYPE, TOOL_TYPE_FUNCTION);
            definition.put(KEY_NAME, tool.name());
            definition.put(KEY_DESCRIPTION, tool.description());
            definition.set(KEY_INPUT_SCHEMA, json.valueToTree(tool.parameters()));
        }
        return request(REQUEST_THREAD_START, METHOD_THREAD_START, params);
    }

    /** 创建承载完整 V2 会话快照的 turn 请求。 */
    private ObjectNode turnStartRequest(String threadId, String prompt) {
        ObjectNode params = json.createObjectNode();
        params.put(KEY_THREAD_ID, threadId);
        params.putArray(KEY_INPUT).addObject().put(KEY_TYPE, INPUT_TYPE_TEXT).put(KEY_TEXT, prompt);
        if (!properties.getModel().isBlank()) {
            params.put(KEY_MODEL, properties.getModel());
        }
        if (!properties.getEffort().isBlank()) {
            params.put(KEY_EFFORT, properties.getEffort());
        }
        return request(REQUEST_TURN_START, METHOD_TURN_START, params);
    }

    /** 创建动态工具请求的 JSON-RPC 成功响应。 */
    private ObjectNode dynamicToolResponse(JsonNode requestId) {
        ObjectNode response = json.createObjectNode();
        response.set(KEY_ID, requestId.deepCopy());
        ObjectNode result = response.putObject(KEY_RESULT);
        result.put(KEY_SUCCESS, true);
        result.putArray(KEY_CONTENT_ITEMS)
                .addObject()
                .put(KEY_TYPE, CONTENT_TYPE_INPUT_TEXT)
                .put(KEY_TEXT, TOOL_ACCEPTED_MESSAGE);
        return response;
    }

    /** 创建 turn 中断请求，防止 Codex 使用占位工具结果继续生成。 */
    private ObjectNode turnInterruptRequest(String threadId, String turnId) {
        ObjectNode params = json.createObjectNode();
        params.put(KEY_THREAD_ID, threadId);
        params.put(KEY_TURN_ID, turnId);
        return request(REQUEST_TURN_INTERRUPT, METHOD_TURN_INTERRUPT, params);
    }

    /** 创建带稳定数字编号的客户端请求。 */
    private ObjectNode request(int id, String method, ObjectNode params) {
        ObjectNode request = json.createObjectNode();
        request.put(KEY_ID, id);
        request.put(KEY_METHOD, method);
        request.set(KEY_PARAMS, params);
        return request;
    }

    /** 创建不带参数的客户端通知。 */
    private ObjectNode notification(String method) {
        ObjectNode notification = json.createObjectNode();
        notification.put(KEY_METHOD, method);
        return notification;
    }

    /** 将单个 JSON 信封按 JSONL 协议原子写入 App Server。 */
    private void send(Connection connection, JsonNode envelope) {
        try {
            connection.writer().write(json.writeValueAsString(envelope));
            connection.writer().newLine();
            connection.writer().flush();
        } catch (IOException failure) {
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED, failure);
        }
    }

    /** 安全读取可缺省文本字段。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? EMPTY_TEXT : value.asString();
    }

    /** 启动继承当前用户 Codex 登录态的 App Server 子进程。 */
    private static Connection openProcess(CodexModelProperties properties) throws IOException {
        boolean temporaryDirectory = properties.getWorkingDirectory().isBlank();
        Path workingDirectory =
                temporaryDirectory
                        ? Files.createTempDirectory(TEMP_DIRECTORY_PREFIX)
                        : Path.of(properties.getWorkingDirectory()).toAbsolutePath().normalize();
        if (!temporaryDirectory) {
            Files.createDirectories(workingDirectory);
        }
        Process process =
                new ProcessBuilder(properties.getExecutable(), ARG_APP_SERVER)
                        .directory(workingDirectory.toFile())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        return new ProcessConnection(process, workingDirectory, temporaryDirectory);
    }

    /** 一次协议会话内累计的 thread、回答阶段和工具调用状态。 */
    private static final class SessionState {
        private final Map<String, String> itemPhases = new HashMap<>();
        private final Map<String, StringBuilder> reasoningSummaries = new HashMap<>();
        private final Set<String> rawReasoningItems = new HashSet<>();
        private final StringBuilder streamedAnswer = new StringBuilder();
        private final List<ModelToolCall> toolCalls = new ArrayList<>();
        private String threadId = EMPTY_TEXT;
        private String completedAnswer = EMPTY_TEXT;
        private boolean interruptRequested;
    }

    /** 提供可替换的 App Server 连接，生产使用子进程，测试使用内存流。 */
    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws IOException;
    }

    /** JSONL 输入输出及其隔离工作目录。 */
    interface Connection extends AutoCloseable {
        BufferedReader reader();

        BufferedWriter writer();

        Path workingDirectory();

        @Override
        void close() throws IOException;
    }

    /** 真实 Codex 子进程连接；关闭时终止子进程并清理本次临时空目录。 */
    private static final class ProcessConnection implements Connection {
        private final Process process;
        private final Path workingDirectory;
        private final boolean temporaryDirectory;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private ProcessConnection(
                Process process, Path workingDirectory, boolean temporaryDirectory) {
            this.process = process;
            this.workingDirectory = workingDirectory;
            this.temporaryDirectory = temporaryDirectory;
            this.reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            this.writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
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
            return workingDirectory;
        }

        @Override
        public void close() throws IOException {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 子进程可能已经在 turn 完成后主动关闭 stdin。
            }
            try {
                reader.close();
            } catch (IOException ignored) {
                // 子进程退出与本地流关闭存在正常竞争。
            }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            if (temporaryDirectory) {
                try {
                    Files.deleteIfExists(workingDirectory);
                } catch (IOException ignored) {
                    // Windows 可能短暂保留子进程 cwd 句柄，JVM 退出时再次清理。
                    workingDirectory.toFile().deleteOnExit();
                }
            }
        }
    }
}
