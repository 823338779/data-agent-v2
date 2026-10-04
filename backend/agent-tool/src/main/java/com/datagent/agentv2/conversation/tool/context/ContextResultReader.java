package com.datagent.agentv2.conversation.tool.context;

/** 安全载荷只读端口；持久化适配器位于应用层，工具模块不依赖数据库。 */
public interface ContextResultReader {
    Page read(String executionId, String reference, long offsetBytes, int limitBytes);

    /** 实际 UTF-8 范围和内容，不把任意用户参数当作可信资源身份。 */
    record Page(
            /** 完整脱敏原文的不透明引用。 */
            String reference,
            /** 本页内容，UTF-8 字符完整。 */
            String content,
            /** 实际左闭字节位置。 */
            long startOffsetBytes,
            /** 实际右开字节位置。 */
            long endOffsetBytes,
            /** 下一页位置，到末尾时为空。 */
            Long nextOffsetBytes,
            /** 完整原文总字节数。 */
            long totalBytes,
            /** 完整原文校验值。 */
            String sha256) {}
}
