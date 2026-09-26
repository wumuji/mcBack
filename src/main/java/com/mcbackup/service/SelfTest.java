package com.mcbackup.service;

import com.mcbackup.model.BackupOptions;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.BackupResult;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Hashing;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;
import com.mcbackup.util.ProgressListener;
import com.mcbackup.util.WorldCopier;
import com.mcbackup.util.ZipUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自带自检:在指定目录上跑一遍完整流程,用来验证「打包后的程序真的能用」。
 *
 * <p>流程:扫描 → 备份(可多轮,每轮先改一点内容)→ 校验 ZIP → 恢复到新目录 →
 * 逐文件比对内容 → 检查没有残留临时文件。全程不依赖界面,退出码 0 表示通过。</p>
 *
 * <p>发布前的冒烟脚本会调用它,所以每一次发布都有一份可复现的证据。</p>
 */
public final class SelfTest {

    /** 自检结果。 */
    public record Result(boolean passed, List<String> lines) {

        public String text() {
            return String.join(System.lineSeparator(), lines);
        }
    }

    private SelfTest() {
    }

    /**
     * 执行自检。
     *
     * @param savesDir  存放测试世界的 saves 目录
     * @param backupDir 备份目录(测试用,内容会被写入)
     * @param cycles    备份轮数(每轮会先修改世界里的一个文件,验证「变化后重新备份」)
     */
    public static Result run(Path savesDir, Path backupDir, int cycles) {
        List<String> lines = new ArrayList<>();
        boolean passed = true;
        Path restoredParent = null;
        try {
            lines.add("saves=" + PathUtils.toDisplayPath(savesDir));
            lines.add("backupDir=" + PathUtils.toDisplayPath(backupDir));

            // 1) 扫描
            WorldScanner scanner = new WorldScanner(() -> List.of());
            var scan = scanner.scan(List.of(savesDir), null);
            lines.add("扫描:发现 " + scan.worlds().size() + " 个世界");
            if (scan.worlds().isEmpty()) {
                lines.add("FAIL 没有发现任何世界,自检无法继续");
                return new Result(false, lines);
            }
            MinecraftWorld world = scan.worlds().get(0);
            lines.add("目标世界:" + world.displayName() + "(" + world.folderName() + ","
                    + FileUtils.humanSize(world.sizeBytes()) + ")");

            BackupRepository repository = new BackupRepository(backupDir);
            BackupService service = new BackupService(repository);
            BackupRecord latest = null;
            int rounds = Math.max(1, cycles);
            for (int i = 1; i <= rounds; i++) {
                if (i > 1) {
                    touchWorld(world);
                }
                world = rescanWorld(savesDir, world);
                BackupResult result = service.backup(world, BackupOptions.of(backupDir, 20),
                        ProgressListener.NOOP);
                latest = result.record();
                ZipUtils.ZipVerification verification = ZipUtils.verify(latest.zipPath());
                long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        / 1024 / 1024;
                lines.add(String.format("第 %d 轮:备份 %s(%s,%d 个文件,耗时 %d ms,状态 %s),"
                                + "ZIP 校验=%s,堆内存 %d MB",
                        i, latest.zipFileName(), latest.sizeText(), latest.fileCount(),
                        latest.durationMillis(), latest.status(), verification.valid() ? "通过" : "失败", heapMb));
                if (!verification.valid()) {
                    lines.add("FAIL ZIP 校验未通过:" + verification.problem());
                    passed = false;
                    break;
                }
            }

            // 2) 恢复到新目录并逐文件比对
            restoredParent = savesDir.getParent().resolve("restored-" + System.currentTimeMillis());
            Path restoredWorld = restoredParent.resolve(latest.worldFolderName());
            RestoreService.RestoreResult restore = new RestoreService().restore(latest.zipPath(),
                    RestoreService.RestoreOptions.to(restoredWorld), ProgressListener.NOOP);
            lines.add("恢复:文件 " + restore.fileCount() + " 个,原世界副本="
                    + (restore.originalBackupDir() == null ? "无" : PathUtils.toDisplayPath(restore.originalBackupDir())));

            List<String> differences = compare(world.worldDir(), restoredWorld);
            if (differences.isEmpty()) {
                lines.add("比对:恢复结果与源世界完全一致");
            } else {
                differences.forEach(diff -> lines.add("FAIL " + diff));
                passed = false;
            }

            // 3) 没有残留临时文件
            List<Path> leftovers = repository.findLeftoverTempFiles();
            if (leftovers.isEmpty()) {
                lines.add("临时文件:无残留");
            } else {
                leftovers.forEach(path -> lines.add("FAIL 残留临时文件 " + PathUtils.toDisplayPath(path)));
                passed = false;
            }

            lines.add(passed ? "PASS 自检通过" : "FAIL 自检未通过");
            return new Result(passed, lines);
        } catch (RuntimeException | IOException e) {
            Log.errorQuietly("自检异常", e instanceof Exception exception ? exception : new RuntimeException(e));
            lines.add("FAIL 异常:" + e);
            return new Result(false, lines);
        } finally {
            if (restoredParent != null) {
                WorldCopier.deleteRecursively(restoredParent);
            }
        }
    }

    /** 改一点世界内容,模拟游戏里继续玩。 */
    private static void touchWorld(MinecraftWorld world) throws IOException {
        Path region = world.worldDir().resolve("region");
        Files.createDirectories(region);
        Path target = region.resolve("r.0.0.mca");
        byte[] extra = ("定期更新 " + System.currentTimeMillis() + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        Files.write(target, extra, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static MinecraftWorld rescanWorld(Path savesDir, MinecraftWorld previous) {
        Path worldDir = previous.worldDir();
        return new MinecraftWorld(previous.folderName(), worldDir, savesDir, previous.kind(),
                previous.sourceLabel(), previous.groupName(), FileUtils.directorySize(worldDir),
                FileUtils.lastModifiedMillis(worldDir), FileUtils.worldChangeStamp(worldDir),
                LevelInfoReader.read(worldDir.resolve("level.dat")),
                FileUtils.probeSessionLock(worldDir), null);
    }

    /** 逐文件比对(相对路径 + 大小 + SHA-256);返回差异描述。 */
    private static List<String> compare(Path expected, Path actual) throws IOException {
        List<String> differences = new ArrayList<>();
        Set<String> expectedFiles = relativeFiles(expected);
        Set<String> actualFiles = relativeFiles(actual);
        for (String file : expectedFiles) {
            if (!actualFiles.contains(file)) {
                differences.add("恢复结果缺少文件:" + file);
                continue;
            }
            Path left = expected.resolve(file);
            Path right = actual.resolve(file);
            if (Files.size(left) != Files.size(right)) {
                differences.add("大小不一致:" + file);
                continue;
            }
            if (!Hashing.sha256(left).equalsIgnoreCase(Hashing.sha256(right))) {
                differences.add("内容不一致:" + file);
            }
        }
        for (String file : actualFiles) {
            if (!expectedFiles.contains(file)) {
                differences.add("恢复结果多出文件:" + file);
            }
        }
        return differences;
    }

    private static Set<String> relativeFiles(Path root) throws IOException {
        Set<String> files = new LinkedHashSet<>();
        for (Path file : ZipUtils.collectFiles(root)) {
            files.add(root.relativize(file).toString().replace('\\', '/'));
        }
        return files;
    }
}
