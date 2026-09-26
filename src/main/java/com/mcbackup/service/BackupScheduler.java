package com.mcbackup.service;

import com.mcbackup.model.BackupOptions;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.BackupResult;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.util.Log;
import com.mcbackup.util.ProgressListener;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 自动备份调度器。
 *
 * <p>低占用设计:</p>
 * <ul>
 *   <li>只用一个守护线程的 {@link ScheduledExecutorService},空闲时线程处于等待状态,不轮询;</li>
 *   <li>每次触发先做一次廉价判断:世界自上次备份以来「变化指纹」没有变就直接跳过,不复制任何文件;</li>
 *   <li>同一时刻只允许一个备份任务;上一次还没结束就跳过本次触发。</li>
 * </ul>
 *
 * <p>「变化指纹」来自 {@code level.dat}、{@code region} 目录和世界顶层条目的最大修改时间,
 * 备份时会把它记录进清单,所以程序重启后依然知道世界有没有变化。</p>
 */
public final class BackupScheduler {

    /** 调度事件回调(界面用它更新状态文字)。 */
    public interface Listener {

        void onSkipped(MinecraftWorld world, String reason);

        void onBackupStarted(MinecraftWorld world);

        void onBackupFinished(MinecraftWorld world, BackupResult result);

        void onBackupFailed(MinecraftWorld world, Exception error);

        void onTickFinished(int backedUp, int skipped, long elapsedMillis);

        /** 空实现。 */
        Listener NOOP = new Listener() {
            @Override
            public void onSkipped(MinecraftWorld world, String reason) {
            }

            @Override
            public void onBackupStarted(MinecraftWorld world) {
            }

            @Override
            public void onBackupFinished(MinecraftWorld world, BackupResult result) {
            }

            @Override
            public void onBackupFailed(MinecraftWorld world, Exception error) {
            }

            @Override
            public void onTickFinished(int backedUp, int skipped, long elapsedMillis) {
            }
        };
    }

    private final Supplier<List<MinecraftWorld>> worldsSupplier;
    private final BackupService service;
    private final Supplier<BackupOptions> optionsSupplier;
    private final BackupRepository repository;
    private final Listener listener;
    private final AtomicBoolean tickRunning = new AtomicBoolean(false);

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private int intervalMinutes;
    /** 下一次计划执行时间(毫秒);0 表示当前没有计划。 */
    private volatile long nextRunAtMillis;

    public BackupScheduler(Supplier<List<MinecraftWorld>> worldsSupplier,
                           BackupService service,
                           Supplier<BackupOptions> optionsSupplier,
                           BackupRepository repository,
                           Listener listener) {
        this.worldsSupplier = worldsSupplier;
        this.service = service;
        this.optionsSupplier = optionsSupplier;
        this.repository = repository;
        this.listener = listener == null ? Listener.NOOP : listener;
    }

    /** 启动(或按新间隔重启)定时任务。 */
    public synchronized void start(int intervalMinutes) {
        if (intervalMinutes <= 0) {
            stop();
            return;
        }
        this.intervalMinutes = intervalMinutes;
        if (executor == null) {
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "mcbackup-autobackup");
                thread.setDaemon(true);
                return thread;
            };
            executor = Executors.newSingleThreadScheduledExecutor(factory);
        }
        if (future != null) {
            future.cancel(false);
        }
        future = executor.scheduleWithFixedDelay(this::tick, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        nextRunAtMillis = System.currentTimeMillis() + intervalMinutes * 60_000L;
        Log.info("自动备份已启动,间隔 %d 分钟", intervalMinutes);
    }

    /** 停止自动备份(不清空已配置的间隔)。 */
    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
            nextRunAtMillis = 0L;
            Log.info("自动备份已停止");
        }
    }

    /** 关闭线程池(程序退出时调用)。 */
    public synchronized void shutdown() {
        stop();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized boolean isRunning() {
        return future != null && !future.isCancelled();
    }

    public synchronized int intervalMinutes() {
        return intervalMinutes;
    }

    /** 下一次自动备份的时间(毫秒);未运行时返回 0。界面用它做倒计时。 */
    public long nextRunAtMillis() {
        return isRunning() ? nextRunAtMillis : 0L;
    }

    /** 距离下一次自动备份还有多少秒;未运行时返回 -1。 */
    public long secondsUntilNextRun() {
        long next = nextRunAtMillis();
        if (next <= 0) {
            return -1L;
        }
        return Math.max(0L, (next - System.currentTimeMillis()) / 1000L);
    }

    /** 把倒计时文案格式化:1 小时 5 分 / 12 分 30 秒 / 45 秒。 */
    public static String formatCountdown(long seconds) {
        if (seconds < 0) {
            return "未启动";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分";
        }
        if (minutes > 0) {
            return minutes + " 分 " + secs + " 秒";
        }
        return secs + " 秒";
    }

    /**
     * 立即执行一次检查(与定时触发走同一套逻辑)。
     *
     * <p>测试直接调用它,不需要真的等待几分钟。</p>
     */
    public void tick() {
        if (!tickRunning.compareAndSet(false, true)) {
            Log.info("上一次自动备份还没结束,跳过本次检查");
            return;
        }
        long started = System.currentTimeMillis();
        int backedUp = 0;
        int skipped = 0;
        try {
            List<MinecraftWorld> worlds = worldsSupplier.get();
            BackupOptions options = optionsSupplier.get();
            Log.info("自动备份检查:共 %d 个世界", worlds.size());
            for (MinecraftWorld world : worlds) {
                Optional<BackupRecord> latest = repository.latestFor(world.folderName());
                if (isUnchanged(world, latest)) {
                    skipped++;
                    listener.onSkipped(world, "世界自上次备份后没有变化");
                    continue;
                }
                listener.onBackupStarted(world);
                try {
                    BackupResult result = service.backup(world, options, ProgressListener.NOOP);
                    backedUp++;
                    listener.onBackupFinished(world, result);
                } catch (RuntimeException e) {
                    Log.error("自动备份失败: " + world.displayName(), e);
                    listener.onBackupFailed(world, e);
                }
            }
        } catch (RuntimeException e) {
            Log.errorQuietly("自动备份检查失败", e);
        } finally {
            long elapsed = System.currentTimeMillis() - started;
            tickRunning.set(false);
            // 固定延迟语义:下一次执行时间 = 本次结束 + 间隔
            if (isRunning()) {
                nextRunAtMillis = System.currentTimeMillis() + intervalMinutes * 60_000L;
            }
            listener.onTickFinished(backedUp, skipped, elapsed);
        }
    }

    /** 世界是否自上次备份后没有变化。 */
    static boolean isUnchanged(MinecraftWorld world, Optional<BackupRecord> latest) {
        if (latest.isEmpty()) {
            return false;
        }
        long stamp = world.changeStamp();
        if (stamp <= 0) {
            // 读不到修改时间:不做「没变化」的判断,宁可多备份一次
            return false;
        }
        return latest.get().sourceChangeStamp() >= stamp;
    }
}
