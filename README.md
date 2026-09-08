# DAView

一个自托管的媒体库应用：扫描 WebDAV 上的影视目录，在本地生成刮削数据库，
记录播放进度与音轨 / 字幕选择，并在桌面与网页端把播放交给 PotPlayer 等外置播放器
——同时仍然把进度同步回服务器。

技术栈：**Kotlin Multiplatform + Compose Multiplatform + Material 3 Expressive**，
后端是同一个仓库里的 **Ktor** 服务。

```
桌面端 / Android：core 跑在应用进程里，不需要另一台服务器
┌─────────────────────────────────────┐   WebDAV / CDN 直链
│ 应用进程                             │ ──────────────────▶ 存储
│  UI ──HTTP(127.0.0.1)──▶ server core │
│                扫描 · 刮削 · SQLite   │
└─────────────────────────────────────┘

网页端：浏览器发不出带凭据的跨域 PROPFIND，只能连一台服务端
┌────────────┐   REST + 流媒体   ┌──────────────┐
│  Web(wasm) │ ────────────────▶ │ 桌面端或独立  │ ──────────────────▶ 存储
└────────────┘                   │ 服务端        │
                                 └──────────────┘
```

桌面端与 Android 端**不依赖任何独立后端**：`:server` 是一个 KMP 模块（jvm + android），
两端都在自己的进程里启动它。仍然走 HTTP，只是走 127.0.0.1——这样三端共用同一套客户端
代码，也才能把地址交给外置播放器和图片加载器（它们没法接收一个 Kotlin 对象）。

跨设备的状态一致由 WebDAV 上的一个同步文件解决（见「跨端同步」），而不是靠共用服务器。

## 为什么是「客户端 + 服务器」而不是纯客户端

这不是偏好，是被两个实测结论逼出来的（针对 `webdav.123pan.cn`）：

| 实测 | 结果 | 影响 |
| --- | --- | --- |
| 浏览器跨域 `PROPFIND` | 预检返回 `401`，无任何 `Access-Control-Allow-*` | 网页端**不可能**直连 WebDAV，必须有服务端代理 |
| `MKCOL` / `PUT` / `DELETE` | `403`，`Allow:` 头里也没有写方法 | 无法把数据库或进度写回网盘，跨端同步必须有服务端 |
| `GET` 文件 | `302` 跳转到签名 CDN 直链，支持 `Range`，`Access-Control-Allow-Origin: *`，有效期约 76 小时，且**不绑定 IP** | 播放可以直连 CDN，服务器不必转发字节 |

所以：服务端负责扫描、刮削、数据库、进度与鉴权；客户端只是 UI。
桌面端默认**在同一进程内启动服务端**，因此单独运行桌面版就是完整可用的。

## 功能

**媒体库**
- 浏览 WebDAV 目录树，把任意子目录指定为「电影 / 电视剧 / 番剧 / 其他」媒体库
- Emby / Jellyfin 命名约定：`片名 (年份)/Season 01/片名 - S01E01.mkv`
- 识别外挂字幕的语言与标记：`.zh-Hans.default.ass`、`.zh-Hant.ass`、`.zh-Hans.ja.ass`（双语）
- 跳过 `Extras/`、`.url`、`sample`/`trailer` 等噪音文件
- 剧集目录里嵌套的 `片名 (年份)` 电影目录（如 `iPartment (2009)/iPartment The Movie (2018)`）
  会被识别成挂在该剧下的「相关影片」，而不是硬塞进某一季

**刮削**
- 可插拔的元数据源：**TMDB**、**TheTVDB v4**、**bangumi.tv**，每个媒体库可自定义顺序
- 年份相差超过 1 年的候选会被直接排除（否则同名的不同作品会互相匹配）
- 结果写入本地 SQLite；HTTP 响应带缓存，重复扫描不会反复打 API
- 图片由服务端下载并缓存到磁盘，客户端只访问服务端，API Key 不出服务器
- 刮错了可以手动指定条目 id（见下节），指定后会被钉住，重新扫描也不会被覆盖回去
- **主来源没匹配上时，退而用次级来源**：所有源都严格匹配失败后，会取排名最高、年份不冲突的
  候选顶上，而不是把条目空着。这种条目在详情页标为「次级来源顶替（未可靠匹配，建议核对）」。
- **刮削状态显示在详情页**：自动匹配 / 次级来源顶替 / 手动指定 / 所有来源都没有匹配 / 尚未刮削，
  并列出各来源的 id。

**合并重复条目**
- 同一部剧被扫成两条是常事：两个文件夹、番剧库和电视剧库各一份、或者只有大小写不同的目录名
  （参考网盘里就有 `/Ani/SCHOOL-LIVE! (2015)` 和 `/Ani/School-Live! (2015)`）
- 详情页的合并按钮把重复项并进当前条目：它们的季与分集移过来，自己不再单独出现
- 重复项的记录**保留**而不是删除，所以随时可以拆分；扫描器每次重建目录树之后会自动重新应用合并
- 拆分按路径前缀把子项还回去，比较是**区分大小写**的——SQLite 的 `LIKE` 不区分 ASCII 大小写，
  用它会把目标自己的分集也一起还走

**容器解析（不依赖 ffmpeg）**
- Matroska：读 EBML 头得到时长、内封音轨 / 字幕轨、编码、语言、默认 / 强制标记
- MP4/MOV：读 `moov` 得到时长与轨道（`moov` 在文件尾部时会按 box 链跳读，不会下载整个文件）
- Matroska 的 `Cues` 索引会被解析成「时间 ↔ 字节」映射，用于推算外置播放器的进度

**播放**
- Android：Media3 / ExoPlayer 内置播放器，可切换音轨与字幕（含外挂字幕）
- 桌面：PotPlayer / VLC / mpv / IINA，自动探测安装路径
- 网页：`potplayer://` 协议交给本机 PotPlayer，或在浏览器标签页里直接播放
- 断点续播、已看标记（超过 90% 自动标记已看）、收藏、继续观看 / 接下来 / 最近添加
- 剧集 / 季 / 单集的详情页都显示观看状态，并且都能手动标记已看 / 未看。
  剧集和季自己没有可播放的内容，所以它们的状态**由下面的分集算出来**
  （`已看 3 / 12 集`），标记它们等于标记其下的全部分集——不另存一份可能和分集打架的标记。

## 刮错了怎么办

自动匹配一定会错一部分：同名不同作、文件夹里没写年份、源站把重制版排在前面。
与其把阈值调到「永远正确」，不如留一个手动出口。

条目页的铅笔按钮打开「手动指定刮削条目」：

1. 选刮削源。只列出这台服务器上真正可用的：TMDB / TheTVDB 要 API Key，bangumi.tv 不用
2. 直接填该站点的条目 id —— TMDB 是 `themoviedb.org/tv/<id>` 里的数字，bangumi 是 `bgm.tv/subject/<id>`
3. 不知道 id 就用下面的搜索框，点候选项即可指定。搜索框默认填的是**文件夹解析出来的原名**，
   不是刮错之后的名字；这里的搜索也**不走**自动匹配那套年份过滤与相似度阈值，结果原样列出。
   片名可以改——bangumi.tv 对英文片名的搜索很差，换成中文或日文往往一次就中

指定之后：

- 上一个匹配写进去的字段（简介、类型、演职人员、评分、海报、背景图）先清空再写，
  不会出现「标题对了但演员还是上一部的」
- 剧集的分集标题先退回文件名，再按新条目重写一遍
- `items.locked_provider` 记下这次选择。以后再刮削这个条目时只按已有 id 取数据，
  **任何源都不再搜索**——搜索正是当初匹配错的原因
- 重新扫描不会覆盖它。扫描器改用一条只更新「文件相关列」的 upsert，已经刮削过的条目，
  标题 / 海报 / provider id 一律保留（`Repository.UPSERT_SCANNED_ITEM`）

对应的接口：

```
GET  /api/items/{id}/identify           # 文件夹原名、可用刮削源、当前钉住的 id
GET  /api/items/{id}/identify/search    # ?provider=&query=&year=，原始候选，不过滤
POST /api/items/{id}/identify           # {"provider":"bangumi","providerId":"49278"}
```

## 备份与迁移

换机器时要带走的东西有三样：服务器设置、媒体库定义、观看进度。手动指定的刮削 id
存在条目里，所以想连它一起带走就得包含刮削数据。

```
GET  /api/backup/export?settings=&libraries=&items=&userdata=&secrets=
POST /api/backup/import                      # 请求体就是备份文件
POST /api/backup/import?source=datadir       # 读数据目录下的 import.json
```

客户端「设置 → 备份与迁移」里有导出 / 复制地址 / 导入三个按钮，两个开关控制
「包含刮削数据」与「包含凭据」：

1. 在旧机器上点「导出」，浏览器下载 `daview-backup-<时间>.json`
2. 在新机器上点「导入…」，用系统文件选择器选中它

导入走的是平台自己的文件选择器（Android 的 SAF、桌面的 AWT 对话框、网页的
`<input type=file>`），而不是「把文件放进数据目录」——**Android 的数据目录在
`filesDir` 下，用户根本放不进东西**，那条路在手机上不成立。

`?source=datadir` 仍然保留，读数据目录里的 `import.json`，用于无界面的服务端恢复。

命令行同样可以：

```bash
curl -o backup.json "http://old:8096/api/backup/export?token=<token>"
```

```bash
curl -X POST -H "Content-Type: application/json" --data-binary @backup.json "http://new:8096/api/backup/import?token=<token>"
```

几个约束：

- **凭据默认不导出**。WebDAV 账号、密码、TMDB / TheTVDB / bangumi 的 Key 只有
  `secrets=true` 时才写进文件——那是一个要在机器之间搬运的明文文件。
  账号和密码一样算凭据（这个网盘的账号就是手机号）。
- **导入时空的凭据表示「保留目标机器已有的」**，所以不含凭据的备份不会把新机器上
  已经填好的 Key 清掉。
- 导出是**流式**写出的：3246 个条目 4.1 MB，服务端堆只有 384 MB 也不会撑爆。
- 条目 id 是 `SHA-1(库 id + 路径)`，所以只要库定义一起带过去，观看进度就能重新对上。
  只导设置和进度、不导刮削数据也可以，代价是新机器要重新扫描一遍。

## 跨端同步

没有第二台服务器可以商量，所以设备之间通过它们本来就共用的那块存储达成一致：把状态写成
WebDAV 上的一个文件，别的设备读回来合并。

「设置 → 跨端同步」里开关 + 指定路径。同步文件里放的是**服务器设置、媒体库定义、观看进度**，
不含刮削结果——那个每台设备扫描一次就有，带上会让每次上传从 1.5 KB 变成 4 MB。

```
GET  /api/sync            # 当前设置与状态
PUT  /api/sync            # 改路径 / 间隔；enabled=true 会先试写
POST /api/sync/upload     # 立即上传
POST /api/sync/pull       # 拉回并合并
```

几个必须知道的点：

- **能不能写只能靠试**。这个网关的 `OPTIONS` 响应里**永远没有 `PUT`**，哪怕它接受 PUT。
  所以「启用」这个动作本身就是一次真实上传：成功才留在开启状态，被拒绝（403）就自动关回去
  并显示「这个 WebDAV 不允许写入，无法同步」。不要去读 `Allow` 头判断。
- **合并规则是按行取新**。`user_data.updated_at` 谁大听谁的，不做三方合并。
  两台设备同时看同一集时，后写的那次覆盖前一次。
- **只写这一个文件**。上传路径由用户指定，除它以外不碰存储上的任何东西。
- 自动上传由一个每分钟的 tick 驱动，条件是「观看状态指纹变了」且距上次上传超过设定间隔。
  指纹是 `MAX(updated_at)` 加行数一条 SQL，所以写进度的地方不需要额外通知同步模块。
- 凭据不进同步文件。把 WebDAV 密码写到需要该密码才能读的地方没有意义。

## 持续集成

`.github/workflows/build.yml`：推送到 master / main、开 PR 或手动触发时跑三件事。

| Job | 平台 | 产物 |
| --- | --- | --- |
| `test` | ubuntu | `:server:test`，报告作为 artifact |
| `msi` | windows | `composeApp/build/compose/binaries/main/msi/*.msi` |
| `apk` | ubuntu | `composeApp/build/outputs/apk/debug/*.apk` |

打 `v*` 标签时多跑一个 `release` job，把 MSI 与 APK 传到对应的 GitHub Release。

两个不显然的地方：

- **MSI 需要 WiX Toolset 3**。jpackage 用它生成 MSI，且不接受 WiX 4/5；
  新的 runner 镜像不再预装，所以 workflow 里用 choco 装了 3.11.2。
- **APK 是 debug 版**。没有密钥库的 release 包是未签名的，装不上。要出 release：
  把密钥库 base64 后存进仓库 secret，在 `composeApp/build.gradle.kts` 里加
  `signingConfigs`，再把 job 换成 `assembleRelease`。

## 外置播放器的进度同步是怎么做的

PotPlayer 不会向任何人汇报播放位置——实测过：

- `%APPDATA%\PotPlayerMini64\Playlist\PotPlayerMini64.dpl` 里确实有 `playname=` 与 `playtime=`，
  但**用 URL 播放时不写 `playtime`**；自建带 `saveplaypos=1` 的播放列表也不会被回写。
- 注册表 `HKCU\Software\DAUM\PotPlayerMini64` 下只有加密的 `MInfo1/MInfo2`，不可用。

所以 DAView 不去猜 PotPlayer 的内部状态，而是**让播放地址指回自己**：

1. 客户端请求 `POST /api/playback/start`，服务端建立会话并返回
   `http://<server>/api/stream/{itemId}/{文件名}?session=…&mode=proxy&token=…`
2. 播放器向这个地址请求字节。服务端因此能看到每一次 `Range`：
   - 有 Matroska `Cues` 时，字节偏移可以精确换算成时间戳
   - 没有索引时按「字节比例 × 时长」线性估算
3. 两次请求之间，位置按墙上时钟推进（正常 1 倍速播放时是准的）
4. 播放器停止读取超过 5 分钟，会话结束并落库

界面会明确标注进度来源（`播放器上报` / `索引推算` / `时钟推算`），不会假装它是精确值。

PotPlayer 的续播用命令行 `/seek=hh:mm:ss`（实测有效），VLC 用 `--start-time`，mpv 用 `--start`。
网页端则走 `potplayer://` 协议——实测 PotPlayer 会剥掉协议前缀、保留完整查询串并向该地址取流，
所以从浏览器交接出去的播放同样会被服务端跟踪。

### 这条链路的实测结果

用 `/Ani/Bocchi the Rock! (2022)/Season 01/Bocchi the Rock! - S01E01.mkv`（1.0 GB / 23:41）跑通：

```
启动 PotPlayer，命令行带 /seek=00:05:00
  t= 24s  pos=  294113 ms (04:54)  src=cue+clock     ← 由 Matroska Cues 定位，误差 6 秒
  t= 60s  pos=  330121 ms (05:30)  src=cue+clock
  t=132s  pos=  402135 ms (06:42)  src=cue+clock
关闭播放器后落库：positionMs=418245, played=false, audioStreamIndex=2, subtitleStreamIndex=1000
再次播放该集：startPositionMs=418245（客户端据此给 PotPlayer 传 /seek）
```

一个必须避开的坑：PotPlayer 打开 MKV 时会先读文件末尾的 `Cues`（实测偏移在 99.998% 处）。
如果把这次 `Range` 当成播放位置，会立刻把整集标记成"已看完"。因此末尾 8 MB 内的
读取被判定为索引读取而忽略，且任何 `Range` 都要静置 3 秒无新请求才会被采纳为播放位置
（打开文件时会连续发好几次探测请求，只有最后一次是真正的起播点）。
这两条行为有单元测试覆盖：`server/src/test/.../PlaybackServiceTest.kt`。

**已知不足**：外置播放器暂停时服务端无从得知，时钟会继续走。
代理模式下会用"已下载字节对应的时间"作上限压制这个误差，但缓冲区通常领先一两分钟，
所以暂停很久后的进度会偏大。

## 快速开始

需要 JDK 17+（推荐 Android Studio 自带的 JBR 21）；Android 构建另需 SDK Platform 37
（Coil 3.6 的 AAR 要求 `compileSdk >= 37`）。

```bash
./gradlew :server-app:installDist
DAVIEW_DATA=./run server-app/build/install/server-app/bin/server-app
```

> 独立服务端只有网页端需要。桌面端和 Android 端自己就带着 core。

首次启动会在日志里打印访问令牌与网页地址：

```
访问令牌: 3f9c…
Web 客户端: http://127.0.0.1:8096/?token=3f9c…
```

存储与 API Key 可以在客户端「设置」里填，也可以用环境变量（适合容器部署）：

```bash
DAVIEW_WEBDAV_URL=https://webdav.example.com/webdav
DAVIEW_WEBDAV_USER=...
DAVIEW_WEBDAV_PASS=...
DAVIEW_TMDB_KEY=...
DAVIEW_TVDB_KEY=...
DAVIEW_TOKEN=...            # 固定访问令牌，不填则自动生成
DAVIEW_DATA=./run           # 数据目录（config.json + daview.db + 图片缓存）
DAVIEW_WEB_DIR=...          # 网页客户端目录，默认取 composeApp 的构建产物
```

> 凭据只写在数据目录下的 `config.json`，该目录已在 `.gitignore` 中。

### 各端构建

```bash
./gradlew :composeApp:run                        # 桌面端（内置服务端）
./gradlew :composeApp:wasmJsBrowserDistribution  # 网页端 → composeApp/build/dist/wasmJs/productionExecutable
./gradlew :composeApp:assembleDebug              # Android APK
./gradlew :composeApp:packageMsi                 # 桌面安装包（Windows）
```

桌面端默认自己起服务端；连远程服务器用：

```bash
./gradlew :composeApp:run --args="--remote http://192.168.1.10:8096 --token <token>"
```

网页端由服务端托管，因此同源、没有跨域问题。

## 目录结构

```
shared/      KMP：DTO 与 REST 客户端（jvm / android / wasmJs）
server/      KMP core（jvm / android）：WebDAV、扫描、命名解析、刮削、SQLite、流媒体、图片缓存
server-app/  独立服务端的启动器，只有一个 main()
composeApp/  Compose Multiplatform 客户端（androidMain / desktopMain / wasmJsMain）
docs/        架构说明与实测记录

`server` 的两个 target 编译同一份 `src/main/kotlin`。唯一真正有平台差异的是 SQL 驱动
（JDBC / Android SQLite），它是注入进 `ServerContext` 的，所以既不需要 expect/actual，
也不需要中间 source set。
```

## 已知限制

- **网页端不能内嵌播放 mkv**：浏览器不解 Matroska。网页端提供 `potplayer://` 交接与
  「在浏览器中播放」（对 mp4 有效），进度依然由服务端跟踪。
- **网页端首屏的中文是方框，任何一次重新布局之后就正常**：实测（Windows + Chromium，
  宿主机装有中日韩字体）页面刚加载完那一帧里中文渲染成方框，但只要触发一次重新布局
  ——改窗口大小、切换页面、列表重绘——同一段文字就正常显示，**并且不需要下载任何字体**
  （`platformNeedsCjkFont` 现在是 `false`，抓包确认没有请求 `/api/font/cjk`）。
  所以这不是「没有字体」，是首帧排版时字体还没就绪、之后又没人让它重排。
  已知的绕法是启动后主动触发一次重排（例如首帧后把根布局的 padding 从 1dp 改回 0dp），
  属于给上游渲染器打补丁，目前没有加。在没有装中日韩字体的机器上会怎样没有测过。
  **桌面端与 Android 端不受影响**，它们走系统字体管理器。
- **外置播放器的进度是推算值**：正常播放时误差在秒级（有 Matroska 索引时），但服务端看不到暂停，
  长时间暂停后的进度会偏大。够用来续播，不能当精确计时。
- **暂无 iOS target**：`shared` 的结构已经允许加 `iosArm64/iosSimulatorArm64`，但需要 macOS 才能构建。
- **AGP 9 兼容**：目前用 `android.builtInKotlin=false` + `android.newDsl=false` 保留经典 KMP 布局，
  后续应迁移到 `com.android.kotlin.multiplatform.library`。
- **Material 3 Expressive** 来自 `org.jetbrains.compose.material3:material3:1.12.0-alpha03`；
  Compose 插件默认锁定的 1.9.0 里这些 API 还是 `internal`。
- 扫描时的容器探测每个文件要几次 HTTP 往返，首次扫描大库会比较慢（并发 6，单库上限 400 个文件，
  其余在播放时按需解析）。
- **bangumi.tv 对英文片名的搜索很差**：自动刮削带 `filter.type=[2]` 只搜动画（去掉它，同名的
  游戏、书籍、真人剧会挤进来，匹配率从 86% 掉回 34%）；手动指定里的搜索不带这个过滤，
  什么类型都能搜到。但英文文件夹名在 bangumi 上经常搜不出对的条目——把片名改成中文或日文再搜，
  或者直接填 id。
- **刮削需要自备 API Key**：没有 TMDB / TheTVDB Key 时，电影与电视剧库只会用文件名建库、没有海报和简介；
  番剧库可以只靠 bangumi.tv。bangumi.tv 默认**不会**作为电影 / 电视剧的兜底源——
  它只收录动画，会把同名的真人剧匹配成动画。

## 测试

```bash
./gradlew :server:jvmTest
```

覆盖命名解析（季 / 集 / 双语字幕 / 噪音过滤）、外置播放器的进度推算
（末尾索引读取不得跳到片尾、静置后才锚定、下载进度作为上限）、刮削匹配的取舍，
以及手动指定（钉住之后不得再搜索、换条目要清掉上一个匹配的字段、id 填错不能动已有数据）。
