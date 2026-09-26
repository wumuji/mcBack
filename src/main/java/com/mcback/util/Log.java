package com.mcback.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 极简日志器:按天一个文件 {@code logs/yyyy-MM-dd.log},UTF-8,每条记录立即 flush。
 *
 * <p>为什么不用日志框架:第一阶段日志量很小(启动、扫描、错误),自写 100 行即可,
 * 避免引入第三方依赖。日志写入失败不会影响程序运行,只回退到 stderr。</p>
 */
public final class Log {

    /** 保留最近多少天的日志。 */
    private static final int RETAIN_DAYS = 14;

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private static Path logsDir;
    private static LocalDate openDay;
    private static BufferedWriter writer;
    private static PrintStream fallback = System.err;
    private static boolean consoleEcho;
    /** 文件日志不可用时置为 true,避免每一行都重复报错(磁盘满、权限不足等)。 */
    private static boolean fileLoggingDisabled;

    private Log() {
    }

    /**
     * 初始化日志目录。失败时不抛异常(仅 stderr 提示),保证程序仍可启动。
     *
 * @param dir 日志目录(例如 %APPDATA%\mcBack\logs)
     */
    public static void init(Path dir) {
        synchronized (LOCK) {
            closeQuietly();
            logsDir = dir;
            try {
                Files.createDirectories(dir);
                cleanupOldLogs(dir);
            } catch (IOException e) {
                fallback.println("[log] 无法创建日志目录 " + dir + ": " + e.getMessage());
            }
        }
    }

    /** 是否同时输出到控制台(开发调试时有用)。 */
    public static void setConsoleEcho(boolean enabled) {
        consoleEcho = enabled;
    }

    public static Path logsDir() {
        return logsDir;
    }

    /** 当前正在写入的日志文件(可能尚未创建)。 */
    public static Path currentFile() {
        LocalDate day = logsDir == null ? LocalDate.now() : LocalDate.now();
        Path dir = logsDir;
        return dir == null ? null : dir.resolve(FILE_DATE.format(day) + ".log");
    }

    public static void info(String message, Object... args) {
        write("INFO", format(message, args), null);
    }

    public static void warn(String message, Object... args) {
        write("WARN", format(message, args), null);
    }

    /** 细节日志(例如单个文件无法访问)。写入频率低,便于排查问题。 */
    public static void debug(String message, Object... args) {
        write("DEBUG", format(message, args), null);
    }

    public static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    /** 记录异常但不中断流程;message 用于说明当时在做哪一步。 */
    public static void errorQuietly(String message, Throwable error) {
        write("ERROR", message, error);
    }

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        try {
            return String.format(Locale.ROOT, message, args);
        } catch (RuntimeException e) {
            return message;
        }
    }

    private static void write(String level, String message, Throwable error) {
        String line = STAMP.format(LocalDateTime.now()) + " " + pad(level) + " [" + Thread.currentThread().getName() + "] " + message;
        synchronized (LOCK) {
            try {
                if (fileLoggingDisabled) {
                    throw new IOException("文件日志已停用");
                }
                ensureWriter();
                if (writer != null) {
                    writer.write(line);
                    writer.newLine();
                    if (error != null) {
                        StringWriter buffer = new StringWriter();
                        error.printStackTrace(new PrintWriter(buffer));
                        writer.write(buffer.toString());
                    }
                    writer.flush();
                } else {
                    if (!consoleEcho) {
                        fallback.println(line);
                    }
                }
            } catch (IOException e) {
                if (!fileLoggingDisabled) {
                    fileLoggingDisabled = true;
                    if (!consoleEcho) {
                        fallback.println("[log] 无法写入日志文件,本次运行改为只输出到控制台: " + e.getMessage());
                    }
                    closeQuietly();
                }
                if (!consoleEcho) {
                    fallback.println(line);
                }
            }
            if (consoleEcho) {
                System.out.println(line);
                if (error != null) {
                    error.printStackTrace(System.out);
                }
            }
        }
    }

    private static String pad(String level) {
        return (level + "     ").substring(0, 5);
    }

    private static void ensureWriter() throws IOException {
        if (logsDir == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (writer != null && today.equals(openDay)) {
            return;
        }
        closeQuietly();
        Path file = logsDir.resolve(FILE_DATE.format(today) + ".log");
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        openDay = today;
    }

    private static void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
            writer = null;
            openDay = null;
        }
    }

    /** 删除超过保留天数的旧日志;失败只记录,不抛异常。 */
    private static void cleanupOldLogs(Path dir) {
        LocalDate cutoff = LocalDate.now().minusDays(RETAIN_DAYS);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.log")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.length() < 10) {
                    continue;
                }
                try {
                    LocalDate day = LocalDate.parse(name.substring(0, 10), FILE_DATE);
                    if (day.isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                } catch (RuntimeException ignored) {
                    // 文件名不是日期格式,跳过
                }
            }
        } catch (IOException e) {
            fallback.println("[log] 清理旧日志失败: " + e.getMessage());
        }
    }
}
