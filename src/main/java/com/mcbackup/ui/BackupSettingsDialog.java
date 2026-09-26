package com.mcbackup.ui;

import com.mcbackup.model.AppSettings;
import com.mcbackup.util.PathUtils;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;

/**
 * 「备份设置…」弹窗:备份位置、间隔、保留份数、压缩方式、完整校验、最小化到托盘。
 *
 * <p>刻意用最朴素的 Swing 控件 + GridBagLayout:一次弹窗改完所有备份相关设置,
 * 不再散落在主设置页里。</p>
 */
public final class BackupSettingsDialog {

    /** 设置结果。 */
    public record Result(String backupDir, int intervalMinutes, int retainCount, boolean fastBackup,
                         boolean fullVerify, boolean minimizeToTray) {
    }

    private BackupSettingsDialog() {
    }

    /** 弹出设置窗口;用户取消时返回 null。 */
    public static Result show(Component parent, AppSettings settings) {
        JTextField dirField = new JTextField(PathUtils.toDisplayPath(settings.backupDirPath()), 28);
        dirField.setEditable(false);
        Path[] chosenDir = {settings.backupDirPath()};
        JButton changeDir = new JButton("更改…");
        changeDir.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择备份目录");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION
                    && chooser.getSelectedFile() != null) {
                chosenDir[0] = chooser.getSelectedFile().toPath();
                dirField.setText(PathUtils.toDisplayPath(chosenDir[0]));
            }
        });

        JComboBox<Integer> intervalCombo = new JComboBox<>(new Integer[]{5, 10, 15, 30, 60, 120});
        intervalCombo.setSelectedItem(nearestInterval(settings.getAutoBackupIntervalMinutes()));
        JComboBox<String> retainCombo = new JComboBox<>(
                new String[]{"5 份", "10 份", "20 份", "50 份", "100 份", "不限制"});
        retainCombo.setSelectedIndex(indexForRetainCount(settings.getRetainCount()));
        JComboBox<String> compressionCombo = new JComboBox<>(
                new String[]{"快速(区域文件不重复压缩)", "体积优先(全部压缩)"});
        compressionCombo.setSelectedIndex(settings.isFastBackup() ? 0 : 1);

        JCheckBox fullVerify = new JCheckBox("备份完成后计算整包 SHA-256(多读一遍磁盘,GB 级世界慢 1~2 秒)",
                settings.isFullVerify());
        JCheckBox tray = new JCheckBox("关闭窗口时最小化到系统托盘,自动备份继续运行",
                settings.isMinimizeToTray());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel("备份位置:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(dirField, c);
        c.gridx = 2;
        c.weightx = 0;
        panel.add(changeDir, c);

        addComboRow(panel, c, row++, "自动备份间隔:", intervalCombo);
        addComboRow(panel, c, row++, "保留份数:", retainCombo);
        addComboRow(panel, c, row++, "压缩方式:", compressionCombo);

        c.gridy = row++;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        panel.add(fullVerify, c);
        c.gridy = row++;
        panel.add(tray, c);

        c.gridy = row++;
        JLabel hint = new JLabel("<html>自动备份只会备份你在「备份」页勾选、并且确实发生变化的存档;<br>"
                + "备份运行中的存档不需要退出游戏。</html>");
        panel.add(hint, c);
        panel.setPreferredSize(new Dimension(560, panel.getPreferredSize().height));

        int answer = JOptionPane.showConfirmDialog(parent, panel, "备份设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        return new Result(
                chosenDir[0].toString(),
                (Integer) intervalCombo.getSelectedItem(),
                retainCountAt(retainCombo.getSelectedIndex()),
                compressionCombo.getSelectedIndex() == 0,
                fullVerify.isSelected(),
                tray.isSelected());
    }

    private static void addComboRow(JPanel panel, GridBagConstraints c, int row, String label,
                                    JComboBox<?> combo) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(combo, c);
        c.gridx = 2;
        c.weightx = 0;
        panel.add(new JLabel(""), c);
    }

    static int retainCountAt(int index) {
        return switch (index) {
            case 0 -> 5;
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 50;
            case 4 -> 100;
            default -> 0;
        };
    }

    static int indexForRetainCount(int count) {
        return switch (count) {
            case 5 -> 0;
            case 10 -> 1;
            case 20 -> 2;
            case 50 -> 3;
            case 100 -> 4;
            default -> count <= 0 ? 5 : 2;
        };
    }

    static int nearestInterval(int minutes) {
        int[] values = {5, 10, 15, 30, 60, 120};
        int best = values[0];
        int bestDiff = Integer.MAX_VALUE;
        for (int value : values) {
            int diff = Math.abs(value - minutes);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = value;
            }
        }
        return best;
    }
}
