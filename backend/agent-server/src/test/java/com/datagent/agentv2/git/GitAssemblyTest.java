package com.datagent.agentv2.git;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.development.application.DevelopmentRecoveryRunner;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.persistence.service.git.*;
import com.datagent.agentv2.server.development.DevelopmentRuntimeConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 迁移原运行时装配保障：延迟初始化、工具白名单、独立恢复与安全错误。 */
class GitAssemblyTest {
    @TempDir Path temp;

    @Configuration(proxyBeanMethods = false)
    @Import({
        com.datagent.agentv2.git.config.GitConfiguration.class,
        DevelopmentRuntimeConfiguration.class
    })
    static class Scan {}

    final DevelopmentRecoveryRunner recovery = mock(DevelopmentRecoveryRunner.class);

    ApplicationContextRunner runner(boolean enabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Scan.class)
                .withPropertyValues(
                        "data-agent.git.enabled=" + enabled,
                        "data-agent.git.workspace-root=" + temp.resolve("workspaces"),
                        "data-agent.development.recovery-delay-ms=60000")
                .withInitializer(
                        ctx ->
                                ctx.getBeanFactory()
                                        .registerSingleton(
                                                "GitWorkspacePersistenceService",
                                                mock(GitWorkspacePersistenceService.class)))
                .withInitializer(
                        ctx ->
                                ctx.getBeanFactory()
                                        .registerSingleton(
                                                "GitChangePersistenceService",
                                                mock(GitChangePersistenceService.class)))
                .withInitializer(
                        ctx ->
                                ctx.getBeanFactory()
                                        .registerSingleton(
                                                "GitSessionWorkspaceService",
                                                mock(GitSessionWorkspaceService.class)))
                .withBean(DevelopmentRecoveryRunner.class, () -> recovery)
                .withBean(
                        ToolPolicyProperties.class,
                        () ->
                                new ToolPolicyProperties(
                                        List.of("current_time"), List.of("current_time")));
    }

    @Test
    void gitAndDbtAllowancesComposeWithoutChangingInitialTools() {
        runner(true)
                .withUserConfiguration(
                        com.datagent.agentv2.server.dbt.DbtRuntimeConfiguration.class)
                .withPropertyValues("data-agent.dbt.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            var policy = ctx.getBean(ToolPolicyProperties.class);
                            assertThat(policy.maximum())
                                    .contains(
                                            "git_files_apply",
                                            "development_save",
                                            "development_preview",
                                            "development_deliver",
                                            "development_status",
                                            "dbt_build_get",
                                            "dbt_query_submit")
                                    .doesNotContain(
                                            "git_change_prepare",
                                            "git_workspace_prepare",
                                            "dbt_validation_submit");
                            assertThat(policy.initial()).containsExactly("current_time");
                        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void backgroundRecoveryFailureIsObservableWithoutExceptionDetails(CapturedOutput output) {
        doThrow(new IllegalStateException("secret-token /private/path stderr"))
                .when(recovery)
                .tick();
        runner(true)
                .run(
                        ctx -> {
                            ctx.getBean(DevelopmentRuntimeConfiguration.class).recover();
                            assertThat(output.getAll())
                                    .contains("开发操作恢复扫描暂未完成")
                                    .doesNotContain("secret-token", "/private/path", "stderr");
                        });
    }

    @Test
    void startupReturnsWhileRecoveryRemainsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            release.await();
                            return null;
                        })
                .when(recovery)
                .tick();
        runner(true)
                .run(
                        ctx -> {
                            try {
                                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                                assertThat(ctx).hasNotFailed();
                                assertThat(release.getCount()).isOne();
                            } finally {
                                release.countDown();
                            }
                        });
    }

    @Test
    void enabledAssemblyAddsLazyToolsAndClosesAsyncWorker() {
        ThreadPoolTaskScheduler[] captured = new ThreadPoolTaskScheduler[1];
        runner(true)
                .run(
                        ctx -> {
                            assertThat(ctx)
                                    .hasNotFailed()
                                    .hasSingleBean(GitWorkspaceService.class)
                                    .doesNotHaveBean(GitSubmissionService.class)
                                    .doesNotHaveBean(GitRecoveryRunner.class);
                            assertThat(ctx.getBean(ToolPolicyProperties.class).maximum())
                                    .contains("development_save");
                            captured[0] = ctx.getBean(ThreadPoolTaskScheduler.class);
                            long caller = Thread.currentThread().threadId();
                            assertThat(
                                            captured[0]
                                                    .submit(() -> Thread.currentThread().threadId())
                                                    .get(5, TimeUnit.SECONDS))
                                    .isNotEqualTo(caller);
                            assertThat(temp.resolve("workspaces")).doesNotExist();
                        });
        assertThat(captured[0].getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }

    @Test
    void disabledAssemblyCreatesNoWorkspaceOrAllowance() {
        runner(false)
                .run(
                        ctx -> {
                            assertThat(ctx)
                                    .hasNotFailed()
                                    .doesNotHaveBean(GitWorkspaceService.class)
                                    .doesNotHaveBean(ThreadPoolTaskScheduler.class);
                            assertThat(ctx.getBean(ToolPolicyProperties.class).maximum())
                                    .containsExactly("current_time");
                            assertThat(temp.resolve("workspaces")).doesNotExist();
                        });
    }

    @Test
    void initialCannotBypassSkillActivation() {
        new ApplicationContextRunner()
                .withUserConfiguration(DevelopmentRuntimeConfiguration.class)
                .withPropertyValues("data-agent.git.enabled=true")
                .withBean(DevelopmentRecoveryRunner.class, () -> recovery)
                .withBean(
                        ToolPolicyProperties.class,
                        () ->
                                new ToolPolicyProperties(
                                        List.of("development_save"), List.of("development_save")))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
