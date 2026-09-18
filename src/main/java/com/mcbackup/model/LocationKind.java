package com.mcbackup.model;

/** 存档目录来自哪里,用于在界面上标注来源,也用于区分「自动检测」与「用户手动添加」。 */
public enum LocationKind {
    OFFICIAL_DEFAULT("官方启动器"),
    VERSION_ISOLATED("版本隔离实例"),
    PRISM("Prism Launcher"),
    MULTIMC("MultiMC"),
    HMCL("HMCL"),
    MODRINTH("Modrinth App"),
    CURSEFORGE("CurseForge"),
    ATLAUNCHER("ATLauncher"),
    LABYMOD("LabyMod"),
    LAUNCHER_CONFIG("启动器配置"),
    MANUAL("手动添加"),
    UNKNOWN("未知来源");

    private final String displayName;

    LocationKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
