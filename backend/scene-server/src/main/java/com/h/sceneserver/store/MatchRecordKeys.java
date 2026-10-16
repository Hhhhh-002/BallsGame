package com.h.sceneserver.store;

/** 从已有用户/比赛标识组成数据库主键；不是请求编号，不随机生成。 */
public final class MatchRecordKeys {
    private MatchRecordKeys() {}
    public static String match(String instance,long scene) {
        if(instance==null || instance.isBlank() || instance.length()>256 || scene==0)
            throw new IllegalArgumentException("必须指定 Scene 实例及比赛 ID");
        return instance+":"+Long.toUnsignedString(scene);
    }
    public static String prefix(String user) {
        if(user==null || user.isBlank() || user.codePointCount(0,user.length())>64)
            throw new IllegalArgumentException("用户 ID 不合法");
        return user.codePointCount(0,user.length())+":"+user+":";
    }
    public static String upper(String user) {String prefix=prefix(user);return prefix.substring(0,prefix.length()-1)+";";}
    public static String result(String user,String match) {
        if(match==null || match.isBlank() || match.length()>288)throw new IllegalArgumentException("比赛标识不合法");
        return prefix(user)+match;
    }
}
