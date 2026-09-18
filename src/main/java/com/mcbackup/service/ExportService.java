package com.mcbackup.service;

import com.mcbackup.model.ExportResult;
import com.mcbackup.model.MinecraftWorld;
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
import java.util.Locale;

/**
 * 导出服务:把世界导出成「解压即用」的标准 ZIP。
 *
 * <p>与备份的区别:导出不写清单、不做保留策略,目标位置由用户选择;
 * 但同样遵循「先写 .tmp,校验通过再原子改名」,避免产生半截 ZIP。</p>
 */
public final class ExportService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    /** 建议的导出文件名(已处理非法字符)。 */
    public static String suggestedFileName(MinecraftWorld world) {
        return PathUtils.sanitizeFileName(world.displayName()) + "_" + FILE_STAMP.format(LocalDateTime.now()) + ".zip";
    }

    /**
     * 导出世界。
     *
     * @param targetZip 用户选择的目标 ZIP 路径(缺少 .zip 扩展名时会自动补上)
     */
    public ExportResult export(MinecraftWorld world, Path targetZip, ProgressListener listener) {
        long started = System.currentTimeMillis();
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        List<String> warnings = new ArrayList<>();

        if (!PathUtils.isDirectory(world.worldDir())) {
            throw new BackupException("世界目录不存在或不可访问:" + PathUtils.toDisplayPath(world.worldDir()));
        }
        Path normalized = normalizeTarget(targetZip);
        Path tempZip = normalized.resolveSibling(normalized.getFileName().toString() + ".tmp");
        Path tempCopyDir = null;
        try {
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tempCopyDir = Files.createTempDirectory("mcbackup-export-");

            progress.onProgress("复制文件", 0, 0, "准备导出 " + world.displayName());
            WorldCopier.CopyResult copy = WorldCopier.copy(world.worldDir(), tempCopyDir, 3, progress);
            warnings.addAll(copy.warnings());

            progress.onProgress("压缩", 0, 0, "压缩 " + world.displayName());
            ZipUtils.ZipStats stats = ZipUtils.zipDirectory(tempCopyDir, tempZip, null, progress);
            warnings.addAll(stats.skippedFiles());

            progress.onProgress("校验", 0, 0, "校验 ZIP 完整性");
            ZipUtils.ZipVerification verification = ZipUtils.verify(tempZip);
            if (!verification.valid()) {
                throw new BackupException("导出失败,ZIP 校验未通过:" + verification.problem());
            }

            progress.onProgress("提交", 0, 0, "写入 " + normalized.getFileName());
            WorldCopier.commitRename(tempZip, normalized);

            long elapsed = System.currentTimeMillis() - started;
            Log.info("导出完成: %s(%d 个文件,耗时 %d ms)", PathUtils.toDisplayPath(normalized),
                    stats.entries(), elapsed);
            return new ExportResult(normalized, sizeOf(normalized), stats.entries(), elapsed,
                    List.copyOf(warnings));
        } catch (BackupException e) {
            cleanup(tempZip);
            throw e;
        } catch (IOException | RuntimeException e) {
            cleanup(tempZip);
            Log.error("导出失败: " + PathUtils.toDisplayPath(normalized), e);
            throw new BackupException("导出失败:" + e.getMessage(), e);
        } finally {
            if (tempCopyDir != null) {
                WorldCopier.deleteRecursively(tempCopyDir);
            }
        }
    }

    /** 规范化目标文件名:补上 .zip,避免用户漏写扩展名。 */
    private static Path normalizeTarget(Path targetZip) {
        String name = targetZip.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name = name + ".zip";
        }
        Path parent = targetZip.getParent();
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    /**
     * 把已有备份 ZIP 另存到用户选择的位置。
     *
     * <p>直接流式复制文件,不重新压缩——导出备份应该和拷贝文件一样快。</p>
     */
    public Path copyZip(Path sourceZip, Path targetZip, ProgressListener listener) {
        if (!PathUtils.isFile(sourceZip)) {
            throw new BackupException("备份文件不存在:" + PathUtils.toDisplayPath(sourceZip));
        }
        Path normalized = normalizeTarget(targetZip);
        Path temp = normalized.resolveSibling(normalized.getFileName().toString() + ".tmp");
        try {
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            long total = Files.size(sourceZip);
            long written = 0;
            int bufferSize = 64 * 1024;
            byte[] buffer = new byte[bufferSize];
            try (java.io.InputStream in = new java.io.BufferedInputStream(
                    Files.newInputStream(sourceZip), bufferSize);
                 java.io.OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(temp,
                         java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                         java.nio.file.StandardOpenOption.WRITE), bufferSize)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (listener != null) {
                        listener.onProgress("复制备份", (int) (written / (1024 * 1024)),
                                (int) (total / (1024 * 1024)), sourceZip.getFileName().toString());
                    }
                }
            }
            WorldCopier.commitRename(temp, normalized);
            Log.info("备份已导出到 %s", PathUtils.toDisplayPath(normalized));
            return normalized;
        } catch (IOException e) {
            cleanup(temp);
            throw new BackupException("导出备份失败:" + e.getMessage(), e);
        }
    }

    private void cleanup(Path tempZip) {
        if (tempZip == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempZip);
        } catch (IOException e) {
            Log.debug("清理未完成的导出文件失败: " + e.getMessage());
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
