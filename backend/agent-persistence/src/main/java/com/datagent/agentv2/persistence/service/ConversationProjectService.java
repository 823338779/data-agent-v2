package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.persistence.entity.ConversationProjectEntity;
import com.datagent.agentv2.persistence.mapping.ConversationProjectPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.ConversationProjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 项目持久化边界，调用方只使用领域模型；持锁调用必须位于应用事务中。 */
@Service
@RequiredArgsConstructor
public class ConversationProjectService
        extends ServiceImpl<ConversationProjectMapper, ConversationProjectEntity> {
    private final ConversationProjectPersistenceMapper models;

    public ConversationProject insertProject(ConversationProject project) {
        var entity = models.toEntity(project);
        save(entity);
        return models.toModel(entity);
    }

    public ConversationProject lockByProjectId(String projectId) {
        // 所有移入与删除先锁项目，避免逻辑删除后出现新的活动关联。
        return models.toModel(
                lambdaQuery()
                        .eq(ConversationProjectEntity::getProjectId, projectId)
                        .last("FOR UPDATE")
                        .one());
    }

    public List<ConversationProject> listByCreatedAtAsc() {
        return lambdaQuery()
                .orderByAsc(ConversationProjectEntity::getCreatedAt)
                .orderByAsc(ConversationProjectEntity::getProjectId)
                .list()
                .stream()
                .map(models::toModel)
                .toList();
    }

    public boolean renameProject(String projectId, String name, long expectedVersion) {
        return baseMapper.update(
                        ConversationProjectEntity.forUpdate(),
                        Wrappers.lambdaUpdate(ConversationProjectEntity.class)
                                .eq(ConversationProjectEntity::getProjectId, projectId)
                                .eq(ConversationProjectEntity::getVersion, expectedVersion)
                                .set(ConversationProjectEntity::getName, name)
                                .setIncrBy(ConversationProjectEntity::getVersion, 1))
                == 1;
    }

    public boolean deleteProject(String projectId, long expectedVersion) {
        return remove(
                Wrappers.lambdaQuery(ConversationProjectEntity.class)
                        .eq(ConversationProjectEntity::getProjectId, projectId)
                        .eq(ConversationProjectEntity::getVersion, expectedVersion));
    }
}
