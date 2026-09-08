# 去内嵌服务端：各端独立工作，WebDAV 只负责对齐

写于 2026-09-08。文中每条「现状」都核对过源码或构建产物，标了出处；推断的地方会写明是推断。

## 0. 目标与不变量

**目标结构**

- 桌面端与 Android 端**不在进程内启动 HTTP 服务端**。core 仍然跑在应用进程里，
  但通过 Kotlin 调用进入，不经过 socket。
- 每台设备独立扫描、刮削、建库。
- 设备之间只通过 WebDAV 上的一个同步文件对齐：库定义 + 观看进度 + 手动刮削指定。

**不能被这次改造破坏的**

1. 一份 UI 代码跑三端，Compose 侧不为平台分叉。
2. 网页端仍然可用——它没有别的活法（浏览器发不出带凭据的跨域 `PROPFIND`）。
   改由独立的 `:server-app` 承担，桌面端不再兼任服务器（决策 C）。
3. 桌面外置播放器的进度推算，即 `PlaybackServiceTest` 覆盖的那套（决策 B）。
4. 现有 SQLite 数据与 `itemId = SHA-1(libraryId + path)` 规则。

## 1. 现状核对

| 事实 | 出处 |
| --- | --- |
| APK 28 MB，且 release 也不做 minify | `composeApp/build/outputs/apk/debug/composeApp-debug.apk`；`composeApp/build.gradle.kts` 的 `isMinifyEnabled = false` |
| APK 里同时装着两个 HTTP 客户端引擎和一个服务端引擎 | dex 中检出 `io/ktor/client/engine/cio`、`io/ktor/client/engine/okhttp`、`io/ktor/server/cio`，以及 server 的 `cors` / `compression` / `calllogging` |
| core 根本不用 Ktor client | `server/src/main/kotlin` 里 `io.ktor.client` 零引用；WebDAV 与三个刮削源都走 OkHttp |
| 服务端在第一帧 UI 之前启动 | `DaViewApplication.onCreate` → `EmbeddedServer.start`，同步调用 |
| 手机监听全网段 | `AppConfig.host = "0.0.0.0"`，Android 侧没有 env 覆盖 |
| 海报绕自己一圈 | `ImageCache.Entry.file` 已经是本地路径，Coil 仍然走 `/api/images/...` |
| 播放字节绕自己一圈 | `PlaybackInfoDto.directUrl` 已算好 CDN 直链，`InternalPlayer.android.kt` 用的却是 `streamUrl` |
| 库 id 是随机的 | `Routing.kt` 的 `post("/api/libraries")`：`UUID.randomUUID()` |
| 同步上传是整文件覆盖 | `SyncService.upload()` → `dav.put(path, bytes)`，上传前不读取合并 |
| 从不自动拉取 | `SyncService.tick()` 只 upload |

## 2. 目标模块结构

```
:shared      DTO + MediaBackend 接口 + HttpBackend（网页端用）
:core        WebDAV · 扫描 · 命名解析 · 刮削 · 容器探测 · SQLite · 同步 · MediaFacade
             ↑ 不依赖任何 ktor server
:server      Ktor 路由 + 鉴权 + 静态托管，依赖 :core        ← 只有 :server-app 用
:server-app  不变
:composeApp  android / desktop → :core（注入 LocalBackend）
             wasmJs            → :shared（注入 HttpBackend）
```

## 阶段 0 — 同步的正确性【必须最先做】

这一阶段和重构没有关系，它是「各设备独立」这个用法本身的前提。
现在就按这个模式用，会丢观看记录。工作量：中。

### 0.1 库 id 确定化

现状是 `UUID.randomUUID().take(12)`。两台设备各自把同一个 WebDAV 目录建成库，
会得到两个库 id → `SHA-1(libraryId + path)` 得到两套 item id → 进度永远对不上，
同步文件里还会出现两个内容相同的库。

改法：`libraryId = SHA-1(规范化 path)` 取前 12 位（规范化 = 去首尾斜杠 + 统一大小写）。
两台设备指同一个目录必然得到同一个 id。

迁移见**决策 A**。

### 0.2 upload 改成 pull-merge-put

现状：`upload()` 把本机全量状态 `put` 上去，覆盖整个文件；60 秒一次的 `tick()`
走的是同一条路。第二台设备的自动上传会静默抹掉第一台的记录。
README 里写的「按行取新」只发生在 `pull()` 里，上传路径上没有任何合并。

改法：`upload()` 先 `readIfPresent` → 解析 → 用与 `pull()` 相同的
`mergeUserDataByTimestamp` 合进本地 → 再序列化上传。

这个网关不支持条件写（没有 `If-Match`），所以做不到真正的乐观并发。
pull-merge-put 把冲突窗口从「整个上传间隔」缩到「一次读和一次写之间」，够用。
要更强就得把文件改成 per-row 的追加日志，现阶段不建议。

### 0.3 自动拉取

现状 `tick()` 只上传。手机看完一集，桌面端不手动点「从云端合并」就永远不知道。

改法：`tick()` 里加拉取（间隔可以比上传长），外加应用回到前台时拉一次
（Android 的 `ON_START`，桌面的窗口 focus）。

### 0.4 同步文件带上手动刮削指定

现状 `items.locked_provider` 存在 items 里，而 items 不进同步文件。
桌面上纠正过的匹配，手机重刮又会错回去——「各设备独立刮削」让这件事从偶发变成必然。

改法：同步文件加一段 `pins`：`{itemId, provider, providerId}`。
每条约 80 字节，3000 条也就 240 KB，仍然远小于全量 items 的 4 MB。刮削前先查 pin。

**阶段 0 验收**：两台设备各自建同一目录的库 → 库 id 相同；A 看一集、B 看另一集 →
不做任何手动操作，两边都能看到对方那集；A 手动指定过的条目，B 扫描后不会刮错。

## 阶段 1 — 止血【不改结构，立刻见效】

工作量：小。六条都是调用点或默认值。

1. Android 绑 `127.0.0.1`（`AppConfig` 加 `bindLoopbackOnly`，或由 `EmbeddedServer` 传 host）。
2. `EmbeddedServer.start` 移出 `Application.onCreate`，改成首次真正需要时启动。
3. `InternalPlayer.android.kt` 改用 `info.directUrl ?: info.streamUrl`——
   内置播放器本来就主动 `reportProgress`，不需要服务端从 Range 反推位置。
4. Android 上 Coil 直读 `ImageCache` 的文件（完整做法见阶段 4 的资源表，这一步可以先只做 Android）。
5. 内嵌 profile 不装 CORS / Compression / CallLogging——loopback 上它们没有意义。
6. 删掉 `server/build.gradle.kts` 里三个零引用的 `ktor.client.*` 依赖
   （`client.core` / `client.cio` / `client.content.negotiation`，第 87–89 行）。

**验收**：重建 APK，dex 里 `io/ktor/client/engine/cio` 消失。

## 阶段 2 — 拆 `:core` / `:server`

工作量：中，但是纯搬运，不改行为。

只有拆模块，Ktor server 才会真的离开 APK；仅仅「不调用 `startServer()`」没有用。

`:core`（新建，jvm + android target）收下这些文件，包名可以保持不变以减少 diff：

```
storage/WebDavClient.kt
library/{Scanner,NameParser,MkvProbe,Mp4Probe}.kt
scraper/{Scrapers,MetadataService}.kt
db/{Database,Repository,Sql}.kt + androidMain/db/AndroidSql.kt + jvmOnly/db/JdbcSql.kt
media/{ImageCache,PlaybackService,ScanService,StreamService,SystemFonts}.kt
sync/SyncService.kt
config/AppConfig.kt
ServerContext.kt（改名 CoreContext，去掉 HTTP 相关字段）
api/Backup.kt 里的 buildBackup / applyBackup（HTTP 壳留在 :server）
```

`:server` 只剩 `api/Routing.kt`、`Main.kt` 和 Backup 的 HTTP 壳，依赖 `:core`。
`:composeApp` 的 android / desktop 源集改成依赖 `:core`。

测试全部跟着源码进 `:core`：`NameParserTest`、`MetadataServiceTest`、`IdentifyTest`、
`MergeTest`、`PlaybackServiceTest`——它们测的都是 core 逻辑，没有一个测 HTTP。

**验收**：重建 APK，dex 里 `io/ktor/server` 全部消失；`./gradlew :core:jvmTest` 全绿。

## 阶段 3 — MediaFacade：把逻辑从 route handler 里抠出来

工作量：中。这是整个改造的核心形状：**一个 facade，两个适配器**。

`Routing.kt` 大部分是薄的转发，但这几处有真逻辑，必须先提到 `:core` 的 `MediaFacade`，
否则 `LocalBackend` 会变成第二份实现，两份迟早分叉：

| handler | 里面的逻辑 |
| --- | --- |
| `POST /api/playback/start` | 音轨默认选择、字幕默认选择、按需探测、直链解析、URL 拼装 |
| `GET /api/items/{id}` | 未探测过的可播放条目在请求里做一次容器探测 |
| `POST /api/items/{id}/played` | 剧集 / 季展开成分集逐条写 |
| `POST /api/items/{id}/identify` 与两个 GET | 清上一次匹配写进去的字段、分集标题回退再重写、钉住 provider |
| `/api/backup/*` | 流式导出、导入合并 |
| `POST /api/libraries` | 库 id 生成（阶段 0.1 之后是确定性的）、默认名、默认 provider 顺序 |

之后 `Routing.kt` 是 `MediaFacade` 的 HTTP 适配器，`LocalBackend` 是同一个 facade 的
另一个适配器。

## 阶段 4 — `MediaBackend` 接口与两个实现

工作量：大。**开工前必须先定决策 B**——桌面要不要保留字节管道，
决定了 `MediaBackend` 里播放相关方法的契约（「返回一个播放器能取的 URL」
和「返回一条 CDN 直链、不跟踪进度」不是同一个接口）。

接口放 `:shared`。`DaViewClient` 现在有 38 个 suspend 方法，
`AppState` + `PlaybackController` 实际用到 22 个，其余是设置页与 identify 用的。

- `HttpBackend(DaViewClient)`——逐个转发，网页端用。
- `LocalBackend(CoreContext)`——调 `MediaFacade`。

`AppState` 改成持有 `MediaBackend`，连接方式由平台注入：

- 网页：仍然是地址 + 令牌，`ConnectScreen` 保留。
- 桌面 / Android：启动即 `LocalBackend`，**没有连接页、没有令牌**。
  设置页的「服务器 / 断开连接」换成「数据目录 / 打开数据目录」。

### 资源三件事

没有 HTTP 之后，几个消费者需要别的表示：

| 消费者 | 现在 | 之后 |
| --- | --- | --- |
| Coil 海报 / 背景 | `/api/images/{id}/{type}?token=` | `posterUrl` 填 `daview://image/<id>/<type>`，注册一个 Coil `Fetcher` 调 `backend.imageFile(...)` 返回 `ImageCache` 的文件（Fetcher 本来就是 suspend，签名天然匹配） |
| ExoPlayer 视频 | `streamUrl`（loopback 代理） | `directUrl()` 的 CDN 直链 |
| ExoPlayer 外挂字幕 | `/api/subtitle/{id}/{index}` | 预下载到 app cache，给 `file://` |
| 桌面外置播放器 | 同 `streamUrl` | 见决策 B |
| 备份导出 | `openUrl(backupUrl)` | 平台保存对话框（Android SAF `CreateDocument`，桌面 `FileDialog.SAVE`） |
| 网页端 CJK 字体 | `/api/font/cjk` | 不变，只有网页端会用到 |

## 阶段 5 — 移动端的扫描形态

工作量：中。「手机自己扫描刮削」这个决定带来三件必须处理的事：

1. **前台服务**。现在切后台进程被回收，扫描静默死掉，回来没有任何提示。
   扫描要放进 `ForegroundService` + 通知（带进度与取消）。
2. **扫描成本**。容器探测每个文件几次 HTTP 往返，`PROBE_BUDGET = 400`。
   手机上建议默认「仅在充电 + WiFi 时探测」，或把探测完全推迟到播放时。
3. **刮削配额**。N 台设备各刮一遍，TMDB / TheTVDB 的调用量乘 N，
   结果还可能不同（源站排序会变）。阶段 0.4 的 pin 同步能压住「结果不同」，
   压不住配额。如果配额成为问题，唯一的解法是把刮削结果也放进同步文件
   （那条 4 MB 的路），到时候再谈。

## 三处需要拍板

**决策 A — 库 id 迁移。** 改了 id 方案，已有库的 item id 全变，现有进度会孤立。

- (a) 只对新建库用确定性 id，老库保留随机 id。零风险，但老库在两台设备之间仍然对不上，
  除非先在一台上导出、另一台导入。
- (b) 一次性重映射：按 path 匹配老库 → 生成新 id → 同一条事务里更新
  `libraries.id`、`items.library_id`、`items.id`、`user_data.item_id`。要写迁移与回滚。

**决策 B — 桌面外置播放器的字节管道。**

- B1 保留一条最小 loopback 管道：`127.0.0.1` + 临时端口 + 一条路由，
  无鉴权、无 CORS、无 JSON。进度推算（cue / clock 那套，有单元测试）保住。
  **建议这条**：它是唯一能保住那个功能的做法，而一条字节路由不构成「一个服务端」。
- B2 直接把 CDN 直链交给 PotPlayer：桌面上彻底没有监听端口，
  代价是外置播放器的进度只剩「起播位置 + 手动标记已看」。

**决策 C — 网页端。** 桌面端不再兼任服务器之后，网页端只能连独立跑的 `:server-app`。
保留这个部署形态，还是砍掉 wasm target（能顺带砍掉 `:shared` 里的 ktor client
与整个 `DaViewClient`）。

## 验收清单

- [ ] 两台设备各建同一目录的库，库 id 相同
- [ ] A / B 交替观看，不做任何手动操作，两边都能看到对方的进度
- [ ] A 手动指定过的条目，B 扫描后不会刮错
- [ ] 重建 APK：dex 里 `io/ktor/server` 命中数为 0
- [ ] 重建 APK：dex 里 `io/ktor/client` 命中数为 0（决策 C 砍掉 wasm，
      或 `:shared` 把 ktor client 收成 wasm-only 之后）
- [ ] Android 冷启动不再在 `Application.onCreate` 里建 socket
- [ ] 手机与桌面都没有 8096 的监听（B1 的临时端口除外，且只在 127.0.0.1）
- [ ] `./gradlew :core:jvmTest` 全绿

## 风险与回滚

- 阶段 0 如果选了决策 A 的 (b)，会改数据：先备份 `daview.db`，迁移写成幂等的单条事务。
- 阶段 2 是纯搬运，不改行为，出问题回滚一个 commit 即可。
- 阶段 3–4 会动播放路径，是最容易出回归的地方：
  先让 `LocalBackend` 与 `HttpBackend` 并存，桌面端用一个开关切换，
  对比两条路径的行为一致之后，再删掉桌面上的 HTTP。
