package com.datagent.agentv2.git.application;

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
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
