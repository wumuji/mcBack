# MC Backup — Minecraft Java 版存档自动备份与导出工具

一个**独立运行**的 Minecraft Java Edition 存档管理工具:

- 不是 Mod,不注入游戏进程,不修改任何游戏文件;
- 原版与模组整合包都能用(只用文件系统观察存档目录);
- 纯本地运行,不需要联网、不需要服务器、不需要数据库;
- 零第三方运行时依赖,界面用 JDK 自带的 Swing 绘制。

当前进度:**第一阶段(项目骨架 + GUI + 存档自动检测 + 世界列表)**。

## 当前能做什么

- 启动一个自绘的现代化窗口(深色/浅色/跟随系统),左侧导航:世界 / 备份 / 导出 / 设置。
- 自动发现存档目录:
  - `%APPDATA%\.minecraft\saves`;
  - `%APPDATA%\.minecraft\versions\<版本>\saves`(PCL 等启动器的版本隔离布局);
  - 各盘符根下的 `.minecraft`(玩家自定义安装位置,如 `E:\.minecraft`);
  - Prism / MultiMC / ATLauncher / Modrinth / CurseForge 的实例目录;
  - PCL.ini、hmcl.json、prismlauncher.cfg 等配置文件中出现的自定义路径(粗提取,失败不影响其它来源);
  - 用户手动添加的目录(保存在配置文件里)。
- 按**结构**识别世界:`level.dat` 必须存在,且命中 `region`/`playerdata`/`advancements`/`data`/`poi`/`entities`/
  `dimensions`/`DIM1`/`DIM-1` 中至少 2 项;仅靠文件夹名字不会被当成世界。
- 列出世界:世界名(读 `level.dat`)、文件夹名、来源目录、游戏版本、模式、难度、世界天数、大小、最后修改时间。
- 通过 `session.lock` 的共享锁探测「世界是否可能正在被 Minecraft 使用」,并在列表里以状态胶囊提示。
- 设置页:主题切换、存档目录管理(添加/移除/重新扫描)、日志目录入口、关于信息。
- 日志按天写入 `%APPDATA%\MCBackup\logs\yyyy-MM-dd.log`,保留 14 天。

备份、导出、恢复等按钮在界面上以禁用态占位,并注明「第二阶段提供」——不会给出点了没反应的假开关。

## 开发环境

- JDK 21 或更高(本机已验证 `C:\Program Files\Java\jdk-21`)。
- 不需要 Maven / Gradle / 任何 IDE。构建脚本是纯 PowerShell + JDK。
- 单元测试需要一次性把 `junit-platform-console-standalone` 下载到 `.tools/`(仅测试期使用,不进产物)。

### 常用命令

```powershell
# 编译(输出 out\classes 与 out\mcbackup.jar)
powershell -File scripts\build.ps1

# 启动界面
powershell -File scripts\run.ps1

# 运行单元测试
powershell -File scripts\test.ps1

# GUI 截图自检(渲染到 build\shots)
powershell -File scripts\screenshot.ps1 -ExtraRoot E:\.minecraft
```

## 目录结构

```
src/main/java/com/mcbackup/
  App.java                     入口:命令行解析、日志、配置、主题、启动窗口
  model/                       MinecraftWorld / WorldInfo / ScanResult / AppSettings / Theme ...
  service/                     LauncherDetector(目录发现)、WorldDetector(世界识别)、
                               WorldScanner(扫描)、LevelInfoReader(level.dat)、WorldSizeCache(大小缓存)
  storage/                     SettingsRepository(config.json 读写,原子保存)
  util/                        Json、Log、PathUtils、FileUtils、NbtReader(自写 NBT 读取)
  ui/                          MainWindow、Sidebar、TopBar、WorldView、SettingsView、ScreenshotRunner
  ui/components/               Card / FlatButton / Pill / TLabel / NavItem / Icons / ScrollPaneStyler ...
  ui/theme/                    Palette(锁定的配色)、ThemeManager(主题解析与下发)、UiFonts
```

## 设计决策

**为什么不用 JavaFX。**第一阶段的界面全部自绘:圆角卡片、扁平按钮、细滚动条、状态胶囊都由
`Graphics2D` 直接绘制,配色集中在一个 `Palette` 里。好处是运行时只需要 `java.base` + `java.desktop`,
后续 `jlink` 裁剪后的体积和内存都能压到最小,也不引入任何第三方 UI 框架。

**为什么不用 SQLite。**第一阶段只有一份很小的配置,用自写的 JSON 读写器,配合「写临时文件 + 原子改名」,
既满足需求又避免额外依赖。

**为什么自己写 NBT 解析。**只需要读 `level.dat` 里几个字段,写一个带安全上限的最小解析器比引入
完整的 NBT 库更符合「不引入庞大第三方依赖」的目标。

**内存占用控制。**代码中不出现 `Files.readAllBytes` 处理大文件:目录大小统计走 `Files.walkFileTree`
流式累加;`level.dat` 读取有 8 MB 文件上限、32 MB 解压上限、64 层嵌套上限、单数组 100 万元素上限、
整棵树 200 万元素上限,损坏或恶意文件只会被拒绝,不会撑爆内存。

**空闲时为什么几乎不耗 CPU。**扫描只按需触发,且只遍历已知的 saves 目录;世界大小按
`路径 + mtime 指纹` 缓存,世界没变就不重新统计;改动检测只读取 `level.dat`、`region` 目录与世界顶层的
时间戳,不做全量比对;界面上只有两处 120–140ms 的动画,没有常驻动画循环。

## 关于「Minecraft 正在运行时备份」

这一条必须说清楚,避免给出做不到的承诺:

原版 Minecraft 没有对外的官方保存接口,独立的外部程序**无法**像 Mod 那样命令游戏「先保存再让我复制」,
所以做不到 100% 原子快照。能做到的是「尽力安全」:检测文件变化、避免复制正在变化的文件、
复制后校验、关键文件重复检查、失败重试、失败文件不拖垮整个任务。

第一阶段的实现仅限于**观察**:通过 `session.lock` 的共享锁判断世界是否可能正在被占用,并在界面上提示。
真正的运行中备份策略(区域文件重试、`.mca` 头部校验、延迟备份)在第二阶段实现。

## 已知限制

- 系统主题只在启动时读取一次,不监听系统的实时切换。
- JDK 的 `Path` 会规范化掉 `\\?\` 前缀,所以超过 260 字符的路径最终取决于系统的长路径设置;
  `PathUtils.isLongPath` / `toExtendedString` 提供了判断与给外部程序使用的扩展路径字符串。
- 界面语言为简体中文。
- 第一阶段不提供打包产物(免安装 jpackage 应用与安装包在后续阶段;本机未安装 WiX,做安装包需要额外授权)。

## 后续阶段

- 第二阶段:手动备份(临时目录 → ZIP → 完整性校验 → 原子改名)、流式 ZIP、备份保留策略、
  自动备份调度、导出世界、备份列表与删除,以及 `BackupStrategy` 接口(`FullBackupStrategy` 为默认实现,
  为将来的增量备份预留)。
- 第三阶段:恢复(先备份当前世界再替换、需要时拒绝恢复)、系统托盘、更完善的运行中备份检测、
  完整性与 SHA-256 校验、`jlink` + `jpackage` 免安装产物。

## 测试

`scripts\test.ps1` 覆盖:JSON 读写与损坏输入、Windows 路径清洗与非法文件名、NBT 读取(含截断文件、
超大数组、过深嵌套、超大文件、压缩炸弹)、世界识别正例与反例、启动器目录发现(官方/版本隔离/实例/盘符根/
配置文件)、扫描器的来源标签与排除规则、大小缓存命中、配置往返与损坏回退、文件工具与 `session.lock` 探测,
以及界面结构与主题一致性(浅色主题下不允许出现深色残留区块)。
