package com.datagent.agentv2.conversation.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CodexContextSummaryModelAdapterTest {

    private static final String MODEL_NAME = "codex-summary-model";
    private static final String SUMMARY_DOCUMENT = "{\"schemaVersion\":1}";
    private static final String SECRET_BODY = "provider-secret-body";

    @Test
    void usesNoToolsNoDeltaAndCarriesSchemaAndOutputLimit() {
        CodexAppServerClient client = mock(CodexAppServerClient.class);
        CodexModelProperties properties = new CodexModelProperties();
        properties.setModel(MODEL_NAME);
        when(client.complete(any(), eq(List.of()), any()))
                .thenReturn(new ModelInvocationResult.FinalAnswer(SUMMARY_DOCUMENT));
        ContextSummaryModelPort adapter = new CodexContextSummaryModelAdapter(client, properties);
        ContextSummaryRequest request =
                new ContextSummaryRequest(
                        "summary-system", "source-document", "{\"type\":\"object\"}", 2_048);

        ContextSummaryResult result = adapter.summarize(request);

        assertThat(result)
                .isEqualTo(
                        new ContextSummaryResult(
                                SUMMARY_DOCUMENT,
                                SUMMARY_DOCUMENT.codePointCount(0, SUMMARY_DOCUMENT.length()),
                                "codex",
                                MODEL_NAME));
        verify(client)
                .complete(
                        org.mockito.ArgumentMatchers.argThat(
                                prompt ->
                                        prompt.contains("summary-system")
                                                && prompt.contains("source-document")
                                                && prompt.contains("\"type\":\"object\"")
                                                && prompt.contains("2048")),
                        eq(List.of()),
                        any());
    }

    @Test
    void removesProviderBodyAndCauseFromStableFailure() {
        CodexAppServerClient client = mock(CodexAppServerClient.class);
        CodexModelProperties properties = new CodexModelProperties();
        when(client.complete(any(), eq(List.of()), any()))
                .thenThrow(new IllegalStateException(SECRET_BODY));
        ContextSummaryModelPort adapter = new CodexContextSummaryModelAdapter(client, properties);

        assertThatThrownBy(
                        () ->
                                adapter.summarize(
                                        new ContextSummaryRequest(
                                                "system", "source", "{\"type\":\"object\"}", 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Codex context summary request failed")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain(SECRET_BODY));
    }

    @Test
    void codexProviderRegistersTheSummaryPortRequiredByLsm() {
        new ApplicationContextRunner()
                .withPropertyValues("data-agent.model.provider=codex")
                .withBean(CodexModelProperties.class, CodexModelProperties::new)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withUserConfiguration(CodexSummaryConfiguration.class)
                .run(
                        context ->
                                assertThat(context)
                                        .hasSingleBean(ContextSummaryModelPort.class)
                                        .hasSingleBean(CodexContextSummaryModelAdapter.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CodexContextSummaryModelAdapter.class)
    static class CodexSummaryConfiguration {}
}
