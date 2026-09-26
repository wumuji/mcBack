package com.mcback.model;

/** 扫描进度回调数据,用于在顶栏显示当前正在做什么。 */
public record ScanProgress(int scannedRoots, int totalRoots, String label) {

    public static ScanProgress idle() {
        return new ScanProgress(0, 0, "准备扫描");
    }
}
