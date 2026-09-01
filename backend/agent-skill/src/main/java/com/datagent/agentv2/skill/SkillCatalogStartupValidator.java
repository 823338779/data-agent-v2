package com.datagent.agentv2.skill;

import com.datagent.agentv2.conversation.tool.supply.ToolSupplyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** 在所有单例创建后校验 Skill 声明的 Tool，避免目录与 Tool 供给互相构造依赖。 */
@Component
@RequiredArgsConstructor
public final class SkillCatalogStartupValidator implements SmartInitializingSingleton {
    private final BundledSkillCatalog catalog;
    private final ToolSupplyRegistry tools;

    @Override
    public void afterSingletonsInstantiated() {
        catalog.bundlesInOrder()
                .forEach(bundle -> bundle.descriptor().requiredTools().forEach(tools::require));
    }
}
