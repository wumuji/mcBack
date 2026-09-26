# MC Backup — Minecraft Java 版存档自动备份与导出工具

一个**独立运行**的 Minecraft Java Edition 存档管理工具:

- 不是 Mod,不注入游戏进程,不修改任何游戏文件;
- 原版与模组整合包都能用(只用文件系统观察存档目录);
- 全部本地运行,不需要联网、不需要服务器、不需要数据库;
- 零第三方运行时依赖,界面用 JDK 自带的 Swing 自绘;
- 目标是把「每次都很快、几乎不占资源」放在第一位。

当前进度:**第三阶段(在第二阶段基础上加入恢复、完整性校验、系统托盘与免安装打包)**。

## 现在能做什么

**世界发现与浏览**

- 自动发现:官方启动器 `%APPDATA%\.minecraft\saves`、版本隔离目录 `versions\*\saves`
  (PCL 等)、各盘符根下的 `.minecraft`、Prism / MultiMC / ATLauncher / Modrinth / CurseForge 实例,
  以及 PCL.ini / hmcl.json / prismlauncher.cfg 里写的自定义路径;也可以手动添加目录。
- 按**结构**识别世界(`level.dat` + 至少 2 项典型结构),不靠文件夹名字。
- 世界列表显示:世界名(读 `level.dat`)、版本、模式、难度、世界天数、大小、最后修改时间,
  并用 `session.lock` 共享锁探测「是否可能正在被游戏使用」。

**备份**

- 「世界」页面选中世界 → 立即备份;或打开自动备份(5/10/15/30/60/120 分钟)。
- 备份流程:复制到临时目录(逐文件重试)→ 流式压缩成 `.zip.tmp` → 打开校验 →
  原子改名成最终 `.zip` → 写入同名 `.json` 清单 → 清理临时副本 → 执行保留策略。
- 备份列表显示世界名、时间、大小、文件数、耗时与状态,支持「打开目录 / 导出 / 删除」。
- 保留策略:每个世界保留最近 N 份(5/10/20/50/100/不限制),自动清理旧备份。
- 自动备份只在世界**确实发生变化**时才复制文件(比较变化指纹),没变化直接跳过。

**导出**

- 「导出」页面选择一个世界 → 导出标准 ZIP:解压后直接得到 `level.dat`、`region/` 等,不会多套一层目录。
- 备份列表里的「导出」是把已有备份 ZIP 另存到指定位置(流式复制,不重新压缩)。

**其它**

- 主题:跟随系统 / 浅色 / 深色;界面刻意保持简单:无动画、无阴影、控件尽量少。
- 日志按天写入 `%APPDATA%\MCBackup\logs\yyyy-MM-dd.log`,保留 14 天。
- 启动时检查上次运行残留的临时文件,弹出提示(删除 / 打开目录 / 稍后处理),**绝不自动删除**。

**恢复(第三阶段)**

- 备份列表里点「恢复」,把任意一份备份还原成世界;恢复前会先把当前世界改名保留为
  `世界名.restore-backup-时间戳`,**永远不删除原世界**。
- 恢复前会检查三件事:备份能否打开且路径安全、世界是否正被 Minecraft 占用
  (`session.lock` 锁探测)、磁盘剩余空间是否够解压;任何一项不过就提前失败。
- 解压到临时目录并确认是完整世界后,才做「改名 + 改名」的原子替换;替换失败会自动把原世界改回来。
- 备份清单里如果记录了 SHA-256,恢复前会先核对整包哈希,不一致直接中止。

**完整性校验(第三阶段)**

- 设置里可以打开「完整校验(SHA-256)」:每次备份完成后额外计算整包哈希并写进清单。
- 备份列表的「校验」按钮:结构校验(毫秒级)+ 有哈希时核对 SHA-256,结果弹窗给出清单哈希与实际哈希。

**系统托盘与打包(第三阶段)**

- 设置里可打开「最小化到托盘」:关闭窗口只是收进托盘,自动备份继续在后台运行;
  托盘菜单提供「显示主窗口 / 立即备份有变化的世界 / 退出」。托盘不可用时自动降级为普通窗口行为。
- `scripts\package.ps1` 用 `jlink` 裁出只含所需模块的运行时,再用 `jpackage` 生成免安装的
  `MCBackup.exe`(内置运行时,不需要安装 Java,也不需要 WiX)。

## 实测性能(本机 NVMe,Java 21)

| 世界 | 文件数 | 大小 | 备份 ZIP | 耗时 | 峰值堆内存 |
| --- | --- | --- | --- | --- | --- |
| 机械动力影院 · 新的世界 | 311 | 454 MB | 310 MB | 5.9 s | 33.7 MB |
| 爽包 · 新的世界(整合包) | 563 | 291 MB | 124 MB | 7.0 s | 61.7 MB |
| 机械动力影院 · 1145 | 45 | 11.5 MB | 1.0 MB | 0.3 s | 10.8 MB |

三次实测都把 JVM 堆上限设成 **128 MB**,峰值只用到 10–62 MB —— 备份 GB 级世界不会出现
几百 MB 甚至 GB 级的额外内存占用。空闲时扫描线程与自动备份线程都处于等待状态,CPU 接近 0。

### 关于压缩方式的取舍

区域文件 `.mca` 内部已经是压缩过的区块数据,重新压缩往往没有收益,但会花掉大部分时间。
所以程序会**先取 3 段各 64KB 采样试压**:

- 压不动的(采样压缩率 > 85%)→ 直接存储,省 CPU、省时间;
- 能压得动的(例如几乎全是空区块的世界)→ 照常压缩,备份体积能小一个数量级。

设置里也可以强制「体积优先(全部压缩)」:454 MB 的世界从 5.9 秒变成约 15 秒,ZIP 从 310 MB 变成 300 MB。

## 开发环境与命令

- JDK 21 或更高(本机已验证 `C:\Program Files\Java\jdk-21`);不需要 Maven / Gradle / IDE。
- 单元测试需要一次性把 `junit-platform-console-standalone` 下载到 `.tools/`(仅测试期使用,不进产物)。

```powershell
powershell -File scripts\build.ps1        # 编译 -> out\classes 与 out\mcbackup.jar
powershell -File scripts\run.ps1          # 启动界面
powershell -File scripts\test.ps1         # 95 个单元测试
powershell -File scripts\screenshot.ps1 -ExtraRoot E:\.minecraft   # GUI 截图自检 + 真实世界备份冒烟
powershell -File scripts\package.ps1      # jlink + jpackage -> release\MCBackup\MCBackup.exe(另有 zip)
```

命令行参数(开发/便携用):

```
--root <目录>         额外加入一个存档目录(不写入配置)
--backup-dir <目录>   临时覆盖备份目录(不写入配置)
--screenshot <目录>   截图自检模式:渲染界面并退场
--export-icons <目录> 导出窗口/托盘/exe 共用的图标(PNG + ICO),导出后退出
--console-log         日志同时输出到控制台
```

### 打包产物(实测)

| 项目 | 大小 |
| --- | --- |
| `release\MCBackup\` 整个免安装目录 | 47.0 MB |
| 其中裁剪后的 Java 运行时 | 46.4 MB |
| 程序本体 `mcbackup.jar` | 0.2 MB |
| `release\MCBackup-portable.zip` 分发包 | 32.2 MB |

打包脚本会自己验证一遍:先用裁剪后的运行时启动程序确认能跑起来,再用打包好的
`MCBackup.exe` 执行一次真实操作(导出图标)。目标机器不需要装 Java、不需要 VC++ 运行库、
不需要 WiX。

**请始终使用 `release\` 目录里的那一份**(或解压 `MCBackup-portable.zip` 到任意位置)。
`build\dist\` 是打包脚本的中间产物,每次打包都会被删除重建;如果正好在重建过程中双击它,
会因为目录残缺而报错。

### 双击 MCBackup.exe 报 “Failed to launch JVM” 怎么办

jpackage 生成的 `MCBackup.exe` 是「启动器 + 子进程」两段式结构,这句话是启动器在**无法拉起子进程**时弹出的。
常见原因与对应处理:

1. **应用目录不完整**(最常见):目录被移动/复制不全,或正好在打包重建过程中被点击。
   → 用 `release\MCBackup-portable.zip` 完整解压后使用。
2. **缺少辅助功能模块**:机器上启用了讲述人/放大镜时,`%USERPROFILE%\.accessibility.properties` 会写着
   `assistive_technologies=com.sun.java.accessibility.AccessBridge`,AWT 初始化必须加载这个类,
   否则直接抛 `AWTError` 崩溃。本项目的运行时已包含 `jdk.accessibility`,并且代码里加了兜底:
   如果运行时确实没有这个模块,会自动为本进程关闭该加载(只影响本进程,不改系统设置)。
3. **安全软件拦截子进程**:少数情况下 360 等会拦住启动器的第二次拉起。
   → 用目录里的 `直接用命令行启动.cmd`(绕过启动器,直接用自带运行时启动)。
4. 想看到真正的错误信息:双击 `出错时运行我-诊断.cmd`,它会检查目录完整性、打印运行时版本,
   并把程序的完整输出显示在窗口里。

程序日志在 `%APPDATA%\MCBackup\logs\yyyy-MM-dd.log`,出问题先看这里。

## 目录结构

```
src/main/java/com/mcbackup/
  App.java                     入口:命令行、日志、配置、主题、启动窗口
  model/                       MinecraftWorld / BackupRecord / BackupOptions / ScanResult / AppSettings ...
  service/                     LauncherDetector(目录发现)、WorldScanner(扫描)、WorldDetector(世界识别)、
                               LevelInfoReader(level.dat)、BackupService(备份流程)、ExportService(导出)、
                               BackupScheduler(自动备份调度)、BackupException
  storage/                     SettingsRepository(config.json)、BackupRepository(备份仓库/清单/删除/保留策略)
  util/                        Json、Log、PathUtils、FileUtils、NbtReader、ZipUtils、WorldCopier、ProgressListener
  ui/                          MainWindow、Sidebar、TopBar、WorldView、BackupView、ExportView、SettingsView、ScreenshotRunner
  ui/components/               Card / FlatButton / Pill / TLabel / NavItem / Icons / EmptyState / ScrollPaneStyler
  ui/theme/                    Palette(锁定的配色)、ThemeManager、UiFonts
```

## 文件安全设计

备份/导出只**读**源世界目录,任何失败都不会改动原始存档:

1. **不产生看起来正常的坏 ZIP**:先写 `<名字>.zip.tmp`,打开校验通过后才原子改名成 `.zip`。
2. **运行中备份**:Minecraft 会持续改文件,复制阶段对每个文件比较复制前后的**大小与修改时间**,
   变化了就删掉重来(最多 3 次);单个文件彻底失败只记录警告并继续,任务不会整体崩掉。
   `session.lock` 属于可选文件:复制不到不影响世界本身。
3. **不误删用户文件**:自动清理与删除只针对「有清单、清单写着 MCBackup、文件名一致」的备份。
   用户自己放进备份目录的 ZIP 会显示为「非本程序生成」,按钮禁用,永不删除。
4. **崩溃残留**:未完成的临时文件会在下次启动时被报告,由用户决定是否清理。
5. **内存纪律**:不使用 `Files.readAllBytes` 处理大文件;复制与压缩都是固定 64KB 缓冲区的流式处理;
   `level.dat` 解析有文件/解压/嵌套/元素数量四重上限,损坏或恶意文件只会被拒绝。

## 关于「Minecraft 正在运行时备份」

原版 Minecraft 没有对外的官方保存接口,独立的外部程序**无法**像 Mod 那样命令游戏「先保存再让我复制」,
所以做不到 100% 原子快照。本程序采取的是「尽力安全」:文件级变化检测与重试、复制后校验、
ZIP 完整性校验、失败文件不拖垮任务。也就是说:**备份失败 ≠ 原存档失败**,任何时候源世界都不会被改动。

## 已知限制

- 系统主题只在启动时读一次,不监听系统实时切换。
- 自动备份间隔是固定档位(5/10/15/30/60/120 分钟),暂不支持任意分钟数与 cron 表达式。
- 界面语言为简体中文。
- JDK 的 `Path` 会规范化掉 `\\?\` 前缀,超长路径最终取决于系统的长路径设置。
- 免安装产物是 app-image 形式;带开始菜单/桌面快捷方式的安装包(`MCBackup-Setup.exe`)需要
  WiX Toolset,本机未安装,尚未提供。
- 恢复是「整目录替换」而不是增量合并;备份格式仍是全量 ZIP。

## 后续阶段

- 安装包(`MCBackup-Setup.exe`):需要安装 WiX Toolset 后再用 `jpackage --type msi/exe` 生成。
- 增量备份:清单里已经预留了 `strategy` 字段(`FULL`),以后新增
  `IncrementalBackupStrategy` 不需要改清单格式。
- 自动备份的更多触发方式(例如退出游戏后、空闲时)。

## 测试

`scripts\test.ps1` 目前 95 个测试,覆盖:JSON 读写与损坏输入、Windows 路径清洗与非法文件名、
NBT 读取(截断 / 超大数组 / 过深嵌套 / 超大文件 / 压缩炸弹)、世界识别正反例、
启动器目录发现(官方 / 版本隔离 / 实例 / 盘符根 / 配置文件)、扫描器来源标签与排除规则、大小缓存命中、
ZIP 条目结构(扁平化、中文名、空目录保留、注释)、区域文件存储与压缩策略、世界复制(含空目录与锁文件)、
备份端到端(原子提交、清单、保留策略、用户 ZIP 保护、进度阶段)、导出端到端(扩展名规范化、无 `..` 层级)、
恢复端到端(内容还原、原世界保留、占用时拒绝、坏备份不动原世界、SHA-256 不匹配中止、路径穿越拒绝)、
完整性校验(结构 / 哈希 / 篡改检测)、图标(窗口 / 托盘 / ICO 打包同源)、
自动备份(变化检测、跳过、失败不中断、启停)、配置往返与损坏回退、`session.lock` 探测,
以及界面结构与主题一致性(浅色主题下不允许出现深色残留区块)。
