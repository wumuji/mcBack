package com.mcbackup.service;

import com.mcbackup.util.PathUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判断一个目录是不是 Minecraft 世界。
 *
 * <p>按要求「不能只看文件夹名字」,必须同时满足:</p>
 * <ol>
 *   <li>存在 {@code level.dat} 文件;</li>
 *   <li>{@link #MARKERS} 中至少命中 {@link #REQUIRED_MARKERS} 项典型结构。</li>
 * </ol>
 *
 * <p>同时排除恢复备份目录与临时目录,避免把程序自己产生的中间产物当成世界。</p>
 */
public final class WorldDetector {

    /** Minecraft 世界的典型结构(不同版本/模组会有差异,取并集)。 */
    public static final List<String> MARKERS = List.of(
            "region", "playerdata", "advancements", "data", "poi",
            "entities", "dimensions", "DIM1", "DIM-1");

    /** 至少命中的结构数量。 */
    public static final int REQUIRED_MARKERS = 2;

    /** 目录名里出现这些片段说明它不该被当成普通世界。 */
    private static final List<String> EXCLUDED_FRAGMENTS = List.of(
            ".restore-backup-", ".backup-", ".tmp", ".partial");

    private WorldDetector() {
    }

    /**
     * 探测结果。
     *
     * @param isWorld        是否认定为世界
     * @param hasLevelDat    是否存在 level.dat
     * @param markers        命中的结构数量
     * @param matchedMarkers 命中的结构名
     * @param excludedReason 被排除的原因(为空表示不是明确排除,只是缺少特征)
     */
    public record Detection(boolean isWorld, boolean hasLevelDat, int markers,
                            List<String> matchedMarkers, String excludedReason) {

        public static Detection notAWorld(String reason) {
            return new Detection(false, false, 0, List.of(), reason);
        }
    }

    /** 判断目录名是否属于需要排除的备份/临时目录;返回 null 表示不排除。 */
    public static String exclusionReason(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return "名称为空";
        }
        String lower = folderName.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")) {
            return "隐藏目录";
        }
        for (String fragment : EXCLUDED_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return "备份/临时目录";
            }
        }
        if (lower.endsWith(".bak") || lower.endsWith(".zip") || lower.endsWith(".old")) {
            return "备份/临时目录";
        }
        return null;
    }

    /** 完整探测。 */
    public static Detection detect(Path dir) {
        if (dir == null) {
            return Detection.notAWorld("路径为空");
        }
        Path fileName = dir.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        String exclusion = exclusionReason(name);
        if (exclusion != null) {
            return new Detection(false, false, 0, List.of(), exclusion);
        }
        boolean hasLevelDat = PathUtils.isFile(dir.resolve("level.dat"));
        if (!hasLevelDat) {
            return new Detection(false, false, 0, List.of(), null);
        }
        List<String> matched = new ArrayList<>();
        for (String marker : MARKERS) {
            if (PathUtils.isDirectory(dir.resolve(marker)) || PathUtils.isFile(dir.resolve(marker))) {
                matched.add(marker);
            }
        }
        boolean isWorld = matched.size() >= REQUIRED_MARKERS;
        return new Detection(isWorld, true, matched.size(), matched, null);
    }

    public static boolean isWorld(Path dir) {
        return detect(dir).isWorld();
    }
}
