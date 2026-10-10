package com.datagent.agentv2.git.model;

/**
 * 带乐观摘要的结构化文件操作，所有路径限定在当前工作区内。
 *
 * @param type CREATE 新建、UPDATE 覆盖、DELETE 删除；决定其余字段的必填/空值约束
 * @param path 工作区相对路径，不允许越界、.git 或符号链接
 * @param expectedSha256 UPDATE/DELETE 必填，为读取完整文件得到的摘要；CREATE 必须为 null
 * @param content CREATE/UPDATE 的完整 UTF-8 文本，空字符串表示空文件；DELETE 必须为 null
 */
public record FileOperation(Type type, String path, String expectedSha256, String content) {
    /** HTTP与工具均不得通过嵌套文件参数覆盖目标或执行选项。 */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("文件操作包含未知字段");
    }

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
