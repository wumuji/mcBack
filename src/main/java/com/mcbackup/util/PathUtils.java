package com.mcbackup.util;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Windows 路径处理工具。
 *
 * <p>这里的每个方法都对应一类真实故障:用户在设置里粘贴带引号/带环境变量的路径、
 * 启动器配置里写的是 {@code %APPDATA%\\.minecraft}、部署路径超过 260 字符等。</p>
 */
public final class PathUtils {

    /** Windows 单个路径段的非法字符(不含路径分隔符)。 */
    private static final char[] ILLEGAL_NAME_CHARS = {'<', '>', ':', '"', '/', '\\', '|', '?', '*'};

    /** 超过这个长度就视为「长路径」,在日志里提示用户。 */
    private static final int LONG_PATH_THRESHOLD = 240;

    private PathUtils() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 规范化用户输入的路径文本:去空白、去首尾引号、展开 %VAR%(不区分大小写)。
     * 返回 null 表示输入无法解析。
     */
    public static String cleanInput(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).strip();
        }
        value = expandEnvironmentVariables(value);
        // Windows 资源管理器复制路径时可能带上尾随空格
        while (value.length() > 1 && (value.endsWith(" ") || value.endsWith("."))) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /** 展开 %NAME% 形式的环境变量;未知变量保持原样。 */
    public static String expandEnvironmentVariables(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%') {
                int end = value.indexOf('%', i + 1);
                if (end > i + 1) {
                    String name = value.substring(i + 1, end);
                    String env = System.getenv(name);
                    if (env == null) {
                        env = System.getenv(name.toUpperCase(Locale.ROOT));
                    }
                    if (env != null) {
                        result.append(env);
                        i = end + 1;
                        continue;
                    }
                }
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    /** 解析用户输入为绝对路径;失败返回 null。 */
    public static Path toPath(String raw) {
        String cleaned = cleanInput(raw);
        if (cleaned == null) {
            return null;
        }
        try {
            return Paths.get(cleaned).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 是否属于 Windows 长路径(超过 240 字符)。
     *
     * <p>说明:JDK 的 {@code Path} 会把 {@code \\?\} 前缀规范化掉(实测 JDK 21 上
     * {@code Path.of("\\\\?\\E:\\a...")} 会退回普通路径),所以 NIO 操作直接使用普通路径;
     * 需要显式长路径前缀时(例如把路径交给外部程序)用 {@link #toExtendedString(Path)}。</p>
     */
    public static boolean isLongPath(Path path) {
        return path != null && path.toString().length() > LONG_PATH_THRESHOLD;
    }

    /** 生成带 {@code \\?\} 前缀的路径字符串;UNC 路径使用 {@code \\?\UNC\} 前缀。 */
    public static String toExtendedString(Path path) {
        if (path == null) {
            return "";
        }
        String text = path.toString();
        if (text.startsWith("\\\\?\\")) {
            return text;
        }
        if (text.startsWith("\\\\")) {
            return "\\\\?\\UNC\\" + text.substring(2);
        }
        return "\\\\?\\" + text;
    }

    /** 去掉 {@code \\?\} 前缀,便于在界面上显示。 */
    public static String toDisplayPath(Path path) {
        String text = path.toString();
        if (text.startsWith("\\\\?\\UNC\\")) {
            return "\\\\" + text.substring(8);
        }
        if (text.startsWith("\\\\?\\")) {
            return text.substring(4);
        }
        return text;
    }

    /** 把任意文本转换为合法 Windows 文件名(导出阶段会用到,先在这里统一实现并测试)。 */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || indexOf(ILLEGAL_NAME_CHARS, c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString();
        while (result.endsWith(" ") || result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        // Windows 保留设备名
        String upper = result.toUpperCase(Locale.ROOT);
        for (String reserved : new String[]{"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
                "LPT1", "LPT2", "LPT3"}) {
            if (upper.equals(reserved)) {
                return "_" + result;
            }
        }
        return result.isEmpty() ? "unnamed" : result;
    }

    private static int indexOf(char[] chars, char c) {
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /** 判断路径是否已存在且是目录(吞掉权限异常)。 */
    public static boolean isDirectory(Path path) {
        try {
            return path != null && Files.isDirectory(path);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 判断路径是否已存在且是普通文件(吞掉权限异常)。 */
    public static boolean isFile(Path path) {
        try {
            return path != null && Files.isRegularFile(path);
        } catch (SecurityException e) {
            return false;
        }
    }
}
