package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.react.ModelToolCall;
import java.util.UUID;

public record PreparedToolInvocation(UUID processItemId, ModelToolCall call) {}
