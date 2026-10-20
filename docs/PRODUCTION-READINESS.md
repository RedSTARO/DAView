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

## 2026-10-20 追加验证（基线 `0f8e138`）

| 范围 | 结果与边界 |
| --- | --- |
| 远端 CI | `6d3156b` 之后的 4 次 master 运行（至 `0f8e138`，run 39104180757）全部成功，包括桌面 `packageRelease*`、APK 签名和 `ci` 预发布；四个平台的映射 ZIP 都已作为构建产物和预发布附件发布 |
| 安装包体积 | 与开启压缩混淆前的 `6533738` 相比：APK 24.4 → 8.3 MB，MSI 167 → 107 MB，DMG 131 → 72 MB，DEB 112 → 57 MB；APK 签名证书 SHA-256 不变（`4a917098…1d376`），v2/v3 签名有效 |
| Linux 运行 | WSL Debian 13 + WSLg：解包运行 CI 的 `.deb`，隔离数据目录下建库、加锁、写日志，首次使用界面正常（OpenGL 不可用时回退软件渲染）。`dpkg -i` 安装 1.0.126 → 升级 1.0.127 → `--purge`：升级后没有旧版文件残留，卸载删除 `/opt/daview` 与菜单项，用户数据保留。1.0.126 启动即报 Okio 的 `VerifyError`，1.0.127 日志无错误，与 `0f8e138` 的修复一致 |
| Linux 依赖与安装脚本 | jpackage 在 Ubuntu 24.04 上把依赖写成 `libasound2t64`、`libpng16-16t64`；在 debootstrap 建的干净 Debian 12.15 根目录里，原包 `apt-get install` 直接报这两个依赖「not installable」。另一个问题：jpackage 的 `postinst` 在 `set -e` 下调用 `xdg-desktop-menu install`，没有桌面菜单目录（无桌面环境、WSL、容器）时失败，包停在半配置状态，且 `prerm` 的 `uninstall` 同样失败，连卸载都卸不掉。`scripts/repack-deb.sh` 把 `…t64` 依赖改成「`新名 \| 旧名`」、让这两处菜单调用失败时只警告、并改用 xz 压缩。重打包后在同一个干净的 Debian 12 里：apt 自动解析依赖并安装成功，`remove` 与 `purge` 成功，`/opt/daview` 被删除；其余 223 个条目的内容、权限、属主不变，体积 60.2 → 56.6 MB。有菜单目录的真实桌面系统上菜单注册走原路径，未实测 |
| Android 生命周期 | API 36 模拟器、`0f8e138` 的 debug 包：把 dataSync 前台服务时限临时调到 60 秒，应用转入后台后系统报 `FGS (dataSync) timed out`，服务按 `onTimeout` 停止、通知移除、进程未崩溃，扫描在后台继续完成；播放中按 Home 进入画中画（`mode=pinned`），字幕正常；熄屏后播放转为暂停、解锁不自动续播；横竖屏切换不重建 Activity、播放不中断；拒绝通知权限后先给出用途说明，扫描的前台服务照常运行；后台进程被杀后冷启动回到同一详情页，播放进度已保存 |
| Windows 安装（Sandbox） | 在 Windows Sandbox 里安装 1.0.113 与 1.0.127 的 MSI 都以 1603 失败：jpackage 的自定义动作 `JpFindRelatedProducts`（`JpCaDll`，只依赖系统 DLL）被代码完整性策略拦截，事件 3077/3033 记录 `msiexec` 加载的未签名 DLL 不满足签名级别要求，策略 ID 以 `{0283ac0f-fff1-49ae-ada1-8a` 开头（按此 ID 判断是智能应用控制，事件中没有直接读到策略名称）；直接 `LoadLibrary` 返回 4551。本机智能应用控制关闭（`VerifiedAndReputablePolicyState=0`），所以本机能装。MSI、`JpCaDll` 与 `DAView.exe` 均未签名（`Get-AuthenticodeSignature`：NotSigned）。推断：强制执行同类策略的 Windows 上安装会同样失败；`DAView.exe` 在该策略下能否运行未实测。MSI 的安装/升级流程因此没能在 Sandbox 里验收 |

## 2026-10-21 第二轮审查与修复（基线 `e1f28ba`）

四路独立审查（首页缓存、启动同步、界面交互、打包与 CI）提出的问题，逐条对照源码核实后修复。下表的「证据」只指单元/集成测试或构建检查；界面改动只经过编译和状态层测试，没有在运行中的应用里逐项点验。修复后本机全量结果：核心 265 项、桌面 167 项通过（1 项真实播放器用例按选择跳过），Android 编译通过，`verifyReleaseBytecode` 通过。

| 范围 | 触发条件与原行为 | 处理 | 证据 |
| --- | --- | --- | --- |
| 桌面 release 枚举（严重） | ProGuard 把枚举的 `values()` 改名（映射里 `values() -> a`），`Enum.valueOf` 通过反射找不到它而抛错；`Repository` 的 6 处 `valueOf` 被 `runCatching` 吞掉，媒体库都读成 OTHER、条目都读成 MOVIE、下载都读成 FAILED。0f8e138 起网络正常后，默认开启的启动扫描会把剧集按电影目录处理、删掉剧集与季的行（按代码路径推断，未在装机上运行）。1.0.126 与 ci 渠道当前的 0f8e138 桌面包都带这个问题；Android 用 AGP 默认规则，不受影响 | `proguard-desktop.pro` 保留所有枚举的 `values()`/`valueOf(String)` | 本机 `verifyReleaseBytecode`：带规则通过；去掉规则时验证器报 102 个枚举失败 |
| 发布前检查 | 只核对约 70 个 Okio 类，上面的问题能通过 | 验证器加载并校验全部 851 个应用类、对 226 个枚举走一遍 `Enum.valueOf`、经发布包里的驱动打开 SQLite | 同上 |
| 启动缓存命中 | `launch` 内的裸 `run {}` 解析成标准库函数：读服务器信息改为同步执行，失败时异常结束启动、不刷新首页 | 显式调用 `this@AppState.run` | 新测试；还原修复后该测试失败 |
| 启动同步与播放 | 启动拉取完成前开始播放，用合并前的旧进度续播，之后的进度上报以当前时间写回，覆盖其他设备的进度或「已看完」 | 开始播放先等启动拉取（有 10 秒上限，已完成或未开同步时立即返回）；「每次询问」改为拉取后重读存储的进度再询问，「继续」不再传界面上的旧进度 | `SyncStartupTest`、`PlaybackControllerRaceTest` 新用例；还原修复后失败 |
| 同步时间戳 | 「从继续观看移除」改写行的 `updated_at`，让本机的旧进度在同步中压过其他设备更新的进度 | 隐藏标记不再动时间戳和同步序号 | `WatchStateWriteTest` |
| 继续观看 | 注释与提交说明里「再次播放会回到继续观看」从未实现 | 播放进度写入时清除隐藏标记 | `WatchStateWriteTest` |
| 观看次数 | `play_count` 在事务外读取，期间合并进来的行会被旧计数覆盖 | 在同一条 UPSERT 里按被替换的行计算 | `WatchStateWriteTest` |
| 启动后的刷新 | 拉取没有合并任何行也刷新所有页面；Android 重建窗口时重放旧的启动结果（重复提示失败或无谓刷新）；同步刷新顶替了未完成的筛选查询后列表不回顶 | 结果带合并计数（新增 `pins`），无变化不刷新；早于本窗口创建的结果不再处理；回顶请求保留到下一次成功加载 | `AppStateStartupSyncTest` 2 个新用例；还原修复后失败 |
| 网络超时 | DNS 解析无法被调用超时打断；启动以外的拉取与上传没有总时限，卡住时一直占着「同步进行中」 | DNS 查询 10 秒上限；拉取 60 秒、上传 180 秒总时限 | `BoundedDnsTest` |
| 列表分页 | 各排序都没有唯一的末位键，翻页可重复或漏掉条目；搜索「加载更多」不去重，重复的 key 会让网格崩溃 | 所有分页查询以 `i.id` 收尾；三处多页加载去重 | 全量测试 |
| 演职人员 | 同名同角色出现两次时 LazyRow key 重复导致详情页崩溃 | 按位置作 key | 编译 |
| 首页缓存临时文件 | 写入中途退出留下的随机名临时文件永不清理 | 启动读取时删除一分钟以前的残留 | `HomeSnapshotCacheTest` |
| 下载 | 取消下载会删掉已下载的部分，没有确认；失败条目的「删除」同样 | 已收到数据时先确认并说明大小；排队中未开始的直接取消 | 编译 |
| 手动指定 | 切换来源时旧搜索的结果会覆盖新列表，「应用」用的是当前选中的来源而非结果自身的来源；连不上或密钥无效显示成「没有结果」 | 新搜索取消旧搜索；应用时用结果自带的来源；搜索为空且请求失败时报出原因（如「HTTP 401：密钥无效或没有权限」） | `HttpScraperTest` |
| 其他交互 | 「查看全部」页移除后条目不消失；恢复刮削结果、解除手动指定无确认；Ctrl+F 在对话框打开时切页；多选合并确认框不拦 Esc；拆分后详情页不刷新；列表视图多选时的长按在结束多选后弹出菜单；Tab 到海报的播放按钮后焦点丢失；Android 搜索键不收起键盘；清空年份被忽略；媒体库都为空时首页空白；扫描进行中「保存并重新获取」被丢弃 | 逐项修正；空首页显示原因与「扫描全部媒体库」；扫描中请求的重新刮削排在当前扫描之后（取消扫描时一并取消） | `ScanServiceOrderTest`、`WatchStateWriteTest` 新用例，其余为编译 |
| CI 发布 | 重跑旧的运行会把 ci 渠道退回旧提交并删掉新包；重跑同一运行用不同字节覆盖同名包；清理会删掉上一份清单仍指向的包（已读到它的客户端下载 404）；映射包随下一次运行删除，日志里没有版本号 | 发布前确认是最新提交且清单不是同一版本；保留上一份清单的包；映射保留最近 40 个提交；桌面日志在启动时记录版本 | 静态检查；本机无 YAML 解析器，未做解析校验，需看下一次远端运行 |

## 尚未满足的验收项

- 已安装 0f8e138 或更早混淆桌面包、并在其中扫描过的设备：剧集库可能已被扫成电影条目。装上修复后的版本后需要对剧集与番剧库重新扫描并重新刮削；观看进度按路径生成的 id 记录，扫描后会重新对上。

- Android 真机：目前 `adb devices -l` 没有连接设备。后台/前台切换、锁屏、画中画、配置重建、通知拒绝和前台服务超时已在 API 36 模拟器上验收（见上表），真机与厂商系统上的行为仍未验证。
- 桌面真实播放器：源码构建的真实 libmpv 全屏失焦测试已通过；这不能证明安装包、HDR、全部显示驱动及播放控制浮层都通过。上述分发目录启动检查已完成，MSI 安装及升级仍需验收。
- 已发出的 1.0.126 桌面版不能自动更新：本机安装的 1.0.126 用临时数据目录启动，日志立即记录 Okio 混淆类的 `VerifyError`；启动后 6–18 秒每 2 秒截图都没有出现「有新版本」提示（清单里已是 1.0.127），进程也没有留下任何网络连接。更新检查与下载都经过 OkHttp/Okio，所以装了 1.0.126 的桌面设备只能手动安装新版；Android 用 R8，不受这个 ProGuard 问题影响。本机注册表中 2026-10-18 安装的就是 1.0.126。
- Windows 代码签名：MSI、其中的自定义动作 DLL 和 `DAView.exe` 都没有 Authenticode 签名。强制执行代码完整性策略（如智能应用控制）的 Windows 会拦下安装（Sandbox 中实测 1603/1723），能否运行未实测；从浏览器下载的安装包首次打开时还会出现 SmartScreen 的未知发布者提示（应用内更新下载的文件不带网络来源标记，不触发这一提示）。解决需要代码签名证书（如 OV/EV 证书或 Azure Trusted Signing），是否购买、用谁的名义签名由所有者决定。
- MSI 验证环境：2026-10-04 检查未找到 WiX 的 candle/light，本机有 JDK 25 和 Android Studio JBR 21。准备在工作区下载便携 WiX、构建 MSI 并只解包检查（不安装）的命令被自动审批拒绝，只返回 `blocked by policy`，命令未执行；没有把该计划记为 MSI 构建通过。
- Linux/macOS 安装包和两端从旧版升级：Linux 已在 WSL Debian 13 上验证运行与 dpkg 升级、卸载（见上表）；Debian 12 / Ubuntu 22.04 实机安装、macOS 的 DMG 仍无运行证据。
- Linux 环境检查：运行打包后的 `.deb` 不需要系统 Java（安装包自带运行时），已在 WSL Debian 13 中完成；macOS 测试主机尚未提供。
- 离线旧数据：已发生的文件碰撞污染、远端同大小内容替换，无法仅凭长度自动发现。新路径分配和续传检查不能证明旧文件正确。
- 同步并发：不支持条件写入的 WebDAV 仍无法保证两个设备同时 PUT 时不互相覆盖。后续拉取补传缺少/较旧行的回归测试已通过；同时间戳时保留本机值的既有冲突规则不变，也没有消除设备时钟偏差。
- 发布配置：远端 CI 已按 `packageRelease*` 成功打出四个平台的包并归档映射（见上表）；macOS 包的运行仍无证据。
- Android 8 兼容：API 26 镜像下载命令被自动审批拦截（只返回 `blocked by policy`）；本机当前只有 API 36/36.1 镜像，最低版本设备的实际运行验收仍缺。验证器支持指定 API 和独立镜像目录，不将 API 36 结果当作 Android 8 实测。
- 项目许可证：用户授权选择开源许可证后，DAView 自有代码已采用 `GPL-3.0-or-later`，根目录 `LICENSE` 为 GNU 官方完整文本。第三方声明继续适用。Windows 内置 libmpv 及其依赖的对应源码、构建信息和实际安装包中的许可材料仍需逐项验证；选定项目许可证并不自动关闭这些发布验收项。
- libmpv 归档长期保留：上游工作流会清理旧 GitHub release，CI 缓存也不是永久镜像；当前已固定的归档仍可获取，但后续失效会明确阻止构建。未擅自向项目 Release 上传副本。`mpv-dev` 归档没有依赖源码/完整通知，构建脚本也引用浮动分支；已经补入 mpv 自身 Copyright/GPL 文本及准确来源记录，这些仍不足以证明全部对应源码材料已齐全。

以上缺口关闭前，不能据单元测试通过宣称应用已达到生产级别。
