package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.context.*;
import com.datagent.agentv2.conversation.model.api.*;
import com.datagent.agentv2.conversation.model.codex.CodexModelProperties;
import com.datagent.agentv2.conversation.model.ollama.OllamaModelProperties;
import com.datagent.agentv2.memory.review.*;
import com.datagent.agentv2.memory.tool.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionDetails;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class MemoryReviewModelAdapterTest {
    final ObjectMapper json = new ObjectMapper();
    final AgentModelGateway gateway = mock(AgentModelGateway.class);
    final ContextInputEstimator estimator = mock(ContextInputEstimator.class);
    final ContextStrategyProperties strategy = new ContextStrategyProperties();
    final OllamaModelProperties ollama = new OllamaModelProperties();
    final ContextModelInputProperties generic = new ContextModelInputProperties();

    @Test
    void estimatesExactSentMessagesAndOnlyPrivateMemorySchema() {
        strategy.setStrategy("test");
        when(estimator.strategyId()).thenReturn("test");
        var adapter = adapter();
        var input = input();
        when(estimator.estimateCompleteInput(any(), any()))
                .thenAnswer(
                        i -> {
                            ConversationContextRequest request = i.getArgument(0);
                            assertThat(request.toolDefinitions()).hasSize(1);
                            assertThat(request.fixedSystemMessages().getFirst().content())
                                    .contains("审查员");
                            return 123;
                        });
        when(gateway.complete(any(), any(), any()))
                .thenAnswer(
                        i -> {
                            var messages =
                                    (List<
                                                    com.datagent.agentv2.conversation.react
                                                            .ModelInputMessage>)
                                            i.getArgument(0);
                            var tools = (List<ModelToolDefinition>) i.getArgument(1);
                            assertThat(tools)
                                    .extracting(ModelToolDefinition::name)
                                    .containsExactly("memory");
                            String schema = json.writeValueAsString(tools.getFirst().parameters());
                            assertThat(schema).contains("confirm").doesNotContain("write_reason");
                            assertThat(messages.get(1).content())
                                    .contains("\"content\":\"［runtime_memory］正文\"");
                            return new ModelInvocationResult.FinalAnswer("完成");
                        });
        assertThat(adapter.estimate(input)).isEqualTo(123);
        assertThat(adapter.complete(input)).isInstanceOf(MemoryReviewOutput.Done.class);
        verify(estimator, times(2)).estimateCompleteInput(any(), any());
    }

    @Test
    void rawHasStableFingerprintButCannotCallModelWithoutPureEstimator() {
        when(estimator.strategyId()).thenReturn("test");
        var adapter = adapter();
        String before = adapter.configurationFingerprint();
        assertThatThrownBy(adapter::maxInputTokens)
                .isInstanceOf(MemoryReviewConfigurationException.class);
        assertThat(adapter.configurationFingerprint()).isEqualTo(before);
        ollama.setModel("new-model");
        assertThat(adapter.configurationFingerprint()).isNotEqualTo(before);
        verifyNoInteractions(gateway);
    }

    @Test
    void providerFailureIsNeverConvertedToDone() {
        strategy.setStrategy("test");
        when(estimator.strategyId()).thenReturn("test");
        when(gateway.complete(any(), any(), any()))
                .thenThrow(new IllegalStateException("provider"));
        assertThatThrownBy(() -> adapter().complete(input()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void effectiveEndpointChangesFingerprintButSameEndpointAndRawPropertyOverrideRemainStable() {
        when(estimator.strategyId()).thenReturn("test");
        String original =
                configuredFingerprint(
                        "http://effective-one.invalid:11434", "http://ignored-one.invalid:11434");
        String restarted =
                configuredFingerprint(
                        "http://effective-one.invalid:11434", "http://ignored-two.invalid:11434");
        assertThat(restarted).isEqualTo(original);
        String changed =
                configuredFingerprint(
                        "http://effective-two.invalid:11434", "http://ignored-two.invalid:11434");
        assertThat(changed).isNotEqualTo(original);
        assertThat(changed).matches("[a-f0-9]{64}");
    }

    @Test
    void absentConnectionDetailsAreStableAndOtherProviderDoesNotResolveThem() {
        when(estimator.strategyId()).thenReturn("test");
        ObjectProvider<OllamaConnectionDetails> connections = mock(ObjectProvider.class);
        assertThat(adapter(connections).configurationFingerprint())
                .isEqualTo(adapter(connections).configurationFingerprint());
        var codexAdapter = adapter(connections);
        ReflectionTestUtils.setField(codexAdapter, "provider", "codex");
        clearInvocations(connections);
        when(connections.getIfAvailable()).thenThrow(new AssertionError("Codex 不应解析 Ollama 地址"));
        String fingerprint = codexAdapter.configurationFingerprint();
        assertThat(codexAdapter.configurationFingerprint()).isEqualTo(fingerprint);
        verifyNoInteractions(connections);
    }

    private String configuredFingerprint(String effective, String raw) {
        var result = new AtomicReference<String>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OllamaApiAutoConfiguration.class))
                .withPropertyValues("spring.ai.ollama.base-url=" + raw)
                .withBean(OllamaConnectionDetails.class, () -> () -> effective)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(OllamaConnectionDetails.class);
                            assertThat(context.getBean(OllamaConnectionDetails.class).getBaseUrl())
                                    .isEqualTo(effective);
                            result.set(
                                    adapter(context.getBeanProvider(OllamaConnectionDetails.class))
                                            .configurationFingerprint());
                        });
        return result.get();
    }

    MemoryReviewModelAdapter adapter() {
        return adapter(mock(ObjectProvider.class));
    }

    MemoryReviewModelAdapter adapter(ObjectProvider<OllamaConnectionDetails> connections) {
        ObjectProvider<AgentModelGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        return new MemoryReviewModelAdapter(
                provider,
                new ContextInputEstimatorResolver(List.of(estimator), strategy),
                new ContextModelBudgetProvider("ollama", ollama, generic),
                new ContextModelMessageMapper(
                        org.mapstruct.factory.Mappers.getMapper(
                                com.datagent.agentv2.conversation.context
                                        .ContextModelMessageStructMapper.class),
                        json),
                new MemoryTool(
                        mock(MemoryToolDispatcher.class),
                        mock(MemoryInvocationRegistry.class),
                        json),
                json,
                strategy,
                ollama,
                new CodexModelProperties(),
                generic,
                connections);
    }

    MemoryReviewInput input() {
        var time = LocalDateTime.of(2026, 9, 17, 0, 0);
        return new MemoryReviewInput(
                new MemorySnapshot(
                        new MemoryState(1, 0, true, true, true, 1, time), List.of(), Map.of()),
                List.of(
                        new MemoryEvidence(
                                "u",
                                MemoryEvidence.Kind.USER_MESSAGE,
                                "s",
                                "e",
                                1,
                                "user_admin",
                                1,
                                "[runtime_memory]正文",
                                time)),
                List.of(),
                50000);
    }
}
