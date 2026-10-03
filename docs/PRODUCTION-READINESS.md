# 生产就绪审查记录

本轮基线：`6533738`，2026-10-03。以当前源码、回归测试和构建结果为证据；之前的 `docs/UI-UX-AUDIT.md` 是历史走查，不能代替本轮运行验收。

## 本轮已确认的问题与修复

| 范围 | 触发条件与原行为 | 本轮处理 | 证据 |
| --- | --- | --- | --- |
| WebDAV 路径 | href 中的字面 `+` 被表单解码规则转为空格 | 保留 `+`，百分号转义只解码一次；相对媒体重定向转成绝对 URL | `WebDavClientTest`，本地 HTTP 服务 |
| 目录完整性 | HTTP 200 的 HTML、失败的目录属性被当作空目录或普通文件 | 拒绝无效/不完整目录响应，使扫描保留已有条目 | `WebDavClientTest` |
| 子目录扫描 | 特典、嵌套影片或字幕目录请求失败被吞掉，后续差集删除旧条目 | 故障传播到当前根目录，保留这一组已有条目和元数据，其他目录继续 | `ScannerWalkTest` |
| 同步文件读取 | 读取上限未生效；重定向后的 Range 前缀被当作完整文件 | 完整 GET，按实际字节数限制；拒绝部分响应；重定向目标的 404 不推断为原文件不存在 | `WebDavClientTest`、`SyncUploadGuardTest` |
| 同步覆盖 | JSON 损坏或版本过新仍被本机内容覆盖 | 报错并保留云端文件；上传期间发生的本地编辑仍待下次上传 | `SyncUploadGuardTest` |
| 同步遗漏 | 最大时间戳与行数不变时不上传；云端被旧快照覆盖后，本地合并无变化也不补传 | 增加事务内的本地变更序号；云端缺少本地行或落后于本地时间戳时重新安排上传 | `SyncRevisionTest`、扩展的 `SyncUploadGuardTest`，已通过 |
| 离线文件 | 特殊字符清理或大小写导致不同下载共用文件；续传全量响应被拼接 | 为成品和部分文件分配独立路径，保留旧登记路径；校验续传响应及大小 | `OfflineLibraryTest` |
| 播放字节管道 | 后缀 Range 被当作文件头，越界返回错误字节，忽略范围时内容长度不符 | 按完整大小解释单区间，裁剪结尾并限制输出；无效区间返回 416 | `PlaybackPipeRangeTest` |
| 扫描取消 | 取消异常只结束一个探测线程，其他探测继续写库，最终仍显示完成 | 有界工作线程只读取，协调线程检查取消并写库，丢弃迟到结果 | 探测取消与扫描取消回归测试 |
| 元数据响应 | 服务商返回 HTML 被缓存一周；错误日志包含 TMDB API Key | 只缓存可解析 JSON，绕过旧坏缓存，限制响应大小，日志隐藏 URL 参数 | `HttpScraperTest` |
| 界面异步状态 | 旧详情请求覆盖新详情；旧分页混入新筛选 | 请求代次和查询快照校验，取消过期请求，分页与字母跳转共用追加锁 | 桌面状态层竞态回归测试 |
| 播放生命周期 | 外部播放入口未清理旧会话；退出时切集请求仍写回；清理依赖已取消 UI scope | 统一请求生命周期，分离资源清理与 UI 生存期 | 桌面播放控制回归测试 |
| 原生与平台状态 | mpv 销毁与其他原生调用并发；Android 生命周期捕获旧 PiP 状态 | 原生调用持有读锁、销毁独占；Android 到 ON_STOP 时暂停 | `MpvPlayerLifecycleTest`；Android 编译 |
| 最低 Android 版本 | minSdk 26 的代码调用 API 33 的读取/URL 方法、API 34 的 Path.of | 用 API 26 可用的有界读取、编码名称重载和 Paths.get | `StreamsTest`、Android lint；[InputStream API](https://developer.android.com/sdk/api_diff/33/changes/java.io.InputStream)、[Path API](https://developer.android.com/reference/java/nio/file/Path) |
| Android 数据库版本 | 系统 SQLite 随 Android 版本变化；API 26 的引擎不支持共享仓库使用的 UPSERT | 改用固定版本 AndroidX bundled SQLite；对整个事务/游标生命周期加锁；保留数据库格式与现有迁移 | `AndroidSqlOnDeviceTest` 7 项在 API 36 上通过；API 26 实机未验证；[Android SQLite 版本](https://developer.android.com/reference/android/database/sqlite/package-summary)、[UPSERT 引入版本](https://www.sqlite.org/releaselog/3_24_0.html) |
| Android 备份 | 旧版备份只排除 config.json，仍收集含凭据的临时/恢复副本 | Android 11 及更早只备份数据库及其 WAL/SHM、界面首选项 | 备份规则检查、Android lint；未做系统备份/恢复实测 |
| Android lint | 进度通知缺权限处理、Media3 opt-in 缺失、无用布局与源码不可见 BOM | 修正权限处理和 opt-in，替换无用布局，使用显式 Unicode 转义 | `:composeApp:lintDebug`，0 错误 |
| Windows 全屏失焦 | AWT 独占全屏在切到其他窗口时最小化 | 当前显示器上的无边框全屏；保留主窗口和视频 HWND，退出时恢复 WINDOWPLACEMENT；关闭时保存进入全屏前的窗口设置 | `WindowsFullscreenTest`，含真实 libmpv 测试图案和焦点转移 |
| Windows 播放引擎发布 | 每次抓取最新版且不核对摘要，下载失败仍生成缺少内置引擎的 MSI | 固定已测引擎的归档与 DLL 摘要；验证后原子替换；CI 失败即阻止打包；随包保留构建来源与 mpv 许可 | 4 项下载/替换保护检查通过；两套 Windows 分发目录的 DLL 摘要、来源与许可文件逐字节校验及启动通过 |
| 配置保存与恢复 | 错误 URL 先持久化再构造客户端，下次启动失败；损坏配置备份失败后仍允许覆盖；保存失败时密码草稿被清空 | 先验证并构造客户端再保存；旧错误地址可在设置中修复；原配置未成功保留则禁止覆盖；只在成功保存后清除本次提交的密码/密钥 | `StorageSettingsTest`、`ConfigStoreTest`，已通过 |

## 当前验证证据

截至 2026-10-04 02:29（Asia/Singapore），下表是本轮已完成的验证范围。报告保存在本机 `build/audit/`，不提交大型运行产物。测试数中的跳过不计为实际通过。

| 范围 | 结果与边界 | 本机证据目录 |
| --- | --- | --- |
| 核心、桌面及 Android 静态检查 | 核心 239 项全部通过；桌面 145 项无失败、1 项真实播放器用例按选择跳过；Android 编译及仪器 APK 组装通过；core lint 0 问题，应用 lint 0 错误、12 警告 | `bundled-sqlite-release-20261004-01/tests/` |
| Windows 全屏失焦专项 | 显式启用真实 libmpv，145 项桌面测试全部通过、无跳过；焦点转移后播放进度继续、未最小化，退出全屏恢复原窗口状态与矩形 | `fullscreen-focus-02/` |
| Android 仪器测试 | 独立 API 36 x86_64 AVD：30 项实际通过、1 项缺少外部字幕素材而跳过；包含 7 项新数据库测试，覆盖 UPSERT、绑定类型、回滚、并发隔离、平台旧库/WAL 迁移与关闭行为 | `bundled-sqlite-release-20261004-02/android-release/` |
| Android R8 release 运行 | 使用临时 QA 证书签名的副本通过首次启动、无效存储配置拒绝、失败后的密码草稿保留、FFmpeg 可用检查；未使用正式密钥，未发布 QA 包 | 同上 |
| Android release 包内容 | APK 8,720,636 字节；项目及 SQLite 许可逐字节匹配；四种 ABI 的 16 个 FFmpeg 库和 4 个 SQLite 库齐全。SHA-256：`4B1B3D2A0AC4C767235B0E58E42558430114726EAD75A2288FC4B76C67215B43` | `bundled-sqlite-release-20261004-02/release-artifacts/` |
| 固定 libmpv 下载及替换 | 4 项检查通过：损坏归档拒绝、解出 DLL 摘要不符拒绝、失败不覆盖原 DLL、成功及重复安装保持正确 DLL 和来源记录 | `fetch-mpv-be8580ca8a2e4b10b81128a114a3b35b/` |
| Windows 分发目录 | 普通/ProGuard 两套目录均出现可见 AWT 窗口，初始化隔离数据库和日志；包内 DLL 摘要、构建来源 JSON、项目及 mpv 许可文件均匹配；Gradle 退出码 0，验证期间源文件哈希未变 | `pinned-mpv-release-20261004-01/` |
| CI 与映射归档 | CI YAML 和四平台映射/发布路径通过静态检查；用真实 R8 映射与 APK 验证归档 ZIP、映射哈希和签名前产物标记。远端 CI 尚未执行 | `mapping-archive-check/` |

固定 libmpv 是上游 `20260903` 构建，归档摘要已与发布元数据核对；解出的 DLL 与此前全屏失焦验收使用的 DLL 完全一致，SHA-256 为 `673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4`。mpv 与构建脚本提交分别为 `69e63f425a531f814431fba12750bdb3721357f2`、`cd1edc11dc6887a50f705717619d879f5a93a488`。SQLite AAR 四种 ABI 的 ELF 加载段均已核对为 16 KB 对齐（`sqlite-api/native-alignment.json`）。

验证器修正也有实际失败证据：窗口检测改用所属进程树的可见 AWT 窗口；Logback 活跃日志使用共享读取；Android 设备名按行 trim 处理 CR-CR-LF，并核对唯一 AVD 身份后才允许关闭；可选字幕测试以 assumption 正确报告跳过；AAR 顶层许可未自动进入 APK，已改为显式 assets 并复验。没有把检测脚本失败当作应用失败，也没有把缺少结果或测试 APK 组装当作运行通过。

`verify-production.ps1` 检查源码输入是否在测试期间变化；`verify-release-artifacts.ps1` 检查包内容和隔离启动；`verify-android-runtime.ps1 -ReleaseUiSmoke` 运行独立 AVD 与 release 黑盒检查。后台任务将结果写入 JSON。本环境的 Windows CLI 没有 `queue`，daemon 仅支持 Unix，现有 App Server 是不可接管的 stdio，无法配置完成后回调当前会话；未设置定时轮询。本轮没有推送或发布。

## 尚未满足的验收项

- Android 真机：目前 `adb devices -l` 没有连接设备。上述模拟器基础测试已完成；后台/前台切换、锁屏、画中画、配置重建、通知拒绝和前台服务超时仍需运行验收。
- 桌面真实播放器：源码构建的真实 libmpv 全屏失焦测试已通过；这不能证明安装包、HDR、全部显示驱动及播放控制浮层都通过。上述分发目录启动检查已完成，MSI 安装及升级仍需验收。
- Linux/macOS 安装包和两端从旧版升级：本轮尚无运行证据。
- 离线旧数据：已发生的文件碰撞污染、远端同大小内容替换，无法仅凭长度自动发现。新路径分配和续传检查不能证明旧文件正确。
- 同步并发：不支持条件写入的 WebDAV 仍无法保证两个设备同时 PUT 时不互相覆盖。后续拉取补传缺少/较旧行的回归测试已通过；同时间戳时保留本机值的既有冲突规则不变，也没有消除设备时钟偏差。
- 发布配置：用户授权的原有压缩/混淆配置已经过上述本机验证；CI 已切换到桌面 `packageRelease*`，各平台归档映射及来源/产物哈希。远端 CI、Linux/macOS 打包及运行仍无本轮证据，不能用 Windows 目录启动替代其验收。
- Android 8 兼容：API 26 镜像下载命令被自动审批拦截（只返回 `blocked by policy`）；本机当前只有 API 36/36.1 镜像，最低版本设备的实际运行验收仍缺。验证器支持指定 API 和独立镜像目录，不将 API 36 结果当作 Android 8 实测。
- 项目许可证：用户授权选择开源许可证后，DAView 自有代码已采用 `GPL-3.0-or-later`，根目录 `LICENSE` 为 GNU 官方完整文本。第三方声明继续适用。Windows 内置 libmpv 及其依赖的对应源码、构建信息和实际安装包中的许可材料仍需逐项验证；选定项目许可证并不自动关闭这些发布验收项。
- libmpv 归档长期保留：上游工作流会清理旧 GitHub release，CI 缓存也不是永久镜像；当前已固定的归档仍可获取，但后续失效会明确阻止构建。未擅自向项目 Release 上传副本。`mpv-dev` 归档没有依赖源码/完整通知，构建脚本也引用浮动分支；已经补入 mpv 自身 Copyright/GPL 文本及准确来源记录，这些仍不足以证明全部对应源码材料已齐全。

以上缺口关闭前，不能据单元测试通过宣称应用已达到生产级别。
