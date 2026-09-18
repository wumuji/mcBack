package com.mcbackup.service;

import com.mcbackup.model.LocationKind;
import com.mcbackup.util.PathUtils;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 一个存档根目录。
 *
 * @param gameDir  游戏目录(例如 {@code E:\.minecraft} 或某个实例目录)
 * @param savesDir 实际存放世界的 saves 目录
 * @param kind     来源类型
 * @param label    界面显示用的来源说明
 */
public record WorldRoot(Path gameDir, Path savesDir, LocationKind kind, String label) {

    /** 去重用的 key(Windows 文件系统不区分大小写)。 */
    public String dedupeKey() {
        return savesDir.toString().toLowerCase(Locale.ROOT);
    }

    public String displayPath() {
        return PathUtils.toDisplayPath(savesDir);
    }
}
