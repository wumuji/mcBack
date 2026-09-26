package com.mcback.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 世界目录的安全复制。
 *
 * <p>为什么需要「先复制到临时目录再压缩」:压缩过程可能需要几十秒到几分钟,
 * 期间 Minecraft 可能继续写入 region 文件。先做一次受控的复制,把「复制阶段」和「压缩阶段」
 * 分开,压缩阶段面对的就是一份不再变化的副本。</p>
 *
 * <p>正在运行的 Minecraft 会让文件持续变化,所以复制阶段做了三件事:</p>
 * <ol>
 *   <li>复制前后比较文件大小与修改时间,变化了就重试(最多 {@code maxRetries} 次);</li>
 *   <li>单个文件彻底失败只记录警告,继续复制其它文件,不让整个任务崩掉;</li>
 *   <li>{@code session.lock} 属于「可失败文件」:它只是游戏的锁文件,复制不到不影响世界本身。</li>
 * </ol>
 *
 * <p>内存:只使用固定 64KB 缓冲区流式复制,不会把大文件读进内存。</p>
 */
public final class WorldCopier {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_RETRIES = 3;

    /** 复制不到也不需要标记为「备份不完整」的文件。 */
    private static final Set<String> OPTIONAL_FILES = Set.of("session.lock");

    /** 复制结果。 */
    public record CopyResult(Path destDir, int fileCount, int copiedFiles, long bytes,
                             List<String> failedFiles, List<String> warnings) {

        public boolean complete() {
            return failedFiles.isEmpty();
        }
    }

    private WorldCopier() {
    }

    public static CopyResult copy(Path sourceDir, Path destDir, ProgressListener listener) throws IOException {
        return copy(sourceDir, destDir, DEFAULT_RETRIES, listener);
    }

    /**
     * 把 {@code sourceDir} 的内容复制到 {@code destDir}。
     *
     * @param maxRetries 单个文件的最大尝试次数
     */
    public static CopyResult copy(Path sourceDir, Path destDir, int maxRetries, ProgressListener listener)
            throws IOException {
        if (!PathUtils.isDirectory(sourceDir)) {
            throw new IOException("源目录不存在或不可访问: " + PathUtils.toDisplayPath(sourceDir));
        }
        List<Path> files = ZipUtils.collectFiles(sourceDir);
        int total = files.size();
        long bytes = 0;
        int copied = 0;
        List<String> failed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int done = 0;

        // 先建目录(含空目录),保证复制结果的结构与原世界一致
        createDirectories(sourceDir, destDir);

        for (Path file : files) {
            Path relative = sourceDir.relativize(file);
            Path target = destDir.resolve(relative);
            String relativeName = relative.toString().replace('\\', '/');
            FileOutcome outcome = copyWithRetry(file, target, Math.max(1, maxRetries), optional(relativeName),
                    warnings);
            if (outcome == FileOutcome.COPIED) {
                copied++;
                bytes += sizeOf(target);
            } else if (outcome == FileOutcome.FAILED_OPTIONAL) {
                warnings.add("未能复制可选文件 " + relativeName);
            } else {
                failed.add(relativeName);
            }
            done++;
            if (listener != null) {
                listener.onProgress("复制文件", done, total, relativeName);
            }
        }

        Log.info("复制世界完成: %s -> %s,文件 %d/%d,字节 %d,失败 %d",
                PathUtils.toDisplayPath(sourceDir), PathUtils.toDisplayPath(destDir),
                copied, total, bytes, failed.size());
        return new CopyResult(destDir, total, copied, bytes, failed, warnings);
    }

    /** 递归创建目标目录结构(包括空目录)。 */
    private static void createDirectories(Path sourceDir, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Files.walkFileTree(sourceDir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(sourceDir)) {
                    Files.createDirectories(destDir.resolve(sourceDir.relativize(dir)));
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                Log.warn("创建目录结构时跳过无法访问的路径: %s", file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private enum FileOutcome {
        COPIED, FAILED_REQUIRED, FAILED_OPTIONAL
    }

    private static FileOutcome copyWithRetry(Path source, Path target, int maxRetries, boolean optional,
                                             List<String> warnings) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Files.createDirectories(target.getParent());
                long beforeSize = Files.size(source);
                FileTime beforeMtime = Files.getLastModifiedTime(source);

                try (InputStream in = new BufferedInputStream(Files.newInputStream(source), BUFFER_SIZE);
                     OutputStream out = new BufferedOutputStream(Files.newOutputStream(target,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE), BUFFER_SIZE)) {
                    in.transferTo(out);
                }

                long afterSize = Files.size(source);
                FileTime afterMtime = Files.getLastModifiedTime(source);
                long copiedSize = Files.size(target);

                boolean stable = beforeSize == afterSize
                        && beforeMtime.toMillis() == afterMtime.toMillis()
                        && copiedSize == afterSize;
                if (stable) {
                    return FileOutcome.COPIED;
                }
                // 文件在复制期间被 Minecraft 改写了:删掉半成品重来
                warnings.add("文件在复制过程中发生变化,已重试: " + source.getFileName());
                Log.debug("文件复制期间发生变化,重试第 %d 次: %s", attempt, source);
                deleteQuietly(target);
            } catch (NoSuchFileException e) {
                // 文件在扫描与复制之间消失(例如 Minecraft 正在整理区块文件)
                if (!optional) {
                    warnings.add("文件已不存在: " + source.getFileName());
                }
                Log.debug("复制时文件已消失: " + source);
                return optional ? FileOutcome.FAILED_OPTIONAL : FileOutcome.FAILED_REQUIRED;
            } catch (IOException e) {
                warnings.add("复制失败(第 " + attempt + " 次): " + source.getFileName()
                        + " - " + e.getClass().getSimpleName());
                Log.warn("复制文件失败(第 %d 次): %s - %s", attempt, source, e.getMessage());
            }
        }
        if (optional) {
            return FileOutcome.FAILED_OPTIONAL;
        }
        Log.warn("多次重试后仍无法复制: %s", source);
        return FileOutcome.FAILED_REQUIRED;
    }

    private static boolean optional(String relativeName) {
        String name = relativeName;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return OPTIONAL_FILES.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 递归删除目录(用于清理临时副本);失败只记录日志。 */
    public static boolean deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return true;
        }
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(directory);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    Log.warn("删除时跳过无法访问的路径: %s (%s)", file, exc.getClass().getSimpleName());
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return !Files.exists(dir);
        } catch (IOException e) {
            Log.warn("清理临时目录失败: %s (%s)", dir, e.getMessage());
            return false;
        }
    }

    /** 把文件移动为最终名字(同目录内原子改名,失败时回退普通移动)。 */
    public static void commitRename(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Log.debug("原子改名不可用,回退普通移动: " + atomicFailed.getMessage());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Log.debug("删除半成品失败: " + path + " (" + e.getMessage() + ")");
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
