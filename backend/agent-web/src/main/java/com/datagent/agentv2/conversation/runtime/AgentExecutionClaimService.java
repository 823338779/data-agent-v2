package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_EXECUTION_ALREADY_OWNED_PREFIX;

import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public final class AgentExecutionClaimService {

    private static final int USER_SCAN_LIMIT = 256;

    private final AgentExecutionService agentExecutions;
    private final AgentExecutionClaimTransactionService claimTransactions;
    private final ExecutionWorkerCapacity capacity;
    private final ExecutionOwnerRegistry owners;
    private final AgentExecutionPort execution;
    private final ExecutionWorkerIdentity identity;

    public AgentExecutionClaimService(
            AgentExecutionService agentExecutions,
            AgentExecutionClaimTransactionService claimTransactions,
            ExecutionWorkerCapacity capacity,
            ExecutionOwnerRegistry owners,
            AgentExecutionPort execution,
            ExecutionWorkerIdentity identity) {
        this.agentExecutions = agentExecutions;
        this.claimTransactions = claimTransactions;
        this.capacity = capacity;
        this.owners = owners;
        this.execution = execution;
        this.identity = identity;
    }

    public boolean claimNext() {
        for (String userId : agentExecutions.queuedOwnerUserIds(USER_SCAN_LIMIT)) {
            Optional<ExecutionWorkerCapacity.Permit> acquired = capacity.tryAcquire();
            if (acquired.isEmpty()) {
                return false;
            }
            ExecutionWorkerCapacity.Permit permit = acquired.orElseThrow();
            AgentExecutionPort.ClaimedAgentExecution claimed;
            try {
                claimed = claimTransactions.claimForUser(userId);
            } catch (RuntimeException failure) {
                permit.close();
                throw failure;
            }
            if (claimed == null) {
                permit.close();
                continue;
            }
            if (!owners.register(claimed, permit)) {
                throw new IllegalStateException(
                        ERROR_EXECUTION_ALREADY_OWNED_PREFIX + claimed.executionId());
            }
            try {
                execution.start(claimed, () -> owners.release(claimed.executionId()));
            } catch (RuntimeException failure) {
                owners.release(claimed.executionId());
                throw failure;
            }
            return true;
        }
        return false;
    }

    public String instanceId() {
        return identity.workerInstanceId();
    }
}
