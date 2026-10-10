package com.datagent.agentv2.development.model;

import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.application.DevelopmentOperations;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.git.model.BranchDiff;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 四组独立事实，不把本地修改、Git保存、构建和部署合并成一个成功状态。 */
public record DevelopmentOverview(
        /** 固定会话目标与当前配置。 */
        DevelopmentContext context,
        /** 当前节点可观察的本地草稿事实。 */
        Local local,
        /** 真实远端分支观察。 */
        Remote remote,
        /** 会话最近构建，可与当前部署不同。 */
        BuildView latestBuild,
        /** 此仓库分支的PREVIEW当前指针。 */
        DeploymentTargetView currentDeployment,
        /** 交付确认需要覆盖的完整范围。 */
        DeliveryScope deliveryScope,
        /** 会话持久动作历史。 */
        List<OperationView> operations) {
    /** revision 是编辑CAS；pushedSha 只有已经核实的Git提交。 */
    public record Local(
            /** 本地状态，不能替代远端保存事实。 */
            String state,
            /** 编辑CAS修订。 */
            long revision,
            /** 最近核实的远端源码。 */
            String pushedSha) {}

    /** observedAt 明确远端观察时间；不可读时sha可空且提供errorCode。 */
    public record Remote(
            /** 当前共享分支HEAD。 */
            String headSha,
            /** 当前main HEAD。 */
            String mainSha,
            /** 此次远端观察时间。 */
            Instant observedAt,
            /** 不可读时的稳定诊断。 */
            String errorCode) {}

    /** confirmedScopeDigest 同时覆盖完整共享差异、两端提交和环境配置。 */
    public record DeliveryScope(
            /** 确认的共享分支提交。 */
            String expectedSourceSha,
            /** 确认的main提交。 */
            String expectedMainSha,
            /** 目标、差异与环境配置的完整摘要。 */
            String confirmedScopeDigest,
            /** 共享分支完整Git差异。 */
            String diff) {
        public static DeliveryScope compose(DevelopmentContext context, BranchDiff branch) {
            // JSON编码保留边界，避免手工连接不明确分隔造成摘要碰撞。
            String material =
                    JsonMapper.builder()
                            .build()
                            .writeValueAsString(
                                    List.of(
                                            context.repository(),
                                            context.branchName(),
                                            context.configSnapshot(),
                                            branch));
            return new DeliveryScope(
                    branch.sourceSha(),
                    branch.mainSha(),
                    DevelopmentOperations.digest(material),
                    branch.diff());
        }
    }
}
