package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.model.GitException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 单实例锁注册表。提交固定先取仓库锁再取会话锁，编辑只取会话锁，避免锁顺序反转。 */
public final class GitWorkspaceLocks {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T workspace(String repository, String session, Supplier<T> action) {
        return locked("w:" + repository + ":" + session, action);
    }

    public <T> T repository(String repository, Supplier<T> action) {
        return locked("r:" + repository, action);
    }

    private <T> T locked(String key, Supplier<T> action) {
        var lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        try {
            // 工具取消通过线程中断传递；排队期间取消必须在业务回调前退出，不能延迟修改草稿。
            lock.lockInterruptibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GitException("GIT_INTERRUPTED", "等待 Git 工作区锁时被中断", failure);
        }
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new GitException("GIT_INTERRUPTED", "Git 操作已被中断");
            }
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
