package com.datagent.agentv2.context.common.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.RuntimeContextContribution;
import com.datagent.agentv2.conversation.context.RuntimeContextInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class RuntimeContextComposerTest {

    private static final ToIntFunction<List<ContextMessage>> MESSAGE_ESTIMATOR =
            messages -> messages.stream().mapToInt(message -> message.content().length() + 8).sum();

    private final RuntimeContextComposer composer = new RuntimeContextComposer();

    @Test
    void noRuntimeInputLeavesProtocolUntouched() {
        var raw = List.of(ContextMessage.user("问题"), ContextMessage.assistant("回答", null));
        var request = request(RuntimeContextInput.empty());

        var result = composer.compose(request, raw, 890, MESSAGE_ESTIMATOR, MESSAGE_ESTIMATOR);

        assertThat(result.dynamicMessages()).isEqualTo(raw);
        assertThat(result.injected()).isZero();
        assertThat(result.omitted()).isZero();
        assertThat(result.contributionTokens()).isZero();
    }

    @Test
    void insertsMetadataAndContributionBeforeLastRealUserWithoutSplittingToolProtocol() {
        ContextMessage toolCall =
                ContextMessage.assistantToolCalls(
                        List.of(new ContextToolCall("call-1", "search", Map.of("q", "x"))));
        ContextMessage toolResult = ContextMessage.tool("结果", "call-1", "search");
        List<ContextMessage> raw =
                List.of(
                        ContextMessage.user("上一问"),
                        ContextMessage.assistant("上一答", null),
                        ContextMessage.user("当前问题"),
                        toolCall,
                        toolResult);
        ContextMessage metadata = ContextMessage.system("必要运行时元数据");
        var contribution = new RuntimeContextContribution("memory", "记忆包裹", List.of("条目"), 100);
        var request = request(new RuntimeContextInput(List.of(metadata), List.of(contribution)));

        var result = composer.compose(request, raw, 1_000, MESSAGE_ESTIMATOR, MESSAGE_ESTIMATOR);

        assertThat(result.dynamicMessages())
                .containsExactly(
                        raw.get(0),
                        raw.get(1),
                        metadata,
                        ContextMessage.user("记忆包裹\n条目"),
                        raw.get(2),
                        toolCall,
                        toolResult);
        assertThat(result.injected()).isOne();
        assertThat(result.omitted()).isZero();
    }

    @Test
    void selectsWholeBlocksAndReportsPrefilterAndBudgetOmissions() {
        var contribution =
                new RuntimeContextContribution(
                        "memory", "W", List.of("keep", "this block is too long", "ok"), 22, 5);
        var request = request(new RuntimeContextInput(List.of(), List.of(contribution)));
        List<ContextMessage> raw = List.of(ContextMessage.user("Q"));

        var result = composer.compose(request, raw, 1_000, MESSAGE_ESTIMATOR, MESSAGE_ESTIMATOR);

        ContextMessage selectedContribution = ContextMessage.user("W\nkeep\nok");
        assertThat(result.dynamicMessages())
                .containsExactly(selectedContribution, ContextMessage.user("Q"));
        assertThat(result.injected()).isEqualTo(2);
        assertThat(result.omitted()).isEqualTo(3);
        assertThat(result.contributionTokens())
                .isEqualTo(MESSAGE_ESTIMATOR.applyAsInt(List.of(selectedContribution)));
    }

    @Test
    void omitsEntireContributionWhenWrapperCannotFit() {
        var contribution =
                new RuntimeContextContribution("memory", "wrapper", List.of("a", "b"), 14, 4);
        var request = request(new RuntimeContextInput(List.of(), List.of(contribution)));
        List<ContextMessage> raw = List.of(ContextMessage.user("Q"));

        var result = composer.compose(request, raw, 1_000, MESSAGE_ESTIMATOR, MESSAGE_ESTIMATOR);

        assertThat(result.dynamicMessages()).isEqualTo(raw);
        assertThat(result.injected()).isZero();
        assertThat(result.omitted()).isEqualTo(4);
        assertThat(result.contributionTokens()).isZero();
    }

    @Test
    void necessaryMetadataConsumesCompleteInputBudgetBeforeOptionalMemory() {
        ContextMessage metadata = ContextMessage.system("metadata");
        var contribution = new RuntimeContextContribution("memory", "W", List.of("entry"), 100);
        var request = request(new RuntimeContextInput(List.of(metadata), List.of(contribution)));
        List<ContextMessage> raw = List.of(ContextMessage.user("Q"));
        ToIntFunction<List<ContextMessage>> completeEstimator = messages -> messages.size() * 10;

        var result = composer.compose(request, raw, 20, completeEstimator, MESSAGE_ESTIMATOR);

        assertThat(result.dynamicMessages()).containsExactly(metadata, ContextMessage.user("Q"));
        assertThat(result.injected()).isZero();
        assertThat(result.omitted()).isOne();
    }

    @Test
    void hardInputLimitStillBoundsContributionsWhenPreferredLimitIsLarger() {
        var contribution = new RuntimeContextContribution("memory", "W", List.of("entry"), 100);
        var request = request(new RuntimeContextInput(List.of(), List.of(contribution)));
        List<ContextMessage> raw = List.of(ContextMessage.user("Q"));
        ToIntFunction<List<ContextMessage>> completeEstimator =
                messages -> messages.size() == 1 ? 880 : 900;

        var result = composer.compose(request, raw, 1_000, completeEstimator, MESSAGE_ESTIMATOR);

        assertThat(result.dynamicMessages()).isEqualTo(raw);
        assertThat(result.injected()).isZero();
        assertThat(result.omitted()).isOne();
    }

    @Test
    void defensivelyCopiesRawAndRuntimeInputs() {
        var metadata = new ArrayList<>(List.of(ContextMessage.system("metadata")));
        var blocks = new ArrayList<>(List.of("entry"));
        var contributions =
                new ArrayList<>(
                        List.of(new RuntimeContextContribution("memory", "W", blocks, 100)));
        var request = request(new RuntimeContextInput(metadata, contributions));
        var raw = new ArrayList<>(List.of(ContextMessage.user("Q")));

        var result = composer.compose(request, raw, 1_000, MESSAGE_ESTIMATOR, MESSAGE_ESTIMATOR);
        raw.clear();
        metadata.clear();
        blocks.clear();
        contributions.clear();

        assertThat(result.dynamicMessages())
                .containsExactly(
                        ContextMessage.system("metadata"),
                        ContextMessage.user("W\nentry"),
                        ContextMessage.user("Q"));
        assertThat(request.runtimeInput().metadata())
                .containsExactly(ContextMessage.system("metadata"));
        assertThat(request.runtimeInput().contributions().getFirst().blocks())
                .containsExactly("entry");
    }

    private static ConversationContextRequest request(RuntimeContextInput runtimeInput) {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant_local", "s", "e", 2),
                new ContextBudget(1_000, 100, 10, 0),
                List.of(),
                List.of(),
                runtimeInput);
    }
}
