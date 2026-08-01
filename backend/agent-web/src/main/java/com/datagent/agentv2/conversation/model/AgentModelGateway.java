package com.datagent.agentv2.conversation.model;

import com.datagent.agentv2.conversation.react.ModelInputMessage;
import java.util.List;
import java.util.function.Consumer;

public interface AgentModelGateway {

    ModelTurnResult complete(
            List<ModelInputMessage> messages,
            List<ModelToolDefinition> tools,
            Consumer<ModelDelta> deltaConsumer);
}
