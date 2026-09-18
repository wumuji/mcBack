package com.mcbackup.model;

import java.nio.file.Path;

/**
 * 一次备份的选项。
 *
 * @param backupDir  备份根目录
 * @param retainCount 每个世界保留的备份数量上限(<=0 表示不限制)
 * @param verify     ZIP 完成后是否做完整性校验(默认开启,只读中央目录,成本极低)
 * @param storeRegionFiles 区域文件(.mca)直接存储、不重复压缩:备份更快、CPU 更省,但 ZIP 更大
 */
public record BackupOptions(Path backupDir, int retainCount, boolean verify, boolean storeRegionFiles) {

    public static BackupOptions of(Path backupDir, int retainCount) {
        return new BackupOptions(backupDir, retainCount, true, true);
    }

    public static BackupOptions of(Path backupDir, int retainCount, boolean storeRegionFiles) {
        return new BackupOptions(backupDir, retainCount, true, storeRegionFiles);
    }

    /** 是否启用保留策略。 */
    public boolean retentionEnabled() {
        return retainCount > 0;
    }
}
