package com.datagent.agentv2.platform.context;

public final class OperatorContext {

    /** 当前无鉴权运行模式使用的固定用户标识。 */
    public static final String USER_ID = "user_default";

    /** 当前无鉴权运行模式使用的固定用户显示名。 */
    public static final String SUBJECT = "local-user";

    private OperatorContext() {}

    public static String requiredUserId() {
        return USER_ID;
    }
}
