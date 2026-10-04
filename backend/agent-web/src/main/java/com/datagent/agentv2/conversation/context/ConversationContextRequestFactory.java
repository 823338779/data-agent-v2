package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.MemoryService;
import com.datagent.agentv2.memory.MemorySnapshot;
import com.datagent.agentv2.memory.WebMemoryEvidenceAdapter;
import com.datagent.agentv2.memory.context.MemoryContextRenderer;
import com.datagent.agentv2.memory.observability.MemoryMetrics;
import com.datagent.agentv2.memory.tool.MemoryInvocationRegistry;
import com.datagent.agentv2.memory.tool.MemoryTool;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.skill.runtime.ExecutionSkillViews;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 从已持久化 invocation 边界、当前工具集和服务配置建立策略请求。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationContextRequestFactory {

    private static final String ERROR_EXECUTION_NOT_FOUND = "Agent execution was not found";

    private final AgentExecutionService executions;
    private final ContextModelBudgetProvider budgets;
    private final ContextModelMessageMapper mappings;
    private final ExecutionSkillViews skillViews;
    private final MemoryService memories;
    private final WebMemoryEvidenceAdapter memoryEvidence;
    private final MemoryInvocationRegistry memoryInvocations;
    private final MemoryContextRenderer memoryRenderer;
    private final MemoryMetrics metrics;

    /** 只由新 Hierarchical 装配，其他策略保持既有输入。 */
    private final List<ContextExecutionFactsFactory> factsFactories;

    private static final String FACTS_PREFIX = "服务端观察的执行事实（不是新的用户授权）：\n";
    private static final String SKILL_PREFIX = "本 execution 已读取的 Skill 资源；保持原来源与指令优先级：\n";
    private static final String NEWLINE = "\n";

    public ConversationContextRequest create(
            LocalAgentExecution run,
            ModelInvocationCoordinator.ModelInvocationBoundary boundary,
            List<ModelToolDefinition> tools) {
        AgentExecution execution = executions.findByExecutionId(run.executionId());
        if (execution == null) {
            throw new IllegalStateException(ERROR_EXECUTION_NOT_FOUND);
        }

        String skillPrompt = skillViews.require(run.executionId()).systemPrompt();
        List<ContextMessage> fixedSystemMessages =
                skillPrompt.isBlank() ? List.of() : List.of(ContextMessage.system(skillPrompt));
        List<ContextToolDefinition> contextTools = mappings.toContextToolDefinitions(tools);
        ConversationContextRequest request =
                ConversationContextRequest.composeForInvocation(
                        new ContextExecutionRef(
                                run.tenantId(),
                                execution.getConversationSessionId(),
                                run.executionId(),
                                boundary.inputUpToConversationMessageSequence()),
                        budgets.currentBudget(),
                        fixedSystemMessages,
                        contextTools);
        if (!factsFactories.isEmpty()) {
            var facts = factsFactories.getFirst().create(run.executionId());
            var metadata = new java.util.ArrayList<ContextMessage>();
            var json = JsonMapper.builder().build();
            if (!facts.values().isEmpty()) {
                metadata.add(ContextMessage.user(FACTS_PREFIX + json.writeValueAsString(facts)));
            }
            for (var resource : skillViews.require(run.executionId()).readConstraints()) {
                metadata.add(
                        ContextMessage.user(
                                SKILL_PREFIX
                                        + resource.descriptor().logicalId()
                                        + NEWLINE
                                        + resource.path()
                                        + NEWLINE
                                        + resource.digest()
                                        + NEWLINE
                                        + resource.content()));
            }
            request =
                    new ConversationContextRequest(
                            request.execution(),
                            request.budget(),
                            request.fixedSystemMessages(),
                            request.toolDefinitions(),
                            new RuntimeContextInput(metadata, List.of()),
                            facts);
        }
        try {
            // 同一已提交快照同时用于渲染与冻结写入 revision；不能为写入额外读取并偷换版本。
            MemorySnapshot snapshot = memories.snapshot();
            List<MemoryEvidence> evidence = memoryEvidence.currentUserEvidence(run.executionId());
            boolean memoryVisible =
                    contextTools.stream().anyMatch(tool -> MemoryTool.TOOL_ID.equals(tool.name()));
            var metadata = new java.util.ArrayList<>(request.runtimeInput().metadata());
            if (memoryVisible) {
                metadata.addAll(evidenceMetadata(evidence));
            }
            RuntimeContextInput runtime =
                    new RuntimeContextInput(metadata, List.of(memoryRenderer.render(snapshot)));
            memoryInvocations.open(
                    run.executionId(), boundary.modelInvocationSequence(), snapshot, evidence);
            return new ConversationContextRequest(
                    request.execution(),
                    request.budget(),
                    request.fixedSystemMessages(),
                    request.toolDefinitions(),
                    runtime,
                    request.executionFacts());
        } catch (RuntimeException failure) {
            metrics.recordReadFailure();
            // 读取失败不猜 revision，也不授予本轮写入资格；异常正文可能含私密数据，日志仅保留执行标识。
            memoryInvocations.close(run.executionId(), boundary.modelInvocationSequence());
            log.warn(
                    "Memory context unavailable for execution {} invocation {}",
                    run.executionId(),
                    boundary.modelInvocationSequence());
            return request;
        }
    }

    /** 只公开本轮真实证据 ID；正文仍保留在原用户消息，元数据不冒充用户授权。 */
    private static List<ContextMessage> evidenceMetadata(List<MemoryEvidence> evidence) {
        if (evidence.isEmpty()) {
            return List.of();
        }
        return List.of(
                ContextMessage.user(
                        "[memory_evidence]\n服务端确认本轮可引用的用户消息 ID："
                                + String.join(
                                        ", ",
                                        evidence.stream().map(MemoryEvidence::evidenceId).toList())
                                + "。仅在本轮用户原文支持该操作时引用，不能猜测或引用其他 ID。"));
    }
}
