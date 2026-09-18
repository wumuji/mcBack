package com.mcbackup.model;

/**
 * 从 level.dat 解析出的世界信息。
 *
 * <p>解析属于「增强功能」:失败时 {@link #available()} 为 false,界面显示「—」,
 * 绝不影响世界扫描本身。</p>
 */
public record WorldInfo(
        String levelName,
        String versionName,
        int dataVersion,
        String gameMode,
        String difficulty,
        boolean hardcore,
        long dayCount,
        boolean available) {

    public static final WorldInfo UNAVAILABLE = new WorldInfo("", "", 0, "", "", false, 0L, false);

    public static WorldInfo available(String levelName, String versionName, int dataVersion,
                                      String gameMode, String difficulty, boolean hardcore, long dayCount) {
        return new WorldInfo(levelName, versionName, dataVersion, gameMode, difficulty, hardcore, dayCount, true);
    }

    public boolean hasName() {
        return available && levelName != null && !levelName.isBlank();
    }
}
