package com.mcbackup.util;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 文件系统辅助方法。
 *
 * <p>所有方法都不会一次性把文件读进内存:目录大小统计使用 {@link Files#walkFileTree}
 * 流式遍历,只累加 {@link BasicFileAttributes#size()}。</p>
 */
public final class FileUtils {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** session.lock 锁探测结果。 */
    public enum LockProbe {
        /** 能拿到独占锁:世界当前没有进程持有它 -> 大概率没有在运行。 */
        FREE,
        /** 拿不到锁:有进程正持有它 -> 可能正在运行。 */
        LOCKED,
        /** 文件不存在或权限不足,无法判断。 */
        UNKNOWN,
        /** session.lock 不存在(世界从未被加载过或已被删除)。 */
        MISSING
    }

    private FileUtils() {
    }

    /**
     * 递归统计目录大小。
     *
     * <p>遇到单个文件失败(权限不足、文件被占用后消失)时跳过并继续,不中断统计。</p>
     */
    public static long directorySize(Path dir) {
        if (dir == null) {
            return 0L;
        }
        final long[] total = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        total[0] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    Log.debug("统计大小时跳过无法访问的路径: " + file + " (" + exc.getClass().getSimpleName() + ")");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) {
            Log.warn("统计目录大小失败: %s (%s)", dir, e.getMessage());
        }
        return total[0];
    }

    /** 读取路径的最后修改时间(毫秒);失败返回 0。 */
    public static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException | SecurityException e) {
            return 0L;
        }
    }

    /**
     * 计算「世界是否变化」的廉价指纹:取 level.dat、region 目录与世界顶层条目的
     * 最大修改时间。不递归遍历 region 里的每个文件,保证空闲时几乎不产生磁盘 IO。
     */
    public static long worldChangeStamp(Path worldDir) {
        long max = lastModifiedMillis(worldDir.resolve("level.dat"));
        max = Math.max(max, lastModifiedMillis(worldDir.resolve("region")));
        max = Math.max(max, lastModifiedMillis(worldDir.resolve("playerdata")));
        try (var stream = Files.newDirectoryStream(worldDir)) {
            for (Path child : stream) {
                max = Math.max(max, lastModifiedMillis(child));
            }
        } catch (IOException | SecurityException e) {
            Log.debug("读取世界顶层条目失败: " + worldDir + " (" + e.getMessage() + ")");
        }
        return max;
    }

    /**
     * 通过尝试获取 session.lock 的独占锁来判断世界是否可能正在运行。
     *
     * <p><b>这只是启发式判断,不是权威结论。</b>Minecraft 在加载世界时会锁定 session.lock,
     * 所以外部程序拿不到锁通常意味着世界正在被使用。为了不给游戏添麻烦,探测到锁可用时
     * 必须立刻释放;所有失败路径都返回 UNKNOWN,绝不抛出。</p>
     *
     * <p>这里用「只读打开 + 共享锁」探测,而不是写打开:一是不会要求写权限(存档可能只读),
     * 二是共享锁与 Minecraft 的独占锁天然互斥,不需要写入任何字节。</p>
     */
    public static LockProbe probeSessionLock(Path worldDir) {
        Path lockFile = worldDir.resolve("session.lock");
        if (!PathUtils.isFile(lockFile)) {
            return LockProbe.MISSING;
        }
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.READ)) {
            FileLock lock = null;
            try {
                lock = channel.tryLock(0L, Long.MAX_VALUE, true);
                return lock == null ? LockProbe.LOCKED : LockProbe.FREE;
            } catch (OverlappingFileLockException e) {
                return LockProbe.LOCKED;
            } finally {
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (IOException e) {
                        Log.debug("释放 session.lock 锁失败: " + e.getMessage());
                    }
                }
            }
        } catch (IOException | SecurityException e) {
            Log.debug("无法探测 session.lock: " + lockFile + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return LockProbe.UNKNOWN;
        }
    }

    /** 人类可读的大小,例如 "423 MB"。 */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        // KB 不显示小数;MB 及以上在数值小于 100 时保留一位小数(11.5 MB),否则取整(423 MB)
        String pattern = (unit <= 0 || value >= 100) ? "%.0f %s" : "%.1f %s";
        return String.format(Locale.ROOT, pattern, value, units[unit]);
    }

    /** 相对时间描述,例如 "5 分钟前";无法计算时返回绝对时间。 */
    public static String relativeTime(long millis) {
        if (millis <= 0) {
            return "—";
        }
        long diff = System.currentTimeMillis() - millis;
        if (diff < 0) {
            diff = 0;
        }
        long seconds = diff / 1000;
        if (seconds < 60) {
            return "刚刚";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + " 天前";
        }
        return absoluteTime(millis);
    }

    public static String absoluteTime(long millis) {
        if (millis <= 0) {
            return "—";
        }
        return DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    public static String shortTime(long millis) {
        if (millis <= 0) {
            return "—";
        }
        return DATE_TIME_SHORT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** 在资源管理器中定位到目录;失败返回 false。 */
    public static boolean openInFileBrowser(Path target) {
        if (target == null) {
            return false;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(filesTarget(target).toFile());
                return true;
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("Desktop.open 失败,尝试 explorer.exe: " + e.getMessage());
        }
        if (PathUtils.isWindows()) {
            try {
                new ProcessBuilder("explorer.exe", PathUtils.toDisplayPath(filesTarget(target))).start();
                return true;
            } catch (IOException e) {
                Log.warn("无法打开目录 %s: %s", target, e.getMessage());
            }
        }
        return false;
    }

    private static Path filesTarget(Path target) {
        return Files.isDirectory(target) ? target : target.getParent();
    }

    /** 复制文本到剪贴板;失败返回 false。 */
    public static boolean copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return true;
        } catch (RuntimeException e) {
            Log.warn("复制到剪贴板失败: %s", e.getMessage());
            return false;
        }
    }
}
