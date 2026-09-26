package com.mcback.ui.theme;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 字体工具:优先使用 Windows 中文界面字体(微软雅黑),找不到时回退到逻辑字体。
 * 不打包字体文件,避免额外体积与授权问题。
 */
public final class UiFonts {

    private static final String[] PREFERRED = {"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "Dialog"};
    private static final String FAMILY = pickFamily();

    private UiFonts() {
    }

    private static String pickFamily() {
        try {
            Set<String> available = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String candidate : PREFERRED) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (RuntimeException e) {
            // 无图形环境(例如单元测试)时忽略
        }
        return "Dialog";
    }

    public static String family() {
        return FAMILY;
    }

    public static Font regular(float size) {
        return new Font(FAMILY, Font.PLAIN, Math.round(size));
    }

    public static Font medium(float size) {
        return new Font(FAMILY, Font.BOLD, Math.round(size));
    }

    public static Font bold(float size) {
        return new Font(FAMILY, Font.BOLD, Math.round(size));
    }
}
