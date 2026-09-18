package com.mcbackup.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * ZIP 工具。
 *
 * <p>内存策略:压缩过程只使用一个固定的 64KB 缓冲区,通过
 * {@code Files.newInputStream -> ZipOutputStream} 流式写入,任何大小的世界都不会被整体读入内存。</p>
 *
 * <p>条目名统一使用正斜杠并使用 UTF-8 编码(带 EFS 标记),这样中文文件名在资源管理器里也能正常显示。</p>
 */
public final class ZipUtils {

    private static final int BUFFER_SIZE = 64 * 1024;
    /** 小于该值的文件压缩成本很低,一律压缩。 */
    private static final long STORE_THRESHOLD_BYTES = 256L * 1024;
    /** 采样压缩率高于该值,说明基本压不动,直接存储更划算。 */
    private static final double STORE_RATIO_THRESHOLD = 0.85;
    /** 采样大小:只读几个小片段来判断可压缩性,成本可忽略。 */
    private static final int SAMPLE_BYTES = 64 * 1024;

    private ZipUtils() {
    }

    /**
     * 压缩结果统计。
     *
     * <p>文件与目录分开计数:空目录也会写成一个目录条目,这样解压后世界结构与原目录一致。</p>
     */
    public record ZipStats(int files, int directories, long uncompressedBytes, long compressedBytes,
                           List<String> skippedFiles) {

        /** 条目总数(文件 + 目录)。 */
        public int entries() {
            return files + directories;
        }
    }

    /** 校验结果。 */
    public record ZipVerification(boolean valid, int entries, int fileEntries, long uncompressedBytes,
                                  String comment, boolean levelDatPresent, boolean regionPresent, String problem) {
    }

    /**
     * 把目录流式压缩成 ZIP。
     *
     * @param sourceDir 源目录(会被递归打包,条目名相对于该目录)
     * @param targetZip 目标文件名(调用方负责先用 .tmp 名字,校验通过后再改名)
     * @param comment   ZIP 注释,用于标记这是本程序生成的备份
     */
    public static ZipStats zipDirectory(Path sourceDir, Path targetZip, String comment,
                                        ProgressListener listener) throws IOException {
        return zipDirectory(sourceDir, targetZip, comment, true, listener);
    }

    /**
     * 把目录流式压缩成 ZIP。
     *
     * @param storeAlreadyCompressed 对已经压缩过的区域文件({@code .mca}/{@code .mcr})直接存储,
     *                               不再 deflate。这些文件本来就没有可压缩空间,重新压缩只会浪费时间与 CPU,
     *                               这对「世界在运行、机器同时在玩」的场景很重要。
     */
    public static ZipStats zipDirectory(Path sourceDir, Path targetZip, String comment,
                                        boolean storeAlreadyCompressed, ProgressListener listener) throws IOException {
        List<Path> files = collectFiles(sourceDir);
        List<Path> emptyDirs = collectEmptyDirectories(sourceDir);
        int total = files.size();
        long totalBytes = 0;
        for (Path file : files) {
            totalBytes += sizeOf(file);
        }

        List<String> skipped = new ArrayList<>();
        long written = 0;
        int entries = 0;
        int stored = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (OutputStream fileOut = Files.newOutputStream(targetZip);
             BufferedOutputStream buffered = new BufferedOutputStream(fileOut, BUFFER_SIZE);
             ZipOutputStream zip = new ZipOutputStream(buffered, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            if (comment != null && !comment.isBlank()) {
                zip.setComment(comment);
            }
            // 空目录也要保留,否则解压后世界结构会缺一层(例如空的 DIM1/)
            for (Path dir : emptyDirs) {
                String name = sourceDir.relativize(dir).toString().replace('\\', '/') + "/";
                zip.putNextEntry(new ZipEntry(name));
                zip.closeEntry();
            }
            for (Path file : files) {
                String name = sourceDir.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                try {
                    entry.setTime(Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // 取不到时间就用默认值,不影响压缩
                }
                long fileSize = sizeOf(file);
                boolean storeEntry = storeAlreadyCompressed && isRegionFile(name)
                        && fileSize >= STORE_THRESHOLD_BYTES
                        && sampleCompressionRatio(file) > STORE_RATIO_THRESHOLD;
                try {
                    if (storeEntry) {
                        // STORED 需要预先知道大小与 CRC:先流式算一遍 CRC(不占内存),再复制一遍
                        long size = fileSize;
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(size);
                        entry.setCompressedSize(size);
                        entry.setCrc(crc32(file));
                    }
                    zip.putNextEntry(entry);
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            zip.write(buffer, 0, read);
                            written += read;
                        }
                    }
                    zip.closeEntry();
                    entries++;
                    if (storeEntry) {
                        stored++;
                    }
                } catch (IOException e) {
                    // 单个文件在压缩过程中消失/被独占:记录后继续,不让整个备份失败
                    skipped.add(name + "(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                    Log.warn("压缩时跳过文件 %s:%s", name, e.getMessage());
                    try {
                        zip.closeEntry();
                    } catch (IOException ignored) {
                        // 关闭条目失败无需处理
                    }
                }
                if (listener != null) {
                    listener.onProgress("压缩", entries, total, name);
                }
            }
        }

        long compressedBytes = sizeOf(targetZip);
        Log.info("ZIP 完成: %s,文件 %d(其中直接存储 %d),空目录 %d,原始 %d 字节,压缩后 %d 字节,跳过 %d 个文件",
                targetZip.getFileName(), entries, stored, emptyDirs.size(), written, compressedBytes,
                skipped.size());
        return new ZipStats(entries, emptyDirs.size(), written, compressedBytes, skipped);
    }

    /** 区域文件(.mca/.mcr)内部已经是压缩过的区块数据。 */
    private static boolean isRegionFile(String entryName) {
        String lower = entryName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mca") || lower.endsWith(".mcr");
    }

    /**
     * 采样估计压缩率(压缩后字节 / 原始字节)。
     *
     * <p>为什么不按文件大小猜:同样是 3MB 的区域文件,有的装了实心地形(几乎压不动),
     * 有的几乎全是空区块(能压十倍),只看大小会做出错误决定。这里从文件中间取 64KB
     * 试压一次,几毫秒就能得到可靠得多的判断。</p>
     *
     * @return 压缩率;读不到文件时返回 1.0(按「压不动」处理,直接存储)
     */
    private static double sampleCompressionRatio(Path file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            long size = raf.length();
            if (size <= 0) {
                return 1.0;
            }
            // 取三处采样求平均,避免只看到「区块位置表」这种局部特征而误判
            double[] offsets = {0.25, 0.5, 0.75};
            double total = 0;
            int samples = 0;
            for (double position : offsets) {
                long offset = Math.max(0, Math.min(size - Math.min(SAMPLE_BYTES, size),
                        (long) (size * position) - SAMPLE_BYTES / 2));
                raf.seek(offset);
                int length = (int) Math.min(SAMPLE_BYTES, size - offset);
                if (length <= 0) {
                    continue;
                }
                byte[] buffer = new byte[length];
                raf.readFully(buffer);
                total += deflateRatio(buffer);
                samples++;
            }
            return samples == 0 ? 1.0 : total / samples;
        } catch (IOException e) {
            Log.debug("采样压缩率失败,按直接存储处理: " + file + " (" + e.getMessage() + ")");
            return 1.0;
        }
    }

    private static double deflateRatio(byte[] data) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] out = new byte[data.length + 64];
            int produced = deflater.deflate(out);
            return (double) produced / data.length;
        } finally {
            deflater.end();
        }
    }

    /** 流式计算文件的 CRC32(只用一个缓冲区,不读入内存)。 */
    private static long crc32(Path file) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    /**
     * 校验 ZIP:能打开、能读目录、关键文件存在、条目路径安全。
     *
     * <p>只读取中央目录,不解压内容,因此对 GB 级备份也是毫秒级。</p>
     */
    public static ZipVerification verify(Path zip) {
        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            int entries = 0;
            int fileEntries = 0;
            long uncompressed = 0;
            boolean levelDat = false;
            boolean region = false;
            String problem = null;
            Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    problem = "存在可疑路径条目: " + name;
                }
                entries++;
                if (!entry.isDirectory()) {
                    fileEntries++;
                }
                if (entry.getSize() > 0) {
                    uncompressed += entry.getSize();
                }
                if ("level.dat".equals(name)) {
                    levelDat = true;
                }
                if (name.startsWith("region/")) {
                    region = true;
                }
            }
            if (entries == 0) {
                problem = "ZIP 内没有任何条目";
            } else if (!levelDat) {
                problem = "ZIP 内缺少 level.dat";
            }
            return new ZipVerification(problem == null, entries, fileEntries, uncompressed,
                    zipFile.getComment(), levelDat, region, problem);
        } catch (IOException e) {
            return new ZipVerification(false, 0, 0, 0L, null, false, false,
                    "无法打开 ZIP: " + e.getMessage());
        }
    }

    /** 递归列出目录内的普通文件(不跟随符号链接语义之外的额外处理,遇到不可访问项跳过)。 */
    public static List<Path> collectFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                Log.warn("遍历时跳过无法访问的路径: %s (%s)", file, exc.getClass().getSimpleName());
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(java.util.Comparator.comparing(Path::toString));
        return files;
    }

    /** 收集「没有任何文件的目录」,用于在 ZIP 里保留空目录结构。 */
    public static List<Path> collectEmptyDirectories(Path dir) throws IOException {
        List<Path> empty = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) {
                if (!directory.equals(dir) && isEmptyDirectory(directory)) {
                    empty.add(directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        empty.sort(java.util.Comparator.comparing(Path::toString));
        return empty;
    }

    private static boolean isEmptyDirectory(Path directory) {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
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
