package com.mcbackup.service;

import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Log;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 世界大小缓存。
 *
 * <p>统计一个 GB 级世界的大小需要遍历成千上万个文件。如果每次扫描都重算,会白白消耗磁盘 IO。
 * 这里用「路径 + 变化指纹」做键:只有世界真的变化了才重新统计。</p>
 */
public final class WorldSizeCache {

    private record Entry(long stamp, long size) {
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private int hits;
    private int misses;

    /**
     * 取得世界大小。
     *
     * @param worldDir    世界目录
     * @param changeStamp {@link FileUtils#worldChangeStamp(Path)} 的结果
     * @param force       true 时强制重新统计
     */
    public synchronized long sizeOf(Path worldDir, long changeStamp, boolean force) {
        String key = worldDir.toString().toLowerCase(Locale.ROOT);
        Entry cached = entries.get(key);
        if (!force && cached != null && cached.stamp() == changeStamp) {
            hits++;
            return cached.size();
        }
        misses++;
        long size = FileUtils.directorySize(worldDir);
        entries.put(key, new Entry(changeStamp, size));
        Log.debug("统计世界大小: %s = %d 字节", worldDir, size);
        return size;
    }

    /** 缓存命中次数(测试与调优用)。 */
    public synchronized int hits() {
        return hits;
    }

    /** 缓存未命中次数(测试与调优用)。 */
    public synchronized int misses() {
        return misses;
    }

    /** 清空缓存(用户点击「重新扫描」并勾选强制刷新时使用)。 */
    public synchronized void clear() {
        entries.clear();
        hits = 0;
        misses = 0;
    }

    public synchronized int size() {
        return entries.size();
    }
}
