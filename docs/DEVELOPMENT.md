# mcBack 开发文档

面向要改代码或自己打包的人。用户向说明见 [README](../README.md)。

## 环境

- JDK 21 或更高(推荐 Temurin / Oracle / Zulu 任一)
- 不需要 Maven / Gradle / IDE;构建脚本是纯 PowerShell + JDK
- 单元测试首次会自动下载 `junit-platform-console-standalone` 到 `.tools\`(仅测试期使用)
- 生成安装包需要 WiX Toolset **3.x** 的 `candle.exe` / `light.exe`;本项目用便携版放在 `.tools\wix\`,**不需要装到系统**

## 常用命令

```powershell
powershell -File scripts\build.ps1 [-Clean]         # 编译 -> out\classes 与 out\mcBack.jar
powershell -File scripts\run.ps1                    # 启动开发版
powershell -File scripts\test.ps1                   # 单元测试(116 个)
powershell -File scripts\screenshot.ps1             # GUI 截图自检 + 真实世界备份冒烟
powershell -File scripts\package.ps1 [-NoInstaller] # jlink + jpackage -> release\mcBack\ + zip + Setup.exe
powershell -File scripts\release-smoke.ps1          # 在打包产物上跑 备份→校验→恢复→比对
powershell -File scripts\release.ps1 -Version 1.0.0 [-Tag]   # 一键发布到 release\v1.0.0\
```

命令行参数(开发/自检用):

```
--root <目录>            额外加入一个存档目录(不写入配置)
--backup-dir <目录>      临时覆盖备份目录(不写入配置)
--screenshot <目录>      截图自检模式:渲染界面并退出
--export-icons <目录>    导出窗口/托盘/exe 共用的图标(PNG + ICO)
--self-test <目录>       无界面自检:备份→校验→恢复→逐文件比对
--self-test-cycles <n>   自检的备份轮数(每轮先改一点内容)
--console-log            日志同时输出到控制台
--no-auto-scan           启动后不自动扫描
```

## 架构

```
src/main/java/com/mcback/
  App.java                  入口:命令行、日志、辅助功能兼容、单实例、主题、启动窗口
  model/                    MinecraftWorld、BackupRecord/Options/Result、ScanResult、AppSettings、Theme
  service/                  LauncherDetector(目录发现)、WorldDetector(世界识别)、WorldScanner(扫描)、
                            LevelInfoReader(level.dat)、BackupService、ExportService、RestoreService、
                            IntegrityService、BackupScheduler、BackupTargets、SelfTest、BackupException
  storage/                  SettingsRepository(config.json)、BackupRepository(清单/列表/删除/保留策略)
  util/                     Json、Log、PathUtils、FileUtils、NbtReader、ZipUtils、WorldCopier、Hashing、
                            ProgressListener、SingleInstanceGuard、AccessibilityGuard
  ui/                       MainWindow、Sidebar、TopBar、AutoBackupView、BackupRecordsView、
                            BackupSettingsDialog、SettingsView、TraySupport、ScreenshotRunner
  ui/components/            Card、FlatButton、Pill、TLabel、NavItem、Icons、EmptyState、ScrollPaneStyler、AppIcon
  ui/theme/                 Palette(锁定的配色)、ThemeManager、UiFonts、ThemeAware
```

线程约定:UI 在 EDT;扫描在 `mcback-scan`;备份/导出/恢复在 `mcback-worker`(串行,避免磁盘抖动);
定时检查在 `mcback-autobackup`。空闲时三个线程都在等待,**没有轮询**,所以空闲 CPU ≈ 0。

## 关键设计决策

**零第三方运行时依赖 / 自绘 Swing**
界面全部用 `Graphics2D` 自绘(卡片、按钮、图标、滚动条),配色集中在 `Palette`。这样运行时只需要
`java.base` + `java.desktop` 等少数模块,`jlink` 后的运行时约 46MB,也不引入任何 UI 框架。

**自研 JSON / NBT / ZIP 处理**
配置与清单只需很小的 JSON,`level.dat` 只读几个字段,ZIP 用 JDK 自带实现。手写这几处比引入依赖更符合
「体积小、可审计、离线可构建」的目标;`NbtReader` 有文件大小、解压上限、嵌套深度、元素数量四重限制。

**内存纪律**
代码里没有 `Files.readAllBytes` 处理大文件;复制与压缩都是固定 64KB 缓冲区的流式处理。
实测 454MB 世界备份峰值堆内存约 34MB(堆上限设为 128MB 也能跑完)。

**备份的原子性**
复制到 `<备份目录>\.tmp\...` → 压缩成 `<名字>.zip.tmp` → 打开校验 → 原子改名 → 写清单 → 清理临时副本。
任何一步失败都只清理临时文件,不会产生「看起来正常的坏 ZIP」。

**压缩策略**
区域文件 `.mca` 先取三段 64KB 采样试压:压不动的(采样压缩率 >85%)直接存储,能压得动的照常压缩。
实测 454MB 世界从 15 秒降到约 6 秒,而几乎全是空区块的世界仍能压到 1MB 量级。

**恢复保护**
校验备份 → 检查世界是否正被占用(`session.lock` 共享锁探测)→ 检查磁盘空间 → 解压到临时目录 →
确认是完整世界 → 把当前世界改名成 `世界名.restore-backup-时间戳` → 原子替换 → 失败自动回滚。
**永不删除原世界。**

**运行中备份的边界**
外部程序无法做到原子快照。因此逐文件比较复制前后的大小与修改时间并重试,失败文件不中断整体任务,
并在清单里记录「备份时游戏是否在运行」。

**单实例**
`%APPDATA%\mcBack\.instance.lock` 文件锁。两个实例同时自动备份同一个世界会互相干扰,因此第二个实例
提示后退出;进程被强制结束时锁由系统回收,不会留下假锁。

**辅助功能兼容**
旧版 Java 会在 `%USERPROFILE%\.accessibility.properties` 里留下 `AccessBridge`,而该类自 JDK 9 起已不存在;
不处理的话 AWT 初始化会直接抛错、程序连窗口都开不出来。`AccessibilityGuard` 在触碰 AWT 之前检查,
发现类不存在就只为本进程关闭该加载(不改系统设置)。

## 打包细节

`scripts\package.ps1` 依次做:

1. `build.ps1` 编译并打 jar;
2. 导出图标(窗口 / 托盘 / exe 用的是同一份绘制代码,避免三处不一致);
3. `jlink --add-modules java.base,java.desktop,java.logging,jdk.charsets,jdk.unsupported,jdk.accessibility`
   ——`jdk.charsets` 用于读 GBK 的启动器配置,`jdk.accessibility` 用于兼容辅助功能机器;
4. 用裁剪后的运行时启动一次程序做冒烟;
5. `jpackage --type app-image` 生成 `release\mcBack\`(含 `mcBack.exe`),再打包成 `release\mcBack-portable.zip`;
6. 若 `.tools\wix\candle.exe` 存在,再生成 `release\mcBack-<版本>-Setup.exe`
   (按用户安装,带开始菜单 / 桌面快捷方式 / 可选安装目录);
7. 在发布目录里附带两个辅助脚本:`直接用命令行启动.cmd`(绕过启动器)和 `出错时运行我-诊断.cmd`。

## 自检与发布验证

- `--self-test` 是程序自带的无界面自检:扫描 → 备份(可多轮,每轮先改内容)→ ZIP 校验 → 恢复到新目录 →
  逐文件 SHA-256 比对 → 检查临时文件残留。退出码 0 表示通过。
- `scripts\release-smoke.ps1` 造一个合成世界,用**打包产物**跑上面的自检,再拉起 `mcBack.exe` 确认能启动,
  最后核对产物(zip / manifest / 临时文件计数)。它还会拒绝比源码更旧的打包产物,避免拿旧 jar 测新参数。
- `scripts\release.ps1` 把「版本一致性检查 → clean 构建 → 测试 → 打包 → 冒烟 → 组装 `release\v<版本>\` →
  生成 SHA256SUMS →(可选)打 tag」串成一条命令。

## 测试

116 个单元测试,覆盖:JSON 与损坏输入、Windows 路径与非法文件名、NBT(截断 / 超大数组 / 过深嵌套 / 压缩炸弹)、
世界识别正反例、启动器目录发现与分组名、扫描排除规则、大小缓存、ZIP 结构(扁平化 / 中文名 / 空目录 / 压缩策略)、
世界复制、备份端到端(原子提交 / 清单 / 保留策略 / 用户 ZIP 保护 / 空间检查 / 运行中标记)、导出、
恢复(内容还原 / 原世界保留 / 占用拒绝 / 坏备份不动原世界 / SHA-256 不匹配 / 路径穿越)、完整性校验、
自动备份(只备份勾选 / 变化检测 / 跳过 / 失败不中断 / 倒计时 / 旧配置迁移)、单实例、配置往返与损坏回退、
辅助功能兼容、图标,以及界面结构与主题一致性。
