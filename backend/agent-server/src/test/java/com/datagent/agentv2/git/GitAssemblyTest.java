package com.datagent.agentv2.git;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.port.GitChangeCardPublisher;
import com.datagent.agentv2.persistence.service.git.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

class GitAssemblyTest {
    @TempDir Path temp;

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = {"com.datagent.agentv2.git.config", "com.datagent.agentv2.server.git"})
    static class Scan {}

    ApplicationContextRunner runner(boolean enabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Scan.class)
                .withPropertyValues(
                        "data-agent.git.enabled=" + enabled,
                        "data-agent.git.remote-uri=unused",
                        "data-agent.git.workspace-root=" + temp.resolve("workspaces"))
                .withInitializer(
                        ctx -> {
                            ctx.getBeanFactory()
                                    .registerSingleton(
                                            "workspaceRecords",
                                            mock(GitWorkspacePersistenceService.class));
                            ctx.getBeanFactory()
                                    .registerSingleton(
                                            "changeRecords",
                                            mock(GitChangePersistenceService.class));
                        })
                .withBean(GitChangeCardPublisher.class, () -> mock(GitChangeCardPublisher.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(
                        ToolPolicyProperties.class,
                        () ->
                                new ToolPolicyProperties(
                                        List.of("current_time"), List.of("current_time")));
    }

    @Test
    void enabledAssemblyAddsLazyToolsAndClosesAsyncWorker() {
        ExecutorService[] captured = new ExecutorService[1];
        runner(true)
                .run(
                        ctx -> {
                            assertThat(ctx)
                                    .hasNotFailed()
                                    .hasSingleBean(GitWorkspaceService.class)
                                    .hasSingleBean(GitSubmissionService.class)
                                    .hasSingleBean(GitRecoveryRunner.class);
                            var policy = ctx.getBean(ToolPolicyProperties.class);
                            assertThat(policy.maximum()).contains("git_change_prepare");
                            assertThat(policy.initial()).containsExactly("current_time");
                            captured[0] = (ExecutorService) ctx.getBean("gitSubmissionExecutor");
                            long callingThread = Thread.currentThread().threadId();
                            assertThat(
                                            captured[0]
                                                    .submit(() -> Thread.currentThread().threadId())
                                                    .get(5, TimeUnit.SECONDS))
                                    .isNotEqualTo(callingThread);
                            assertThat(temp.resolve("workspaces")).doesNotExist();
                        });
        assertThat(captured[0].isShutdown()).isTrue();
    }

    @Test
    void disabledAssemblyCreatesNoWorkspaceServicesOrToolAllowance() {
        runner(false)
                .run(
                        ctx -> {
                            assertThat(ctx)
                                    .hasNotFailed()
                                    .doesNotHaveBean(GitWorkspaceService.class)
                                    .doesNotHaveBean(GitSubmissionService.class);
                            assertThat(ctx.getBean(ToolPolicyProperties.class).maximum())
                                    .containsExactly("current_time");
                            assertThat(temp.resolve("workspaces")).doesNotExist();
                        });
    }

    @Test
    void enabledWithoutRemoteFailsBeforeServingRequests() {
        runner(true)
                .withPropertyValues("data-agent.git.remote-uri=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
