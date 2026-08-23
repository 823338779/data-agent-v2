package com.datagent.agentv2.context.common.summary;

import com.datagent.agentv2.context.common.summary.EventContextSummaryDocument.Entry;
import com.datagent.agentv2.context.common.summary.EventContextSummaryDocument.Event;
import com.datagent.agentv2.context.common.summary.EventContextSummaryDocument.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 把完整审计文档确定性渲染为主模型使用的紧凑事件文本。 */
public final class EventContextSummaryRenderer {

    public String render(EventContextSummaryDocument document) {
        Objects.requireNonNull(document, "document");
        List<String> lines = new ArrayList<>();
        for (Event event : document.events()) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add(header(event));
            append(lines, "事实", event.confirmedFacts());
            append(lines, "偏好", event.userPreferences());
            append(lines, "假设", event.workingHypotheses());
            append(lines, "决策", event.decisions());
            append(lines, "排除", event.rejectedApproaches());
            append(lines, "工具", event.toolFindings());
            append(lines, "问题", event.openQuestions());
            append(lines, "下一步", event.nextSteps());
        }
        return String.join("\n", lines);
    }

    public String renderCatalog(EventContextSummaryDocument document) {
        Objects.requireNonNull(document, "document");
        return document.events().stream()
                .map(EventContextSummaryRenderer::header)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static String header(Event event) {
        return "[事件] " + normalize(event.title().text()) + "｜" + status(event.status().value());
    }

    private static void append(List<String> lines, String label, List<Entry> entries) {
        for (Entry entry : entries) {
            lines.add(label + "：" + normalize(entry.statement()));
        }
    }

    private static String normalize(String text) {
        String normalized = text.replaceAll("\\R+", " ").trim();
        return normalized.startsWith("[") ? "［" + normalized.substring(1) : normalized;
    }

    private static String status(Status status) {
        return switch (status) {
            case ACTIVE -> "进行中";
            case COMPLETED -> "已完成";
            case BLOCKED -> "受阻";
            case UNKNOWN -> "未知";
        };
    }
}
