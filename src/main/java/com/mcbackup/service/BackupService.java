package com.mcbackup.service;

import com.mcbackup.model.BackupOptions;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.BackupResult;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;
import com.mcbackup.util.ProgressListener;
import com.mcbackup.util.WorldCopier;
import com.mcbackup.util.ZipUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份服务:把「世界目录」变成一份可校验、可回溯的 ZIP 备份。
 *
 * <p>流程严格围绕「不产生看起来正常的坏 ZIP」这个目标设计:</p>
 * <pre>
 * 1. 复制      世界 -> &lt;备份目录&gt;/.tmp/&lt;名字&gt;/      逐文件重试,失败只记录不中断
 * 2. 压缩      临时副本 -> &lt;世界目录&gt;/&lt;名字&gt;.zip.tmp  流式压缩,固定 64KB 缓冲区
 * 3. 校验      .zip.tmp 能否打开、条目数、level.dat 是否存在
 * 4. 提交      .zip.tmp --原子改名--&gt; &lt;名字&gt;.zip        只有校验通过才会出现在最终名字下
 * 5. 清单      写入同名 .json(证明确实是本程序生成的备份,并记录耗时/文件数/变化指纹)
 * 6. 清理      删除临时副本,再执行保留策略
 * </pre>
 */
public final class BackupService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    /** 备份期间 Minecraft 可能仍在改写文件,因此复制阶段允许重试。 */
    private static final int COPY_RETRIES = 3;
    /** 备份期间需要的空间系数:临时副本(≈源大小)+ ZIP(最坏情况≈源大小)+ 余量。 */
    private static final double SPACE_FACTOR = 2.1;

    private final BackupRepository repository;

    public BackupService(BackupRepository repository) {
        this.repository = repository;
    }

    public BackupRepository repository() {
        return repository;
    }

    /**
     * 执行一次全量备份。
     *
     * @throws BackupException 备份无法完成(源目录不可访问、临时目录不可写、ZIP 校验失败等)
     */
    public BackupResult backup(MinecraftWorld world, BackupOptions options, ProgressListener listener) {
        long started = System.currentTimeMillis();
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        List<String> warnings = new ArrayList<>();

        Path worldDir = world.worldDir();
        if (!PathUtils.isDirectory(worldDir)) {
            throw new BackupException("世界目录不存在或不可访问:" + PathUtils.toDisplayPath(worldDir));
        }
        if (!WorldDetector.detect(worldDir).isWorld()) {
            throw new BackupException("该目录不是有效的 Minecraft 世界(缺少 level.dat 或结构特征)");
        }

        try {
            repository.ensureExists();
            Files.createDirectories(repository.tempDir());
        } catch (IOException e) {
            throw new BackupException("无法创建备份目录:" + PathUtils.toDisplayPath(options.backupDir()), e);
        }

        // 空间检查:提前失败,好过压到一半磁盘满
        long sourceBytes = world.sizeBytes() > 0 ? world.sizeBytes() : FileUtils.directorySize(worldDir);
        try {
            long usable = Files.getFileStore(repository.backupDir()).getUsableSpace();
            ensureEnoughSpace(usable, sourceBytes, PathUtils.toDisplayPath(repository.backupDir()));
        } catch (IOException e) {
            Log.warn("无法检查备份目录剩余空间,继续尝试备份: %s", e.getMessage());
        }

        String baseName = PathUtils.sanitizeFileName(world.displayName())
                + "_" + FILE_STAMP.format(LocalDateTime.now());
        Path tempCopyDir = null;
        Path tempZip = null;
        try {
            String zipFileName = repository.uniqueZipFileName(world.folderName(), baseName);
            Path worldBackupDir = repository.worldDir(world.folderName());
            Files.createDirectories(worldBackupDir);
            tempZip = worldBackupDir.resolve(zipFileName + ".tmp");
            tempCopyDir = repository.tempDir().resolve(zipFileName);

            // 1) 复制到临时目录
            progress.onProgress("复制文件", 0, 0, "准备复制 " + world.displayName());
            WorldCopier.CopyResult copy = WorldCopier.copy(worldDir, tempCopyDir, COPY_RETRIES, progress);
            warnings.addAll(copy.warnings());
            if (!copy.complete()) {
                Log.warn("世界 %s 有 %d 个文件未能复制", world.displayName(), copy.failedFiles().size());
            }

            // 2) 压缩
            progress.onProgress("压缩", 0, 0, "压缩 " + world.displayName());
            String comment = BackupRecord.PRODUCER + " v1 " + world.folderName();
            ZipUtils.ZipStats stats = ZipUtils.zipDirectory(tempCopyDir, tempZip, comment,
                    options.storeRegionFiles(), progress);
            warnings.addAll(stats.skippedFiles());

            // 3) 校验
            progress.onProgress("校验", 0, 0, "校验 ZIP 完整性");
            if (options.verify()) {
                ZipUtils.ZipVerification verification = ZipUtils.verify(tempZip);
                if (!verification.valid()) {
                    throw new BackupException("ZIP 校验未通过:" + verification.problem());
                }
                if (verification.fileEntries() != stats.files()) {
                    warnings.add("文件条目数与写入数量不一致(" + verification.fileEntries()
                            + "/" + stats.files() + ")");
                }
                Log.info("ZIP 校验通过: %s,文件 %d,空目录 %d", zipFileName,
                        verification.fileEntries(), stats.directories());
            }

            // 4) 原子提交
            progress.onProgress("提交", 0, 0, "写入最终文件");
            Path finalZip = worldBackupDir.resolve(zipFileName);
            WorldCopier.commitRename(tempZip, finalZip);
            tempZip = null;

            // 5) 清单(放在提交之后,避免出现「有清单却没有 ZIP」)
            long zipBytes = sizeOf(finalZip);
            String sha256 = "";
            if (options.computeHash()) {
                progress.onProgress("计算哈希", 0, 0, "SHA-256");
                long hashStarted = System.currentTimeMillis();
                sha256 = com.mcbackup.util.Hashing.sha256Quietly(finalZip);
                Log.info("SHA-256 计算完成(%d ms): %s", System.currentTimeMillis() - hashStarted, sha256);
            }
            BackupRecord record = new BackupRecord(
                    BackupRecord.PRODUCER,
                    world.folderName(),
                    world.displayName(),
                    worldDir.toString(),
                    world.sourceLabel(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis() - started,
                    zipBytes,
                    stats.files(),
                    copy.bytes(),
                    world.changeStamp(),
                    BackupRecord.STRATEGY_FULL,
                    copy.complete() ? BackupRecord.STATUS_OK : BackupRecord.STATUS_INCOMPLETE,
                    copy.failedFiles().size(),
                    List.copyOf(warnings),
                    world.possiblyRunning(),
                    sha256,
                    zipFileName,
                    finalZip,
                    null);
            Path manifest = repository.saveManifest(record);
            record = withManifest(record, manifest, finalZip);

            // 6) 清理临时副本 + 保留策略
            progress.onProgress("清理", 0, 0, "清理临时文件");
            if (!WorldCopier.deleteRecursively(tempCopyDir)) {
                warnings.add("临时副本未能完全删除:" + PathUtils.toDisplayPath(tempCopyDir));
            }
            BackupRepository.RetentionReport retention =
                    repository.applyRetention(world.folderName(), options.retainCount());

            long elapsed = System.currentTimeMillis() - started;
            Log.info("备份成功: %s -> %s(%s,文件 %d,耗时 %d ms,状态 %s)",
                    world.displayName(), zipFileName, FileUtils.humanSize(zipBytes),
                    stats.files(), elapsed, record.status());
            return new BackupResult(record, List.copyOf(warnings), retention.deleted(), elapsed);
        } catch (BackupException e) {
            cleanupTemp(tempZip, tempCopyDir);
            throw e;
        } catch (IOException | RuntimeException e) {
            cleanupTemp(tempZip, tempCopyDir);
            Log.error("备份失败: " + world.displayName(), e);
            throw new BackupException("备份失败:" + e.getMessage(), e);
        }
    }

    private static BackupRecord withManifest(BackupRecord record, Path manifest, Path zip) {
        return new BackupRecord(record.producer(), record.worldFolderName(), record.worldDisplayName(),
                record.worldPath(), record.sourceLabel(), record.createdAt(), record.durationMillis(),
                record.zipBytes(), record.fileCount(), record.sourceBytes(), record.sourceChangeStamp(),
                record.strategy(), record.status(), record.failedFiles(), record.warnings(),
                record.sourceRunning(),
                record.sha256(), record.zipFileName(), zip, manifest);
    }

    /** 备份这个大小的世界需要多少可用空间。 */
    static long requiredSpaceBytes(long sourceBytes) {
        return sourceBytes <= 0 ? 0L : (long) (sourceBytes * SPACE_FACTOR);
    }

    /**
     * 空间是否够用;不够就抛出带明确数字的异常。
     *
     * <p>抽成纯函数是为了能直接测试(真造一个磁盘满的环境不现实)。</p>
     */
    static void ensureEnoughSpace(long usableBytes, long sourceBytes, String backupDirText) {
        long required = requiredSpaceBytes(sourceBytes);
        if (required > 0 && usableBytes < required) {
            throw new BackupException("备份目录剩余空间不足:备份这个存档需要约 "
                    + FileUtils.humanSize(required) + ",当前可用 "
                    + FileUtils.humanSize(usableBytes) + "(" + backupDirText
                    + "\\n备份过程需要同时放下临时副本和 ZIP,可以先清理旧备份或换一个磁盘。");
        }
    }

    /** 失败或中断时清理本次产生的临时文件(不影响已有备份)。 */
    private void cleanupTemp(Path tempZip, Path tempCopyDir) {
        if (tempZip != null) {
            try {
                Files.deleteIfExists(tempZip);
            } catch (IOException e) {
                Log.debug("清理未完成的 ZIP 失败: " + e.getMessage());
            }
        }
        if (tempCopyDir != null) {
            WorldCopier.deleteRecursively(tempCopyDir);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }
}
