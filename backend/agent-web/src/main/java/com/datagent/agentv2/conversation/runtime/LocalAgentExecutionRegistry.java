package com.datagent.agentv2.conversation.runtime;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import org.springframework.stereotype.Component;

/**
 * 保存当前进程正在运行的任务，负责按 executionId 去重和中断。
 *
 * <p>注册表只表达当前 JVM 的瞬时状态，不是数据库事实来源，也不提供跨进程协调。任务结束时使用条件删除，避免旧任务的回调误删同标识的新任务。
 */
@Component
public final class LocalAgentExecutionRegistry implements AutoCloseable {

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, Future<?>> active = new ConcurrentHashMap<>();

    public boolean start(String executionId, Runnable action) {
        // 先把 FutureTask 放入 map 再提交，确保极短任务也能由 done() 完成可靠清理。
        FutureTask<Void> task =
                new FutureTask<>(action, null) {
                    @Override
                    protected void done() {
                        active.remove(executionId, this);
                    }
                };
        if (active.putIfAbsent(executionId, task) != null) {
            // 重复的 AFTER_COMMIT 事件不能启动第二个相同执行。
            return false;
        }
        workers.execute(task);
        return true;
    }

    public boolean cancel(String executionId) {
        Future<?> future = active.remove(executionId);
        // cancel(true) 通过线程中断唤醒阻塞调用；业务循环仍需用 LocalExecutionGuard 检查数据库状态。
        return future != null && future.cancel(true);
    }

    @Override
    @PreDestroy
    public void close() {
        // 应用停机时先发出中断再关闭执行器；数据库中的 running 记录会在下次启动时失败收敛。
        active.values().forEach(task -> task.cancel(true));
        active.clear();
        workers.shutdownNow();
    }
}
