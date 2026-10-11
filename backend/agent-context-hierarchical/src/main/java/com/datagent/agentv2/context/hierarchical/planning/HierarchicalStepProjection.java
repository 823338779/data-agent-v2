package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference;
import com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference.Direction;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 只构建模型的历史视图，不改写过程记录，也不产生可执行的工具指令。 */
public final class HierarchicalStepProjection {
    private static final String PREVIEW = "preview";
    private static final String TRUNCATED = "truncated";
    private static final String REFERENCE = "reference";
    private static final String AVAILABILITY = "availability";
    private static final String MISSING = "MISSING";
    private static final String FACTS = "facts";
    private static final String EMPTY = "";
    private static final String STATUS = "status",
            STATE = "state",
            BUILD_ID = "buildId",
            QUERY_ID = "queryId";
    private static final Set<String> POLL_TOOLS = Set.of("dbt_build_get", "dbt_query_get");
    private static final Set<String> ACTIVE_STATUSES =
            Set.of("RUNNING", "PENDING", "QUEUED", "ACCEPTED");

    /** 业务身份与已观察状态优先于长正文，不能由自由摘要替换。 */
    private static final Set<String> IDENTITY_FIELDS =
            Set.of(
                    "operationId",
                    "buildId",
                    "repository",
                    "branchName",
                    "pushedSha",
                    "mergeSha",
                    "buildStatus",
                    "deploymentStatus",
                    "phase",
                    "workspaceId",
                    "projectId",
                    "branchId",
                    "gitRef",
                    "revision",
                    "draftRevision",
                    "baseCommit",
                    "commitId",
                    "validationId",
                    "changeId",
                    "queryId",
                    "releaseId",
                    "status",
                    "errorCode",
                    "confirmationVersion",
                    "confirmedAt");

    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();

    public ContextStepGroup project(ContextStepGroup source, int payloadTokenLimit) {
        var messages = new ArrayList<ContextMessage>();
        for (var message : source.messages()) {
            // 当前用户锚点由组装层保留原文；工具配对身份始终从原记录复制。
            if (message.role() == ContextMessage.Role.USER) {
                messages.add(message);
                continue;
            }
            var calls = new ArrayList<ContextToolCall>();
            for (var call : message.toolCalls()) {
                String encoded = json.writeValueAsString(call.arguments());
                Map<String, Object> arguments = call.arguments();
                if (bytes(encoded) > payloadTokenLimit) {
                    arguments =
                            bounded(
                                    encoded,
                                    source.payloadReferences()
                                            .get(
                                                    ContextStepGroup.payloadKey(
                                                            call.id(), Direction.INPUT)),
                                    payloadTokenLimit);
                }
                calls.add(new ContextToolCall(call.id(), call.name(), arguments));
            }
            var reference =
                    source.payloadReferences()
                            .get(
                                    ContextStepGroup.payloadKey(
                                            message.toolCallId(), Direction.OUTPUT));
            String content = projectText(message.content(), reference, payloadTokenLimit);
            String thinking =
                    message.thinking() == null
                            ? null
                            : projectText(message.thinking(), null, payloadTokenLimit);
            messages.add(
                    new ContextMessage(
                            message.role(),
                            content,
                            thinking,
                            calls,
                            message.toolCallId(),
                            message.toolName()));
        }
        return new ContextStepGroup(
                source.fromExclusive(),
                source.throughInclusive(),
                messages,
                source.sourceDigest(),
                source.complete(),
                source.payloadReferences());
    }

    /** 仅压缩摘要源中的重复只读轮询；原文协议从不删 observation。 */
    public java.util.List<SummarySource> summarySources(java.util.List<ContextStepGroup> groups) {
        var result = new ArrayList<SummarySource>();
        for (int index = 0; index < groups.size(); ) {
            String key = pollKey(groups.get(index));
            int end = index + 1;
            while (key != null && end < groups.size() && key.equals(pollKey(groups.get(end)))) {
                end++;
            }
            if (end - index > 2) {
                var messages = new ArrayList<>(groups.get(index).messages());
                messages.addAll(groups.get(end - 1).messages());
                result.add(
                        new SummarySource(
                                groups.subList(index, end).stream()
                                        .map(ContextStepGroup::throughInclusive)
                                        .toList(),
                                messages));
            } else {
                for (int current = index; current < end; current++) {
                    var group = groups.get(current);
                    result.add(
                            new SummarySource(
                                    java.util.List.of(group.throughInclusive()), group.messages()));
                }
            }
            index = end;
        }
        return java.util.List.copyOf(result);
    }

    private String pollKey(ContextStepGroup group) {
        if (!group.complete() || group.messages().size() != 2) {
            return null;
        }
        var first = group.messages().getFirst();
        var last = group.messages().getLast();
        if (first.toolCalls().size() != 1 || last.role() != ContextMessage.Role.TOOL) {
            return null;
        }
        var call = first.toolCalls().getFirst();
        if (!POLL_TOOLS.contains(call.name())
                || !(call.arguments().containsKey(BUILD_ID)
                        || call.arguments().containsKey(QUERY_ID))) {
            return null;
        }
        try {
            var output = json.readTree(last.content());
            String status = output.path(STATUS).asString(output.path(STATE).asString(EMPTY));
            if (!ACTIVE_STATUSES.contains(status.toUpperCase(java.util.Locale.ROOT))) {
                return null;
            }
            // 固定同一源 assistant，进一步保证 execution 的分支/工作区绑定相同。
            return json.writeValueAsString(
                    java.util.List.of(
                            group.throughInclusive().messageSequence(),
                            call.name(),
                            call.arguments(),
                            last.content()));
        } catch (tools.jackson.core.JacksonException invalid) {
            return null;
        }
    }

    /** 折叠后仍列出所有真实来源，首次与最新消息均完整保留。 */
    public record SummarySource(
            java.util.List<com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor>
                    sources,
            java.util.List<ContextMessage> messages) {
        public SummarySource {
            sources = java.util.List.copyOf(sources);
            messages = java.util.List.copyOf(messages);
        }
    }

    /** 提取服务端工具输出中的标量身份；不把任意正文提升为指令。 */
    public Map<String, String> identities(String content) {
        var result = new LinkedHashMap<String, String>();
        try {
            collect(json.readTree(content), result);
        } catch (tools.jackson.core.JacksonException notJson) {
            // 普通工具文本没有结构化身份，保留在预览或全文引用中。
        }
        return Map.copyOf(result);
    }

    private void collect(JsonNode node, Map<String, String> result) {
        if (node.isObject()) {
            for (var property : node.properties()) {
                if (IDENTITY_FIELDS.contains(property.getKey())
                        && property.getValue().isValueNode()
                        && !property.getValue().isNull()) {
                    result.put(property.getKey(), property.getValue().asString());
                } else if ((property.getValue().isObject() || property.getValue().isArray())) {
                    collect(property.getValue(), result);
                }
            }
        } else if (node.isArray()) {
            node.forEach(value -> collect(value, result));
        }
    }

    private String projectText(String content, ContextPayloadReference reference, int limit) {
        return bytes(content) <= limit
                ? content
                : json.writeValueAsString(bounded(content, reference, limit));
    }

    private Map<String, Object> bounded(
            String content, ContextPayloadReference reference, int limit) {
        var result = new LinkedHashMap<String, Object>();
        result.put(TRUNCATED, true);
        result.put(FACTS, identities(content));
        if (reference == null) {
            result.put(AVAILABILITY, MISSING);
        } else {
            result.put(REFERENCE, json.convertValue(reference, Map.class));
        }
        result.put(PREVIEW, EMPTY);
        int overhead = bytes(json.writeValueAsString(result));
        if (overhead > limit) {
            throw new IllegalArgumentException(
                    "Essential payload identity exceeds projection budget");
        }
        // 以 code point 截断避免代理字符破损；随后核对 JSON 转义后的真实字节数。
        int points = Math.min(content.codePointCount(0, content.length()), limit - overhead);
        while (points > 0) {
            result.put(PREVIEW, content.substring(0, content.offsetByCodePoints(0, points)));
            if (bytes(json.writeValueAsString(result)) <= limit) {
                return Map.copyOf(result);
            }
            points /= 2;
        }
        result.put(PREVIEW, EMPTY);
        return Map.copyOf(result);
    }

    private static int bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
