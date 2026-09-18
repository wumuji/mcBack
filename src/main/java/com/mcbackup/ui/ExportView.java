package com.mcbackup.ui;

import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.model.ScanResult;
import com.mcbackup.service.ExportService;
import com.mcbackup.ui.components.Card;
import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.components.TLabel;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.PathUtils;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 「导出」页面:把世界导出成标准 ZIP(解压后直接就是 level.dat / region / …)。
 *
 * <p>控件只有三样:世界下拉框、导出按钮、上次导出的结果操作,尽量缩短操作路径。</p>
 */
public class ExportView extends JPanel implements ThemeAware {

    /** 页面回调。 */
    public interface Callbacks {

        void onExportWorld(MinecraftWorld world, Path targetZip);

        void onOpenDirectory(Path file);
    }

    private final Callbacks callbacks;
    private final JComboBox<String> worldCombo = new JComboBox<>();
    private final FlatButton exportButton = new FlatButton("导出为 ZIP…", FlatButton.Variant.PRIMARY);
    private final TLabel resultLabel = new TLabel("还没有导出记录", TLabel.Role.MUTED);
    private final FlatButton openFolderButton = new FlatButton("打开所在目录");
    private final FlatButton copyPathButton = new FlatButton("复制路径", FlatButton.Variant.GHOST);
    private final JPanel resultRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

    private List<MinecraftWorld> worlds = List.of();
    private Path lastExport;

    public ExportView(Callbacks callbacks) {
        this.callbacks = callbacks;
        setOpaque(false);
        setLayout(new BorderLayout(0, 14));
        add(buildCard(), BorderLayout.NORTH);
        add(buildResultCard(), BorderLayout.CENTER);
        updateResult();
    }

    private Card buildCard() {
        Card card = new Card(new BorderLayout(0, 12));
        card.add(new TLabel("导出世界", TLabel.Role.H2), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        TLabel hint = new TLabel("导出的 ZIP 解压后直接得到 level.dat、region/ 等,不会多套一层世界目录。",
                TLabel.Role.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.setAlignmentX(LEFT_ALIGNMENT);
        worldCombo.setPreferredSize(new Dimension(340, 30));
        exportButton.addActionListener(e -> chooseTarget());
        controls.add(new TLabel("世界:"));
        controls.add(worldCombo);
        controls.add(exportButton);

        column.add(hint);
        column.add(Box.createVerticalStrut(12));
        column.add(controls);
        card.add(column, BorderLayout.CENTER);
        return card;
    }

    private Card buildResultCard() {
        Card card = new Card(new BorderLayout(0, 10));
        card.add(new TLabel("上次导出", TLabel.Role.H2), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        resultLabel.setAlignmentX(LEFT_ALIGNMENT);
        resultRow.setOpaque(false);
        resultRow.setAlignmentX(LEFT_ALIGNMENT);
        openFolderButton.addActionListener(e -> {
            if (lastExport != null) {
                callbacks.onOpenDirectory(lastExport);
            }
        });
        copyPathButton.addActionListener(e -> {
            if (lastExport != null) {
                boolean ok = FileUtils.copyToClipboard(PathUtils.toDisplayPath(lastExport));
                resultLabel.setText(ok ? "已复制路径" : "复制失败,请检查剪贴板权限");
            }
        });
        resultRow.add(openFolderButton);
        resultRow.add(copyPathButton);
        column.add(resultLabel);
        column.add(Box.createVerticalStrut(10));
        column.add(resultRow);
        card.add(column, BorderLayout.CENTER);
        return card;
    }

    /** 扫描结果变化时刷新下拉框(尽量保留已选中项)。 */
    public void setScanResult(ScanResult result) {
        String previous = (String) worldCombo.getSelectedItem();
        worlds = result == null ? List.of() : result.worlds();
        worldCombo.removeAllItems();
        for (MinecraftWorld world : worlds) {
            worldCombo.addItem(world.displayName() + "  —  " + world.folderName()
                    + "  (" + FileUtils.humanSize(world.sizeBytes()) + ")");
        }
        if (previous != null) {
            for (int i = 0; i < worldCombo.getItemCount(); i++) {
                if (previous.equals(worldCombo.getItemAt(i))) {
                    worldCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        exportButton.setEnabled(!worlds.isEmpty());
        if (worlds.isEmpty()) {
            resultLabel.setText("还没有发现世界,先到「世界」页面扫描");
        }
    }

    /** 选中指定世界(从「世界」页面跳过来时同步选择)。 */
    public void selectWorld(MinecraftWorld world) {
        if (world == null) {
            return;
        }
        for (int i = 0; i < worlds.size(); i++) {
            if (worlds.get(i).key().equals(world.key())) {
                worldCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** 显示导出结果。 */
    public void setLastExport(Path zip) {
        this.lastExport = zip;
        if (zip != null && Files.isRegularFile(zip)) {
            resultLabel.setText(PathUtils.toDisplayPath(zip) + "(" + FileUtils.humanSize(sizeOf(zip)) + ")");
        }
        updateResult();
    }

    public void setBusy(boolean busy) {
        exportButton.setEnabled(!busy && !worlds.isEmpty());
        exportButton.setText(busy ? "导出中…" : "导出为 ZIP…");
    }

    /** 便于测试:当前可选世界数量。 */
    int worldCount() {
        return worlds.size();
    }

    private void updateResult() {
        boolean has = lastExport != null;
        openFolderButton.setEnabled(has);
        copyPathButton.setEnabled(has);
        repaint();
    }

    private void chooseTarget() {
        MinecraftWorld world = selectedWorld();
        if (world == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出到…");
        chooser.setSelectedFile(new java.io.File(ExportService.suggestedFileName(world)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = PathUtils.toPath(chooser.getSelectedFile().getAbsolutePath());
        if (target == null) {
            return;
        }
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this,
                "目标文件已存在,要覆盖吗?\n" + PathUtils.toDisplayPath(target),
                "MC Backup", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        callbacks.onExportWorld(world, target);
    }

    private MinecraftWorld selectedWorld() {
        int index = worldCombo.getSelectedIndex();
        return index >= 0 && index < worlds.size() ? worlds.get(index) : null;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public void onThemeChanged() {
        repaint();
    }
}
