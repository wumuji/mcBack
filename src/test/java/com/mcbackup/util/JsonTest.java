package com.mcbackup.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON 读写器测试:既要能解析配置文件,也要在损坏时明确报错。 */
class JsonTest {

    @Test
    void parsesNestedStructures() {
        Map<String, Object> parsed = Json.parseObject("""
                {
                  "theme": "DARK",
                  "count": 12,
                  "ratio": 1.5,
                  "enabled": true,
                  "manualWorldDirs": ["C:\\\\saves", "E:\\\\minecraft\\\\saves"],
                  "nested": {"a": [1, 2, 3], "b": null}
                }
                """);

        assertEquals("DARK", Json.optString(parsed, "theme", "LIGHT"));
        assertEquals(12, Json.optInt(parsed, "count", 0));
        assertTrue(Json.optBoolean(parsed, "enabled", false));
        assertEquals(List.of("C:\\saves", "E:\\minecraft\\saves"), Json.optStringList(parsed, "manualWorldDirs"));
        assertEquals(1.5, ((Number) parsed.get("ratio")).doubleValue());
        assertTrue(parsed.get("nested") instanceof Map);
    }

    @Test
    void roundTripsThroughWriter() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("schemaVersion", 1);
        original.put("theme", "FOLLOW_SYSTEM");
        original.put("manualWorldDirs", List.of("E:\\ai项目\\存档", "D:\\MC\\saves"));
        original.put("flag", false);

        Map<String, Object> reparsed = Json.parseObject(Json.write(original));

        assertEquals(((Number) original.get("schemaVersion")).longValue(),
                ((Number) reparsed.get("schemaVersion")).longValue());
        assertEquals(original.get("theme"), reparsed.get("theme"));
        assertEquals(original.get("manualWorldDirs"), reparsed.get("manualWorldDirs"));
        assertEquals(Boolean.FALSE, reparsed.get("flag"));
    }

    @Test
    void escapesSpecialCharacters() {
        Map<String, Object> value = Map.of("text", "引号\"\n换行\t制表\\反斜杠");
        Map<String, Object> reparsed = Json.parseObject(Json.write(value));
        assertEquals("引号\"\n换行\t制表\\反斜杠", reparsed.get("text"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(Json.JsonException.class, () -> Json.parseObject("{\"a\": }"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("{\"a\": 1"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1,2,3]"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject(""));
    }

    @Test
    void returnsDefaultsForMissingKeys() {
        Map<String, Object> parsed = Json.parseObject("{}");
        assertEquals("fallback", Json.optString(parsed, "missing", "fallback"));
        assertEquals(7, Json.optInt(parsed, "missing", 7));
        assertFalse(Json.optBoolean(parsed, "missing", false));
        assertTrue(Json.optStringList(parsed, "missing").isEmpty());
    }
}
