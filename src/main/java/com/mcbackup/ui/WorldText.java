package com.mcbackup.ui;

import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.util.FileUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界信息的文字化工具。
 *
 * <p>所有「解析失败」的字段统一显示为「—」,让「世界是好的但信息读不出来」这件事在界面上是可见的,
 * 而不是显示空白或缺省值。</p>
 */
public final class WorldText {

    /** 未知值占位符。 */
    public static final String UNKNOWN = "—";

    private WorldText() {
    }

    private static String orUnknown(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }

    /** 列表行副标题:版本 · 模式 · 大小 · 最后修改。 */
    public static String listSubtitle(MinecraftWorld world) {
        List<String> parts = new ArrayList<>();
        if (world.info().available()) {
            parts.add(orUnknown(world.info().versionName()));
            parts.add(orUnknown(world.info().gameMode()));
        } else {
            parts.add("信息不可读");
        }
        parts.add(FileUtils.humanSize(world.sizeBytes()));
        parts.add(FileUtils.relativeTime(world.lastModified()));
        return String.join("  ·  ", parts);
    }

    /** 详情面板中的字段列表(label, value)。 */
    public static List<String[]> detailFields(MinecraftWorld world) {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"文件夹名", orUnknown(world.folderName())});
        fields.add(new String[]{"来源目录", world.sourceLabel()});
        fields.add(new String[]{"游戏版本", world.info().available() ? orUnknown(world.info().versionName()) : UNKNOWN});
        String mode = world.info().available() ? orUnknown(world.info().gameMode()) : UNKNOWN;
        if (world.info().hardcore()) {
            mode = mode + " · 极限";
        }
        fields.add(new String[]{"游戏模式", mode});
        fields.add(new String[]{"难度", world.info().available() ? orUnknown(world.info().difficulty()) : UNKNOWN});
        fields.add(new String[]{"世界天数",
                (world.info().available() && world.info().dayCount() > 0) ? ("第 " + world.info().dayCount() + " 天") : UNKNOWN});
        fields.add(new String[]{"世界大小", FileUtils.humanSize(world.sizeBytes())});
        fields.add(new String[]{"最后修改", FileUtils.absoluteTime(world.lastModified())});
        fields.add(new String[]{"运行状态", world.runningText()});
        fields.add(new String[]{"最近备份", UNKNOWN});
        fields.add(new String[]{"备份状态", UNKNOWN});
        return fields;
    }
}
