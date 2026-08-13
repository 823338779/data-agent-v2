package com.datagent.agentv2.context.hierarchical.summary;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.summary.ContextSummaryDocumentValidator.ValidationResult;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** 生成、校验并确定性标识 hierarchical Warm/Cold 摘要候选。 */
public final class HierarchicalSummaryGenerator {

    /** 两次均失败时向运行时暴露的稳定失败代码。 */
    public static final String FAILURE_CONTEXT_COMPACTION = "FAILURE_CONTEXT_COMPACTION";

    private static final int MAX_ATTEMPTS = 2;
    private static final int FIRST_ATTEMPT = 1;
    private static final String INVALID_MODEL_IDENTITY =
            "Summary result model identity does not match the requested identity";
    private static final String INVALID_PROVIDER_TOKEN_COUNT =
            "Summary provider token count exceeds the configured node limit";
    private static final String INVALID_POLICY_VERSION =
            "Frozen summary versions are not supported by this generator";
    private static final String INVALID_SOURCE = "Invalid hierarchical summary source";

    private static final Comparator<HierarchicalSummaryNode> NODE_ORDER =
            Comparator.comparingLong(HierarchicalSummaryNode::startConversationMessageSequence);

    private final ContextSummaryModelPort model;
    private final ContextSummaryDocumentValidator validator;
    private final HierarchicalNodeKeyFactory keyFactory;
    private final ObjectMapper json;

    /** 使用共享摘要端口、固定 validator 与确定性 key 工厂构造纯生成器。 */
    public HierarchicalSummaryGenerator(
            ContextSummaryModelPort model,
            ContextSummaryDocumentValidator validator,
            HierarchicalNodeKeyFactory keyFactory) {
        this(model, validator, keyFactory, JsonMapper.builder().build());
    }

    HierarchicalSummaryGenerator(
            ContextSummaryModelPort model,
            ContextSummaryDocumentValidator validator,
            HierarchicalNodeKeyFactory keyFactory,
            ObjectMapper json) {
        this.model = Objects.requireNonNull(model, "model");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 最多调用共享模型端口两次，第二次只追加稳定纠错指令。 */
    public GeneratedSummary generate(GenerationRequest generationRequest) {
        GenerationRequest request = Objects.requireNonNull(generationRequest, "generationRequest");
        if (request.source() instanceof LeafSource leaf) {
            request.validateLeaf(leaf);
        }
        SummaryGenerationIdentity identity = generationIdentity(request);
        String sourceDocument = sourceDocument(request, identity);
        String nodeKey = keyFactory.create(request, identity);
        RuntimeException lastFailure = null;

        // 每次尝试只处理内存候选；不记录 prompt、来源或模型正文，也不执行持久化操作。
        for (int attempt = FIRST_ATTEMPT; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ContextSummaryResult result =
                        model.summarize(toModelRequest(request, sourceDocument, attempt));
                validateResultIdentity(result, identity);
                ValidationResult validation =
                        validator.validate(
                                result.content(), request.maxSummaryTokens(), request.range());
                if (result.estimatedTokenCount() > request.maxSummaryTokens()) {
                    throw new IllegalArgumentException(INVALID_PROVIDER_TOKEN_COUNT);
                }
                int tokenCount =
                        Math.max(result.estimatedTokenCount(), validation.estimatedTokenCount());
                return new GeneratedSummary(
                        nodeKey, result.content(), tokenCount, identity, request.range());
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
        }
        throw new IllegalStateException(FAILURE_CONTEXT_COMPACTION, lastFailure);
    }

    /** 返回调用 provider 前已经固定的会话、协议和模型身份。 */
    public SummaryGenerationIdentity generationIdentity(GenerationRequest request) {
        GenerationRequest checked = Objects.requireNonNull(request, "request");
        HierarchicalContextPolicySnapshot policy = checked.policySnapshot();
        if (policy.summarySchemaVersion() != ContextSummaryDocumentValidator.SCHEMA_VERSION
                || policy.summaryPromptVersion() != ContextSummaryPrompt.PROMPT_VERSION) {
            throw new IllegalArgumentException(INVALID_POLICY_VERSION);
        }
        return new SummaryGenerationIdentity(
                checked.conversationSessionId(),
                policy.summarySchemaVersion(),
                policy.summaryPromptVersion(),
                Objects.requireNonNull(model.identity(), "summary model identity"));
    }

    /** 在 provider 调用前返回同 identity 可复用的确定性节点 key。 */
    public String nodeKey(GenerationRequest request) {
        return keyFactory.create(request, generationIdentity(request));
    }

    private ContextSummaryRequest toModelRequest(
            GenerationRequest request, String sourceDocument, int attempt) {
        String prompt =
                attempt == FIRST_ATTEMPT
                        ? ContextSummaryPrompt.systemPrompt(request.range())
                        : ContextSummaryPrompt.correctiveSystemPrompt(request.range());
        return new ContextSummaryRequest(
                prompt, sourceDocument, validator.schemaDocument(), request.maxSummaryTokens());
    }

    private static void validateResultIdentity(
            ContextSummaryResult result, SummaryGenerationIdentity identity) {
        ContextSummaryResult checked = Objects.requireNonNull(result, "result");
        ContextSummaryModelIdentity expected = identity.modelIdentity();
        if (!expected.provider().equals(checked.modelProvider())
                || !expected.model().equals(checked.modelName())) {
            throw new IllegalArgumentException(INVALID_MODEL_IDENTITY);
        }
    }

    private String sourceDocument(GenerationRequest request, SummaryGenerationIdentity identity) {
        SummarySource source = request.source();
        if (source instanceof LeafSource leaf) {
            return json.writeValueAsString(leaf.turns());
        }

        // 父节点来源数组严格只含左摘要和右摘要，顺序不可由调用者改变。
        if (source instanceof ParentSource parent) {
            return summaryArray(validatedParentDocuments(request, parent, identity));
        }

        // Cold 来源先保留旧 Cold（若有），再按消息范围升序追加所有迁移节点。
        return summaryArray(validatedColdDocuments(request, (ColdSource) source, identity));
    }

    private List<String> validatedParentDocuments(
            GenerationRequest request, ParentSource parent, SummaryGenerationIdentity identity) {
        HierarchicalSummaryNode left = parent.left();
        HierarchicalSummaryNode right = parent.right();
        GenerationRange parentRange = request.range();
        long childTurns = expectedNodeTurns(left.level());

        // 可信节点元数据必须构成请求范围的精确二叉父节点，不允许缺口、部分覆盖或跨身份合并。
        if (left.level() != right.level()
                || parentRange.level() != left.level() + 1
                || left.coveredCompletedTurnCount() != childTurns
                || right.coveredCompletedTurnCount() != childTurns
                || parentRange.endTurn() - parentRange.startTurn() + 1L != childTurns * 2L
                || !isAligned(parentRange.startTurn(), parentRange.level())
                || left.startConversationMessageSequence() != parentRange.startMessageSequence()
                || right.startConversationMessageSequence()
                        != left.endConversationMessageSequence() + 1L
                || right.endConversationMessageSequence() != parentRange.endMessageSequence()) {
            throw new IllegalArgumentException(INVALID_SOURCE);
        }
        validateNodeIdentity(left, identity);
        validateNodeIdentity(right, identity);

        // 子正文 coverage 只能与外部节点元数据推导出的左右范围比对，不能反向充当元数据。
        GenerationRange leftRange =
                new GenerationRange(
                        parentRange.startTurn(),
                        parentRange.startTurn() + childTurns - 1L,
                        left.startConversationMessageSequence(),
                        left.endConversationMessageSequence(),
                        left.level());
        GenerationRange rightRange =
                new GenerationRange(
                        leftRange.endTurn() + 1L,
                        parentRange.endTurn(),
                        right.startConversationMessageSequence(),
                        right.endConversationMessageSequence(),
                        right.level());
        validateNodeDocument(left, leftRange);
        validateNodeDocument(right, rightRange);
        return List.of(left.summaryDocument(), right.summaryDocument());
    }

    private List<String> validatedColdDocuments(
            GenerationRequest request, ColdSource cold, SummaryGenerationIdentity identity) {
        GenerationRange requested = request.range();
        if (requested.level() != 0) {
            throw new IllegalArgumentException(INVALID_SOURCE);
        }
        long nextTurn = requested.startTurn();
        long nextMessage = requested.startMessageSequence();
        List<String> documents = new ArrayList<>();

        // 旧 Cold 的范围与身份由 checkpoint 元数据提供；JSON 内 coverage 仅用于一致性校验。
        ColdSummary oldCold = cold.oldColdSummary();
        if (oldCold != null) {
            validateColdIdentity(oldCold, identity);
            GenerationRange oldRange = oldCold.range();
            if (oldRange.level() != 0
                    || oldRange.startTurn() != nextTurn
                    || oldRange.startMessageSequence() != nextMessage) {
                throw new IllegalArgumentException(INVALID_SOURCE);
            }
            validator.validate(oldCold.document(), oldCold.estimatedTokenCount(), oldCold.range());
            documents.add(oldCold.document());
            nextTurn = oldRange.endTurn() + 1L;
            nextMessage = oldRange.endMessageSequence() + 1L;
        }

        // promotion 按可信消息范围排序，并逐节点要求与前一来源精确连续。
        for (HierarchicalSummaryNode node :
                cold.promotedNodes().stream().sorted(NODE_ORDER).toList()) {
            validateNodeIdentity(node, identity);
            long coveredTurns = expectedNodeTurns(node.level());
            if (node.coveredCompletedTurnCount() != coveredTurns
                    || !isAligned(nextTurn, node.level())
                    || node.startConversationMessageSequence() != nextMessage) {
                throw new IllegalArgumentException(INVALID_SOURCE);
            }
            GenerationRange nodeRange =
                    new GenerationRange(
                            nextTurn,
                            nextTurn + coveredTurns - 1L,
                            node.startConversationMessageSequence(),
                            node.endConversationMessageSequence(),
                            node.level());
            validateNodeDocument(node, nodeRange);
            documents.add(node.summaryDocument());
            nextTurn = nodeRange.endTurn() + 1L;
            nextMessage = nodeRange.endMessageSequence() + 1L;
        }

        // 最后一个来源也必须精确抵达请求末端，不能留下 turn 或 message 部分覆盖。
        if (nextTurn != requested.endTurn() + 1L
                || nextMessage != requested.endMessageSequence() + 1L) {
            throw new IllegalArgumentException(INVALID_SOURCE);
        }
        return List.copyOf(documents);
    }

    private void validateNodeDocument(HierarchicalSummaryNode node, GenerationRange range) {
        validator.validate(node.summaryDocument(), node.estimatedTokenCount(), range);
    }

    private static void validateNodeIdentity(
            HierarchicalSummaryNode node, SummaryGenerationIdentity identity) {
        if (!identity.conversationSessionId().equals(node.conversationSessionId())
                || identity.schemaVersion() != node.summarySchemaVersion()
                || identity.promptVersion() != node.summaryPromptVersion()
                || !identity.modelIdentity().provider().equals(node.modelProvider())
                || !identity.modelIdentity().model().equals(node.modelName())) {
            throw new IllegalArgumentException(INVALID_SOURCE);
        }
    }

    private static void validateColdIdentity(ColdSummary cold, SummaryGenerationIdentity identity) {
        if (!identity.conversationSessionId().equals(cold.conversationSessionId())
                || identity.schemaVersion() != cold.schemaVersion()
                || identity.promptVersion() != cold.promptVersion()
                || !identity.modelIdentity().provider().equals(cold.modelProvider())
                || !identity.modelIdentity().model().equals(cold.modelName())) {
            throw new IllegalArgumentException(INVALID_SOURCE);
        }
    }

    private static long expectedNodeTurns(int level) {
        if (level < 0 || level > 62) {
            throw new IllegalArgumentException(INVALID_SOURCE);
        }
        return 1L << level;
    }

    private static boolean isAligned(long startTurn, int level) {
        long coveredTurns = expectedNodeTurns(level);
        return (startTurn - 1L) % coveredTurns == 0L;
    }

    private String summaryArray(List<String> documents) {
        ArrayNode summaries = json.createArrayNode();
        for (String document : documents) {
            JsonNode summary = json.readTree(document);
            if (summary == null || !summary.isObject()) {
                throw new IllegalArgumentException(INVALID_SOURCE);
            }
            summaries.add(summary);
        }
        return json.writeValueAsString(summaries);
    }

    /** 生成类型参与 key，避免 Cold 与相同范围的 Warm 候选发生混淆。 */
    public enum SourceKind {
        LEAF,
        PARENT,
        COLD
    }

    /** 三类来源只暴露不可变结构，不接受预拼接的任意 source document。 */
    public sealed interface SummarySource permits LeafSource, ParentSource, ColdSource {
        /** 返回来源序列化和 key 使用的固定类别。 */
        SourceKind kind();
    }

    /** 叶节点保存范围内完整 message/thinking/tool 历史。 */
    public record LeafSource(List<ConversationTurn> turns) implements SummarySource {

        public LeafSource {
            turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
            if (turns.isEmpty()) {
                throw new IllegalArgumentException(INVALID_SOURCE);
            }
        }

        @Override
        public SourceKind kind() {
            return SourceKind.LEAF;
        }
    }

    /** 父节点固定保存左、右两个直接子摘要。 */
    public record ParentSource(HierarchicalSummaryNode left, HierarchicalSummaryNode right)
            implements SummarySource {

        public ParentSource {
            left = Objects.requireNonNull(left, "left");
            right = Objects.requireNonNull(right, "right");
        }

        @Override
        public SourceKind kind() {
            return SourceKind.PARENT;
        }
    }

    /** Cold 固定保存可选旧摘要的可信元数据和待迁移的不可变节点。 */
    public record ColdSource(
            ColdSummary oldColdSummary, List<HierarchicalSummaryNode> promotedNodes)
            implements SummarySource {

        public ColdSource {
            promotedNodes = List.copyOf(Objects.requireNonNull(promotedNodes, "promotedNodes"));
            if (promotedNodes.isEmpty()) {
                throw new IllegalArgumentException(INVALID_SOURCE);
            }
        }

        @Override
        public SourceKind kind() {
            return SourceKind.COLD;
        }
    }

    /** 旧 Cold 正文及其来自 checkpoint、不可由 JSON 正文声明替代的可信元数据。 */
    public record ColdSummary(
            /** Cold 所属会话。 */
            String conversationSessionId,
            /** Cold 已正式发布的 turn/message 覆盖范围。 */
            GenerationRange range,
            /** 已通过上次发布门禁的结构化摘要正文。 */
            String document,
            /** checkpoint 保存的正文 token 估算，也是本次复验上限。 */
            int estimatedTokenCount,
            /** checkpoint 保存的摘要 schema 版本。 */
            int schemaVersion,
            /** checkpoint 保存的摘要 prompt 版本。 */
            int promptVersion,
            /** checkpoint 保存的模型 provider。 */
            String modelProvider,
            /** checkpoint 保存的模型名称。 */
            String modelName) {

        public ColdSummary {
            conversationSessionId =
                    Objects.requireNonNull(conversationSessionId, "conversationSessionId");
            range = Objects.requireNonNull(range, "range");
            document = Objects.requireNonNull(document, "document");
            modelProvider = Objects.requireNonNull(modelProvider, "modelProvider");
            modelName = Objects.requireNonNull(modelName, "modelName");
            if (conversationSessionId.isBlank()
                    || document.isBlank()
                    || estimatedTokenCount <= 0
                    || schemaVersion <= 0
                    || promptVersion <= 0
                    || modelProvider.isBlank()
                    || modelName.isBlank()) {
                throw new IllegalArgumentException(INVALID_SOURCE);
            }
        }
    }

    /** 摘要必须精确覆盖的 turn/message 范围以及 Warm 节点层级。 */
    public record GenerationRange(
            /** 首个完整问答序号。 */
            long startTurn,
            /** 最后一个完整问答序号。 */
            long endTurn,
            /** 首条持久化消息序号。 */
            long startMessageSequence,
            /** 末条持久化消息序号。 */
            long endMessageSequence,
            /** Warm 二叉树层级；Cold 候选使用零。 */
            int level) {

        public GenerationRange {
            if (startTurn <= 0L
                    || endTurn < startTurn
                    || startMessageSequence <= 0L
                    || endMessageSequence < startMessageSequence
                    || level < 0) {
                throw new IllegalArgumentException("Invalid summary generation range");
            }
        }
    }

    /** 一次摘要调用的可信范围、冻结策略、来源和输出上限。 */
    public record GenerationRequest(
            /** 候选所属会话。 */
            String conversationSessionId,
            /** 可信规划器给出的覆盖范围。 */
            GenerationRange range,
            /** 完整叶历史、左右子摘要或 Cold 来源。 */
            SummarySource source,
            /** execution 首次进入策略时冻结的版本与窗口参数。 */
            HierarchicalContextPolicySnapshot policySnapshot,
            /** 当前节点允许的严格摘要 token 上限。 */
            int maxSummaryTokens) {

        public GenerationRequest {
            conversationSessionId =
                    Objects.requireNonNull(conversationSessionId, "conversationSessionId");
            range = Objects.requireNonNull(range, "range");
            source = Objects.requireNonNull(source, "source");
            policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
            if (conversationSessionId.isBlank() || maxSummaryTokens <= 0) {
                throw new IllegalArgumentException("Invalid summary generation request");
            }
        }

        /** 构造携带完整 turn 历史的叶节点请求。 */
        public static GenerationRequest leaf(
                String conversationSessionId,
                GenerationRange range,
                List<ConversationTurn> turns,
                HierarchicalContextPolicySnapshot policySnapshot,
                int maxSummaryTokens) {
            return new GenerationRequest(
                    conversationSessionId,
                    range,
                    new LeafSource(turns),
                    policySnapshot,
                    maxSummaryTokens);
        }

        /** 构造固定左、右摘要顺序的父节点请求。 */
        public static GenerationRequest parent(
                String conversationSessionId,
                GenerationRange range,
                HierarchicalSummaryNode left,
                HierarchicalSummaryNode right,
                HierarchicalContextPolicySnapshot policySnapshot,
                int maxSummaryTokens) {
            return new GenerationRequest(
                    conversationSessionId,
                    range,
                    new ParentSource(left, right),
                    policySnapshot,
                    maxSummaryTokens);
        }

        /** 构造旧 Cold 在前、迁移节点随后排序的滚动摘要请求。 */
        public static GenerationRequest cold(
                String conversationSessionId,
                GenerationRange range,
                ColdSummary oldColdSummary,
                List<HierarchicalSummaryNode> promotedNodes,
                HierarchicalContextPolicySnapshot policySnapshot,
                int maxSummaryTokens) {
            return new GenerationRequest(
                    conversationSessionId,
                    range,
                    new ColdSource(oldColdSummary, promotedNodes),
                    policySnapshot,
                    maxSummaryTokens);
        }

        /** 仅用于基于另一可信规划范围派生同类请求。 */
        public GenerationRequest withRange(GenerationRange replacement) {
            return new GenerationRequest(
                    conversationSessionId, replacement, source, policySnapshot, maxSummaryTokens);
        }

        private void validateLeaf(LeafSource leaf) {
            List<ConversationTurn> turns = leaf.turns();
            ConversationTurn first = turns.getFirst();
            ConversationTurn last = turns.getLast();
            if (first.ordinal() != range.startTurn()
                    || last.ordinal() != range.endTurn()
                    || first.startMessageSequence() != range.startMessageSequence()
                    || last.endMessageSequence() != range.endMessageSequence()
                    || turns.size() != range.endTurn() - range.startTurn() + 1L) {
                throw new IllegalArgumentException(INVALID_SOURCE);
            }
            for (int index = 0; index < turns.size(); index++) {
                ConversationTurn turn = turns.get(index);
                if (!turn.completed() || turn.ordinal() != range.startTurn() + index) {
                    throw new IllegalArgumentException(INVALID_SOURCE);
                }
            }
        }
    }

    /** 已校验、尚未持久化的不可变摘要候选。 */
    public record GeneratedSummary(
            /** 范围和完整生成身份决定的 SHA-256 key。 */
            String nodeKey,
            /** 已通过 schema、coverage 和体积门禁的 JSON 正文。 */
            String document,
            /** provider 与本地估算中的较大 token 数。 */
            int estimatedTokenCount,
            /** 生成前固定的 schema、prompt 和模型身份。 */
            SummaryGenerationIdentity identity,
            /** 候选覆盖的可信范围。 */
            GenerationRange range) {

        public GeneratedSummary {
            nodeKey = Objects.requireNonNull(nodeKey, "nodeKey");
            document = Objects.requireNonNull(document, "document");
            identity = Objects.requireNonNull(identity, "identity");
            range = Objects.requireNonNull(range, "range");
            if (estimatedTokenCount < 0) {
                throw new IllegalArgumentException("Summary token count must not be negative");
            }
        }
    }
}
