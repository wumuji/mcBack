package com.mcbackup.service;

import com.mcbackup.model.WorldInfo;
import com.mcbackup.util.Log;
import com.mcbackup.util.NbtReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 从 level.dat 提取世界信息。
 *
 * <p>这是增强功能:任何失败(文件损坏、结构不符、超出 {@link NbtReader} 的安全上限)都会
 * 记录日志并返回 {@link WorldInfo#UNAVAILABLE},不会抛给调用方,保证扫描不中断。</p>
 */
public final class LevelInfoReader {

    private LevelInfoReader() {
    }

    /** 读取 level.dat;失败时返回不可用信息。 */
    public static WorldInfo read(Path levelDat) {
        if (levelDat == null) {
            return WorldInfo.UNAVAILABLE;
        }
        try {
            Map<String, Object> root = NbtReader.readFile(levelDat);
            Map<String, Object> data = asMap(root.get("Data"));
            if (data == null) {
                data = root;
            }

            String name = firstNonBlank(
                    asString(data.get("LevelName")),
                    asString(data.get("TileName")),
                    asString(root.get("LevelName")));

            String versionName = "";
            Object version = data.get("Version");
            if (version instanceof Map<?, ?> versionMap) {
                versionName = firstNonBlank(
                        asString(versionMap.get("Name")),
                        asString(versionMap.get("Id")));
            } else if (version != null) {
                // 1.8 及更早版本这里存的是世界格式版本号
                versionName = "格式 " + asString(version);
            }

            int dataVersion = (int) asLong(data.get("DataVersion"), 0L);
            int gameType = (int) asLong(data.get("GameType"), -1L);
            int difficulty = (int) asLong(data.get("Difficulty"), -1L);
            boolean hardcore = asLong(data.get("Hardcore"), 0L) != 0L;
            long dayTime = asLong(data.get("DayTime"), 0L);

            return WorldInfo.available(name, versionName, dataVersion,
                    gameModeText(gameType), difficultyText(difficulty), hardcore,
                    dayTime > 0 ? dayTime / 24000L : 0L);
        } catch (IOException | RuntimeException e) {
            Log.warn("解析 level.dat 失败,世界信息将显示为「—」: %s (%s)", levelDat, e.getMessage());
            return WorldInfo.UNAVAILABLE;
        }
    }

    /** GameType -> 中文模式名。 */
    public static String gameModeText(int gameType) {
        return switch (gameType) {
            case 0 -> "生存";
            case 1 -> "创造";
            case 2 -> "冒险";
            case 3 -> "旁观";
            default -> "";
        };
    }

    /** Difficulty -> 中文难度名。 */
    public static String difficultyText(int difficulty) {
        return switch (difficulty) {
            case 0 -> "和平";
            case 1 -> "简单";
            case 2 -> "普通";
            case 3 -> "困难";
            default -> "";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "" : asString(list.get(0));
        }
        return String.valueOf(value);
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1L : 0L;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
