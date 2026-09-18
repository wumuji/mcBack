package com.mcbackup.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows 路径处理测试:覆盖用户粘贴路径时的各种脏数据。 */
class PathUtilsTest {

    @Test
    void cleansQuotedAndPaddedInput() {
        assertEquals("D:\\MC\\.minecraft", PathUtils.cleanInput("  \"D:\\MC\\.minecraft\"  "));
        assertEquals("D:\\MC", PathUtils.cleanInput("D:\\MC   "));
        assertEquals("D:\\MC", PathUtils.cleanInput("\"D:\\MC.\""));
        assertNull(PathUtils.cleanInput("   "));
        assertNull(PathUtils.cleanInput(null));
    }

    @Test
    void expandsEnvironmentVariables() {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return;
        }
        assertEquals(appData + "\\.minecraft", PathUtils.expandEnvironmentVariables("%APPDATA%\\.minecraft"));
        // 未知变量保持原样,便于用户看出自己写错了
        assertEquals("%NOT_EXIST_VAR%\\x", PathUtils.expandEnvironmentVariables("%NOT_EXIST_VAR%\\x"));
    }

    @Test
    void sanitizesIllegalFileNames() {
        assertEquals("Survival_2026_09_18", PathUtils.sanitizeFileName("Survival:2026/09/18"));
        assertEquals("abc_def", PathUtils.sanitizeFileName("abc?def"));
        assertEquals("_CON", PathUtils.sanitizeFileName("CON"));
        assertEquals("trailing", PathUtils.sanitizeFileName("trailing..."));
        assertEquals("unnamed", PathUtils.sanitizeFileName("   "));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void identifiesLongPathsAndBuildsExtendedString() {
        String longSegment = "a".repeat(120);
        Path path = Path.of("E:\\", longSegment, longSegment, "world");
        assertTrue(PathUtils.isLongPath(path), "超过 240 字符应被判定为长路径");
        String extended = PathUtils.toExtendedString(path);
        assertTrue(extended.startsWith("\\\\?\\"), "外部程序使用的路径应带扩展前缀");
        assertEquals("\\\\?\\" + path, extended);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void treatsShortPathsAsNormal() {
        Path path = Path.of("E:\\mcbackup\\saves");
        assertTrue(!PathUtils.isLongPath(path));
    }

    @Test
    void parsesUserInputToAbsolutePath() {
        Path path = PathUtils.toPath("  \"E:\\minecraft\\saves\" ");
        assertNotNull(path);
        assertTrue(path.isAbsolute());
        assertNull(PathUtils.toPath("   "));
    }
}
