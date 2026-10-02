package com.datagent.agentv2.persistence.service.dbt;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.dbt.DbtValidationEntity;
import com.datagent.agentv2.persistence.mapper.dbt.DbtValidationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 短数据库操作建立尝试身份；调用方不能在事务内发 HTTP。 */
@Service
public class DbtValidationPersistenceService
        extends ServiceImpl<DbtValidationMapper, DbtValidationEntity> {
    private static final String FAILED = "FAILED", UNKNOWN = "UNKNOWN", SUCCEEDED = "SUCCEEDED";

    public DbtValidationEntity snapshot(String key) {
        return lambdaQuery().eq(DbtValidationEntity::getSnapshotKey, key).one();
    }

    public DbtValidationEntity find(String recordId) {
        return lambdaQuery().eq(DbtValidationEntity::getRecordId, recordId).one();
    }

    public DbtValidationEntity remote(String sessionId, String validationId) {
        return lambdaQuery()
                .eq(DbtValidationEntity::getConversationSessionId, sessionId)
                .eq(DbtValidationEntity::getValidationId, validationId)
                .one();
    }

    public DbtValidationEntity reserve(DbtValidationEntity candidate) {
        var row = snapshot(candidate.getSnapshotKey());
        if (row == null) {
            // 唯一约束解决跨实例竞争；插入独立事务失败后再读取胜者，不在已回滚事务中恢复。
            try {
                save(candidate);
            } catch (DuplicateKeyException race) {
                /* 同快照并发受理。 */
            }
            row = snapshot(candidate.getSnapshotKey());
        }
        if (row == null) throw new IllegalStateException("验证受理意图未保存");
        if (FAILED.equals(row.getState())) {
            lambdaUpdate()
                    .eq(DbtValidationEntity::getRecordId, row.getRecordId())
                    .eq(DbtValidationEntity::getAttempt, row.getAttempt())
                    .eq(DbtValidationEntity::getState, FAILED)
                    .set(DbtValidationEntity::getAttempt, row.getAttempt() + 1)
                    .set(DbtValidationEntity::getState, UNKNOWN)
                    .set(DbtValidationEntity::getValidationId, null)
                    .set(DbtValidationEntity::getResultJson, null)
                    .update(new DbtValidationEntity());
            row = snapshot(candidate.getSnapshotKey());
        }
        return row;
    }

    public void record(
            DbtValidationEntity attempt, String validationId, String state, String resultJson) {
        // 旧回调不得覆盖后续尝试，也不得把已完成的成功凭证退回排队状态。
        lambdaUpdate()
                .eq(DbtValidationEntity::getRecordId, attempt.getRecordId())
                .eq(DbtValidationEntity::getAttempt, attempt.getAttempt())
                .and(
                        condition ->
                                condition
                                        .notIn(DbtValidationEntity::getState, SUCCEEDED, FAILED)
                                        .or()
                                        .isNull(DbtValidationEntity::getResultJson))
                .set(DbtValidationEntity::getValidationId, validationId)
                .set(DbtValidationEntity::getState, state)
                .set(DbtValidationEntity::getResultJson, resultJson)
                .update(new DbtValidationEntity());
    }
}
