package com.mcbackup.ui;

import com.mcbackup.App;
import com.mcbackup.ui.components.Icons;
import com.mcbackup.ui.components.NavItem;
import com.mcbackup.ui.components.TLabel;
import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 左侧导航栏:品牌区 + 四个页面入口 + 版本说明。 */
public class Sidebar extends JPanel implements ThemeAware {

    private final Map<String, NavItem> items = new LinkedHashMap<>();

    public Sidebar(Consumer<String> onNavigate) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setPreferredSize(new Dimension(232, 0));
        setBorder(BorderFactory.createEmptyBorder(22, 18, 18, 14));

        add(brand());
        add(Box.createVerticalStrut(26));
        add(navItem("world", NavItem.Glyph.WORLD, "世界", onNavigate));
        add(Box.createVerticalStrut(6));
        add(navItem("backup", NavItem.Glyph.BOX, "备份", onNavigate));
        add(Box.createVerticalStrut(6));
        add(navItem("export", NavItem.Glyph.EXPORT, "导出", onNavigate));
        add(Box.createVerticalStrut(6));
        add(navItem("settings", NavItem.Glyph.GEAR, "设置", onNavigate));
        add(Box.createVerticalGlue());

        TLabel footer = new TLabel("第一阶段 · 浏览与检测", TLabel.Role.MUTED);
        footer.setAlignmentX(LEFT_ALIGNMENT);
        add(footer);
    }

    private JComponent navItem(String key, NavItem.Glyph glyph, String label, Consumer<String> onNavigate) {
        NavItem item = new NavItem(glyph, label, () -> onNavigate.accept(key));
        item.setAlignmentX(LEFT_ALIGNMENT);
        items.put(key, item);
        return item;
    }

    private JComponent brand() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JComponent logo = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(36, 36);
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Palette p = ThemeManager.palette();
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(p.accent());
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                Icons.box(g, 9, 9, 18, p.onAccent());
                g.dispose();
            }
        };
        logo.setPreferredSize(new Dimension(36, 36));
        logo.setMaximumSize(new Dimension(36, 36));

        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        TLabel name = new TLabel(App.NAME, TLabel.Role.TITLE);
        TLabel version = new TLabel("v" + App.VERSION, TLabel.Role.MUTED);
        name.setAlignmentX(LEFT_ALIGNMENT);
        version.setAlignmentX(LEFT_ALIGNMENT);
        texts.add(name);
        texts.add(Box.createVerticalStrut(2));
        texts.add(version);

        row.add(logo);
        row.add(Box.createHorizontalStrut(12));
        row.add(texts);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /** 高亮当前页面。 */
    public void setSelected(String key) {
        items.forEach((name, item) -> item.setSelected(name.equals(key)));
    }

    @Override
    public void onThemeChanged() {
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Palette p = ThemeManager.palette();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(p.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(p.border());
        g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
        g.dispose();
    }

}
