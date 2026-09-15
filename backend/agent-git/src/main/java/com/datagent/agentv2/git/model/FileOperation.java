package com.datagent.agentv2.git.model;

/** 带乐观摘要的结构化文件操作；更新和删除必须携带调用方读取到的 SHA-256。 */
public record FileOperation(Type type, String path, String expectedSha256, String content) {
    public enum Type {
        CREATE,
        UPDATE,
        DELETE
    }

    public static FileOperation create(String path, String content) {
        return new FileOperation(Type.CREATE, path, null, content);
    }

    public static FileOperation update(String path, String expectedSha256, String content) {
        return new FileOperation(Type.UPDATE, path, expectedSha256, content);
    }

    public static FileOperation delete(String path, String expectedSha256) {
        return new FileOperation(Type.DELETE, path, expectedSha256, null);
    }
}
