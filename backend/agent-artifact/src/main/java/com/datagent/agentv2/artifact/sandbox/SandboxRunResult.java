package com.datagent.agentv2.artifact.sandbox;

import java.time.Duration;

/** 一次受控沙箱调用的安全结果摘要。 */
public record SandboxRunResult(boolean succeeded, String resultCode, Duration duration) {}
