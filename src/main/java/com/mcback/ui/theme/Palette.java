package com.mcback.ui.theme;

import java.awt.Color;

/**
 * 配色方案。数值在计划里已经锁定,界面代码只从这里取色,避免风格不一致。
 */
public record Palette(
        boolean dark,
        Color background,
        Color surface,
        Color surfaceHover,
        Color border,
        Color text,
        Color textMuted,
        Color accent,
        Color accentHover,
        Color danger,
        Color warning,
        Color selectionFill) {

    public static final Palette DARK = new Palette(
            true,
            new Color(0x12151A),
            new Color(0x1A1F27),
            new Color(0x212733),
            new Color(0x262C36),
            new Color(0xE6E9EF),
            new Color(0x98A2B3),
            new Color(0x46C07A),
            new Color(0x3BAA6A),
            new Color(0xE5534B),
            new Color(0xE0A33E),
            new Color(0x1F3A2C));

    public static final Palette LIGHT = new Palette(
            false,
            new Color(0xF5F6F8),
            new Color(0xFFFFFF),
            new Color(0xF0F2F5),
            new Color(0xE3E6EC),
            new Color(0x1B1F27),
            new Color(0x5B6472),
            new Color(0x2E9E5B),
            new Color(0x27864E),
            new Color(0xD9483F),
            new Color(0xC98A22),
            new Color(0xE3F4E9));

    /** 主按钮上的文字颜色:深色主题的强调色较亮,用深色文字更清楚。 */
    public Color onAccent() {
        return dark ? new Color(0x0E1418) : Color.WHITE;
    }

    /** 带透明度的颜色(悬停底色、状态 pill 等)。 */
    public Color alpha(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), clamp(alpha));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
