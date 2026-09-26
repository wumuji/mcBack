package com.mcbackup.ui;

import com.mcbackup.model.AppSettings;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.model.ScanResult;
import com.mcbackup.service.BackupTargets;
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
import com.mcbackup.util.PathUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「备份」页面 = 自动备份主控台。
 *
 * <p>结构刻意保持最简单:上面一条状态栏(状态 + 倒计时 + 三个按钮),
 * 下面三栏「版本 → 存档 → 存档明细」。勾选框决定哪些存档纳入自动备份。</p>
 */
public class AutoBackupView extends JPanel implements ThemeAware {

    /** 页面回调。 */
    public interface Callbacks {

        void onTargetToggled(MinecraftWorld world, boolean selected);

        void onSelectGroup(String groupName, boolean selected);

        void onBackupNow(MinecraftWorld world);

        void onExportWorld(MinecraftWorld world);

        void onOpenWorldDir(MinecraftWorld world);

        void onViewRecords(MinecraftWorld world);

        void onToggleAutoBackup();

        void onBackupOnce();

        void onOpenBackupSettings();

        void onOpenBackupDir();
    }

    private final Callbacks callbacks;

    private final TLabel stateLabel = new TLabel("自动备份:已暂停", TLabel.Role.H2);
    private final TLabel countdownLabel = new TLabel("未启动", TLabel.Role.ACCENT);
    private final TLabel summaryLabel = new TLabel("", TLabel.Role.MUTED);
    private final FlatButton startPauseButton = new FlatButton("开始自动备份", FlatButton.Variant.PRIMARY);
    private final FlatButton backupOnceButton = new FlatButton("立即备份一次");
    private final FlatButton settingsButton = new FlatButton("备份设置…", FlatButton.Variant.GHOST);

    private final JPanel groupList = new JPanel();
    private final JPanel worldList = new JPanel();
    private final JPanel detailBody = new JPanel(new BorderLayout());
    private final TLabel worldListTitle = new TLabel("存档", TLabel.Role.H2);
    private final TLabel worldListHint = new TLabel("", TLabel.Role.MUTED);
    private final FlatButton selectAllButton = new FlatButton("全选本版本", FlatButton.Variant.GHOST);
    private final FlatButton selectNoneButton = new FlatButton("全不选", FlatButton.Variant.GHOST);

    private AppSettings settings;
    private ScanResult scanResult = ScanResult.empty();
    private List<MinecraftWorld> visibleWorlds = List.of();
    private Map<String, BackupRecord> latestBackupByFolder = Map.of();
    private Map<String, Integer> backupCountByFolder = Map.of();

    private String selectedGroup;
    private MinecraftWorld selectedWorld;
    private boolean autoBackupRunning;
    private boolean busy;
    private String statusHint = "";

    public AutoBackupView(Callbacks callbacks) {
        this.callbacks = callbacks;
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));
        add(buildStatusCard(), BorderLayout.NORTH);
        add(buildColumns(), BorderLayout.CENTER);
        updateStatusCard();
    }

    // ------------------------------------------------------------------
    // 顶部状态栏
    // ------------------------------------------------------------------

    private JComponent buildStatusCard() {
        Card card = new Card(new BorderLayout(0, 6));
        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        JPanel firstLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        firstLine.setOpaque(false);
        firstLine.add(stateLabel);
        firstLine.add(countdownLabel);
        firstLine.setAlignmentX(LEFT_ALIGNMENT);
        summaryLabel.setAlignmentX(LEFT_ALIGNMENT);
        texts.add(firstLine);
        texts.add(Box.createVerticalStrut(4));
        texts.add(summaryLabel);
        card.add(texts, BorderLayout.CENTER);

        startPauseButton.addActionListener(e -> callbacks.onToggleAutoBackup());
        backupOnceButton.setToolTipText("只备份已经勾选、并且确有变化的存档");
        backupOnceButton.addActionListener(e -> callbacks.onBackupOnce());
        settingsButton.addActionListener(e -> callbacks.onOpenBackupSettings());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(settingsButton);
        buttons.add(backupOnceButton);
        buttons.add(startPauseButton);
        card.add(buttons, BorderLayout.EAST);
        return card;
    }

    // ------------------------------------------------------------------
    // 三栏
    // ------------------------------------------------------------------

    private JComponent buildColumns() {
        JPanel columns = new JPanel(new BorderLayout(12, 0));
        columns.setOpaque(false);
        columns.add(buildGroupColumn(), BorderLayout.WEST);
        columns.add(buildWorldColumn(), BorderLayout.CENTER);
        columns.add(buildDetailColumn(), BorderLayout.EAST);
        return columns;
    }

    private JComponent buildGroupColumn() {
        Card card = new Card(new BorderLayout(0, 10));
        card.setPreferredSize(new Dimension(232, 0));
        card.add(new TLabel("版本 / 实例", TLabel.Role.H2), BorderLayout.NORTH);
        groupList.setOpaque(false);
        groupList.setLayout(new BoxLayout(groupList, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(groupList);
        ScrollPaneStyler.apply(scroll);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildWorldColumn() {
        Card card = new Card(new BorderLayout(0, 10));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel titlePanel = new JPanel();
        titlePanel.setOpaque(false);
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        worldListTitle.setAlignmentX(LEFT_ALIGNMENT);
        worldListHint.setAlignmentX(LEFT_ALIGNMENT);
        titlePanel.add(worldListTitle);
        titlePanel.add(Box.createVerticalStrut(2));
        titlePanel.add(worldListHint);
        header.add(titlePanel, BorderLayout.WEST);

        selectAllButton.addActionListener(e -> {
            if (selectedGroup != null) {
                callbacks.onSelectGroup(selectedGroup, true);
            }
        });
        selectNoneButton.addActionListener(e -> {
            if (selectedGroup != null) {
                callbacks.onSelectGroup(selectedGroup, false);
            }
        });
        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerButtons.setOpaque(false);
        headerButtons.add(selectAllButton);
        headerButtons.add(selectNoneButton);
        header.add(headerButtons, BorderLayout.EAST);
        card.add(header, BorderLayout.NORTH);

        worldList.setOpaque(false);
        worldList.setLayout(new BoxLayout(worldList, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(worldList);
        ScrollPaneStyler.apply(scroll);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildDetailColumn() {
        Card card = new Card(new BorderLayout());
        card.setPreferredSize(new Dimension(340, 0));
        detailBody.setOpaque(false);
        card.add(detailBody, BorderLayout.CENTER);
        return card;
    }

    // ------------------------------------------------------------------
    // 数据刷新
    // ------------------------------------------------------------------

    /** 更新扫描结果与设置,重建三栏。 */
    public void setContext(AppSettings settings, ScanResult result,
                           Map<String, BackupRecord> latestBackupByFolder,
                           Map<String, Integer> backupCountByFolder) {
        this.settings = settings;
        this.scanResult = result == null ? ScanResult.empty() : result;
        this.latestBackupByFolder = latestBackupByFolder == null ? Map.of() : latestBackupByFolder;
        this.backupCountByFolder = backupCountByFolder == null ? Map.of() : backupCountByFolder;
        rebuildGroups();
        updateStatusCard();
    }

    /** 更新自动备份开关状态(用于状态栏文字与按钮)。 */
    public void setAutoBackupState(boolean running, boolean hasTargets) {
        this.autoBackupRunning = running;
        this.hasTargets = hasTargets;
        updateStatusCard();
    }

    private boolean hasTargets;

    /** 倒计时文字(由 MainWindow 每秒刷新)。 */
    public void setCountdown(String text) {
        countdownLabel.setText(text);
    }

    /** 附加状态信息,例如上次备份结果。 */
    public void setStatusHint(String text) {
        this.statusHint = text == null ? "" : text;
        refreshSummary();
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        updateStatusCard();
    }

    /** 便于测试:当前显示的存档数量。 */
    List<MinecraftWorld> visibleWorlds() {
        return new ArrayList<>(visibleWorlds);
    }

    private void rebuildGroups() {
        groupList.removeAll();
        Set<String> groups = new LinkedHashSet<>();
        for (MinecraftWorld world : scanResult.worlds()) {
            groups.add(groupName(world));
        }
        if (groups.isEmpty()) {
            TLabel empty = new TLabel("没有发现存档", TLabel.Role.MUTED);
            empty.setAlignmentX(LEFT_ALIGNMENT);
            groupList.add(empty);
        }
        if (selectedGroup == null || !groups.contains(selectedGroup)) {
            selectedGroup = groups.isEmpty() ? null : groups.iterator().next();
        }
        for (String group : groups) {
            groupList.add(new GroupRow(group).component());
            groupList.add(Box.createVerticalStrut(4));
        }
        groupList.revalidate();
        groupList.repaint();
        rebuildWorlds();
    }

    private void rebuildWorlds() {
        worldList.removeAll();
        visibleWorlds = scanResult.worlds().stream()
                .filter(world -> groupName(world).equals(selectedGroup))
                .toList();
        if (selectedWorld == null || visibleWorlds.stream()
                .noneMatch(world -> world.key().equals(selectedWorld.key()))) {
            selectedWorld = visibleWorlds.isEmpty() ? null : visibleWorlds.get(0);
        }
        if (visibleWorlds.isEmpty()) {
            EmptyState empty = new EmptyState();
            empty.setTitle("这个版本下没有存档");
            empty.setSubtitle("换一个版本,或到设置里添加存档目录");
            worldList.add(empty);
        } else {
            for (MinecraftWorld world : visibleWorlds) {
                worldList.add(new WorldRow(world).component());
                worldList.add(Box.createVerticalStrut(4));
            }
        }
        long selectedInGroup = visibleWorlds.stream()
                .filter(world -> BackupTargets.isSelected(world, settings == null
                        ? List.of() : settings.getAutoBackupTargets()))
                .count();
        worldListTitle.setText(selectedGroup == null ? "存档" : selectedGroup);
        worldListHint.setText(visibleWorlds.size() + " 个存档 · 已纳入 " + selectedInGroup + " 个");
        selectAllButton.setEnabled(!visibleWorlds.isEmpty());
        selectNoneButton.setEnabled(!visibleWorlds.isEmpty());
        rebuildDetail();
        worldList.revalidate();
        worldList.repaint();
    }

    private void rebuildDetail() {
        detailBody.removeAll();
        if (selectedWorld == null) {
            EmptyState empty = new EmptyState();
            empty.setTitle("选择一个存档");
            empty.setSubtitle("左边选版本,中间选存档,这里会显示明细");
            detailBody.add(empty, BorderLayout.CENTER);
            detailBody.revalidate();
            detailBody.repaint();
            return;
        }
        MinecraftWorld world = selectedWorld;
        boolean selected = settings != null && BackupTargets.isSelected(world, settings.getAutoBackupTargets());
        BackupRecord latest = latestBackupByFolder.get(world.folderName());
        int count = backupCountByFolder.getOrDefault(world.folderName(), 0);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        TLabel name = new TLabel(world.displayName(), TLabel.Role.H2);
        TLabel path = new TLabel(PathUtils.toDisplayPath(world.worldDir()), TLabel.Role.MUTED);
        name.setAlignmentX(LEFT_ALIGNMENT);
        path.setAlignmentX(LEFT_ALIGNMENT);
        column.add(name);
        column.add(Box.createVerticalStrut(2));
        column.add(path);
        column.add(Box.createVerticalStrut(12));

        addField(column, "版本", world.info().available() && !world.info().versionName().isBlank()
                ? world.info().versionName() : WorldText.UNKNOWN);
        addField(column, "模式 / 难度", (world.info().available() && !world.info().gameMode().isBlank()
                ? world.info().gameMode() : WorldText.UNKNOWN)
                + " / " + (world.info().available() && !world.info().difficulty().isBlank()
                ? world.info().difficulty() : WorldText.UNKNOWN));
        addField(column, "大小 / 文件", FileUtils.humanSize(world.sizeBytes()));
        addField(column, "最后修改", FileUtils.absoluteTime(world.lastModified()));
        addField(column, "运行状态", world.runningText());
        addField(column, "自动备份", selected ? "已纳入" : "未纳入");
        addField(column, "备份份数", count + " 份");
        addField(column, "最近备份", latest == null ? WorldText.UNKNOWN
                : latest.createdText() + "(" + latest.sizeText() + ")");
        if (latest != null && !latest.complete()) {
            addField(column, "上次结果", "有 " + latest.failedFiles() + " 个文件未复制");
        }
        column.add(Box.createVerticalStrut(12));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        actions.setOpaque(false);
        FlatButton backup = new FlatButton("立即备份", FlatButton.Variant.PRIMARY);
        backup.addActionListener(e -> callbacks.onBackupNow(world));
        FlatButton records = new FlatButton("查看备份记录");
        records.addActionListener(e -> callbacks.onViewRecords(world));
        FlatButton export = new FlatButton("导出…", FlatButton.Variant.GHOST);
        export.addActionListener(e -> callbacks.onExportWorld(world));
        FlatButton openDir = new FlatButton("打开目录", FlatButton.Variant.GHOST);
        openDir.addActionListener(e -> callbacks.onOpenWorldDir(world));
        actions.add(backup);
        actions.add(records);
        actions.add(export);
        actions.add(openDir);
        column.add(actions);
        column.add(Box.createVerticalGlue());

        detailBody.add(column, BorderLayout.NORTH);
        detailBody.revalidate();
        detailBody.repaint();
    }

    private void addField(JPanel column, String label, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        TLabel labelComponent = new TLabel(label + ":", TLabel.Role.MUTED);
        labelComponent.setPreferredSize(new Dimension(78, 18));
        row.add(labelComponent);
        row.add(new TLabel(value, TLabel.Role.BODY));
        column.add(row);
        column.add(Box.createVerticalStrut(4));
    }

    private void updateStatusCard() {
        if (autoBackupRunning) {
            stateLabel.setText("自动备份:运行中");
            startPauseButton.setText("暂停自动备份");
        } else {
            stateLabel.setText("自动备份:已暂停");
            startPauseButton.setText("开始自动备份");
            countdownLabel.setText("未启动");
        }
        startPauseButton.setEnabled(!busy);
        backupOnceButton.setEnabled(!busy && hasTargets);
        backupOnceButton.setToolTipText(hasTargets
                ? "只备份已经勾选、并且确有变化的存档"
                : "先在中间勾选要自动备份的存档");
        refreshSummary();
        revalidate();
        repaint();
    }

    private void refreshSummary() {
        if (settings == null) {
            summaryLabel.setText("");
            return;
        }
        int selected = settings.getAutoBackupTargets().size();
        int missing = BackupTargets.missingCount(scanResult.worlds(), settings.getAutoBackupTargets());
        StringBuilder text = new StringBuilder();
        text.append("已纳入 ").append(selected).append(" 个存档");
        if (missing > 0) {
            text.append("(其中 ").append(missing).append(" 个当前未找到)");
        }
        text.append(" · 间隔 ").append(settings.getAutoBackupIntervalMinutes()).append(" 分钟")
                .append(" · 保留 ").append(settings.getRetainCount() <= 0
                        ? "不限" : settings.getRetainCount() + " 份")
                .append(" · 压缩 ").append(settings.isFastBackup() ? "快速" : "体积优先");
        if (selected == 0) {
            text.append(" · 请先在中间勾选要自动备份的存档");
        }
        if (!statusHint.isBlank()) {
            text.append(" · ").append(statusHint);
        }
        summaryLabel.setText(text.toString());
    }

    private static String groupName(MinecraftWorld world) {
        String group = world.groupName();
        return group == null || group.isBlank() ? "其它" : group;
    }

    @Override
    public void onThemeChanged() {
        rebuildGroups();
        repaint();
    }

    // ------------------------------------------------------------------
    // 行组件
    // ------------------------------------------------------------------

    /** 版本/实例一行。 */
    private final class GroupRow {

        private final String group;
        private final JPanel row;
        private boolean hovered;

        GroupRow(String group) {
            this.group = group;
            row = new JPanel(new BorderLayout(8, 0)) {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics graphics) {
                    boolean selected = group.equals(selectedGroup);
                    if (selected || hovered) {
                        Palette palette = ThemeManager.palette();
                        Graphics2D g = (Graphics2D) graphics.create();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(selected ? palette.selectionFill() : palette.surfaceHover());
                        g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                        g.dispose();
                    }
                }
            };
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.add(new TLabel(group, TLabel.Role.TITLE), BorderLayout.CENTER);

            long total = scanResult.worlds().stream().filter(w -> groupName(w).equals(group)).count();
            long selectedCount = scanResult.worlds().stream()
                    .filter(w -> groupName(w).equals(group))
                    .filter(w -> settings != null && BackupTargets.isSelected(w, settings.getAutoBackupTargets()))
                    .count();
            row.add(new TLabel(selectedCount + "/" + total, TLabel.Role.MUTED), BorderLayout.EAST);

            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    row.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    row.repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    selectedGroup = group;
                    rebuildGroups();
                }
            });
        }

        JComponent component() {
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            return row;
        }
    }

    /** 存档一行(勾选框 + 名称 + 状态)。 */
    private final class WorldRow {

        private final MinecraftWorld world;
        private final JPanel row;

        WorldRow(MinecraftWorld world) {
            this.world = world;
            row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JCheckBox check = new JCheckBox();
            check.setOpaque(false);
            check.setSelected(settings != null && BackupTargets.isSelected(world, settings.getAutoBackupTargets()));
            check.setToolTipText("勾选后纳入自动备份");
            check.addActionListener(e -> callbacks.onTargetToggled(world, check.isSelected()));
            row.add(check, BorderLayout.WEST);

            JPanel texts = new JPanel();
            texts.setOpaque(false);
            texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
            TLabel name = new TLabel(world.displayName(), TLabel.Role.TITLE);
            TLabel meta = new TLabel(metaText(world), TLabel.Role.MUTED);
            name.setAlignmentX(LEFT_ALIGNMENT);
            meta.setAlignmentX(LEFT_ALIGNMENT);
            texts.add(name);
            texts.add(Box.createVerticalStrut(2));
            texts.add(meta);
            row.add(texts, BorderLayout.CENTER);

            JPanel pills = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            pills.setOpaque(false);
            pills.add(statusPill(world));
            row.add(pills, BorderLayout.EAST);

            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectedWorld = world;
                    rebuildDetail();
                    worldList.repaint();
                }
            });
        }

        JComponent component() {
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
            return row;
        }
    }

    private static String metaText(MinecraftWorld world) {
        return FileUtils.humanSize(world.sizeBytes()) + "  ·  " + world.folderName();
    }

    private Pill statusPill(MinecraftWorld world) {
        Palette palette = ThemeManager.palette();
        BackupRecord latest = latestBackupByFolder.get(world.folderName());
        if (world.possiblyRunning()) {
            return new Pill("运行中", palette.warning());
        }
        if (latest == null) {
            return new Pill("未备份", palette.textMuted());
        }
        long stamp = world.changeStamp();
        if (stamp > 0 && latest.sourceChangeStamp() >= stamp) {
            return new Pill("已是最新", palette.accent());
        }
        return new Pill("有变化", palette.warning());
    }

    /** 便于测试:状态栏文字。 */
    String stateText() {
        return stateLabel.getText();
    }

    /** 便于测试:倒计时文字。 */
    String countdownText() {
        return countdownLabel.getText();
    }
}
