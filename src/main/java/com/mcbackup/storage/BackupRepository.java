package com.mcbackup.storage;

import com.mcbackup.model.BackupRecord;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Json;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;
import com.mcbackup.util.WorldCopier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 备份仓库:负责备份目录的布局、清单读写、列表、删除与保留策略。
 *
 * <p>目录布局:</p>
 * <pre>
 * &lt;备份目录&gt;/
 *   新的世界/                                 每个世界一个子目录
 *     生存世界_2026-09-18_193000.zip
 *     生存世界_2026-09-18_193000.json         sidecar 清单(证明这是本程序生成的备份)
 *   .tmp/                                     复制与压缩过程中的临时文件
 * </pre>
 *
 * <p><b>安全底线</b>:删除与自动清理只针对「有清单、清单写着 MCBackup、文件名一致」的备份;
 * 用户自己放进来的 ZIP 只会被列出并标注为「非本程序生成」,永远不会被删除。</p>
 */
public final class BackupRepository {

    private static final String MANIFEST_SUFFIX = ".json";
    private static final String ZIP_SUFFIX = ".zip";
    private static final String TEMP_DIR = ".tmp";
    private static final String TMP_SUFFIX = ".tmp";
    private static final int SCHEMA_VERSION = 1;

    /** 删除结果。 */
    public enum DeleteStatus {
        DELETED, NOT_MANAGED, MISSING, FAILED
    }

    /** 删除结果详情。 */
    public record DeleteOutcome(DeleteStatus status, String message) {

        public boolean deleted() {
            return status == DeleteStatus.DELETED;
        }
    }

    /** 保留策略执行报告。 */
    public record RetentionReport(int examined, List<Path> deleted, List<String> skipped) {
    }

    private final Path backupDir;

    public BackupRepository(Path backupDir) {
        this.backupDir = backupDir;
    }

    public Path backupDir() {
        return backupDir;
    }

    /** 确保备份目录存在;失败时抛异常由调用方转成用户可读的提示。 */
    public void ensureExists() throws IOException {
        Files.createDirectories(backupDir);
    }

    /** 临时工作目录(复制与压缩都在这里进行)。 */
    public Path tempDir() {
        return backupDir.resolve(TEMP_DIR);
    }

    /** 某个世界的备份子目录。 */
    public Path worldDir(String worldFolderName) {
        return backupDir.resolve(PathUtils.sanitizeFileName(worldFolderName));
    }

    /**
     * 生成不冲突的 ZIP 文件名。
     *
     * @param baseName 不含扩展名的基础名
     */
    public String uniqueZipFileName(String worldFolderName, String baseName) throws IOException {
        Path dir = worldDir(worldFolderName);
        String candidate = baseName + ZIP_SUFFIX;
        int counter = 2;
        while (Files.exists(dir.resolve(candidate))) {
            candidate = baseName + " (" + counter + ")" + ZIP_SUFFIX;
            counter++;
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // 清单读写
    // ------------------------------------------------------------------

    /** 原子写入清单文件;返回写入的路径。 */
    public Path saveManifest(BackupRecord record) throws IOException {
        Path dir = record.zipPath() == null ? worldDir(record.worldFolderName()) : record.zipPath().getParent();
        Files.createDirectories(dir);
        String fileName = record.zipFileName() == null
                ? PathUtils.sanitizeFileName(record.worldDisplayName()) + ZIP_SUFFIX
                : record.zipFileName();
        Path manifest = dir.resolve(baseNameOf(fileName) + MANIFEST_SUFFIX);
        Path temp = dir.resolve(baseNameOf(fileName) + MANIFEST_SUFFIX + TMP_SUFFIX);

        String text = Json.write(toJson(record)) + System.lineSeparator();
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(temp, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
        return manifest;
    }

    /**
     * 列出所有备份。
     *
     * <p>只读清单文件,不打开 ZIP,所以即使有几十个 GB 级备份也很快。若发现没有清单的 ZIP,
     * 会作为「非本程序生成」的条目列出来(只读展示,不可删除)。</p>
     */
    public List<BackupRecord> listAll() {
        List<BackupRecord> records = new ArrayList<>();
        if (!PathUtils.isDirectory(backupDir)) {
            return records;
        }
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(backupDir)) {
            for (Path worldDir : worlds) {
                if (!PathUtils.isDirectory(worldDir) || TEMP_DIR.equals(worldDir.getFileName().toString())) {
                    continue;
                }
                records.addAll(listWorldDir(worldDir));
            }
        } catch (IOException | SecurityException e) {
            Log.warn("列出备份目录失败: %s (%s)", backupDir, e.getMessage());
        }
        records.sort(Comparator.comparingLong(BackupRecord::createdAt).reversed());
        return records;
    }

    /** 列出某个世界的备份(按时间倒序)。 */
    public List<BackupRecord> listForWorld(String worldFolderName) {
        List<BackupRecord> records = new ArrayList<>();
        Path dir = worldDir(worldFolderName);
        if (!PathUtils.isDirectory(dir)) {
            return records;
        }
        try {
            records.addAll(listWorldDir(dir));
        } catch (IOException e) {
            Log.warn("列出世界备份失败: %s (%s)", dir, e.getMessage());
        }
        records.sort(Comparator.comparingLong(BackupRecord::createdAt).reversed());
        return records;
    }

    /** 某个世界最近一次成功备份(用于自动备份判断「世界有没有变化」)。 */
    public Optional<BackupRecord> latestFor(String worldFolderName) {
        return listForWorld(worldFolderName).stream()
                .filter(BackupRecord::managed)
                .findFirst();
    }

    private List<BackupRecord> listWorldDir(Path worldDir) throws IOException {
        List<BackupRecord> records = new ArrayList<>();
        List<String> zipsWithoutManifest = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worldDir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(TMP_SUFFIX)) {
                    continue;
                }
                if (name.endsWith(MANIFEST_SUFFIX) && PathUtils.isFile(path)) {
                    BackupRecord record = readManifest(path);
                    if (record != null) {
                        records.add(record);
                    }
                } else if (name.toLowerCase(Locale.ROOT).endsWith(ZIP_SUFFIX) && PathUtils.isFile(path)) {
                    zipsWithoutManifest.add(name);
                }
            }
        }
        // 有清单的 ZIP 不再重复列出
        for (BackupRecord record : records) {
            zipsWithoutManifest.remove(record.zipFileName());
        }
        for (String zipName : zipsWithoutManifest) {
            Path zip = worldDir.resolve(zipName);
            records.add(new BackupRecord("", worldDir.getFileName().toString(),
                    worldDir.getFileName().toString(), "", "非本程序生成", FileUtils.lastModifiedMillis(zip),
                    0L, sizeOf(zip), 0, 0L, 0L, BackupRecord.STRATEGY_FULL, "UNKNOWN", 0,
                    List.of("没有找到对应的清单文件,不会被自动清理或删除"), false, "",
                    zipName, zip, null));
            Log.info("发现非本程序生成的 ZIP(只读展示): %s", PathUtils.toDisplayPath(zip));
        }
        return records;
    }

    private BackupRecord readManifest(Path manifest) {
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(manifest, StandardCharsets.UTF_8));
            String zipFileName = Json.optString(root, "zipFileName", "");
            if (zipFileName.isBlank()) {
                Log.warn("清单缺少 zipFileName,已忽略: %s", PathUtils.toDisplayPath(manifest));
                return null;
            }
            Path zip = manifest.getParent().resolve(zipFileName);
            if (!PathUtils.isFile(zip)) {
                Log.warn("清单对应的 ZIP 不存在: %s", PathUtils.toDisplayPath(zip));
                return null;
            }
            return new BackupRecord(
                    Json.optString(root, "producer", ""),
                    Json.optString(root, "worldFolderName", manifest.getParent().getFileName().toString()),
                    Json.optString(root, "worldDisplayName", ""),
                    Json.optString(root, "worldPath", ""),
                    Json.optString(root, "sourceLabel", ""),
                    Json.optLong(root, "createdAt", FileUtils.lastModifiedMillis(zip)),
                    Json.optLong(root, "durationMillis", 0L),
                    Json.optLong(root, "zipBytes", sizeOf(zip)),
                    Json.optInt(root, "fileCount", 0),
                    Json.optLong(root, "sourceBytes", 0L),
                    Json.optLong(root, "sourceChangeStamp", 0L),
                    Json.optString(root, "strategy", BackupRecord.STRATEGY_FULL),
                    Json.optString(root, "status", BackupRecord.STATUS_OK),
                    Json.optInt(root, "failedFiles", 0),
                    Json.optStringList(root, "warnings"),
                    Json.optBoolean(root, "sourceRunning", false),
                    Json.optString(root, "sha256", ""),
                    zipFileName,
                    zip,
                    manifest);
        } catch (IOException | RuntimeException e) {
            Log.warn("读取备份清单失败,已忽略: %s (%s)", PathUtils.toDisplayPath(manifest), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toJson(BackupRecord record) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("producer", record.producer());
        root.put("worldFolderName", record.worldFolderName());
        root.put("worldDisplayName", record.worldDisplayName());
        root.put("worldPath", record.worldPath());
        root.put("sourceLabel", record.sourceLabel());
        root.put("createdAt", record.createdAt());
        root.put("durationMillis", record.durationMillis());
        root.put("zipBytes", record.zipBytes());
        root.put("fileCount", record.fileCount());
        root.put("sourceBytes", record.sourceBytes());
        root.put("sourceChangeStamp", record.sourceChangeStamp());
        root.put("strategy", record.strategy());
        root.put("status", record.status());
        root.put("failedFiles", record.failedFiles());
        root.put("warnings", new ArrayList<>(record.warnings()));
        root.put("sourceRunning", record.sourceRunning());
        root.put("sha256", record.sha256() == null ? "" : record.sha256());
        root.put("zipFileName", record.zipFileName());
        return root;
    }

    // ------------------------------------------------------------------
    // 删除与保留策略
    // ------------------------------------------------------------------

    /** 删除一个备份(只删本程序生成的)。 */
    public DeleteOutcome delete(BackupRecord record) {
        if (!record.managed() || record.manifestPath() == null || record.zipPath() == null) {
            return new DeleteOutcome(DeleteStatus.NOT_MANAGED,
                    "不是本程序生成的备份,已跳过:" + record.zipFileName());
        }
        BackupRecord fresh = readManifest(record.manifestPath());
        if (fresh == null) {
            return new DeleteOutcome(DeleteStatus.MISSING, "备份清单已不存在,未做任何删除");
        }
        if (!fresh.zipFileName().equals(record.zipFileName())
                || !BackupRecord.PRODUCER.equalsIgnoreCase(fresh.producer())) {
            return new DeleteOutcome(DeleteStatus.NOT_MANAGED, "清单校验不通过,出于安全考虑未删除");
        }
        try {
            Files.deleteIfExists(record.zipPath());
            Files.deleteIfExists(record.manifestPath());
            Log.info("已删除备份: %s", PathUtils.toDisplayPath(record.zipPath()));
            return new DeleteOutcome(DeleteStatus.DELETED, "已删除 " + record.zipFileName());
        } catch (IOException e) {
            Log.error("删除备份失败: " + record.zipPath(), e);
            return new DeleteOutcome(DeleteStatus.FAILED, "删除失败:" + e.getMessage());
        }
    }

    /**
     * 按保留数量清理旧备份。
     *
     * @param retainCount 保留最近多少份(<=0 表示不限制)
     */
    public RetentionReport applyRetention(String worldFolderName, int retainCount) {
        List<Path> deleted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (retainCount <= 0) {
            return new RetentionReport(0, deleted, skipped);
        }
        List<BackupRecord> records = listForWorld(worldFolderName).stream()
                .filter(BackupRecord::managed)
                .sorted(Comparator.comparingLong(BackupRecord::createdAt).reversed())
                .toList();
        int examined = records.size();
        for (int i = retainCount; i < records.size(); i++) {
            BackupRecord old = records.get(i);
            DeleteOutcome outcome = delete(old);
            if (outcome.deleted()) {
                deleted.add(old.zipPath());
            } else {
                skipped.add(outcome.message());
            }
        }
        if (!deleted.isEmpty()) {
            Log.info("保留策略:世界 %s 清理了 %d 个旧备份", worldFolderName, deleted.size());
        }
        return new RetentionReport(examined, deleted, skipped);
    }

    /**
     * 查找上次运行留下的临时文件(程序被强制关闭时会残留)。
     *
     * <p>只报告,不删除——里面可能有用户还没确认的数据。</p>
     */
    public List<Path> findLeftoverTempFiles() {
        List<Path> left = new ArrayList<>();
        if (!PathUtils.isDirectory(backupDir)) {
            return left;
        }
        // 1) .tmp 目录下的残留副本
        Path temp = tempDir();
        if (PathUtils.isDirectory(temp)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(temp)) {
                for (Path path : stream) {
                    left.add(path);
                }
            } catch (IOException e) {
                Log.warn("检查临时目录失败: %s (%s)", temp, e.getMessage());
            }
        }
        // 2) 各个世界目录下未完成的 *.tmp(包含 .zip.tmp 与 .json.tmp)
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(backupDir)) {
            for (Path worldDir : worlds) {
                if (!PathUtils.isDirectory(worldDir) || TEMP_DIR.equals(worldDir.getFileName().toString())) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(worldDir, "*" + TMP_SUFFIX)) {
                    for (Path path : files) {
                        left.add(path);
                    }
                }
            }
        } catch (IOException e) {
            Log.warn("检查备份目录失败: %s (%s)", backupDir, e.getMessage());
        }
        return left;
    }

    /** 清理临时文件(仅在用户确认后调用)。 */
    public int cleanTempFiles(List<Path> paths) {
        int removed = 0;
        for (Path path : paths) {
            if (WorldCopier.deleteRecursively(path)) {
                removed++;
            }
        }
        Log.info("已清理 %d 个临时文件/目录", removed);
        return removed;
    }

    private static String baseNameOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index <= 0 ? fileName : fileName.substring(0, index);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }
}
