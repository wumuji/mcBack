package com.mcbackup.model;

import com.mcbackup.util.FileUtils;

import java.nio.file.Path;
import java.util.List;

/**
 * 一条备份记录(与 sidecar 清单文件一一对应)。
 *
 * <p>{@code producer} 用来回答「这个 ZIP 是不是本程序生成的」:只有清单里写着 {@link #PRODUCER}
 * 的备份才会被自动清理或删除,用户自己的 ZIP 一律不碰。</p>
 *
 * @param zipPath      ZIP 文件路径(null 表示清单里的 ZIP 已丢失)
 * @param manifestPath 清单文件路径(null 表示这是未托管的 ZIP)
 */
public record BackupRecord(
        String producer,
        String worldFolderName,
        String worldDisplayName,
        String worldPath,
        String sourceLabel,
        long createdAt,
        long durationMillis,
        long zipBytes,
        int fileCount,
        long sourceBytes,
        long sourceChangeStamp,
        String strategy,
        String status,
        int failedFiles,
        List<String> warnings,
        boolean sourceRunning,
        String sha256,
        String zipFileName,
        Path zipPath,
        Path manifestPath) {

    /** 本程序的标识,写入清单文件。 */
    public static final String PRODUCER = "MCBackup";
    /** 备份成功。 */
    public static final String STATUS_OK = "OK";
    /** 备份完成但有文件没能复制(会用明显标记提示用户)。 */
    public static final String STATUS_INCOMPLETE = "INCOMPLETE";
    /** 第一阶段只有全量备份,为将来的增量备份预留。 */
    public static final String STRATEGY_FULL = "FULL";

    public String id() {
        return worldFolderName + "/" + zipFileName;
    }

    /** 是否由本程序生成(决定能不能自动清理/删除)。 */
    public boolean managed() {
        return PRODUCER.equalsIgnoreCase(producer);
    }

    public boolean complete() {
        return STATUS_OK.equalsIgnoreCase(status);
    }

    public String createdText() {
        return FileUtils.shortTime(createdAt);
    }

    public String sizeText() {
        return FileUtils.humanSize(zipBytes);
    }

    public String durationText() {
        return durationMillis < 1000 ? durationMillis + " ms" : String.format("%.1f 秒", durationMillis / 1000.0);
    }

    /** 是否记录了整包哈希(用于「校验」按钮判断能否做深度校验)。 */
    public boolean hasHash() {
        return sha256 != null && !sha256.isBlank();
    }
}
