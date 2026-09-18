package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** 把 Swing 默认滚动条替换成细圆角样式,并让滚动容器背景透明。 */
public final class ScrollPaneStyler {

    private ScrollPaneStyler() {
    }

    public static void apply(JScrollPane scrollPane) {
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        vertical.setUI(new SlimScrollBarUI());
        vertical.setPreferredSize(new Dimension(10, 0));
        vertical.setUnitIncrement(18);
        vertical.setOpaque(false);

        JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
        horizontal.setUI(new SlimScrollBarUI());
        horizontal.setPreferredSize(new Dimension(0, 10));
        horizontal.setUnitIncrement(18);
        horizontal.setOpaque(false);
    }

    /** 递归应用到整棵组件树(主题切换后需要重新应用)。 */
    public static void applyRecursively(Container container) {
        if (container instanceof JScrollPane scrollPane) {
            apply(scrollPane);
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container childContainer) {
                applyRecursively(childContainer);
            }
        }
    }

    private static final class SlimScrollBarUI extends BasicScrollBarUI {

        @Override
        protected void configureScrollBarColors() {
            // 颜色在绘制时从当前调色板读取
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            // 不透明的话会用 LAF 默认底色,深色主题下会露出浅色小方块
            button.setOpaque(false);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            button.setBorder(BorderFactory.createEmptyBorder());
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            // 轨道保持透明
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            if (bounds.isEmpty() || !c.isEnabled()) {
                return;
            }
            Palette p = ThemeManager.palette();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hovered = isThumbRollover();
            g2.setColor(p.alpha(p.textMuted(), hovered ? 170 : 110));
            int width = Math.min(bounds.width, 8);
            int x = bounds.x + (bounds.width - width) / 2;
            g2.fillRoundRect(x, bounds.y + 2, width, Math.max(12, bounds.height - 4), width, width);
            g2.dispose();
        }
    }
}
