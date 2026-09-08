# DAView

一个自托管的媒体库应用：扫描 WebDAV 上的影视目录，在本地生成刮削数据库，
记录播放进度与音轨 / 字幕选择，并在桌面与网页端把播放交给 PotPlayer 等外置播放器
——同时仍然把进度同步回服务器。

技术栈：**Kotlin Multiplatform + Compose Multiplatform + Material 3 Expressive**，
后端是同一个仓库里的 **Ktor** 服务。

```
┌────────────┐   REST + 流媒体   ┌──────────────────────────────┐   WebDAV / CDN 直链
│ composeApp │ ────────────────▶ │ server (Ktor, JVM)           │ ──────────────────▶ 存储
│  Android   │                   │  扫描 · 刮削 · SQLite · 进度  │
│  Desktop   │ ◀──────────────── │  流媒体代理 · 图片缓存        │
│  Web(wasm) │    进度 / 元数据   └──────────────────────────────┘
└────────────┘
```

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

**容器解析（不依赖 ffmpeg）**
- Matroska：读 EBML 头得到时长、内封音轨 / 字幕轨、编码、语言、默认 / 强制标记
- MP4/MOV：读 `moov` 得到时长与轨道（`moov` 在文件尾部时会按 box 链跳读，不会下载整个文件）
- Matroska 的 `Cues` 索引会被解析成「时间 ↔ 字节」映射，用于推算外置播放器的进度

**播放**
- Android：Media3 / ExoPlayer 内置播放器，可切换音轨与字幕（含外挂字幕）
- 桌面：PotPlayer / VLC / mpv / IINA，自动探测安装路径
- 网页：`potplayer://` 协议交给本机 PotPlayer，或在浏览器标签页里直接播放
- 断点续播、已看标记（超过 90% 自动标记已看）、收藏、继续观看 / 接下来 / 最近添加

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

## 快速开始

需要 JDK 17+（推荐 Android Studio 自带的 JBR 21）与 Android SDK（仅 Android 构建需要）。

```bash
./gradlew :server:installDist
DAVIEW_DATA=./run server/build/install/server/bin/server
```

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
server/      Ktor 服务：WebDAV、扫描、命名解析、刮削、SQLite、流媒体、图片缓存
composeApp/  Compose Multiplatform 客户端（androidMain / desktopMain / wasmJsMain）
docs/        架构说明与实测记录
```

## 已知限制

- **网页端不能内嵌播放 mkv**：浏览器不解 Matroska。网页端提供 `potplayer://` 交接与
  「在浏览器中播放」（对 mp4 有效），进度依然由服务端跟踪。
- **外置播放器的进度是推算值**，暂停时钟仍在走，误差通常在几十秒内；续播够用，不适合当精确计时。
- **暂无 iOS target**：`shared` 的结构已经允许加 `iosArm64/iosSimulatorArm64`，但需要 macOS 才能构建。
- **AGP 9 兼容**：目前用 `android.builtInKotlin=false` + `android.newDsl=false` 保留经典 KMP 布局，
  后续应迁移到 `com.android.kotlin.multiplatform.library`。
- **Material 3 Expressive** 来自 `org.jetbrains.compose.material3:material3:1.12.0-alpha03`；
  Compose 插件默认锁定的 1.9.0 里这些 API 还是 `internal`。
- 扫描时的容器探测每个文件要几次 HTTP 往返，首次扫描大库会比较慢（并发 6，单库上限 400 个文件，
  其余在播放时按需解析）。
