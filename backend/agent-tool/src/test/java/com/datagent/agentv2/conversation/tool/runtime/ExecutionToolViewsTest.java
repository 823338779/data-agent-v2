package com.datagent.agentv2.conversation.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.supply.ToolSupplyRegistry;
import com.datagent.agentv2.conversation.tool.support.StubAgentTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionToolViewsTest {

    @Test
    void opensWithInitialToolsAndActivatesAllowedToolsAtomically() throws Exception {
        ExecutionToolViews views = views();
        ExecutionToolView view = views.open("execution-1");

        assertThat(view.visibleNames()).containsExactly("skill_read");

        ToolActivationResult result = view.activateAll(List.of("web_search"));

        assertThat(result.activatedToolNames()).containsExactly("web_search");
        assertThat(result.alreadyActivated()).isFalse();
        assertThat(view.visibleNames()).containsExactly("skill_read", "web_search");
        assertThat(view.visibleTools())
                .extracting(binding -> binding.runtimeName())
                .containsExactly("skill_read", "web_search");
    }

    @Test
    void activationIsAllOrNoneWhenAnyToolIsOutsideMaximum() {
        ExecutionToolViews views = views();
        ExecutionToolView view = views.open("execution-1");

        assertThatThrownBy(() -> view.activateAll(List.of("web_search", "secret")))
                .isInstanceOf(ToolBusinessException.class)
                .satisfies(
                        failure ->
                                assertThat(((ToolBusinessException) failure).code())
                                        .isEqualTo("skill_activation_denied"));
        assertThat(view.visibleNames()).containsExactly("skill_read");
    }

    @Test
    void repeatedActivationIsIdempotent() throws Exception {
        ExecutionToolView view = views().open("execution-1");
        view.activateAll(List.of("web_search"));

        ToolActivationResult repeated = view.activateAll(List.of("web_search"));

        assertThat(repeated.activatedToolNames()).containsExactly("web_search");
        assertThat(repeated.alreadyActivated()).isTrue();
    }

    @Test
    void hiddenToolCannotBeResolvedAndSafeMessageDoesNotLeakItsName() {
        ExecutionToolView view = views().open("execution-1");

        assertThatThrownBy(() -> view.requireVisible("web_search"))
                .isInstanceOf(ToolBusinessException.class)
                .satisfies(
                        failure -> {
                            ToolBusinessException businessFailure = (ToolBusinessException) failure;
                            assertThat(businessFailure.code()).isEqualTo("tool_not_visible");
                            assertThat(businessFailure.safeMessage())
                                    .isEqualTo("Tool is not visible for this execution")
                                    .doesNotContain("web_search");
                        });
    }

    @Test
    void rejectsInvalidPolicies() {
        ToolSupplyRegistry supply = supply();

        assertThatThrownBy(
                        () ->
                                new ExecutionToolViews(
                                        supply,
                                        new ToolPolicyProperties(
                                                List.of("skill_read"),
                                                List.of("skill_read", "web_search"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new ExecutionToolViews(
                                        supply,
                                        new ToolPolicyProperties(
                                                List.of("skill_read", "unknown"),
                                                List.of("skill_read"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new ExecutionToolViews(
                                        supply,
                                        new ToolPolicyProperties(
                                                List.of("skill_read", "skill_read"),
                                                List.of("skill_read"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDuplicateOpenAndCloseRemovesExecutionState() {
        ExecutionToolViews views = views();
        views.open("execution-1");

        assertThatThrownBy(() -> views.open("execution-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(views.contains("execution-1")).isTrue();

        views.close("execution-1");

        assertThat(views.contains("execution-1")).isFalse();
        assertThatThrownBy(() -> views.require("execution-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ExecutionToolViews views() {
        return new ExecutionToolViews(
                supply(),
                new ToolPolicyProperties(
                        List.of("skill_read", "web_search"), List.of("skill_read")));
    }

    private static ToolSupplyRegistry supply() {
        return new ToolSupplyRegistry(
                List.of(
                        new StubAgentTool("skill_read"),
                        new StubAgentTool("web_search"),
                        new StubAgentTool("secret")));
    }
}
