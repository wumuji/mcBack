package com.mcbackup.model;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 一个被识别出来的 Minecraft 世界。
 *
 * @param folderName       世界文件夹名(saves 下的目录名)
 * @param worldDir         世界目录绝对路径
 * @param savesDir         所属 saves 目录
 * @param kind             来源类型
 * @param sourceLabel      来源说明,例如「版本隔离实例 · 1.21.1-机械动力影院」
 * @param groupName        分组名(版本/实例名),备份页按它做「版本 → 存档」两级展示
 * @param sizeBytes        世界目录总大小
 * @param lastModified     世界最后修改时间(毫秒)
 * @param changeStamp      便宜的变化指纹(level.dat/region/顶层条目的最大 mtime)
 * @param info             level.dat 解析结果,可能不可用
 * @param lockProbe        session.lock 探测结果
 * @param issue            识别该世界时发现的问题(null 表示无)
 */
public record MinecraftWorld(
        String folderName,
        Path worldDir,
        Path savesDir,
        LocationKind kind,
        String sourceLabel,
        String groupName,
        long sizeBytes,
        long lastModified,
        long changeStamp,
        WorldInfo info,
        com.mcbackup.util.FileUtils.LockProbe lockProbe,
        ScanIssue issue) {

    /** 兼容旧调用:分组名按 saves 的父目录推断(版本隔离目录、实例目录都适用)。 */
    public MinecraftWorld(String folderName, Path worldDir, Path savesDir, LocationKind kind,
                          String sourceLabel, long sizeBytes, long lastModified, long changeStamp,
                          WorldInfo info, com.mcbackup.util.FileUtils.LockProbe lockProbe, ScanIssue issue) {
        this(folderName, worldDir, savesDir, kind, sourceLabel,
                deriveGroupName(savesDir, sourceLabel), sizeBytes, lastModified, changeStamp,
                info, lockProbe, issue);
    }

    private static String deriveGroupName(Path savesDir, String sourceLabel) {
        if (savesDir != null && savesDir.getParent() != null
                && savesDir.getParent().getFileName() != null) {
            return savesDir.getParent().getFileName().toString();
        }
        return sourceLabel == null || sourceLabel.isBlank() ? "其它" : sourceLabel;
    }

    /** 显示名:优先 level.dat 里的世界名,失败时回退到文件夹名。 */
    public String displayName() {
        return info != null && info.hasName() ? info.levelName() : folderName;
    }

    /** 是否可能正在被 Minecraft 使用(启发式,非权威)。 */
    public boolean possiblyRunning() {
        return lockProbe == com.mcbackup.util.FileUtils.LockProbe.LOCKED;
    }

    /** 运行状态文案。 */
    public String runningText() {
        return switch (lockProbe) {
            case LOCKED -> "可能运行中";
            case FREE -> "未运行";
            case MISSING, UNKNOWN -> "未知";
        };
    }

    /** 用于排序/查找的稳定 key。 */
    public String key() {
        return worldDir.toString().toLowerCase(Locale.ROOT);
    }
}
