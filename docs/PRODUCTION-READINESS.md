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
| 离线文件 | 特殊字符清理或大小写导致不同下载共用文件；续传全量响应被拼接 | 为成品和部分文件分配独立路径，保留旧登记路径；校验续传响应及大小 | `OfflineLibraryTest` |
| 播放字节管道 | 后缀 Range 被当作文件头，越界返回错误字节，忽略范围时内容长度不符 | 按完整大小解释单区间，裁剪结尾并限制输出；无效区间返回 416 | `PlaybackPipeRangeTest` |
| 扫描取消 | 取消异常只结束一个探测线程，其他探测继续写库，最终仍显示完成 | 有界工作线程只读取，协调线程检查取消并写库，丢弃迟到结果 | 探测取消与扫描取消回归测试 |
| 元数据响应 | 服务商返回 HTML 被缓存一周；错误日志包含 TMDB API Key | 只缓存可解析 JSON，绕过旧坏缓存，限制响应大小，日志隐藏 URL 参数 | `HttpScraperTest` |
| 界面异步状态 | 旧详情请求覆盖新详情；旧分页混入新筛选 | 请求代次和查询快照校验，取消过期请求，分页与字母跳转共用追加锁 | 桌面状态层竞态回归测试 |
| 播放生命周期 | 外部播放入口未清理旧会话；退出时切集请求仍写回；清理依赖已取消 UI scope | 统一请求生命周期，分离资源清理与 UI 生存期 | 桌面播放控制回归测试 |
| 原生与平台状态 | mpv 销毁与其他原生调用并发；Android 生命周期捕获旧 PiP 状态 | 原生调用持有读锁、销毁独占；Android 到 ON_STOP 时暂停 | `MpvPlayerLifecycleTest`；Android 编译 |
| 最低 Android 版本 | minSdk 26 的代码调用 API 33 的读取/URL 方法、API 34 的 Path.of | 用 API 26 可用的有界读取、编码名称重载和 Paths.get | `StreamsTest`、Android lint；[InputStream API](https://developer.android.com/sdk/api_diff/33/changes/java.io.InputStream)、[Path API](https://developer.android.com/reference/java/nio/file/Path) |
| Android 备份 | 旧版备份只排除 config.json，仍收集含凭据的临时/恢复副本 | Android 11 及更早只备份数据库及其 WAL/SHM、界面首选项 | 备份规则检查、Android lint；未做系统备份/恢复实测 |
| Android lint | 进度通知缺权限处理、Media3 opt-in 缺失、无用布局与源码不可见 BOM | 修正权限处理和 opt-in，替换无用布局，使用显式 Unicode 转义 | `:composeApp:lintDebug`，0 错误 |
| Windows 全屏失焦 | AWT 独占全屏在切到其他窗口时最小化 | 当前显示器上的无边框全屏；保留主窗口和视频 HWND，退出时恢复 WINDOWPLACEMENT；关闭时保存进入全屏前的窗口设置 | `WindowsFullscreenTest`，含真实 libmpv 测试图案和焦点转移 |

## 验证状态

- 最后验证在 2026-10-03 22:19（Asia/Singapore）完成，Gradle 退出码为 0，验证期间输入文件哈希保持一致。
- `:core:jvmTest`：223 项，0 失败、0 错误、0 跳过；`:composeApp:desktopTest`：142 项，0 失败、0 错误、0 跳过，包含状态层竞态及 mpv 原生接口替身测试。
- `:composeApp:compileDebugKotlinAndroid`、`:composeApp:assembleDebugAndroidTest` 成功。**组装仪器测试 APK 不等于运行真机测试。**
- `:core:lintDebug`：0 问题；`:composeApp:lintDebug`：0 错误、12 警告。警告涉及 PiP 过渡建议、冗余 SDK 判断/资源目录、Modifier 参数顺序和 KTX 建议；未通过屏蔽规则或基线文件隐藏错误。
- 验证由 `scripts/verify-production.ps1` 启动的一个 Gradle 进程执行。可复核本机 `build/audit/production-20261003-final/` 下的 `gradle.log`、`inputs.sha256` 和 `result.json`。脚本只有在 Gradle 成功且验证期间源文件未变化时才标记 `verified=true`。
- 本轮开始时没有可连接的后台回调通道：当前 Windows CLI 没有 `queue`，其 daemon 管理只支持 Unix，桌面 App Server 使用现有进程的 stdio。后台验证不会创建定时轮询或尝试接管该进程。
- Android 运行验收入口为 `scripts/verify-android-runtime.ps1`：用本机已有 Android 36 x86_64 系统镜像建立独立 AVD，不读取或复制现有模拟器的用户数据。脚本校验 APK 中许可证文本、运行仪器测试、启动应用、保存首页截图/UI 树和 logcat，结束后关闭该测试设备。结果待本轮运行后核验。
- 可选的 `e07.ass` 真实字幕参考用例在缺少文件时改为 JUnit assumption 跳过，避免空执行被计为通过。
- 全屏专项验证在 2026-10-03 22:47（Asia/Singapore）完成：桌面 145 项测试，0 失败、0 错误、0 跳过。新增 3 项 Windows 测试验证句柄不重建、恢复原窗口、释放已销毁窗口；显式启用的真实 libmpv 用例让另一个窗口取得焦点，并检查播放进度继续前进、窗口未最小化、退出全屏后原最大化状态及普通窗口矩形恢复。运行产物在本机 `build/audit/fullscreen-focus-02/`。首次运行未配置测试 DLL 路径，实际播放用例未运行；以上数字对应修正路径后的成功运行。

## 尚未满足的验收项

- Android 真机：目前 `adb devices -l` 没有连接设备。模拟器测试另行执行；后台/前台切换、锁屏、画中画、配置重建、通知拒绝和前台服务超时仍需运行验收。
- 桌面真实播放器：原生接口替身测试不能证明 libmpv、显示驱动、HDR、全屏浮层在安装包内的行为。需要实际安装包和播放源验收。
- Linux/macOS 安装包和两端从旧版升级：本轮尚无运行证据。
- 离线旧数据：已发生的文件碰撞污染、远端同大小内容替换，无法仅凭长度自动发现。新路径分配和续传检查不能证明旧文件正确。
- 同步并发：上传前合并缩小了丢数据窗口；不支持条件写入的 WebDAV 仍无法保证两个设备同时 PUT 时不互相覆盖。
- 发布配置：开始本轮前工作区已有 `composeApp/build.gradle.kts` 和两份 ProGuard 规则的未提交改动；它们独立于本轮审查修复，发布前仍需验证 R8/ProGuard 后的安装包及映射文件归档。
- 项目许可证：用户授权选择开源许可证后，DAView 自有代码已采用 `GPL-3.0-or-later`，根目录 `LICENSE` 为 GNU 官方完整文本。第三方声明继续适用。Windows 内置 libmpv 及其依赖的对应源码、构建信息和实际安装包中的许可材料仍需逐项验证；选定项目许可证并不自动关闭这些发布验收项。

以上缺口关闭前，不能据单元测试通过宣称应用已达到生产级别。
