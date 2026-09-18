package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.ThemeAware;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * 空状态提示:标题 + 说明 + 若干操作按钮。
 *
 * <p>文本与按钮都可以动态替换,方便在「没有世界」「没有选中世界」等场景复用。</p>
 */
public class EmptyState extends JPanel implements ThemeAware {

    private final TLabel title = new TLabel("", TLabel.Role.H2);
    private final TLabel subtitle = new TLabel("", TLabel.Role.MUTED);
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));

    public EmptyState() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        actions.setOpaque(false);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        actions.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        subtitle.setHorizontalAlignment(SwingConstants.CENTER);
        add(Box.createVerticalGlue());
        add(title);
        add(Box.createVerticalStrut(8));
        add(subtitle);
        add(Box.createVerticalStrut(18));
        add(actions);
        add(Box.createVerticalGlue());
        setPreferredSize(new Dimension(360, 240));
    }

    public void setTitle(String text) {
        title.setText(text);
        revalidate();
        repaint();
    }

    /** 说明文字。 */
    public void setSubtitle(String text) {
        subtitle.setText(text);
        revalidate();
        repaint();
    }

    public void setActions(JComponent... components) {
        actions.removeAll();
        for (JComponent component : components) {
            actions.add(component);
        }
        actions.revalidate();
        actions.repaint();
        revalidate();
        repaint();
    }

    @Override
    public void onThemeChanged() {
        invalidate();
        repaint();
    }

    // 空状态不再画虚线边框:少一层绘制,视觉也更干净
}
