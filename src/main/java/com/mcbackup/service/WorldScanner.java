package com.mcbackup.service;

import com.mcbackup.model.LocationKind;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.model.ScanIssue;
import com.mcbackup.model.ScanProgress;
import com.mcbackup.model.ScanResult;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 世界扫描器。
 *
 * <p>调用方负责把它放到后台线程执行(界面里是单线程的扫描执行器),它本身不做线程调度。</p>
 *
 * <p>低占用要点:</p>
 * <ul>
 *   <li>只遍历已知的 saves 目录,不做全盘搜索;</li>
 *   <li>世界大小走 {@link WorldSizeCache},世界没变就不重新统计;</li>
 *   <li>变化判定只看廉价的 mtime 指纹,不逐文件比较。</li>
 * </ul>
 */
public final class WorldScanner {

    private final WorldRootProvider provider;
    private final WorldSizeCache sizeCache = new WorldSizeCache();

    public WorldScanner(WorldRootProvider provider) {
        this.provider = provider;
    }

    public WorldSizeCache sizeCache() {
        return sizeCache;
    }

    public ScanResult scan(List<Path> manualSavesDirs, Consumer<ScanProgress> progress) {
        return scan(manualSavesDirs, progress, false);
    }

    /**
     * 执行一次完整扫描。
     *
     * @param manualSavesDirs 用户手动添加的目录(可以是指向 saves 的目录,也可以直接是世界目录)
     * @param progress        进度回调(可能为 null)
     * @param forceSizeRecalc true 时忽略大小缓存
     */
    public ScanResult scan(List<Path> manualSavesDirs, Consumer<ScanProgress> progress, boolean forceSizeRecalc) {
        long started = System.currentTimeMillis();
        List<ScanIssue> issues = new ArrayList<>();
        List<MinecraftWorld> worlds = new ArrayList<>();
        Map<String, Integer> worldCountByRoot = new LinkedHashMap<>();

        List<WorldRoot> roots = collectRoots(manualSavesDirs, issues);
        Log.info("开始扫描 %d 个存档目录", roots.size());

        int index = 0;
        for (WorldRoot root : roots) {
            index++;
            report(progress, index - 1, roots.size(), "扫描 " + root.displayPath());
            if (!PathUtils.isDirectory(root.savesDir())) {
                issues.add(new ScanIssue("存档目录", root.displayPath(), "目录不存在或无法访问"));
                Log.warn("存档目录不可访问: %s", root.displayPath());
                continue;
            }
            List<MinecraftWorld> found = scanRoot(root, issues, forceSizeRecalc, progress, index, roots.size());
            worldCountByRoot.put(root.savesDir().toString(), found.size());
            worlds.addAll(found);
        }

        worlds.sort(Comparator.comparingLong(MinecraftWorld::lastModified).reversed()
                .thenComparing(MinecraftWorld::displayName, String.CASE_INSENSITIVE_ORDER));

        long elapsed = System.currentTimeMillis() - started;
        report(progress, roots.size(), roots.size(), "扫描完成");
        Log.info("扫描完成:发现 %d 个世界,耗时 %d ms,问题 %d 条,大小缓存命中 %d 次",
                worlds.size(), elapsed, issues.size(), sizeCache.hits());
        return new ScanResult(worlds, issues, roots, worldCountByRoot, elapsed);
    }

    /** 合并自动检测与手动添加的目录(按 saves 路径去重)。 */
    private List<WorldRoot> collectRoots(List<Path> manualSavesDirs, List<ScanIssue> issues) {
        Map<String, WorldRoot> roots = new LinkedHashMap<>();
        try {
            for (WorldRoot root : provider.discover()) {
                roots.putIfAbsent(root.dedupeKey(), root);
            }
        } catch (RuntimeException e) {
            issues.add(new ScanIssue("目录检测", "-", "自动检测启动器目录失败: " + e.getMessage()));
            Log.errorQuietly("自动检测启动器目录失败", e);
        }
        if (manualSavesDirs != null) {
            for (Path manual : manualSavesDirs) {
                if (manual == null) {
                    continue;
                }
                Path gameDir = manual.getParent() == null ? manual : manual.getParent();
                WorldRoot root = new WorldRoot(gameDir, manual, LocationKind.MANUAL, "手动添加");
                roots.putIfAbsent(root.dedupeKey(), root);
            }
        }
        return new ArrayList<>(roots.values());
    }

    /** 扫描单个存档根目录下的所有世界。 */
    private List<MinecraftWorld> scanRoot(WorldRoot root, List<ScanIssue> issues, boolean forceSizeRecalc,
                                          Consumer<ScanProgress> progress, int rootIndex, int rootTotal) {
        List<MinecraftWorld> result = new ArrayList<>();
        List<Path> candidates = new ArrayList<>();

        // 手动添加的目录允许直接指向一个世界,此时把目录本身当作候选
        if (root.kind() == LocationKind.MANUAL && WorldDetector.isWorld(root.savesDir())) {
            candidates.add(root.savesDir());
        } else {
            try (var stream = Files.newDirectoryStream(root.savesDir())) {
                for (Path child : stream) {
                    if (PathUtils.isDirectory(child)) {
                        candidates.add(child);
                    }
                }
            } catch (IOException | SecurityException e) {
                issues.add(new ScanIssue("存档目录", root.displayPath(), "读取目录失败: " + e.getMessage()));
                Log.errorQuietly("读取存档目录失败: " + root.displayPath(), e);
                return result;
            }
        }

        for (Path candidate : candidates) {
            String folderName = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
            WorldDetector.Detection detection = WorldDetector.detect(candidate);
            if (!detection.isWorld()) {
                if (detection.excludedReason() != null) {
                    Log.debug("跳过非世界目录 %s(%s)", candidate, detection.excludedReason());
                } else if (detection.hasLevelDat()) {
                    String message = "疑似世界结构不完整,仅命中 " + detection.markers()
                            + "/" + WorldDetector.REQUIRED_MARKERS + " 项特征";
                    issues.add(new ScanIssue("世界结构", PathUtils.toDisplayPath(candidate), message));
                    Log.warn("%s: %s", message, candidate);
                }
                continue;
            }
            report(progress, rootIndex, rootTotal, "读取世界 " + folderName);
            try {
                result.add(buildWorld(root, candidate, forceSizeRecalc));
            } catch (RuntimeException e) {
                issues.add(new ScanIssue("读取世界", PathUtils.toDisplayPath(candidate), e.getMessage()));
                Log.errorQuietly("读取世界失败: " + candidate, e);
            }
        }
        return result;
    }

    private MinecraftWorld buildWorld(WorldRoot root, Path worldDir, boolean forceSizeRecalc) {
        long stamp = FileUtils.worldChangeStamp(worldDir);
        long size = sizeCache.sizeOf(worldDir, stamp, forceSizeRecalc);
        var info = LevelInfoReader.read(worldDir.resolve("level.dat"));
        var lockProbe = FileUtils.probeSessionLock(worldDir);
        long lastModified = Math.max(stamp, FileUtils.lastModifiedMillis(worldDir));
        String folderName = worldDir.getFileName() == null ? "" : worldDir.getFileName().toString();
        return new MinecraftWorld(folderName, worldDir, root.savesDir(), root.kind(), root.label(),
                size, lastModified, stamp, info, lockProbe, null);
    }

    private static void report(Consumer<ScanProgress> progress, int scanned, int total, String label) {
        if (progress != null) {
            progress.accept(new ScanProgress(scanned, total, label));
        }
    }
}
