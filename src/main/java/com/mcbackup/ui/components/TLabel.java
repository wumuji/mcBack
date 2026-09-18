package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.ui.theme.UiFonts;

import javax.swing.JLabel;

/**
 * 带语义角色的标签。角色决定字体与颜色,避免每处都手写颜色导致主题切换时漏改。
 */
public class TLabel extends JLabel implements ThemeAware {

    /** 语义角色。 */
    public enum Role {
        /** 页面主标题(20px 粗体)。 */
        H1,
        /** 卡片标题(15px 粗体)。 */
        H2,
        /** 列表项标题(14px 粗体)。 */
        TITLE,
        /** 正文(13px)。 */
        BODY,
        /** 次要说明(12px 灰色)。 */
        MUTED,
        /** 强调文字(13px 强调色)。 */
        ACCENT
    }

    private final Role role;

    public TLabel(String text) {
        this(text, Role.BODY);
    }

    public TLabel(String text, Role role) {
        super(text == null ? "" : text);
        this.role = role;
        apply();
    }

    public Role role() {
        return role;
    }

    private void apply() {
        Palette p = ThemeManager.palette();
        switch (role) {
            case H1 -> {
                setFont(UiFonts.bold(20));
                setForeground(p.text());
            }
            case H2 -> {
                setFont(UiFonts.bold(15));
                setForeground(p.text());
            }
            case TITLE -> {
                setFont(UiFonts.medium(14));
                setForeground(p.text());
            }
            case MUTED -> {
                setFont(UiFonts.regular(12));
                setForeground(p.textMuted());
            }
            case ACCENT -> {
                setFont(UiFonts.medium(13));
                setForeground(p.accent());
            }
            default -> {
                setFont(UiFonts.regular(13));
                setForeground(p.text());
            }
        }
    }

    @Override
    public void onThemeChanged() {
        apply();
    }
}
