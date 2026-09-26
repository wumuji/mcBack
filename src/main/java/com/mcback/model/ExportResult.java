package com.mcback.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 导出结果。
 *
 * @param zipPath       最终 ZIP 路径
 * @param zipBytes      ZIP 大小
 * @param fileCount     打包的文件数量
 * @param durationMillis 耗时
 * @param warnings      警告信息(个别文件未能复制等)
 */
public record ExportResult(Path zipPath, long zipBytes, int fileCount, long durationMillis,
                           List<String> warnings) {
}
