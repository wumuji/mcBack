package com.mcbackup.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 这条链路曾经真的踩过:机器启用了辅助功能 + 裁剪后的运行时缺 jdk.accessibility
// -> AWT 直接 AWTError,程序连窗口都开不出来。
class AccessibilityGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsMissingClasses() {
        List<String> missing = AccessibilityGuard.missingClasses(List.of(
                "java.lang.String",
                "com.mcbackup.NotExistingClass",
                "com.sun.java.accessibility.AccessBridge"));

        assertTrue(missing.contains("com.mcbackup.NotExistingClass"));
        assertFalse(missing.contains("java.lang.String"), "存在的类不应被误判为缺失");
    }

    @Test
    void keepsWorkingWhenNothingConfigured() {
        // 本机没有配置辅助功能时,守卫不应改动任何东西
        assertFalse(AccessibilityGuard.missingClasses(List.of()).stream().findAny().isPresent());
    }

    @Test
    void parsesTechnologiesFromPropertiesFile() throws Exception {
        Path file = tempDir.resolve(".accessibility.properties");
        Files.writeString(file, """
                # comment
                assistive_technologies=com.sun.java.accessibility.AccessBridge
                screen_magnifier_present=true
                """);

        List<String> parsed = AccessibilityGuard.technologiesInFile(file);

        assertEquals(List.of("com.sun.java.accessibility.AccessBridge"), parsed);
        assertTrue(AccessibilityGuard.technologiesInFile(tempDir.resolve("not-there.properties")).isEmpty());
    }

    @Test
    void disablesAssistiveTechnologyWhenClassIsMissing() {
        String key = "javax.accessibility.assistive_technologies";
        String original = System.getProperty(key);
        try {
            boolean handled = AccessibilityGuard.apply(List.of("com.mcbackup.DefinitelyMissingAssistiveClass"));

            assertTrue(handled, "缺失的辅助功能类应被识别出来");
            assertEquals("", System.getProperty(key), "应只在本进程里临时置空,避免 AWT 启动崩溃");
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    @Test
    void leavesExistingClassAlone() {
        String key = "javax.accessibility.assistive_technologies";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "java.lang.String");
            assertFalse(AccessibilityGuard.apply(List.of("java.lang.String")), "存在的类不应触发降级");
            assertEquals("java.lang.String", System.getProperty(key), "不需要降级时不应改动属性");
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }
}
