package com.datagent.agentv2.persistence.mapper.artifact;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.artifact.ConversationMessageArtifactEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMessageArtifactMapper
        extends BaseMapper<ConversationMessageArtifactEntity> {}
