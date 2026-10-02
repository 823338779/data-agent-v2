package com.datagent.agentv2.server.git;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.tool.*;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 服务级异步提交和一次启动恢复；生命周期由 server 关闭，不查询指标平台。 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public class GitRuntimeConfiguration {
    // 关闭时中断子进程并停止排队，遗留确认记录由下次启动的只读恢复收敛。
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService gitSubmissionExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("git-submission-", 0).factory());
    }

    @Bean
    GitSubmissionService gitSubmissionService(
            GitWorkspaceService w,
            GitChangeService c,
            GitChangePersistenceService records,
            GitRepositoryClient git,
            @Qualifier("gitSubmissionExecutor") ExecutorService executor,
            Clock clock) {
        return new GitSubmissionService(w, c, records, git, executor, clock);
    }

    @Bean
    GitRecoveryRunner gitRecoveryRunner(
            GitChangePersistenceService records, GitSubmissionService submissions) {
        return new GitRecoveryRunner(records, submissions);
    }

    @Bean
    ApplicationRunner gitStartupRecovery(
            GitRecoveryRunner recovery,
            @Qualifier("gitSubmissionExecutor") ExecutorService executor) {
        // 就绪前固定遗留 ID；只有短数据库查询在启动线程执行，远端核实仍不阻塞 readiness。
        return args -> {
            Runnable recoveryTask;
            try {
                recoveryTask = recovery.captureRecovery();
            } catch (RuntimeException failure) {
                // 捕获失败同样只记录固定说明，不把数据库异常详情带入日志或阻塞非 Git 能力。
                log.error("Git 启动恢复失败，请通过卡片核实遗留提交");
                return;
            }
            executor.execute(
                    () -> {
                        try {
                            recoveryTask.run();
                        } catch (RuntimeException failure) {
                            // 异步失败必须可观察；异常消息和堆栈可能含远端凭据、路径或 stderr，不能直接记录。
                            log.error("Git 启动恢复失败，请通过卡片核实遗留提交");
                        }
                    });
        };
    }

    /** 能力开关同时扩展最大集合，初始集合仍不暴露 Git；其他未知配置继续由原有严格校验拒绝。 */
    @Bean
    static BeanPostProcessor gitToolAllowance() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof ToolPolicyProperties p)) {
                    return bean;
                }
                List<String> gitTools =
                        List.of(
                                GitWorkspacePrepareTool.TOOL_ID,
                                GitFilesListTool.TOOL_ID,
                                GitFilesSearchTool.TOOL_ID,
                                GitFileReadTool.TOOL_ID,
                                GitFilesApplyTool.TOOL_ID,
                                GitDiffTool.TOOL_ID,
                                GitChangePrepareTool.TOOL_ID);
                if (p.initial().stream().anyMatch(gitTools::contains)) {
                    throw new IllegalStateException("Git 工具必须通过 Skill 读取激活，不能进入 initial");
                }
                List<String> maximum = new ArrayList<>(p.maximum());
                for (String tool : gitTools) {
                    if (!maximum.contains(tool)) {
                        maximum.add(tool);
                    }
                }
                return new ToolPolicyProperties(maximum, p.initial());
            }
        };
    }
}
