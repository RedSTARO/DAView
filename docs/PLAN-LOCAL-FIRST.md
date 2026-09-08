# 去内嵌服务端：各端独立工作，WebDAV 只负责对齐

写于 2026-09-08，决策已定（见第 0 节）。
文中每条「现状」都核对过源码或构建产物；推断的地方会写明是推断。

## 0. 已定的决策

| # | 决策 | 选择 | 后果 |
| --- | --- | --- | --- |
| A | 库 id 迁移 | **清库重建** | 不写迁移代码。库 id 改成按路径推导后，现有库要手动删除重建，现有观看进度与手动指定随之丢失 |
| B | 桌面外置播放器的字节管道 | **保留最小管道** | `127.0.0.1` + 临时端口 + 一条路由，无鉴权 / CORS / JSON。cue+clock 进度推算与 `PlaybackServiceTest` 保住 |
| B' | Android 外置播放器 | **按需起临时管道** | 与桌面共用同一份管道代码，只在外放会话期间监听，会话结束即关闭 |
| C | 网页端 | **砍掉 wasm target** | 连带删除 `DaViewClient`、`ConnectScreen`、令牌鉴权、CORS、`/api/font/cjk`、`coil-network-ktor` |
| D | `:server` / `:server-app` | **全部删掉** | 只剩一个后端实现，因此**不做 `MediaBackend` 接口**，`AppState` 直接持有 `MediaFacade` |
| E | 开工范围 | **整个方案做完** | 六个阶段一次做到底 |
| F | 移动端扫描 | **这轮就做前台服务** | `ForegroundService` + 通知（进度与取消）+ 相应权限 |
| G | 验收 | **自动验 + 手工验** | 自动部分见第 8 节，手工部分见第 9 节 |

决策 D 让阶段顺序倒过来了：**先建替代品，最后再删旧的**，这样每个中间提交都能编译、能跑。

## 1. 目标结构

```
:shared      只剩 DTO（Models.kt），jvm + android 两个 target
:core        WebDAV · 扫描 · 命名解析 · 刮削 · 容器探测 · SQLite · 同步
             · MediaFacade · PlaybackPipe
:composeApp  desktop / android，直接调 :core，没有 HTTP、没有令牌、没有连接页
```

删除：`:server`、`:server-app`、`DaViewClient`、`ConnectScreen`、wasm target。

唯一剩下的监听端口是 `PlaybackPipe`：`127.0.0.1` + 临时端口，只在外置播放器会话期间存在。

## 2. 现状核对

| 事实 | 出处 |
| --- | --- |
| APK 28 MB，且 release 也不做 minify | `composeApp/build/outputs/apk/debug/composeApp-debug.apk`；`isMinifyEnabled = false` |
| APK 里同时装着两个 HTTP 客户端引擎和一个服务端引擎 | dex 中检出 `io/ktor/client/engine/cio`、`io/ktor/client/engine/okhttp`、`io/ktor/server/cio`，以及 server 的 `cors` / `compression` / `calllogging` |
| core 根本不用 Ktor client | `server/src/main/kotlin` 里 `io.ktor.client` 零引用；WebDAV 与三个刮削源都走 OkHttp |
| 服务端在第一帧 UI 之前启动 | `DaViewApplication.onCreate` → `EmbeddedServer.start`，同步调用 |
| 手机监听全网段 | `AppConfig.host = "0.0.0.0"`，Android 侧没有 env 覆盖 |
| 海报绕自己一圈 | `ImageCache.Entry.file` 已经是本地路径，Coil 仍然走 `/api/images/...` |
| 播放字节绕自己一圈 | `PlaybackInfoDto.directUrl` 已算好 CDN 直链，`InternalPlayer.android.kt` 用的却是 `streamUrl`（并因此需要 `setAllowCrossProtocolRedirects` 才能播） |
| 库 id 是随机的 | `Routing.kt` 的 `post("/api/libraries")`：`UUID.randomUUID()` |
| 同步上传是整文件覆盖 | `SyncService.upload()` → `dav.put(path, bytes)`，上传前不读取合并 |
| 从不自动拉取 | `SyncService.tick()` 只 upload |

## 阶段 0 — 同步的正确性【先做】

在现有架构下做，改动最小。这一阶段与重构无关，是「各设备独立」这个用法本身的前提：
不做完就切过去用，会丢观看记录。

### 0.1 库 id 确定化

`UUID.randomUUID().take(12)` → `SHA-1(规范化 path)` 取前 12 位
（规范化 = 去首尾斜杠 + 统一大小写）。两台设备指同一个目录必然得到同一个 id。

按决策 A **不写迁移**。现有库的 id 仍是随机值，不会自动变；要让两台设备对齐，
用户需要把库删掉重新添加、重新扫描。这条要写进 README 的升级说明。

### 0.2 upload 改成 pull-merge-put

`upload()` 现在把本机全量状态 `put` 上去覆盖整个文件，60 秒一次的 `tick()` 走同一条路，
第二台设备的自动上传会静默抹掉第一台的记录（README 写的「按行取新」只发生在 `pull()` 里）。

改成：先 `readIfPresent` → 解析 → 用与 `pull()` 相同的 `mergeUserDataByTimestamp`
合进本地 → 再序列化上传。

网关不支持条件写（没有 `If-Match`），做不到真正的乐观并发；这一步把冲突窗口从
「整个上传间隔」缩到「一次读和一次写之间」。要更强就得改成 per-row 追加日志，现阶段不做。

### 0.3 自动拉取

`tick()` 里加拉取（间隔可比上传长），外加应用回到前台时拉一次
（Android 的 `ON_START`，桌面的窗口 focus）。

### 0.4 同步文件带上手动刮削指定

`items.locked_provider` 存在 items 里，而 items 不进同步文件——独立刮削会让
「桌面纠正过、手机重刮又错」从偶发变成必然。

同步文件加一段 `pins`：`{itemId, provider, providerId}`，每条约 80 字节，
3000 条约 240 KB，仍远小于全量 items 的 4 MB。刮削前先查 pin。

## 阶段 1 — 拆出 `:core`

纯搬运，不改行为。`:server` 暂时保留并依赖 `:core`，此时行为与现在完全一致。

`:core`（jvm + android target）收下，包名保持不变以减少 diff：

```
storage/WebDavClient.kt
library/{Scanner,NameParser,MkvProbe,Mp4Probe}.kt
scraper/{Scrapers,MetadataService}.kt
db/{Database,Repository,Sql}.kt + androidMain/db/AndroidSql.kt + jvmOnly/db/JdbcSql.kt
media/{ImageCache,PlaybackService,ScanService,StreamService}.kt
sync/SyncService.kt
config/AppConfig.kt
ServerContext.kt → CoreContext（去掉 HTTP 相关字段）
api/Backup.kt 的 buildBackup / applyBackup
```

测试全部跟着源码进 `:core`：`NameParserTest`、`MetadataServiceTest`、`IdentifyTest`、
`MergeTest`、`PlaybackServiceTest`——没有一个测 HTTP。CI 的 `:server:test` 改成 `:core:jvmTest`。

`media/SystemFonts.kt` 只服务 `/api/font/cjk`，随阶段 5 一起删。

## 阶段 2 — `MediaFacade`

把逻辑从 route handler 提到 `:core`，`Routing.kt` 变成薄适配器。行为不变，靠现有测试保护。

要搬的是这几处真逻辑：

| handler | 逻辑 |
| --- | --- |
| `POST /api/playback/start` | 音轨 / 字幕默认选择、按需探测、直链解析、URL 拼装 |
| `GET /api/items/{id}` | 未探测过的可播放条目做一次容器探测 |
| `POST /api/items/{id}/played` | 剧集 / 季展开成分集逐条写 |
| `POST /api/items/{id}/identify` 与两个 GET | 清上一次匹配的字段、分集标题回退再重写、钉住 provider |
| `/api/backup/*` | 流式导出、导入合并 |
| `POST /api/libraries` | 库 id 生成（阶段 0.1 之后是确定性的）、默认名、默认 provider 顺序 |

## 阶段 3 — 客户端直连 `MediaFacade`

`AppState` 与 `PlaybackController` 改成持有 `MediaFacade`（决策 D：不做接口）。
桌面与 Android 从这一步起不再需要 HTTP。

### 资源改造

| 消费者 | 现在 | 之后 |
| --- | --- | --- |
| Coil 海报 / 背景 | `/api/images/{id}/{type}?token=` | `posterUrl` 填 `daview://image/<id>/<type>`，注册 Coil `Fetcher` 调 `facade.imageFile(...)` 返回 `ImageCache` 的文件。Fetcher 本身是 suspend，签名天然匹配。`coil-network-ktor` 可以整个删掉——所有图片都来自本地缓存，Coil 不再需要网络 |
| ExoPlayer 视频 | `streamUrl`（loopback 代理） | `directUrl()` 的 CDN 直链。顺带去掉为跨协议跳转加的 `setAllowCrossProtocolRedirects` |
| ExoPlayer 外挂字幕 | `/api/subtitle/{id}/{index}` | 预下载到 app cache，给 `file://` |
| 外置播放器 | 同 `streamUrl` | `PlaybackPipe` 的临时地址（阶段 4） |
| 备份导出 | `openUrl(backupUrl)` | 平台保存对话框（Android SAF `CreateDocument`，桌面 `FileDialog.SAVE`） |

## 阶段 4 — `PlaybackPipe`

桌面与 Android 共用的一条字节管道，取代 `/api/stream`：

- 绑 `127.0.0.1`，端口取 0 由系统分配
- 一条路由，只认自己生成的一次性 session token，无鉴权插件、无 CORS、无 JSON、无日志
- 随外放会话启动，会话结束或进程退出即关闭
- 内部仍然用现有的 `PlaybackService`：Range → cue/clock 推算，
  末尾 8 MB 视为索引读取，静置 3 秒才锚定——这些行为由 `PlaybackServiceTest` 保护，不改

内置播放器（Android ExoPlayer）不经过它，直接用 CDN 直链并主动 `reportProgress`。

## 阶段 5 — 删除

到这一步旧路径已经没有使用者：

- 模块：`:server`、`:server-app`
- 代码：`api/Routing.kt`、`server/Main.kt`、`DaViewClient`、`ConnectScreen`、
  `media/SystemFonts.kt`、`platformNeedsCjkFont` 与 web 字体加载、`PlatformInfo.isWeb` 分支
- target：`composeApp` 与 `shared` 的 `wasmJs`，以及 `wasmJsMain` 整个源集
- 依赖：`ktor-server-*`、`ktor-client-*`、`coil-network-ktor`
- 概念：访问令牌、`?token=`、CORS、`usesCleartextTraffic`（如果没有别的 http 需求）
- 文档：README 的架构图、快速开始、跨端同步三节要重写；CI 的 job 与产物要跟着改

## 阶段 6 — Android 扫描的前台服务

- `ForegroundService` + 通知，通知里带进度与「取消」
- manifest 加 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`
- 运行时申请通知权限（Android 13+），被拒时退化为「请保持应用在前台」的界面提示
- 容器探测在手机上默认更保守：`PROBE_BUDGET` 调小，或只在充电 + 不计费网络时跑

## 8. 自动验收（我负责）

- [ ] `./gradlew :core:jvmTest` 全绿
- [ ] 桌面端能启动、能浏览、能拉起外置播放器
- [ ] 重建 APK：dex 里 `io/ktor/server` 命中数为 0
- [ ] 重建 APK：dex 里 `io/ktor/client` 命中数为 0
- [ ] APK 体积对比（当前 28 MB debug）
- [ ] 冷启动路径上没有 socket 绑定（`Application.onCreate` 里不再有 `EmbeddedServer.start`）

## 9. 手工验收（需要真机与真 WebDAV）

1. 两台设备各自把同一个 WebDAV 目录添加为媒体库 → 两边的库 id 相同（设置页可显示，或看导出文件）
2. A 看一集到中途，等一个同步周期 → B 不做任何手动操作，首页「继续观看」出现该集且位置一致
3. B 看完另一集 → A 同样能看到，且 A 之前的记录**没有**被覆盖
4. A 上手动指定某条目的刮削 id → B 扫描后该条目不会被刮错
5. Android 内置播放器：能播、能切音轨字幕、退出后进度写回
6. Android 外置播放器（MX Player）：能播，退出后进度写回（走 `PlaybackPipe`）
7. 桌面 PotPlayer：能播、能续播（`/seek=`）、退出后进度写回
8. 手机上扫描时切到后台、锁屏 → 扫描继续，通知里有进度
9. 扫描期间与空闲期间，手机与桌面都没有常驻监听端口

## 10. 风险与回滚

- 阶段 0 会改同步文件的格式（新增 `pins`）：旧版本读到新文件时要能忽略未知字段
  （`DaViewJson` 已经 `ignoreUnknownKeys = true`，确认一下即可）
- 阶段 1 是纯搬运，不改行为，出问题回滚一个 commit
- 阶段 3–4 会动播放路径，是最容易出回归的地方：这两个阶段结束前不删 `:server`，
  桌面端可以在两条路径之间对比行为
- 阶段 5 是不可逆的删除，放在最后，且只在阶段 8 的自动验收全过之后做
