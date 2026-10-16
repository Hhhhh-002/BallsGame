package com.h.gamecommon.code;

public enum LoginStatus {
    /**
     * 登录中
     */
    LOGINING(10, "登录中"),
    /**
     * 游戏中
     */
    PLAYING(20, "游戏中"),
    /**
     * 登出中
     */
    LOGOUTING(30, "登出中");

    private final int code;
    private final String desc;

    LoginStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据code反向查找枚举
     */
    public static LoginStatus getByCode(int code) {
        for (LoginStatus state : LoginStatus.values()) {
            if (state.getCode() == code) {
                return state;
            }
        }
        return null;
    }
}
