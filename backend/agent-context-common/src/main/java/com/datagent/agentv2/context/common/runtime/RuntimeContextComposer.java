package com.datagent.agentv2.context.common.runtime;

import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.RuntimeContextContribution;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** 在当前真实 USER 消息前组装必要元数据，并按独立贡献预算和完整输入预算选择可选块。 */
public final class RuntimeContextComposer {

    public Composition compose(
            ConversationContextRequest request,
            List<ContextMessage> dynamic,
            int preferredInputLimit,
            ToIntFunction<List<ContextMessage>> completeEstimator,
            ToIntFunction<List<ContextMessage>> messageEstimator) {
        ConversationContextRequest contextRequest = Objects.requireNonNull(request, "request");
        List<ContextMessage> raw = List.copyOf(dynamic);
        Objects.requireNonNull(completeEstimator, "completeEstimator");
        Objects.requireNonNull(messageEstimator, "messageEstimator");

        int completeLimit =
                Math.min(contextRequest.budget().hardInputLimitTokens(), preferredInputLimit);
        List<ContextMessage> contributionMessages = new ArrayList<>();
        int injected = 0;
        int omitted = 0;

        for (RuntimeContextContribution contribution :
                contextRequest.runtimeInput().contributions()) {
            int selectedForContribution = 0;
            ContextMessage wrapperMessage = ContextMessage.user(contribution.wrapper());
            List<ContextMessage> wrapperContributions =
                    replaceContribution(
                            contributionMessages, contributionMessages.size(), wrapperMessage);
            if (contribution.blocks().isEmpty()
                    || !fits(
                            contribution,
                            List.of(wrapperMessage),
                            raw,
                            contextRequest,
                            wrapperContributions,
                            completeLimit,
                            completeEstimator,
                            messageEstimator)) {
                omitted += contribution.candidateCount();
                continue;
            }

            List<String> selectedBlocks = new ArrayList<>();
            int contributionMessageIndex = contributionMessages.size();
            for (String block : contribution.blocks()) {
                List<String> candidateBlocks = new ArrayList<>(selectedBlocks);
                candidateBlocks.add(block);
                ContextMessage candidateMessage = render(contribution, candidateBlocks);
                List<ContextMessage> candidateContributions =
                        replaceContribution(
                                contributionMessages, contributionMessageIndex, candidateMessage);
                if (fits(
                        contribution,
                        List.of(candidateMessage),
                        raw,
                        contextRequest,
                        candidateContributions,
                        completeLimit,
                        completeEstimator,
                        messageEstimator)) {
                    selectedBlocks.add(block);
                    selectedForContribution++;
                    if (contributionMessageIndex == contributionMessages.size()) {
                        contributionMessages.add(candidateMessage);
                    } else {
                        contributionMessages.set(contributionMessageIndex, candidateMessage);
                    }
                }
            }
            injected += selectedForContribution;
            omitted += contribution.candidateCount() - selectedForContribution;
        }

        List<ContextMessage> selected =
                insertBeforeLastUser(
                        raw,
                        contextRequest.runtimeInput().metadata(),
                        List.copyOf(contributionMessages));
        int contributionTokens =
                contributionMessages.isEmpty()
                        ? 0
                        : messageEstimator.applyAsInt(List.copyOf(contributionMessages));
        return new Composition(selected, injected, omitted, contributionTokens);
    }

    private static boolean fits(
            RuntimeContextContribution contribution,
            List<ContextMessage> currentContribution,
            List<ContextMessage> raw,
            ConversationContextRequest request,
            List<ContextMessage> allContributionMessages,
            int completeLimit,
            ToIntFunction<List<ContextMessage>> completeEstimator,
            ToIntFunction<List<ContextMessage>> messageEstimator) {
        if (messageEstimator.applyAsInt(currentContribution) > contribution.maxTokens()) {
            return false;
        }
        List<ContextMessage> candidate =
                insertBeforeLastUser(
                        raw, request.runtimeInput().metadata(), allContributionMessages);
        return completeEstimator.applyAsInt(candidate) <= completeLimit;
    }

    private static List<ContextMessage> replaceContribution(
            List<ContextMessage> selected, int index, ContextMessage candidate) {
        List<ContextMessage> replaced = new ArrayList<>(selected);
        if (index == replaced.size()) {
            replaced.add(candidate);
        } else {
            replaced.set(index, candidate);
        }
        return List.copyOf(replaced);
    }

    private static ContextMessage render(
            RuntimeContextContribution contribution, List<String> blocks) {
        return ContextMessage.user(contribution.wrapper() + "\n" + String.join("\n", blocks));
    }

    /** 最后一个 USER 是本次真实请求锚点；其后的 assistant/tool 属于同一轮工具协议，整体留在锚点之后。 */
    private static List<ContextMessage> insertBeforeLastUser(
            List<ContextMessage> raw,
            List<ContextMessage> metadata,
            List<ContextMessage> contributions) {
        int anchor = raw.size();
        for (int index = raw.size() - 1; index >= 0; index--) {
            if (raw.get(index).role() == ContextMessage.Role.USER) {
                anchor = index;
                break;
            }
        }
        List<ContextMessage> composed =
                new ArrayList<>(raw.size() + metadata.size() + contributions.size());
        composed.addAll(raw.subList(0, anchor));
        composed.addAll(metadata);
        composed.addAll(contributions);
        composed.addAll(raw.subList(anchor, raw.size()));
        return List.copyOf(composed);
    }

    /**
     * @param dynamicMessages 加入必要元数据及最终可选贡献后的动态消息
     * @param injected 实际保留的完整候选块数量
     * @param omitted 条数预筛与 token 筛选合计省略的候选数量
     * @param contributionTokens 最终贡献消息经策略消息估算器计得的实际 token 数
     */
    public record Composition(
            List<ContextMessage> dynamicMessages,
            int injected,
            int omitted,
            int contributionTokens) {

        public Composition {
            dynamicMessages = List.copyOf(dynamicMessages);
            if (injected < 0 || omitted < 0 || contributionTokens < 0) {
                throw new IllegalArgumentException("运行时贡献统计不得为负数");
            }
        }

        /** 兼容暂不消费真实贡献 token 指标的既有调用方。 */
        public Composition(List<ContextMessage> dynamicMessages, int injected, int omitted) {
            this(dynamicMessages, injected, omitted, 0);
        }
    }
}
