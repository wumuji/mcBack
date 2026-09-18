package com.mcbackup.model;

import com.mcbackup.service.WorldRoot;

import java.util.List;
import java.util.Map;

/**
 * 一次扫描的完整结果。
 *
 * @param worlds          识别出的世界(按最后修改时间倒序)
 * @param issues          扫描过程中的问题
 * @param roots           本次参与扫描的存档目录
 * @param worldCountByRoot 各 saves 目录识别出的世界数量(key 为 savesDir 字符串)
 * @param elapsedMillis   扫描耗时
 */
public record ScanResult(
        List<MinecraftWorld> worlds,
        List<ScanIssue> issues,
        List<WorldRoot> roots,
        Map<String, Integer> worldCountByRoot,
        long elapsedMillis) {

    public static ScanResult empty() {
        return new ScanResult(List.of(), List.of(), List.of(), Map.of(), 0L);
    }

    public int rootCount() {
        return roots.size();
    }
}
