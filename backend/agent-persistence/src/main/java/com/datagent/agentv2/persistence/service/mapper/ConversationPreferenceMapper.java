package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.datagent.agentv2.persistence.entity.ConversationPreferenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationPreferenceMapper {

    /** 新用户首次访问会话能力时惰性建立排序偏好。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert(
            """
            insert into conversation_preference(tenant_id, user_id)
            values (#{tenantId}, #{userId})
            on conflict (tenant_id, user_id) do nothing
            """)
    int insertIfMissing(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /** 锁定当前用户偏好，使版本比较和完整重排位于同一事务。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select(
            "select * from conversation_preference where tenant_id = #{tenantId} and user_id = #{userId} for update")
    ConversationPreferenceEntity selectByUserIdForUpdate(
            @Param("tenantId") String tenantId, @Param("userId") String userId);

    /** 返回快照所需的当前 ordering version。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select(
            "select * from conversation_preference where tenant_id = #{tenantId} and user_id = #{userId}")
    ConversationPreferenceEntity selectByUserId(
            @Param("tenantId") String tenantId, @Param("userId") String userId);

    /** 成功改变完整用户顺序后单调递增业务版本。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update(
            """
            update conversation_preference
               set ordering_version = ordering_version + 1,
                   updated_at = current_timestamp, version = version + 1
             where tenant_id = #{tenantId} and user_id = #{userId}
               and ordering_version = #{expectedOrderingVersion}
            """)
    int incrementOrderingVersion(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("expectedOrderingVersion") long expectedOrderingVersion);
}
