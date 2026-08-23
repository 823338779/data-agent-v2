package com.datagent.agentv2.evals.compact;

import java.util.ArrayList;
import java.util.List;

/** 把长文本切成公开消息接口可接受的连续历史轮次。 */
final class CompactEvalTextSplitter {

    private static final int CHUNK_CONTENT_LIMIT = 7000;

    private CompactEvalTextSplitter() {}

    static List<String> split(String content, String label) {
        int chunkCount =
                Math.max(1, (content.length() + CHUNK_CONTENT_LIMIT - 1) / CHUNK_CONTENT_LIMIT);
        List<String> chunks = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int start = index * CHUNK_CONTENT_LIMIT;
            int end = Math.min(start + CHUNK_CONTENT_LIMIT, content.length());
            chunks.add(
                    label
                            + "分片 "
                            + (index + 1)
                            + "/"
                            + chunkCount
                            + "（请按顺序保留，只确认已读取，不要调用工具）：\n"
                            + content.substring(start, end));
        }
        return List.copyOf(chunks);
    }
}
