package com.datagent.agentv2.workflow.model;

import java.time.Instant;

/** 半开时间区间；结束时刻不包含在查询范围内。 */
public record QueryTimeRange(String field, Instant startInclusive, Instant endExclusive) {}
