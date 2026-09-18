package com.mcbackup.util;

/**
 * 长任务进度回调(复制、压缩、备份、导出都会用到)。
 *
 * <p>实现方负责切回自己的线程(界面里是 SwingUtilities.invokeLater)。</p>
 */
@FunctionalInterface
public interface ProgressListener {

    /** 空实现,便于测试与不需要进度的场景。 */
    ProgressListener NOOP = (stage, done, total, detail) -> {
    };

    /**
     * @param stage  阶段名,例如「复制文件」「压缩」「校验」「提交」
     * @param done   已完成数量(文件数)
     * @param total  总数(0 表示未知)
     * @param detail 当前处理的文件或说明
     */
    void onProgress(String stage, int done, int total, String detail);
}
