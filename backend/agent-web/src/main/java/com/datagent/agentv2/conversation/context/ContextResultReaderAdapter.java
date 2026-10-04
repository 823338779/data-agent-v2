package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.context.hierarchical.persistence.repository.ContextPayloadRepository;
import com.datagent.agentv2.conversation.tool.context.ContextResultReader;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 先验证当前执行的会话可见性，再按字节预算读取安全原文。 */
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public final class ContextResultReaderAdapter implements ContextResultReader {
    private static final String LOG_READ = "Context result read start={} end={} total={} budget={}";
    private final ContextPayloadRepository payloads;
    private final AgentExecutionService executions;
    private final ConversationSessionService sessions;
    private final ContextModelBudgetProvider budgets;

    /** 读取页的配置上限，单位为 UTF-8 字节。 */
    private final int maxPageBytes;

    @Override
    public Page read(String executionId, String reference, long offsetBytes, int limitBytes) {
        if (offsetBytes < 0 || limitBytes <= 0 || maxPageBytes <= 0) {
            throw new IllegalArgumentException("Invalid context result byte range");
        }
        var execution = executions.findByExecutionId(executionId);
        if (execution == null
                || sessions.findByUuid(execution.getConversationSessionId()) == null) {
            throw new IllegalArgumentException("Context result is unavailable");
        }
        var payload = payloads.findVisible(execution.getConversationSessionId(), reference);
        if (payload == null) {
            throw new IllegalArgumentException("Context result is unavailable");
        }
        byte[] bytes = payload.getContent().getBytes(StandardCharsets.UTF_8);
        if (offsetBytes > bytes.length) {
            throw new IllegalArgumentException("Context result offset exceeds length");
        }
        int start = (int) offsetBytes;
        while (start < bytes.length && continuation(bytes[start])) {
            start++;
        }
        int budget = Math.min(4096, budgets.currentBudget().hardInputLimitTokens() / 10);
        int length = Math.min(Math.min(limitBytes, maxPageBytes), budget);
        int end = start + Math.min(length, bytes.length - start);
        // 页尾向前退到完整字符，序列化元数据和 JSON 转义也必须计入模型投影预算。
        while (true) {
            while (end > start && end < bytes.length && continuation(bytes[end])) {
                end--;
            }
            if (end == start && start < bytes.length) {
                throw new IllegalArgumentException(
                        "Context result budget cannot fit a complete character");
            }
            var page =
                    new Page(
                            reference,
                            new String(bytes, start, end - start, StandardCharsets.UTF_8),
                            start,
                            end,
                            end == bytes.length ? null : (long) end,
                            bytes.length,
                            payload.getSha256());
            if (JsonMapper.builder().build().writeValueAsBytes(page).length <= budget) {
                log.debug(LOG_READ, start, end, bytes.length, budget);
                return page;
            }
            if (end == start) {
                throw new IllegalArgumentException("Context result metadata exceeds budget");
            }
            end--;
        }
    }

    private static boolean continuation(byte value) {
        return (value & 0xc0) == 0x80;
    }
}
