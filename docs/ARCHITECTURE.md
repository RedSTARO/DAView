# 架构与实测记录

这份文档记录 DAView 的结构，以及那些**先测过再写代码**的结论。凡是「实测」段落
都对应一次真实请求或一次真实运行，不是推断。

## 1. 存储层：WebDAV 能做什么、不能做什么

针对 `https://webdav.123pan.cn/webdav`（也适用于多数网盘 WebDAV 网关）：

```
OPTIONS /webdav/            → 200, Allow: OPTIONS, LOCK, DELETE, PROPPATCH, COPY, MOVE, UNLOCK, PROPFIND
OPTIONS 预检 (跨域 PROPFIND) → 401, 无 Access-Control-Allow-* 响应头
MKCOL / PUT / DELETE        → 403
GET  <file>                 → 302 → https://<ip>-v3.pd1.cjjd19.com/... ?t=<expiry>&s=<signature>
     跳转后                  → 206 Partial Content, Access-Control-Allow-Origin: *
```

三条推论直接决定了架构：

1. **网页端必须有服务端。** 浏览器发不出带 `Authorization` 的跨域 `PROPFIND`。
2. **跨端进度同步必须有服务端。** 网盘是只读的，没法把状态写回去。
3. **播放不必经过服务器转发字节。** 签名直链不绑定 IP（用另一台机器的出口
   请求同一条链接仍返回 `206`），也不需要凭据，所以可以直接给播放器或 `<video>`。

第 3 点让 `mode=redirect` 成为默认的低开销路径；只有需要跟踪外置播放器进度时
才用 `mode=proxy` 让字节流过服务端。

## 2. 媒体库结构

实际目录长这样：

```
/Ani/Bocchi the Rock! (2022)/Season 01/Bocchi the Rock! - S01E01.mkv
                                      /Bocchi the Rock! - S01E01.zh-Hans.default.ass
                                      /Bocchi the Rock! - S01E01.zh-Hant.ass
/Movie/Spirited Away (2001)/Spirited Away (2001).mkv
                           /Spirited Away (2001).zh-Hans.ja.ass
                           /Extras/
/TV/iPartment (2009)/Season 01/ … Season 08/
                    /iPartment The Movie (2018)/
                    /搬运工.url
```

`NameParser` 处理的几种真实边界情况：

| 输入 | 处理 |
| --- | --- |
| `Extras/`、`Featurettes/`、`花絮/` | 跳过 |
| `搬运工.url`、`Thumbs.db`、`*sample*` | 跳过 |
| `iPartment The Movie (2018)/`（剧集目录内的电影目录） | 建成 `MOVIE`，`parentId` 指向该剧 |
| `.zh-Hans.default.ass` | 语言 `zh-Hans` + 默认标记 |
| `.zh-Hans.ja.ass` | 双语，标题显示 `zh-Hans/ja` |
| `第03话`、`[Group] Name - 03 [1080p]`、`1x03`、`S01E03` | 都能解析出集号 |

外挂字幕的 `index` 从 `1000` 起编号，避免和容器内轨道号冲突。

## 3. 容器解析

服务端不带 ffmpeg。两个解析器都只读文件的一小部分：

- `MkvProbe`：EBML。`Info` 给时长与 `TimecodeScale`，`Tracks` 给每条轨道的
  编号 / 类型 / 编码 / 语言 / 默认与强制标记 / 分辨率 / 声道数。`SeekHead` 指向
  `Cues` 的位置，`Cues` 解析成 `(时间戳, 绝对字节偏移)` 列表。
- `Mp4Probe`：ISO-BMFF。只解析 `moov`；`moov` 在文件尾部时按顶层 box 链
  逐个跳读（每次只读 16 字节头），不会下载中间的 `mdat`。

> 踩过的坑：box 遍历一度用 `Int` 累加偏移，遇到超过 2GB 的 `mdat` 时
> `size.toInt()` 变负数导致游标倒退、死循环，扫描卡死。现在偏移全程用 `Long`，
> 并且每层有迭代次数上限；探测线程另有 15 分钟的整体截止时间。

解析是按需的：扫描时最多探测 400 个文件（并发 6），其余在
`GET /api/items/{id}` 或 `POST /api/playback/start` 时惰性解析并落库。

## 4. 播放会话与进度推算

```
POST /api/playback/start
  → 会话 { itemId, player, fileSize, runtimeMs, anchorPosition, anchorWallClock }
  → streamUrl = /api/stream/{itemId}/{name}?session=…&mode=proxy|redirect&token=…

GET /api/stream/…            每次请求都会调用 onRangeRequest(session, rangeStart)
  mode=redirect              302 → CDN 直链（零转发，但只在请求边界看得到位置）
  mode=proxy                 服务端转发字节，并持续调用 onBytesRead(session, offset)
```

位置的三种来源，界面会如实标注：

| 来源 | 条件 | 精度 |
| --- | --- | --- |
| `client` | 内置播放器主动上报 | 精确 |
| `cue+clock` | Matroska 有 `Cues`，字节偏移换算时间 + 时钟推进 | 秒级 |
| `clock` | 只能按字节比例线性估算 + 时钟推进 | 数十秒 |

跳转的判定：某次请求推算出的时间与当前推进值相差超过 30 秒，就认为发生了
seek，重新锚定。读取指针总是领先画面，所以 `onBytesRead` 修正时会回退 20 秒
的预读余量。

超过 90% 视为看完（与 Emby 一致），并清空续播点。

## 5. 为什么不用 PotPlayer 自己的记录

试过三条路，都走不通，记录在这里避免重复劳动：

1. `%APPDATA%\PotPlayerMini64\Playlist\PotPlayerMini64.dpl`
   播放本地文件时有 `playtime=<秒>`，但**播放 URL 时不写**。
   好消息是 `playname=` 存的是**我们给的地址**（而不是跳转后的 CDN 地址），
   所以如果哪天它开始写 `playtime`，匹配是没问题的。
2. 自己生成一个 `saveplaypos=1` 的 `.dpl` 传给 PotPlayer：能正常播放、
   `1*title*` 也能控制窗口标题，但退出时不回写这个文件。
3. 注册表 `HKCU\Software\DAUM\PotPlayerMini64`：只有加密的 `MInfo1` / `MInfo2`。

命令行方面实测有效的是 `/seek=hh:mm:ss`（续播）、`/sub=<url>`（外挂字幕）、
`/title=`、`/volume=`。`potplayer://` 协议由安装程序注册在
`HKLM\SOFTWARE\Classes\potplayer`，命令是 `PotPlayerMini64.exe "%1"`，
这正是网页端交接播放的基础。

## 6. 数据库

单文件 SQLite（`daview.db`，WAL 模式），三张主表：

- `libraries` — 用户定义的媒体库与刮削顺序
- `items` — 电影 / 剧集 / 季 / 单集，JSON 列存放类型 / 演职人员 / 轨道
- `user_data` — 播放位置、已看、收藏、音轨与字幕选择

外加 `scrape_cache` 缓存刮削 API 的原始响应。所有写操作走一条连接 + 短事务，
避免 `SQLITE_BUSY`；`migrations` 是一个版本号驱动的语句数组。

`items` 有两条写入路径，区别只在 `ON CONFLICT` 上：

| 语句 | 谁用 | 冲突时怎么写 |
| --- | --- | --- |
| `UPSERT_ITEM` | 刮削、探测、手动指定 | 整行覆盖 |
| `UPSERT_SCANNED_ITEM` | 扫描器 | `scraped_at` 非空时，只更新文件相关列 |

分开的原因是扫描器只知道文件名。合成一条的话，每次重新扫描都会把刮削结果连同
`scraped_at` 一起抹掉——于是整库重刮，手动指定的 id 也一起没了。

条目 ID 是 `SHA-1(libraryId + "|" + path)` 的前 12 字节，因此重复扫描是幂等的，
文件没动过 ID 就不会变，进度也不会丢。

## 7. 手动指定刮削条目

自动匹配的失败模式是有限的几种：同名不同作、文件夹缺年份、源站排序把别的版本放在前面。
`bestMatch` 的阈值只能压低错误率，压不到零，所以留了一条手动通道。

```
GET  /api/items/{id}/identify           → 文件夹原名 + 可用源 + 当前 id
GET  /api/items/{id}/identify/search    → 原始候选（不过滤年份、不算相似度）
POST /api/items/{id}/identify           → 钉住 provider + id 并重建元数据
```

三个约束是这个功能能用的前提：

1. **搜索框的默认词取自文件夹，不是当前标题。** 刮错的条目标题就是错的，
   拿它去搜只会再错一次。`MetadataService.folderTitle()` 按 Scanner 的同一套规则
   反推：剧集取目录名，顶层电影取上一级目录名，剧集内嵌的电影取文件名。
2. **换条目要先清空上一个匹配写的字段。** `enrichItem` 的合并规则是
   `新值 ?: 旧值`，新条目没有的字段会留着旧的——标题换了、演职人员还是上一部的。
   所以 `identify` 先 `stripScrapedFields`，剧集还要把分集标题退回文件名。
3. **钉住之后任何源都不再搜索。** `items.locked_provider` 存下选择；
   `enrichItem` 看到它就把该源排到最前，其余源只在**已经有 id** 时才取数据
   （TMDB 的 `external_ids` 会顺手给出 tvdb id），一律不再 `search()`。
   这条有单元测试守着：`IdentifyTest.a pinned item is never searched again`。

海报地址还带了一个版本号（`/api/images/{id}/primary?v=...`，由远端 URL 算出）。
不带的话条目地址不变，重新指定之后客户端会继续显示自己缓存里的旧海报。

## 8. 客户端

`shared` 只放 DTO 与 `DaViewClient`（Ktor client，引擎由各平台的 artifact 决定，
`HttpClient()` 无参构造自动选取）。

`composeApp` 的平台差异集中在 `platform/Platform.*.kt`：

| 能力 | Android | Desktop | Web |
| --- | --- | --- | --- |
| 设置存储 | SharedPreferences | `java.util.prefs` | `localStorage` |
| 内置播放器 | Media3 / ExoPlayer | 无（交给外置） | 无（交给外置 / 标签页） |
| 外置播放器 | `ACTION_VIEW` 选择器 / MX / VLC | 探测 exe 路径后起进程 | `potplayer://` / `vlc://` |
| 进程退出可观测 | 否 | 是（用于立即结束会话） | 否 |

### 网页端的字体问题

**结论先写**：现在的构建**不需要**服务端发字体，中文能正常显示——但**首屏那一帧是方框**，
触发一次重新布局（改窗口大小、切页面、列表重绘）之后就恢复正常。实测环境是 Windows +
Chromium，宿主机装有中日韩字体；抓包确认 `platformNeedsCjkFont = false` 时页面根本没有
请求 `/api/font/cjk`，方框也照样在重排后消失。也就是说字形一直是有的，问题出在
首帧排版时字体尚未就绪、而之后没有任何东西让文本重新测量。

绕过它需要在启动后主动制造一次重排（例如首帧后把根布局的 padding 从 1dp 改成 0dp）。
这是在给上游渲染器打补丁，所以默认没有加。

下面这张表记录的是**另一件事**：曾经试图把服务器上的字体注入进去，五种方式全部无效。
既然不注入也能显示中文，这条路已经没有必要走；但那个结论本身仍然成立——
`platform.Font(identity, bytes)` 在 wasm 上不会被字体解析器接受。

| 尝试 | 结果 |
| --- | --- |
| 服务端 `/api/font/cjk` 提供宿主机上的 `simhei.ttf`（9.7 MB，magic `00 01 00 00`，是正规 TTF） | 下载成功，控制台确认 9745792 字节 |
| `FontFamily(Font("DAViewCJK", bytes))`（`androidx.compose.ui.text.platform.Font`） | 构造不抛异常 |
| 通过 `MaterialExpressiveTheme(typography = …)` 应用到全部文本样式 | 仍是方框 |
| 直接给单个 `Text` 传 `fontFamily =` | 仍是方框（拉丁字形也没变，说明字体根本没生效） |
| `FontFamily.Resolver.preload(family)` 之后再用 | 仍是方框 |

代码保留在 `UiFont.*.kt` 与 `/api/font/cjk`，但 `platformNeedsCjkFont` 在 wasm 上设为
`false`：既然不注入字体也能渲染中文，每次冷启动下 10 MB 就是纯浪费。
桌面端与 Android 端走平台字体管理器，不受影响。

**下次从哪儿接着试**：`androidx.compose.ui.text.platform.Font(identity, bytes)` 是
skiko/JVM 的便利构造，wasm 渲染器并没有把它接进字体解析器——这与上面五种尝试的
现象一致（family 建得出来、拉丁字形不变、preload 无效）。Compose Multiplatform 在
网页端的正规路径是把字体放进 `composeResources/font/`，用 `Res.font.*` 配合
`preloadFont` 加载。代价是仓库里要放一个约 10 MB 的字体（Noto Sans SC 之类的
OFL 字体），或者在构建时下载。

Material 3 Expressive 用到的 `MaterialExpressiveTheme`、`MotionScheme.expressive()`、
`MaterialShapes`、`ButtonGroup`、`LinearWavyProgressIndicator`、`ContainedLoadingIndicator`、
`ToggleButton`、`HorizontalFloatingToolbar` 都来自
`org.jetbrains.compose.material3:material3:1.12.0-alpha03`——Compose 插件默认锁定的
`1.9.0` 里它们还是 `internal`（编译报 “it is internal in file”），所以这个模块的版本
是显式声明的。
