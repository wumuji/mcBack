package com.mcbackup.ui;

import com.mcbackup.model.BackupRecord;
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
 * 「备份记录」页面:列出所有备份,支持恢复 / 校验 / 导出 / 打开目录 / 删除。
 *
 * <p>可以从「备份」页的存档明细跳过来并只显示该存档的记录。</p>
 */
public class BackupRecordsView extends JPanel implements ThemeAware {

    /** 页面回调(选文件的操作留在界面里,后台任务交给 MainWindow)。 */
    public interface Callbacks {

        void onOpenBackupDir();

        void onExportRecord(BackupRecord record, Path targetZip);

        void onDeleteRecord(BackupRecord record);

        void onVerifyRecord(BackupRecord record);

        void onRestoreRecord(BackupRecord record);
    }

    private final Callbacks callbacks;
    private final TLabel summary = new TLabel("", TLabel.Role.MUTED);
    private final TLabel filterHint = new TLabel("", TLabel.Role.MUTED);
    private final TLabel notice = new TLabel("", TLabel.Role.MUTED);
    private final FlatButton showAllButton = new FlatButton("显示全部", FlatButton.Variant.GHOST);
    private final JPanel listPanel = new JPanel();
    private final Card listCard = new Card(new BorderLayout(0, 10));
    private final EmptyState emptyState = new EmptyState();
    private final JScrollPane listScroll;

    private List<BackupRecord> records = List.of();
    private String filterFolder;

    public BackupRecordsView(Callbacks callbacks) {
        this.callbacks = callbacks;
        setOpaque(false);
        setLayout(new BorderLayout(0, 14));
        add(buildToolbar(), BorderLayout.NORTH);
        listCard.add(buildListHeader(), BorderLayout.NORTH);
        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listScroll = new JScrollPane(listPanel);
        ScrollPaneStyler.apply(listScroll);
        renderRecords();
    }

    private JPanel buildToolbar() {
        Card card = new Card(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        FlatButton openDir = new FlatButton("打开备份目录");
        openDir.addActionListener(e -> callbacks.onOpenBackupDir());
        left.add(openDir);
        left.add(filterHint);
        left.add(showAllButton);
        card.add(left, BorderLayout.WEST);
        showAllButton.addActionListener(e -> {
            filterFolder = null;
            renderRecords();
        });
        showAllButton.setVisible(false);
        JPanel wrapper = new JPanel();
        wrapper.setOpaque(false);
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        card.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.add(card);
        wrapper.add(Box.createVerticalStrut(6));
        notice.setAlignmentX(LEFT_ALIGNMENT);
        notice.setVisible(false);
        wrapper.add(notice);
        JPanel outer = new JPanel(new BorderLayout());
        outer.setOpaque(false);
        outer.add(wrapper, BorderLayout.CENTER);
        return outer;
    }

    /** 顶部提示(例如自动备份失败);warning 时用警示色。 */
    public void setNotice(String text, boolean warning) {
        boolean visible = text != null && !text.isBlank();
        notice.setText(visible ? text : "");
        notice.setForeground(warning ? ThemeManager.palette().danger() : ThemeManager.palette().textMuted());
        notice.setVisible(visible);
        revalidate();
        repaint();
    }

    private JPanel buildListHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(new TLabel("备份记录", TLabel.Role.H2), BorderLayout.WEST);
        header.add(summary, BorderLayout.EAST);
        return header;
    }

    /** 只显示某个存档的备份;传 null 显示全部。 */
    public void setFilter(String worldFolderName) {
        this.filterFolder = worldFolderName;
        renderRecords();
    }

    /** 更新备份列表。 */
    public void setBackups(List<BackupRecord> newRecords) {
        this.records = newRecords == null ? List.of() : newRecords;
        renderRecords();
    }

    private void renderRecords() {
        List<BackupRecord> visible = filterFolder == null ? records : records.stream()
                .filter(record -> filterFolder.equals(record.worldFolderName()))
                .toList();
        listPanel.removeAll();
        boolean filtering = filterFolder != null;
        showAllButton.setVisible(filtering);
        filterHint.setText(filtering ? "只显示:" + filterFolder : "");
        if (visible.isEmpty()) {
            emptyState.setTitle(filtering ? "这个存档还没有备份" : "还没有备份");
            emptyState.setSubtitle(filtering
                    ? "回到「备份」页勾选它并开始自动备份,或直接点立刻备份"
                    : "在「备份」页勾选要自动备份的存档,然后点「开始自动备份」");
            listCard.remove(listScroll);
            listCard.add(emptyState, BorderLayout.CENTER);
        } else {
            listCard.remove(emptyState);
            for (BackupRecord record : visible) {
                listPanel.add(new BackupRow(record).component());
                listPanel.add(Box.createVerticalStrut(6));
            }
            if (listCard.getComponentCount() == 1) {
                listCard.add(listScroll, BorderLayout.CENTER);
            }
        }
        long totalBytes = visible.stream().mapToLong(BackupRecord::zipBytes).sum();
        long managed = visible.stream().filter(BackupRecord::managed).count();
        summary.setText(visible.isEmpty() ? ""
                : visible.size() + " 份 · 共 " + FileUtils.humanSize(totalBytes)
                        + (managed == visible.size() ? ""
                                : "(其中 " + (visible.size() - managed) + " 份非本程序生成)"));
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

    /** 便于测试。 */
    List<BackupRecord> records() {
        return new ArrayList<>(records);
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
            String meta = record.sizeText() + "  ·  " + record.fileCount() + " 个文件"
                    + (record.durationMillis() > 0 ? "  ·  耗时 " + record.durationText() : "")
                    + (record.worldFolderName().isBlank() ? "" : "  ·  " + record.worldFolderName());
            TLabel name = new TLabel(title + "   " + record.createdText(), TLabel.Role.TITLE);
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
            } else if (record.hasHash()) {
                right.add(new Pill("已记录哈希", palette.accent()));
            }
            if (record.sourceRunning()) {
                Pill running = new Pill("备份时游戏在运行", palette.warning());
                running.setToolTipText("备份时该世界正被 Minecraft 使用,个别文件可能未能复制");
                right.add(running);
            }

            FlatButton verify = new FlatButton("校验", FlatButton.Variant.GHOST);
            verify.setToolTipText(record.hasHash()
                    ? "校验 ZIP 结构并核对 SHA-256"
                    : "校验 ZIP 结构(该备份未记录 SHA-256)");
            verify.addActionListener(e -> callbacks.onVerifyRecord(record));
            FlatButton restore = new FlatButton("恢复");
            restore.setToolTipText("把这份备份还原成世界;原世界会保留为 .restore-backup-…");
            restore.addActionListener(e -> confirmRestore());
            FlatButton open = new FlatButton("打开目录", FlatButton.Variant.GHOST);
            open.addActionListener(e -> FileUtils.openInFileBrowser(record.zipPath()));
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

        private void chooseExportTarget() {
            if (record.zipPath() == null || !Files.isRegularFile(record.zipPath())) {
                JOptionPane.showMessageDialog(BackupRecordsView.this,
                        "备份文件已不存在:" + record.zipFileName(),
                        "MC Backup", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("导出备份到…");
            chooser.setSelectedFile(new java.io.File(record.zipFileName()));
            if (chooser.showSaveDialog(BackupRecordsView.this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path target = PathUtils.toPath(chooser.getSelectedFile().getAbsolutePath());
            if (target == null) {
                return;
            }
            if (Files.exists(target) && JOptionPane.showConfirmDialog(BackupRecordsView.this,
                    "目标文件已存在,要覆盖吗?\n" + PathUtils.toDisplayPath(target),
                    "MC Backup", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            Log.info("导出备份到 " + PathUtils.toDisplayPath(target));
            callbacks.onExportRecord(record, target);
        }

        private void confirmDelete() {
            int answer = JOptionPane.showConfirmDialog(BackupRecordsView.this,
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
            int answer = JOptionPane.showConfirmDialog(BackupRecordsView.this,
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
}
