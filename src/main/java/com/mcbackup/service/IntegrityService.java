package com.mcbackup.service;

import com.mcbackup.model.BackupRecord;
import com.mcbackup.util.Hashing;
import com.mcbackup.util.Log;
import com.mcbackup.util.ProgressListener;
import com.mcbackup.util.ZipUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 备份完整性校验。
 *
 * <p>分两层:</p>
 * <ol>
 *   <li>结构校验:ZIP 能否打开、有多少条目、有没有 level.dat、有没有可疑路径(毫秒级);</li>
 *   <li>内容校验:把整包 SHA-256 与清单里记录的值比对(需要读完整包,仅当清单里有哈希时进行)。</li>
 * </ol>
 */
public final class IntegrityService {

    /** 校验结果。 */
    public record IntegrityReport(boolean valid, int entries, int fileEntries, long uncompressedBytes,
                                  boolean hashChecked, boolean hashMatches, String storedHash,
                                  String actualHash, long durationMillis, String problem) {

        /** 一句话结论,供界面直接显示。 */
        public String summary() {
            if (!valid) {
                return "校验失败:" + problem;
            }
            if (hashChecked) {
                return hashMatches
                        ? "校验通过:结构与 SHA-256 均一致(" + fileEntries + " 个文件)"
                        : "校验失败:内容与清单记录不一致,备份可能已被修改或损坏";
            }
            return "校验通过:ZIP 结构正常(" + fileEntries + " 个文件;该备份未记录 SHA-256)";
        }
    }

    /** 校验一份备份。 */
    public IntegrityReport verify(BackupRecord record, ProgressListener listener) {
        long started = System.currentTimeMillis();
        Path zip = record.zipPath();
        if (zip == null || !Files.isRegularFile(zip)) {
            return new IntegrityReport(false, 0, 0, 0L, false, false, record.sha256(), "",
                    System.currentTimeMillis() - started, "备份文件不存在");
        }
        if (listener != null) {
            listener.onProgress("校验", 0, 0, zip.getFileName().toString());
        }
        ZipUtils.ZipVerification verification = ZipUtils.verify(zip);
        if (!verification.valid()) {
            return new IntegrityReport(false, verification.entries(), verification.fileEntries(),
                    verification.uncompressedBytes(), false, false, record.sha256(), "",
                    System.currentTimeMillis() - started, verification.problem());
        }
        boolean hashChecked = record.hasHash();
        boolean hashMatches = true;
        String actual = "";
        String problem = null;
        if (hashChecked) {
            actual = Hashing.sha256Quietly(zip);
            hashMatches = record.sha256().equalsIgnoreCase(actual);
            if (!hashMatches) {
                problem = "内容与清单记录不一致(SHA-256 不匹配),备份可能已被修改或损坏";
                Log.warn("SHA-256 不一致: 清单=%s 实际=%s", record.sha256(), actual);
            }
        }
        long elapsed = System.currentTimeMillis() - started;
        Log.info("完整性校验完成: %s(%d ms,哈希校验=%s)", zip.getFileName(), elapsed, hashChecked);
        return new IntegrityReport(hashMatches, verification.entries(), verification.fileEntries(),
                verification.uncompressedBytes(), hashChecked, hashMatches, record.sha256(), actual,
                elapsed, problem);
    }
}
