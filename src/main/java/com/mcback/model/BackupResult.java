package com.mcback.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 备份结果。
 *
 * @param record            备份记录
 * @param warnings          过程中的警告(例如个别文件重试、可选文件未复制)
 * @param deletedByRetention 被保留策略清理掉的旧备份
 * @param elapsedMillis     总耗时
 */
public record BackupResult(BackupRecord record, List<String> warnings, List<Path> deletedByRetention,
                           long elapsedMillis) {

    public boolean complete() {
        return record.complete();
    }
}
