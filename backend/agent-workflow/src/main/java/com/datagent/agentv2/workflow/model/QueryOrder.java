package com.datagent.agentv2.workflow.model;

/** 结构化排序项。 */
public record QueryOrder(String field, Direction direction) {
    public enum Direction {
        ASC,
        DESC
    }
}
