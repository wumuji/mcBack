package com.mcback.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例保护。
 *
 * <p>为什么必须做:自动备份是常驻的,两个实例同时跑会自动备份同一个世界、抢同一个目录,
 * 还可能一边在写备份一边被另一边清理。这里用一个锁文件 + 操作系统文件锁保证只有一个实例在跑。</p>
 *
 * <p>实现细节:</p>
 * <ul>
 *   <li>锁文件放在配置目录({@code %APPDATA%\mcBack\.instance.lock}),进程退出时操作系统会自动释放;</li>
 *   <li>程序被强制结束时也不会留下"假锁",因为锁是内核对象而不是文件内容;</li>
 *   <li>锁文件所在目录不可写时不会阻止启动(只是失去多开保护),并记录日志。</li>
 * </ul>
 */
public final class SingleInstanceGuard {

    private static FileChannel channel;
    private static FileLock lock;
    private static Path lockFile;

    private SingleInstanceGuard() {
    }

    /**
     * 尝试获取单实例锁。
     *
     * @return true 表示当前进程拿到了锁(可以继续启动);false 表示已有实例在运行
     */
    public static synchronized boolean acquire(Path file) {
        lockFile = file;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            FileLock acquired = null;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException sameProcess) {
                // 同一个进程内重复获取:同样视为"已经在运行"
                acquired = null;
            }
            if (acquired == null) {
                closeQuietly();
                return false;
            }
            lock = acquired;
            writeOwnerPid();
            Log.info("已获得单实例锁: %s", PathUtils.toDisplayPath(file));
            return true;
        } catch (IOException | RuntimeException e) {
            // 拿不到锁文件时不阻止启动,只失去多开保护
            Log.warn("无法使用单实例锁(%s),仍会继续启动: %s", PathUtils.toDisplayPath(file), e.getMessage());
            closeQuietly();
            return true;
        }
    }

    /** 当前进程是否持有锁。 */
    public static synchronized boolean isHeld() {
        return lock != null && lock.isValid();
    }

    /** 锁文件路径(用于提示用户)。 */
    public static synchronized Path lockFilePath() {
        return lockFile;
    }

    /** 释放锁(退出时调用;不释放也不会残留,进程结束由系统回收)。 */
    public static synchronized void release() {
        closeQuietly();
    }

    private static void writeOwnerPid() {
        if (channel == null) {
            return;
        }
        try {
            String text = "pid=" + ProcessHandle.current().pid()
                    + System.lineSeparator() + "启动时间=" + java.time.LocalDateTime.now()
                    + System.lineSeparator();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            channel.truncate(0);
            channel.position(0);
            channel.write(ByteBuffer.wrap(bytes));
        } catch (IOException e) {
            Log.debug("写入锁文件信息失败: " + e.getMessage());
        }
    }

    private static void closeQuietly() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException e) {
            Log.debug("释放单实例锁失败: " + e.getMessage());
        } finally {
            lock = null;
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            Log.debug("关闭锁文件失败: " + e.getMessage());
        } finally {
            channel = null;
        }
    }
}
