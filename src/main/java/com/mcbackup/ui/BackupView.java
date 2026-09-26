package com.mcbackup.ui;

import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.ui.components.Card;
import com.mcbackup.ui.components.EmptyState;
import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.components.Pill;
import com.mcbackup.ui.components.ScrollPaneStyler;
import com.mcbackup.ui.components.TLabel;
import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 「备份」页面:备份列表 + 立即备份 + 每份备份的导出/删除。
 *
 * <p>界面保持简单:一行动作按钮、一个列表,不做图表和动画。</p>
 */
public class BackupView extends JPanel implements ThemeAware {

    /** 页面回调(选文件的操作留在界面里,后台任务交给 MainWindow)。 */
    public interface Callbacks {

        void onBackupNow();

        void onOpenBackupDir();

        void onExportRecord(BackupRecord record, Path targetZip);

        void onDeleteRecord(BackupRecord record);

        void onVerifyRecord(BackupRecord record);

        void onRestoreRecord(BackupRecord record);
    }

    private final Callbacks callbacks;
    private final FlatButton backupButton = new FlatButton("立即备份", FlatButton.Variant.PRIMARY);
    private final TLabel autoStatus = new TLabel("自动备份:已关闭", TLabel.Role.MUTED);
    private final TLabel summary = new TLabel("", TLabel.Role.MUTED);
    private final JPanel listPanel = new JPanel();
    private final Card listCard = new Card(new BorderLayout(0, 10));
    private final EmptyState emptyState = new EmptyState();
    private final JScrollPane listScroll;

    private MinecraftWorld selectedWorld;
    private List<BackupRecord> records = List.of();

    public BackupView(Callbacks callbacks) {
        this.callbacks = callbacks;
        setOpaque(false);
        setLayout(new BorderLayout(0, 14));

        add(buildToolbar(), BorderLayout.NORTH);

        listCard.add(buildListHeader(), BorderLayout.NORTH);
        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listScroll = new JScrollPane(listPanel);
        ScrollPaneStyler.apply(listScroll);
        listCard.add(listScroll, BorderLayout.CENTER);
        add(listCard, BorderLayout.CENTER);

        updateBackupButton();
        renderRecords();
    }

    private JPanel buildToolbar() {
        Card card = new Card(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);

        backupButton.setToolTipText("选择世界后即可备份");
        backupButton.addActionListener(e -> {
            if (selectedWorld != null) {
                callbacks.onBackupNow();
            }
        });
        FlatButton openDir = new FlatButton("打开备份目录");
        openDir.addActionListener(e -> callbacks.onOpenBackupDir());
        left.add(backupButton);
        left.add(openDir);
        card.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(autoStatus);
        card.add(right, BorderLayout.EAST);
        return card;
    }

    private JPanel buildListHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(new TLabel("备份列表", TLabel.Role.H2), BorderLayout.WEST);
        header.add(summary, BorderLayout.EAST);
        return header;
    }

    /** 更新当前选中的世界(决定「立即备份」是否可用)。 */
    public void setSelectedWorld(MinecraftWorld world) {
        this.selectedWorld = world;
        updateBackupButton();
    }

    /** 更新自动备份状态文字。 */
    public void setAutoBackupStatus(boolean enabled, int intervalMinutes) {
        autoStatus.setText(enabled ? "自动备份:每 " + intervalMinutes + " 分钟" : "自动备份:已关闭");
        autoStatus.onThemeChanged();
        repaint();
    }

    /** 刷新列表。 */
    public void setBackups(List<BackupRecord> newRecords) {
        this.records = newRecords == null ? List.of() : newRecords;
        renderRecords();
    }

    /** 备份过程中禁用会冲突的按钮。 */
    public void setBusy(boolean busy) {
        backupButton.setEnabled(!busy && selectedWorld != null);
        backupButton.setText(busy ? "备份中…" : "立即备份");
    }

    private void updateBackupButton() {
        boolean enabled = selectedWorld != null;
        backupButton.setEnabled(enabled);
        backupButton.setText("立即备份");
        backupButton.setToolTipText(enabled
                ? "备份 " + selectedWorld.displayName() + "(" + FileUtils.humanSize(selectedWorld.sizeBytes()) + ")"
                : "先在「世界」页面选择要备份的世界");
    }

    private void renderRecords() {
        listPanel.removeAll();
        if (records.isEmpty()) {
            emptyState.setTitle("还没有备份");
            emptyState.setSubtitle("在「世界」页面选择世界后点「立即备份」,或到设置里打开自动备份");
            listCard.remove(listScroll);
            listCard.add(emptyState, BorderLayout.CENTER);
        } else {
            listCard.remove(emptyState);
            for (BackupRecord record : records) {
                listPanel.add(new BackupRow(record).component());
                listPanel.add(Box.createVerticalStrut(6));
            }
            if (listCard.getComponentCount() == 1) {
                listCard.add(listScroll, BorderLayout.CENTER);
            }
        }
        long totalBytes = records.stream().mapToLong(BackupRecord::zipBytes).sum();
        long managed = records.stream().filter(BackupRecord::managed).count();
        summary.setText(records.isEmpty()
                ? ""
                : records.size() + " 份 · 共 " + FileUtils.humanSize(totalBytes)
                        + (managed == records.size() ? "" : "(其中 " + (records.size() - managed) + " 份非本程序生成)"));
        listCard.revalidate();
        listCard.repaint();
        listPanel.revalidate();
        listPanel.repaint();
    }

    @Override
    public void onThemeChanged() {
        renderRecords();
        repaint();
    }

    /** 单条备份的展示与操作。 */
    private final class BackupRow {

        private final BackupRecord record;

        BackupRow(BackupRecord record) {
            this.record = record;
        }

        JPanel component() {
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 8));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));

            JPanel texts = new JPanel();
            texts.setOpaque(false);
            texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
            String title = record.worldDisplayName().isBlank()
                    ? record.worldFolderName() : record.worldDisplayName();
            TLabel name = new TLabel(title + "   " + record.createdText(), TLabel.Role.TITLE);
            String meta = record.sizeText() + "  ·  " + record.fileCount() + " 个文件"
                    + (record.durationMillis() > 0 ? "  ·  耗时 " + record.durationText() : "")
                    + (record.worldFolderName().isBlank() ? "" : "  ·  " + record.worldFolderName());
            TLabel metaLabel = new TLabel(meta, TLabel.Role.MUTED);
            name.setAlignmentX(LEFT_ALIGNMENT);
            metaLabel.setAlignmentX(LEFT_ALIGNMENT);
            texts.add(name);
            texts.add(Box.createVerticalStrut(3));
            texts.add(metaLabel);
            row.add(texts, BorderLayout.CENTER);

            Palette palette = ThemeManager.palette();
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            if (!record.managed()) {
                Pill external = new Pill("非本程序生成", palette.textMuted());
                external.setToolTipText("没有清单文件,不会被自动清理或删除");
                right.add(external);
            } else if (!record.complete()) {
                Pill incomplete = new Pill("有文件未复制", palette.warning());
                incomplete.setToolTipText("备份已完成,但有 " + record.failedFiles() + " 个文件没能复制");
                right.add(incomplete);
            }

            FlatButton open = new FlatButton("打开目录", FlatButton.Variant.GHOST);
            open.addActionListener(e -> FileUtils.openInFileBrowser(record.zipPath()));
            FlatButton verify = new FlatButton("校验", FlatButton.Variant.GHOST);
            verify.setToolTipText(record.hasHash()
                    ? "校验 ZIP 结构并核对 SHA-256"
                    : "校验 ZIP 结构(该备份未记录 SHA-256)");
            verify.addActionListener(e -> callbacks.onVerifyRecord(record));
            FlatButton restore = new FlatButton("恢复");
            restore.setToolTipText("把这份备份还原成世界;原世界会保留为 .restore-backup-…");
            restore.addActionListener(e -> confirmRestore());
            FlatButton export = new FlatButton("导出", FlatButton.Variant.GHOST);
            export.setEnabled(record.zipPath() != null);
            export.addActionListener(e -> chooseExportTarget());
            FlatButton delete = new FlatButton("删除", FlatButton.Variant.DANGER);
            delete.setEnabled(record.managed());
            delete.setToolTipText(record.managed() ? "删除这份备份" : "非本程序生成的 ZIP,不能在这里删除");
            delete.addActionListener(e -> confirmDelete());
            right.add(verify);
            right.add(restore);
            right.add(open);
            right.add(export);
            right.add(delete);
            row.add(right, BorderLayout.EAST);
            return row;
        }

        /** 把备份里的 ZIP 另存到用户指定位置。 */
        private void chooseExportTarget() {
            if (record.zipPath() == null || !Files.isRegularFile(record.zipPath())) {
                JOptionPane.showMessageDialog(BackupView.this, "备份文件已不存在:" + record.zipFileName(),
                        "MC Backup", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("导出备份到…");
            chooser.setSelectedFile(new java.io.File(record.zipFileName()));
            if (chooser.showSaveDialog(BackupView.this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path target = PathUtils.toPath(chooser.getSelectedFile().getAbsolutePath());
            if (target == null) {
                return;
            }
            if (Files.exists(target) && JOptionPane.showConfirmDialog(BackupView.this,
                    "目标文件已存在,要覆盖吗?\n" + PathUtils.toDisplayPath(target),
                    "MC Backup", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            Log.info("导出备份到 " + PathUtils.toDisplayPath(target));
            callbacks.onExportRecord(record, target);
        }

        private void confirmDelete() {
            int answer = JOptionPane.showConfirmDialog(BackupView.this,
                    "删除这份备份?\n" + record.zipFileName() + "\n\n只删除备份文件,不会动原始世界。",
                    "删除备份", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                callbacks.onDeleteRecord(record);
            }
        }

        private void confirmRestore() {
            String target = record.worldPath() == null || record.worldPath().isBlank()
                    ? "(这份备份没有记录原世界路径,恢复时会让你选择一个目录)"
                    : PathUtils.toDisplayPath(Path.of(record.worldPath()));
            int answer = JOptionPane.showConfirmDialog(BackupView.this,
                    "恢复这份备份?\n\n"
                            + "备份:" + record.zipFileName() + "\n"
                            + "备份时间:" + record.createdText() + "\n"
                            + "恢复到:" + target + "\n\n"
                            + "恢复前会先把当前世界改名保留为 \"世界名.restore-backup-时间戳\",\n"
                            + "不会删除任何东西。如果 Minecraft 正在使用这个世界,恢复会被拒绝。",
                    "恢复备份", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                callbacks.onRestoreRecord(record);
            }
        }
    }

    /** 便于测试:当前列表里的记录。 */
    List<BackupRecord> records() {
        return new ArrayList<>(records);
    }
}
