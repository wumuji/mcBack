package com.mcbackup.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 单实例保护:自动备份是常驻的,两个实例同时跑会互相抢世界和备份目录。
class SingleInstanceGuardTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void releaseGuard() {
        SingleInstanceGuard.release();
    }

    @Test
    void secondAcquireFailsWhileFirstHoldsTheLock() {
        Path lockFile = tempDir.resolve(".instance.lock");

        assertTrue(SingleInstanceGuard.acquire(lockFile), "第一次应该拿到锁");
        assertTrue(SingleInstanceGuard.isHeld());
        assertFalse(SingleInstanceGuard.acquire(lockFile), "已有实例持有时不应再次拿到锁");

        SingleInstanceGuard.release();
        assertFalse(SingleInstanceGuard.isHeld());
        assertTrue(SingleInstanceGuard.acquire(lockFile), "释放后应能重新拿到锁");
    }

    @Test
    void leftoverLockFileFromCrashDoesNotBlockStartup() throws IOException {
        Path lockFile = tempDir.resolve(".instance.lock");
        // 模拟上次被强制结束后留下的锁文件(没有任何进程持有它)
        Files.writeString(lockFile, "pid=12345" + System.lineSeparator());

        assertTrue(SingleInstanceGuard.acquire(lockFile), "残留的锁文件不应阻止启动");
    }

    @Test
    void writesOwnerPidIntoLockFile() throws IOException {
        Path lockFile = tempDir.resolve(".instance.lock");
        SingleInstanceGuard.acquire(lockFile);
        // 持锁期间文件被独占,先释放再读(锁文件内容会保留,便于事后排查)
        SingleInstanceGuard.release();

        String content = Files.readString(lockFile);

        assertTrue(content.contains("pid=" + ProcessHandle.current().pid()),
                "锁文件里应记录持有者 pid,便于排查:" + content);
    }
}
