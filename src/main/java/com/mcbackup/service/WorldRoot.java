package com.mcbackup.service;

import com.mcbackup.model.LocationKind;
import com.mcbackup.util.PathUtils;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 一个存档根目录。
 *
 * @param gameDir   游戏目录(例如 {@code E:\.minecraft} 或某个实例目录)
 * @param savesDir  实际存放世界的 saves 目录
 * @param kind      来源类型
 * @param label     界面显示用的来源说明
 * @param groupName 分组名(版本隔离 → 版本名;实例 → 实例名;官方 → 官方 .minecraft),
 *                  备份页的「版本 → 存档」两级就是按它分的
 */
public record WorldRoot(Path gameDir, Path savesDir, LocationKind kind, String label, String groupName) {

    /** 兼容旧调用:分组名按目录结构推断。 */
    public WorldRoot(Path gameDir, Path savesDir, LocationKind kind, String label) {
        this(gameDir, savesDir, kind, label, deriveGroupName(gameDir, savesDir, kind));
    }

    private static String deriveGroupName(Path gameDir, Path savesDir, LocationKind kind) {
        if (kind == LocationKind.VERSION_ISOLATED && savesDir != null && savesDir.getParent() != null) {
            return savesDir.getParent().getFileName().toString();
        }
        Path base = gameDir != null ? gameDir : savesDir;
        if (base == null) {
            return "其它";
        }
        Path name = base.getFileName();
        return name == null ? base.toString() : name.toString();
    }

    /** 去重用的 key(Windows 文件系统不区分大小写)。 */
    public String dedupeKey() {
        return savesDir.toString().toLowerCase(Locale.ROOT);
    }

    public String displayPath() {
        return PathUtils.toDisplayPath(savesDir);
    }
}
