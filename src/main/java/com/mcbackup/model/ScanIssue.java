package com.mcbackup.model;

/**
 * 单条扫描问题:某个目录/世界在扫描或解析过程中出现的问题。
 *
 * <p>扫描不会因为单条问题中断,问题会汇总显示并写入日志。</p>
 */
public record ScanIssue(String stage, String path, String message) {

    @Override
    public String toString() {
        return "[" + stage + "] " + path + " - " + message;
    }
}
