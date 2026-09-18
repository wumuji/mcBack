package com.mcbackup.ui;

import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.components.TLabel;
import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** 顶栏:页面标题、扫描状态,以及「重新扫描」「切换主题」两个快捷操作。 */
public class TopBar extends JPanel implements ThemeAware {

    private final TLabel titleLabel = new TLabel("世界", TLabel.Role.H1);
    private final TLabel statusLabel = new TLabel("准备扫描…", TLabel.Role.MUTED);
    private final FlatButton themeButton;
    private final FlatButton rescanButton;

    public TopBar(Runnable onRescan, Runnable onToggleTheme) {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(20, 24, 14, 24));

        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        texts.add(titleLabel);
        texts.add(Box.createVerticalStrut(6));
        texts.add(statusLabel);
        add(texts, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        rescanButton = new FlatButton("重新扫描");
        rescanButton.setToolTipText("重新扫描所有存档目录");
        rescanButton.addActionListener(e -> onRescan.run());
        themeButton = new FlatButton("浅色", FlatButton.Variant.GHOST);
        themeButton.setToolTipText("在浅色与深色之间切换");
        themeButton.addActionListener(e -> onToggleTheme.run());
        buttons.add(rescanButton);
        buttons.add(themeButton);
        add(buttons, BorderLayout.EAST);

        refreshThemeButtonText();
    }

    public void setTitle(String text) {
        titleLabel.setText(text);
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public void setRescanEnabled(boolean enabled) {
        rescanButton.setEnabled(enabled);
    }

    /** 按钮文字表示「点击后会切换成什么主题」。 */
    public final void refreshThemeButtonText() {
        themeButton.setText(ThemeManager.palette().dark() ? "浅色" : "深色");
        themeButton.repaint();
    }

    @Override
    public void onThemeChanged() {
        refreshThemeButtonText();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Palette p = ThemeManager.palette();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(p.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(p.border());
        g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
        g.dispose();
    }
}
