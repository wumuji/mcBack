package com.mcbackup.service;

import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Hashing;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;
import com.mcbackup.util.ProgressListener;
import com.mcbackup.util.WorldCopier;
import com.mcbackup.util.ZipUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 恢复服务:把备份 ZIP 还原成一个可玩的世界。
 *
 * <p>恢复是唯一会改动存档目录的操作,所以步骤刻意设计得非常保守:</p>
 * <pre>
 * 1. 校验备份    能打开、无路径穿越、level.dat 存在,(若清单里有 SHA-256 就一并核对)
 * 2. 占用检查    目标世界的 session.lock 拿不到共享锁 -> 判定游戏正在使用,直接拒绝
 * 3. 空间检查    按备份声明的解压大小预留空间,不足则提前失败
 * 4. 解压到临时  &lt;世界父目录&gt;/.mcbackup-restore-&lt;时间戳&gt;(同卷,便于快速改名)
 * 5. 解压后校验  临时目录必须是一个结构完整的世界
 * 6. 原子替换    当前世界改名为 &lt;世界名&gt;.restore-backup-&lt;时间戳&gt;,再把临时目录改成世界名
 * 7. 失败回滚    第 6 步中任一步失败,把旧世界原样改回,绝不留下半个世界
 * </pre>
 *
 * <p><b>永远不删除原世界</b>:旧世界一定会以 {@code .restore-backup-} 后缀保留下来,
 * 由用户自己决定什么时候删。</p>
 */
public final class RestoreService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String TEMP_PREFIX = ".mcbackup-restore-";
    private static final String ORIGINAL_SUFFIX = ".restore-backup-";
    private static final int BUFFER_SIZE = 64 * 1024;
    /** 解压总量允许比声明值多出的冗余(应对声明为 0 的流式条目)。 */
    private static final long SIZE_TOLERANCE = 64L * 1024 * 1024;

    /** 恢复选项。 */
    public record RestoreOptions(Path targetWorldDir, String expectedSha256) {

        public static RestoreOptions to(Path targetWorldDir) {
            return new RestoreOptions(targetWorldDir, null);
        }
    }

    /** 恢复结果。 */
    public record RestoreResult(Path worldDir, Path originalBackupDir, int fileCount, long bytes,
                                long durationMillis, List<String> warnings) {
    }

    /**
     * 执行恢复。
     *
     * @param backupZip 备份 ZIP 路径
     */
    public RestoreResult restore(Path backupZip, RestoreOptions options, ProgressListener listener) {
        long started = System.currentTimeMillis();
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        List<String> warnings = new ArrayList<>();
        Path target = options.targetWorldDir();

        if (!PathUtils.isFile(backupZip)) {
            throw new BackupException("备份文件不存在:" + PathUtils.toDisplayPath(backupZip));
        }
        if (target == null || target.getParent() == null) {
            throw new BackupException("恢复目标路径无效");
        }

        // 1) 校验备份
        progress.onProgress("校验备份", 0, 0, backupZip.getFileName().toString());
        ZipUtils.ZipVerification verification = ZipUtils.verify(backupZip);
        if (!verification.valid()) {
            throw new BackupException("备份校验未通过:" + verification.problem());
        }
        if (options.expectedSha256() != null && !options.expectedSha256().isBlank()) {
            String actual = Hashing.sha256Quietly(backupZip);
            if (!options.expectedSha256().equalsIgnoreCase(actual)) {
                throw new BackupException("备份内容与清单记录不一致(SHA-256 不匹配),已中止恢复");
            }
        }

        // 2) 占用检查:游戏正在使用这个世界时不允许恢复
        if (Files.exists(target)) {
            FileUtils.LockProbe probe = FileUtils.probeSessionLock(target);
            if (probe == FileUtils.LockProbe.LOCKED) {
                throw new BackupException("检测到世界可能正在使用,请先退出 Minecraft 再恢复");
            }
        }

        // 3) 空间检查
        long declaredBytes = verification.uncompressedBytes();
        ensureEnoughSpace(target.getParent(), declaredBytes);

        // 4) 解压到临时目录
        Path tempDir = target.getParent().resolve(TEMP_PREFIX + STAMP.format(LocalDateTime.now()));
        try {
            if (Files.exists(tempDir)) {
                throw new BackupException("临时目录已存在,请先清理:" + PathUtils.toDisplayPath(tempDir));
            }
            Files.createDirectories(tempDir);
            ExtractStats stats = extract(backupZip, tempDir, declaredBytes, progress);

            // 5) 解压后校验
            progress.onProgress("校验世界", 0, 0, "检查解压结果");
            if (!WorldDetector.isWorldContent(tempDir)) {
                throw new BackupException("备份内容不是一个完整的 Minecraft 世界(缺少 level.dat 或结构特征)");
            }

            // 6) 原子替换
            progress.onProgress("替换世界", 0, 0, "切换目录");
            Path originalBackup = swap(target, tempDir);
            if (originalBackup != null) {
                warnings.add("原世界已保留为:" + PathUtils.toDisplayPath(originalBackup));
            }

            long elapsed = System.currentTimeMillis() - started;
            Log.info("恢复完成: %s(文件 %d,耗时 %d ms)", PathUtils.toDisplayPath(target),
                    stats.files(), elapsed);
            return new RestoreResult(target, originalBackup, stats.files(), stats.bytes(), elapsed,
                    List.copyOf(warnings));
        } catch (BackupException e) {
            deleteTemp(tempDir);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteTemp(tempDir);
            Log.error("恢复失败: " + PathUtils.toDisplayPath(target), e);
            throw new BackupException("恢复失败:" + e.getMessage(), e);
        }
    }

    private record ExtractStats(int files, long bytes) {
    }

    /** 解压 ZIP 到临时目录,拒绝绝对路径与路径穿越,并按声明大小限制总量。 */
    private ExtractStats extract(Path backupZip, Path tempDir, long declaredBytes,
                                 ProgressListener progress) throws IOException {
        long limit = declaredBytes + SIZE_TOLERANCE;
        long written = 0;
        int files = 0;
        int index = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipFile zipFile = new ZipFile(backupZip.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                Path out = tempDir.resolve(name).normalize();
                if (!out.startsWith(tempDir)) {
                    throw new BackupException("备份内包含非法路径,已中止:" + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                try (InputStream in = new BufferedInputStream(zipFile.getInputStream(entry), BUFFER_SIZE);
                     OutputStream os = new BufferedOutputStream(Files.newOutputStream(out,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE), BUFFER_SIZE)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                        written += read;
                        if (written > limit) {
                            throw new BackupException("备份解压后体积超过预期,已中止(可能是损坏或异常的 ZIP)");
                        }
                    }
                }
                files++;
                index++;
                if (progress != null) {
                    progress.onProgress("解压", index, 0, name);
                }
            }
        }
        Log.info("解压完成: %d 个文件,共 %d 字节 -> %s", files, written, PathUtils.toDisplayPath(tempDir));
        return new ExtractStats(files, written);
    }

    /**
     * 把临时目录切换成正式世界目录。
     *
     * @return 原世界的备份路径(目标原本不存在时为 null)
     */
    private Path swap(Path target, Path tempDir) throws IOException {
        Path originalBackup = null;
        if (Files.exists(target)) {
            if (!PathUtils.isDirectory(target)) {
                throw new IOException("目标路径已存在且不是目录: " + PathUtils.toDisplayPath(target));
            }
            originalBackup = target.getParent().resolve(
                    target.getFileName() + ORIGINAL_SUFFIX + STAMP.format(LocalDateTime.now()));
            if (Files.exists(originalBackup)) {
                throw new IOException("原世界备份目录已存在,请先处理:" + PathUtils.toDisplayPath(originalBackup));
            }
            Files.move(target, originalBackup, StandardCopyOption.ATOMIC_MOVE);
            Log.info("原世界已改名为 %s", PathUtils.toDisplayPath(originalBackup));
        }
        try {
            Files.move(tempDir, target, StandardCopyOption.ATOMIC_MOVE);
            return originalBackup;
        } catch (IOException moveFailed) {
            // 回滚:把原世界改回原名,保证用户至少还能玩到原来的世界
            if (originalBackup != null && !Files.exists(target)) {
                try {
                    Files.move(originalBackup, target, StandardCopyOption.ATOMIC_MOVE);
                    Log.warn("替换失败,原世界已回滚到 %s", PathUtils.toDisplayPath(target));
                } catch (IOException rollbackFailed) {
                    Log.error("回滚失败,原世界保留在: " + PathUtils.toDisplayPath(originalBackup), rollbackFailed);
                    throw new IOException("替换失败且自动回滚失败。原世界仍在 "
                            + PathUtils.toDisplayPath(originalBackup) + ",请手动改回原名", rollbackFailed);
                }
            }
            throw moveFailed;
        }
    }

    /** 按备份声明的大小预留空间;空间不足时提前失败并给出明确提示。 */
    private void ensureEnoughSpace(Path directory, long requiredBytes) {
        if (requiredBytes <= 0) {
            return;
        }
        try {
            long usable = Files.getFileStore(directory).getUsableSpace();
            long needed = (long) (requiredBytes * 1.1);
            if (usable < needed) {
                throw new BackupException("磁盘空间不足:需要约 " + FileUtils.humanSize(needed)
                        + ",可用 " + FileUtils.humanSize(usable));
            }
        } catch (IOException e) {
            Log.warn("无法检查磁盘剩余空间,继续尝试恢复: %s", e.getMessage());
        }
    }

    private void deleteTemp(Path tempDir) {
        if (tempDir != null && WorldCopier.deleteRecursively(tempDir)) {
            Log.debug("已清理恢复临时目录: " + PathUtils.toDisplayPath(tempDir));
        }
    }
}
