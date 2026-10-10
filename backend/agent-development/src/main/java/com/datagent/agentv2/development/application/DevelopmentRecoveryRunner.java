package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.persistence.service.development.DevelopmentOperationPersistenceService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 独立于HTTP和模型执行的有界恢复扫描；每个动作失败不阻塞后续动作。 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public class DevelopmentRecoveryRunner {
    private static final String CONFLICT = "DEVELOPMENT_CONFLICT";
    private final DevelopmentOperations operations;
    private final DevelopmentOperationPersistenceService records;
    private final SourceSaveService saves;
    private final DevelopmentBranchService branches;
    private final ObjectProvider<PreviewBuildService> previews;
    private final ObjectProvider<DeliveryService> deliveries;

    public OperationView getOperation(String session, UUID id) {
        return operations.view(operations.owned(session, id));
    }

    public OperationView recover(String session, UUID id, RecoverRequest request) {
        var row = operations.owned(session, id);
        Phase phase =
                OperationType.DELIVER.name().equals(row.getType())
                        ? Phase.RELEASE_SUBMITTING
                        : Phase.BUILD_SUBMITTING;
        try {
            return operations.view(
                    records.recover(
                            id.toString(),
                            request.expectedVersion(),
                            request.idempotencyKey(),
                            phase.name()));
        } catch (IllegalStateException conflict) {
            throw new DevelopmentException(CONFLICT, conflict.getMessage(), 409);
        }
    }

    public void tick() {
        for (var row : records.pending()) {
            try {
                UUID id = UUID.fromString(row.getOperationId());
                switch (OperationType.valueOf(row.getType())) {
                    case SAVE -> saves.reconcile(id);
                    case CREATE_BRANCH -> branches.reconcile(id);
                    case PREVIEW -> {
                        var service = previews.getIfAvailable();
                        if (service != null) service.reconcile(id);
                    }
                    case DELIVER -> {
                        var service = deliveries.getIfAvailable();
                        if (service != null) service.reconcile(id);
                    }
                }
            } catch (RuntimeException failure) {
                // 不记录外部响应正文、仓库凭据或文件内容。
                log.warn(
                        "开发动作恢复未完成 operationId={} errorType={}",
                        row.getOperationId(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
