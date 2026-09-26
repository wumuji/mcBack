package com.mcback.model;

/** 主题选项。FOLLOW_SYSTEM 会读取 Windows 的浅色/深色设置。 */
public enum Theme {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    private final String displayName;

    Theme(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 解析配置文件中的值;无法识别时回退到跟随系统。 */
    public static Theme fromName(String name) {
        if (name != null) {
            for (Theme theme : values()) {
                if (theme.name().equalsIgnoreCase(name)) {
                    return theme;
                }
            }
        }
        return FOLLOW_SYSTEM;
    }
}
