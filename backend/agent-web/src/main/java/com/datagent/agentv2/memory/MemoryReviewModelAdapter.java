package com.datagent.agentv2.memory;

import com.datagent.agentv2.conversation.context.*;
import com.datagent.agentv2.conversation.model.api.*;
import com.datagent.agentv2.conversation.model.codex.CodexModelProperties;
import com.datagent.agentv2.conversation.model.ollama.OllamaModelProperties;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.memory.review.*;
import com.datagent.agentv2.memory.tool.MemoryTool;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 后台只使用 memory 私有 schema，复用实际 provider 和纯估算器；不进入主会话工具、SSE 或检查点。 */
@Component
@RequiredArgsConstructor
public final class MemoryReviewModelAdapter implements MemoryReviewModelPort {
    private final ObjectProvider<AgentModelGateway> gateways;
    private final ContextInputEstimatorResolver estimators;
    private final ContextModelBudgetProvider budgets;
    private final ContextModelMessageMapper messages;
    private final MemoryTool memoryTool;
    private final ObjectMapper json;

    @Value("${data-agent.model.provider:ollama}")
    private String provider = "ollama";

    private final ContextStrategyProperties strategy;
    private final OllamaModelProperties ollama;
    private final CodexModelProperties codex;
    private final ContextModelInputProperties generic;

    @Override
    public int maxInputTokens() {
        try {
            if (gateways.getIfAvailable() == null
                    || ("ollama".equals(provider)
                            && (ollama.getModel() == null || ollama.getModel().isBlank())))
                throw new MemoryReviewConfigurationException();
            estimators.current();
            return budgets.currentBudget().hardInputLimitTokens();
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw new MemoryReviewConfigurationException();
        }
    }

    @Override
    public String configurationFingerprint() {
        // 只选影响后台阻塞的有效参数，不序列化认证材料；空配置也是稳定值，解析器缺失不妨碍计算。
        var settings = new TreeMap<String, Object>();
        settings.put("provider", provider);
        settings.put("strategy", strategy.getStrategy());
        settings.put("gateway", gateways.getIfAvailable() != null);
        try {
            settings.put("estimator", estimators.current().getClass().getName());
        } catch (IllegalStateException absent) {
            settings.put("estimator", "missing");
        }
        if ("ollama".equals(provider)) settings.put("model", json.valueToTree(ollama));
        else {
            settings.put("model", json.valueToTree(codex));
            settings.put("budget", json.valueToTree(generic));
        }
        return MemoryReviewWorker.digest(json.writeValueAsString(settings));
    }

    @Override
    public int estimate(MemoryReviewInput input) {
        return estimate(buildRequest(input));
    }

    @Override
    public MemoryReviewOutput complete(MemoryReviewInput input) {
        maxInputTokens();
        var request = buildRequest(input);
        if (estimate(request)
                > Math.min(input.maxInputTokens(), budgets.currentBudget().hardInputLimitTokens()))
            throw new IllegalArgumentException("后台完整输入超过模型预算");
        var result =
                gateways.getIfAvailable()
                        .complete(request.messages(), request.tools(), ignored -> {});
        if (result instanceof ModelInvocationResult.FinalAnswer)
            return new MemoryReviewOutput.Done();
        var calls =
                ((ModelInvocationResult.ToolCalls) result)
                        .calls().stream()
                                .map(
                                        call -> {
                                            if (!"memory".equals(call.name()))
                                                throw new IllegalArgumentException("后台只允许 memory");
                                            return new MemoryReviewOutput.Call(
                                                    call.toolCallId(),
                                                    json.convertValue(call.arguments(), Map.class));
                                        })
                                .toList();
        return new MemoryReviewOutput.Calls(calls);
    }

    private int estimate(Request request) {
        try {
            var mapped = messages.toContextMessages(request.messages());
            var context =
                    new ConversationContextRequest(
                            new ContextExecutionRef(
                                    TenantContext.requiredTenantId(),
                                    "memory-review",
                                    "memory-review",
                                    0),
                            budgets.currentBudget(),
                            List.of(mapped.getFirst()),
                            messages.toContextToolDefinitions(request.tools()));
            return estimators
                    .current()
                    .estimateCompleteInput(context, mapped.subList(1, mapped.size()));
        } catch (IllegalStateException failure) {
            throw new MemoryReviewConfigurationException();
        }
    }

    /** 估算和发送共享唯一构造路径，包含提示词、完整小库、转义后的正文、schema 与工具协议历史。 */
    private Request buildRequest(MemoryReviewInput input) {
        var result = new ArrayList<ModelInputMessage>();
        result.add(ModelInputMessage.plain("system", MemoryReviewPrompts.SYSTEM));
        var payload = Map.of("snapshot", input.snapshot(), "evidence", input.evidence());
        result.add(
                ModelInputMessage.plain(
                        "user",
                        "[runtime_memory]\n"
                                + escape(json.writeValueAsString(payload))
                                + "\n[/runtime_memory]"));
        for (var item : input.history()) {
            switch ((String) item.get("type")) {
                case "calls" -> {
                    var calls = json.valueToTree(item.get("calls"));
                    var converted = new ArrayList<ModelToolCall>();
                    for (var call : calls)
                        converted.add(
                                new ModelToolCall(
                                        call.path("providerCallId").asString(),
                                        "memory",
                                        call.path("arguments")));
                    result.add(ModelInputMessage.assistantToolCalls(converted));
                }
                case "result" ->
                        result.add(
                                ModelInputMessage.tool(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "historical",
                                                        item.get("historical"),
                                                        "outcome",
                                                        item.get("outcome"))),
                                        (String) item.get("callId"),
                                        "memory"));
                case "historical" ->
                        result.add(
                                ModelInputMessage.plain(
                                        "user",
                                        "历史已提交结果（非新提交；目标存在性以当前快照为准）："
                                                + json.writeValueAsString(item.get("results"))));
                default -> throw new IllegalArgumentException("未知后台历史类型");
            }
        }
        return new Request(
                List.copyOf(result),
                List.of(
                        new ModelToolDefinition(
                                "memory", MemoryReviewPrompts.SYSTEM, privateSchema())));
    }

    private Map<String, Object> privateSchema() {
        var root = (ObjectNode) memoryTool.parametersSchema().deepCopy();
        var branches = json.createArrayNode();
        for (var branch : root.path("oneOf")) {
            String action = branch.path("properties").path("action").path("const").asString();
            if ("list".equals(action)) continue;
            var copy = (ObjectNode) branch.deepCopy();
            ((ObjectNode) copy.path("properties")).remove("write_reason");
            if ("add".equals(action))
                ((ObjectNode) copy.path("properties").path("restore")).put("const", false);
            var required = json.createArrayNode();
            for (var field : copy.path("required"))
                if (!"write_reason".equals(field.asString())) required.add(field.asString());
            copy.set("required", required);
            branches.add(copy);
            if ("remove".equals(action)) {
                var confirm = copy.deepCopy();
                ((ObjectNode) confirm.path("properties").path("action")).put("const", "confirm");
                branches.add(confirm);
            }
        }
        root.set("oneOf", branches);
        return json.convertValue(root, Map.class);
    }

    private static String escape(String value) {
        return value.replace("[", "［").replace("]", "］").replace("<", "＜").replace(">", "＞");
    }

    private record Request(List<ModelInputMessage> messages, List<ModelToolDefinition> tools) {}
}
