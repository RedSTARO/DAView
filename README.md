# DAView

一个自托管的媒体库应用：扫描 WebDAV 上的影视目录，在本地生成刮削数据库，
记录播放进度与音轨 / 字幕选择。桌面端在应用内直接播放（libmpv，支持 NVIDIA
RTX Video Super Resolution 与 RTX Video HDR），也可以把播放交给 PotPlayer 等
外置播放器——那种情况下仍然把进度记回来。

技术栈：**Kotlin Multiplatform + Compose Multiplatform + Material 3 Expressive**。
桌面端与 Android 端各自跑一份 core，设备之间只通过存储上的一个同步文件对齐。

```
每个应用自己跑一份 core，进程内直接调用，不经过 socket
┌──────────────────────────────────────┐   WebDAV / CDN 直链
│ 桌面端 / Android                      │ ──────────────────▶ 存储
│  UI ──直接调用──▶ core                │                      │
│            扫描 · 刮削 · SQLite        │                      │
└──────────────────────────────────────┘                      │
              ▲                                               │
              └─── 观看进度 / 媒体库定义 / 手动指定 ◀───────────┘
                   （存储上的一个同步文件）
```

唯一还会监听端口的是播放用的字节管道：`127.0.0.1` + 临时端口，开始播放那一刻才起，
播完就关。内置播放器也读它——这样 CDN 直链在片子放到一半过期时可以重新解析
（见「外置播放器的进度同步是怎么做的」）。

> 网页端已经移除：它是唯一必须有一台服务器才能活的形态。

跨设备的状态一致由 WebDAV 上的一个同步文件解决（见「跨端同步」），而不是靠共用服务器。

## 为什么每台设备各跑一份 core

针对 `webdav.123pan.cn` 的实测结论决定了能做什么、不能做什么：

| 实测 | 结果 | 影响 |
| --- | --- | --- |
| `MKCOL` / `PUT` / `DELETE` | 曾经 `403`；2026-09-09 复测为 `PUT 201` / `DELETE 204`。两次的 `Allow:` 头都没列 `PUT` | 可写与否会变，且不能从 `Allow:` 读出来；同步开关先试写一次再决定 |
| `GET` 文件 | `302` 跳转到签名 CDN 直链，支持 `Range`，`Access-Control-Allow-Origin: *`，有效期约 76 小时，且**不绑定 IP** | 播放可以直连 CDN，字节不必经过任何中间层 |
| 浏览器跨域 `PROPFIND` | 预检返回 `401`，无任何 `Access-Control-Allow-*` | 浏览器**不可能**直连 WebDAV。网页端因此必须有一台常驻服务器，这也是它被移除的原因 |

原生端没有这个限制：Android 和桌面都是原生 HTTP 客户端，直连 WebDAV 没有跨域一说。
所以每台设备自己扫描、自己刮削、自己存库，谁也不依赖谁开着。

## 功能

**媒体库**
- 浏览 WebDAV 目录树，把任意子目录指定为「电影 / 电视剧 / 番剧 / 其他」媒体库
- Emby / Jellyfin 命名约定：`片名 (年份)/Season 01/片名 - S01E01.mkv`
- 识别外挂字幕的语言与标记：`.zh-Hans.default.ass`、`.zh-Hant.ass`、`.zh-Hans.ja.ass`（双语）
- 跳过 `.url`、`Thumbs.db`、`sample`/`trailer` 等噪音文件
- 季目录里的 `SPs/`、`Extras/`、`CDs/` 之类的子目录会被下钻一层，里面的**视频**
  挂到该剧的「特别篇」（第 0 季）而不是混进正片列表；原声带那类目录里全是音频，
  `isVideoFile` 本来就不收，所以它们自然进不来
- 剧集目录里嵌套的 `片名 (年份)` 电影目录（如 `iPartment (2009)/iPartment The Movie (2018)`）
  会被识别成挂在该剧下的「相关影片」，而不是硬塞进某一季

**刮削**
- 可插拔的元数据源：**TMDB**、**TheTVDB v4**、**bangumi.tv**，每个媒体库可自定义顺序
- 年份相差超过 1 年的候选会被直接排除（否则同名的不同作品会互相匹配）
- 结果写入本地 SQLite；HTTP 响应带缓存，重复扫描不会反复打 API
- 图片下载后缓存到磁盘，界面直接读缓存文件，API Key 不出本机
- 刮错了可以手动指定条目 id（见下节），指定后会被钉住，重新扫描也不会被覆盖回去
- **主来源没匹配上时，退而用次级来源**：所有源都严格匹配失败后，会取排名最高、年份不冲突的
  候选顶上，而不是把条目空着。这种条目在详情页标为「次级来源顶替（未可靠匹配，建议核对）」。
- **三种扫描方式**（媒体库卡片上的刷新按钮）：扫描文件并刮削新条目 / **仅刮削未刮削的条目**
  （跳过文件遍历与容器探测，实测 5 秒完成，完整扫描要十分钟）/ 重新刮削全部
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
- 桌面：**应用内直接播放**，引擎是 libmpv；找不到 libmpv 时退回外置播放器
  （PotPlayer / VLC / mpv / IINA，自动探测安装路径），随时也可以手动选外置
- 桌面 + NVIDIA：**RTX Video Super Resolution** 与 **RTX Video HDR**
  （见「桌面端的应用内播放」）
- 断点续播、已看标记（超过 90% 自动标记已看）、收藏、继续观看 / 接下来 / 最近添加
- Android 上扫描跑在前台服务里，通知栏带进度与「取消」——否则切到别的应用，
  进程一被回收，扫描就静默死了
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

导入走的是平台自己的文件选择器（Android 的 SAF、桌面的 AWT 对话框），而不是「把文件放进数据目录」——**Android 的数据目录在
`filesDir` 下，用户根本放不进东西**，那条路在手机上不成立。

无界面恢复仍可把备份文件放进数据目录，命名为 `import.json`。

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
- 导出是**流式**写出的：3246 个条目 4.1 MB，一次只在内存里持有一页，手机上也不会撑爆。
- 条目 id 是 `SHA-1(库 id + 路径)`，所以只要库定义一起带过去，观看进度就能重新对上。
  只导设置和进度、不导刮削数据也可以，代价是新机器要重新扫描一遍。

## 跨端同步

没有第二台服务器可以商量，所以设备之间通过它们本来就共用的那块存储达成一致：把状态写成
WebDAV 上的一个文件，别的设备读回来合并。

「设置 → 跨端同步」里开关 + 指定路径。同步文件里放的是**服务器设置、媒体库定义、观看进度、
手动指定的刮削条目**，不含刮削结果——那个每台设备扫描一次就有，带上会让每次上传从 1.5 KB 变成 4 MB。
手动指定是例外：它不是刮削出来的，是刮削错了之后人做的判断，重扫一遍不会重新得到它。

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
- **上传前一定先读回来合并**。`PUT` 替换的是整个文件，而这个网关没有条件写（没有 `If-Match`），
  所以直接上传本机状态会把另一台设备写进去的行整片抹掉。读不回来时**不上传**——
  拿一次网络抖动换掉别人的记录不划算；文件读回来了但解析不了才覆盖，那种情况下覆盖就是修复。
- **自动上传和自动拉取都有**。每分钟一个 tick：拉取按上传间隔的一半跑，
  上传的条件是「观看状态指纹变了」且距上次上传超过设定间隔。指纹是 `MAX(updated_at)` 加行数
  一条 SQL，所以写进度的地方不需要额外通知同步模块。只写不读的设备永远学不到别人看了什么，
  所以拉取不能只挂在那个手动按钮上。
- **只写这一个文件**。上传路径由用户指定，除它以外不碰存储上的任何东西。
- **库 id 由路径推导**（`SHA-1` 前 12 位，忽略首尾斜杠与大小写），不是随机的。
  两台设备各自把同一个目录添加成媒体库，必须自己算出同一个 id——item id 是
  `SHA-1(库 id + 路径)`，库 id 不同则每一条观看记录都对不上，同步文件里还会出现两个一模一样的库。

  > **升级注意**：这个规则改之前建的库拿的是随机 id，不会自动变。要让两台设备对上，
  > 需要把库删掉重新添加、重新扫描——现有的观看进度、收藏与手动指定会随之丢失。
  > 想留就先在「设置 → 备份与迁移」里导出一份。
- 凭据不进同步文件。把 WebDAV 密码写到需要该密码才能读的地方没有意义。

## 持续集成

`.github/workflows/build.yml`：推送到 master / main、开 PR 或手动触发时跑三件事。

| Job | 平台 | 产物 |
| --- | --- | --- |
| `test` | ubuntu | `:core:jvmTest`，报告作为 artifact |
| `msi` | windows | `composeApp/build/compose/binaries/main/msi/*.msi` |
| `apk` | ubuntu | `composeApp/build/outputs/apk/debug/*.apk` |

打 `v*` 标签时多跑一个 `release` job，把 MSI 与 APK 传到对应的 GitHub Release。

两个不显然的地方：

- **MSI 需要 WiX Toolset 3**。jpackage 用它生成 MSI，且不接受 WiX 4/5；
  新的 runner 镜像不再预装，所以 workflow 里用 choco 装了 3.11.2。
- **Windows 那个 job 会先拉 libmpv**（`scripts/fetch-libmpv.ps1`），拉到才有内置播放器。
  这一步是 `continue-on-error`：拉不到照样出 MSI，只是那个包退回外置播放器。
  `:composeApp:desktopTest` 也挂在这个 job 上，因为它是唯一已经在配置并构建 composeApp 的 runner。
- **APK 是 debug 版**。没有密钥库的 release 包是未签名的，装不上。要出 release：
  把密钥库 base64 后存进仓库 secret，在 `composeApp/build.gradle.kts` 里加
  `signingConfigs`，再把 job 换成 `assembleRelease`。

## 桌面端的应用内播放

桌面端在应用窗口里直接放片，引擎是 **libmpv**。选它不是因为省事，是因为要的两个
NVIDIA 功能只有这条路走得通。

### 为什么画面是 mpv 自己的原生子窗口

- **RTX Video Super Resolution** 和 **RTX Video HDR** 都活在 D3D11 的视频处理器里
  （`ID3D11VideoContext::VideoProcessorSetStreamExtension` 加两个未公开的 NVIDIA GUID，
  Chromium / Firefox / mpv / VLC 四家实现逐字节一致），终点是一条 D3D11 swapchain。
- HDR 尤其如此：它必须被**呈现**在 PQ / BT.2020 色彩空间里。把帧经由 mpv 的 render API
  交给 Skia 合成，等于拿到像素、丢掉 swapchain，HDR 和 RTX 一起没。

所以 DAView 把一个重量级 AWT `Canvas` 的原生句柄交给 mpv 的 `wid`，mpv 在它下面建
自己的子窗口。直接后果是：**Compose 画的东西盖不到视频上**（`SwingPanel` 永远在
Compose 之上，实验开关 `compose.interop.blending` 在 DirectX 上是「擦掉前一个」）。

> `osc` / `input-default-bindings` / `input-vo-keyboard` 在 libmpv 的内置 profile 里
> 默认是关的（`mpv --show-profile=libmpv` 可以看到），但那只是默认值——脚本本身
> 编在 `libmpv-2.dll` 里，打开就能用。

### 界面在哪一层：全部交给 mpv 的 OSD

「盖不到视频上」意味着 Compose 画的任何东西都只能**挤在画面旁边**，不可能浮在画面之上。
早先的做法是在视频上方常驻一条工具条，音轨 / 字幕列表展开时再把画面往下推一截——
全屏时那条工具条也还在，于是全屏并不是全屏。

现在这一层整个搬到 mpv 自己的 OSD：

| 东西 | 画在哪 | 怎么触发 |
| --- | --- | --- |
| 播放暂停 / 进度条 / 时间 / 音量 | DAView 自己的控件条 | 鼠标一动就出来；空格 / ← / → 走 mpv 的键 |
| 正在播放的片名 | 控件条 + mpv 的 `force-media-title` | 起播时写入 |
| 音轨 / 字幕列表 | mpv 的 OSD（`show-text`） | `#` / `j`，或控件条上的按钮 |
| RTX 超分 / HDR 结果 | 起播 2.5 秒后在 OSD 报一次 | 自动 |

**mpv 自带的 OSC 在这里用不了，所以根本不开。** 实测：嵌在别人窗口里的 mpv
收不到任何鼠标事件——整部钠 `mouse-pos` 一直是 `{x:0,y:0,hover:false}`，连
`input-cursor=yes` 也改不了。于是 OSC 的默认 `auto` 永远等不到那一下鼠标移动；
强行 `osc-visibility always` 把它叫出来，按钮也一样不响应点击。所以传输控件只能
DAView 自己画。

鼠标也因此只有一个地方能抓到：重量级 AWT `Canvas` 上的 `MouseMotionListener`。
Compose 看不到画面上的鼠标（那是原生子窗口），mpv 也看不到，只剩 AWT。
全屏时控件条靠它唤回来，点画面暂停也靠它。

> 坐标要取 `xOnScreen` 而不是组件坐标，否则有一个自己转起来的环：控件条一收起，
> 画面就长高一截，AWT 把“组件在鼠标下面动了”报成鼠标移动，控件条又回来了。

OSD 消息不占布局，所以**轨道列表不会把画面推下去**。控件条则不行：Compose 画的东西
盖不到视频上，只能占布局，所以它一出一进画面就会变一次尺寸。非全屏时它常驻（上面
本来就有标题栏）；全屏时鼠标停下 3 秒后收起，一动就回来。暂停时不收，
否则就是一帧静止画面加上“猜控件条刚才在哪”。

轨道列表因此不是一个可以打开 / 关闭的菜单，而是一条消息：按一次就切到下一条轨道，
并把整张列表连同当前标记重画一遍（`TrackMenu`）。列表超过 8 行时以选中项为中心裁剪，
否则一个季度包里十几个外挂字幕会把屏幕从上到下铺满。

**键都是 mpv 的键，但决定权在 DAView。**`#`（下一条音轨）和 `j`（下一条字幕）是 mpv
用户本来就有的肌肉记忆，DAView 用 `keybind` 把它们指回自己（`script-message` →
客户端消息），于是外挂字幕文件和内封轨道处在同一个循环里、名字是 DAView 起的名字、
选择会被记进会话。`f` 和 `ESC` 同理——嵌在别人窗口里的 mpv 没法自己全屏，这两个键
原本按下去什么都不会发生，现在它们去要求窗口全屏 / 退出全屏。

反过来也读：每 5 秒把 mpv 的 `aid` / `sid` 读回来映射成 DAView 的索引。mpv 自己也会
选轨道，`J` 之类的默认绑定也还在，不读回来的话在 mpv 里换的轨道根本不会被记录，
换台设备续播时就落在没人选过的那条上。

### 这两个功能不叫 DLSS

DLSS 需要渲染管线提供运动矢量和深度，解码出来的视频帧没有这些。NVIDIA 自己也明确说
RTX Video Super Resolution **不使用** DLSS。面向视频的产品名就是
RTX Video Super Resolution 和 RTX Video HDR。

### 实测有效的配置

```
--vo=gpu-next --gpu-api=d3d11 --hwdec=d3d11va
--vf=d3d11vpp=scale=2:scaling-mode=nvidia:nvidia-true-hdr=yes
--target-colorspace-hint=yes
--d3d11-adapter=NVIDIA
```

四条踩过的坑，都写进了代码：

- **`scaling-mode=nvidia` 单独给没有任何效果。** 倍率为 1 且没有格式变化时，滤镜判定
  「不需要过滤」，视频处理器根本不会被创建，驱动扩展也就从没被设置过——没有报错，
  画面也没有区别。所以超分一定带 `scale > 1`（`VideoEnhancement` 里强制夹紧）。
- **`nvidia-true-hdr=yes` 会自己强制处理器存在**，所以只开 HDR 时不需要放大，也不该放大。
- **双显卡笔记本必须指定 `--d3d11-adapter=NVIDIA`。** 实测这台机器（RTX 4060 Laptop +
  Radeon 780M）不指定时 mpv 落在 780M 上，NVIDIA 扩展只会失败。设置页里可以自己选显卡
  （「自动」在开着 RTX 时就等于要求 NVIDIA）；列表是**问出来的**——`--d3d11-adapter=help`
  的输出拿不到，但设置这个选项时 mpv 会当场校验名字，所以只列出这台机器真的接受的。
  匹配是描述的前缀、不区分大小写，取第一个命中的。
- **要用 mpv 的 git master 构建，不能用编号版本。** RTX Video HDR 的两个关键提交
  （输出格式改 `X2BGR10`、按 HDR10 重新打标）在 0.41.0 之后才进 master，任何编号版本上
  这个开关都只是设了扩展、什么也不做。shinchiro 的 Windows 构建跟 master，可以直接用。

成功与否只能读 mpv 自己的日志（`d3d11vpp` 没有可查询的属性，超分那一路连能力探测都没有，
只看 `HRESULT`）。播放页上那两个状态标签就是这么来的：

```
NVIDIA RTX Super Resolution enabled.
NVIDIA RTX Video HDR enabled.
```

排障时加 `-Ddaview.mpv.log=1`，mpv 的日志会原样打到 stderr。

### libmpv 从哪来

Windows 包内置 `libmpv-2.dll`（约 115 MB），但它不在 git 里：

```powershell
./scripts/fetch-libmpv.ps1        # 拉 shinchiro 最新构建，放进 composeApp/nativeResources/windows/
```

CI 在打 MSI 前跑这一步；拉不到也不会让打包失败，只是那个包没有内置播放器。
运行时的查找顺序是：设置里手动指定的路径 → 安装目录的 `app/resources` →
源码树的 `composeApp/nativeResources/<os>/` → 系统安装位置 → 系统库搜索路径。
Linux / macOS 不内置，用系统装的 libmpv（`libmpv.so.2` / `libmpv.2.dylib`）；
这两个平台上 RTX 那两项不存在，其余功能一样。

**一个 DAView 管不到的前提**：这两项还要求用户在 NVIDIA 控制面板 / NVIDIA App 的
「调整视频图像设置」里把对应开关打开。没打开时驱动会照常接受调用并返回成功，
只是什么都不做——所以设置页把它们写成「请求」，播放页只报告 mpv 说了什么。

**许可**：shinchiro 的构建是 GPL（其 FFmpeg 用 `--enable-gpl --enable-version3` 编译，
实际是 GPLv3），内置它意味着分发时要承担相应义务。`d3d11vpp` 滤镜本身是 LGPLv2.1+，
需要 LGPL 的话得自己用 `-Dgpl=false` 配 LGPL 的 FFmpeg 重新构建 libmpv。

## 外置播放器的进度同步是怎么做的

PotPlayer 不会向任何人汇报播放位置——实测过：

- `%APPDATA%\PotPlayerMini64\Playlist\PotPlayerMini64.dpl` 里确实有 `playname=` 与 `playtime=`，
  但**用 URL 播放时不写 `playtime`**；自建带 `saveplaypos=1` 的播放列表也不会被回写。
- 注册表 `HKCU\Software\DAUM\PotPlayerMini64` 下只有加密的 `MInfo1/MInfo2`，不可用。

所以 DAView 不去猜 PotPlayer 的内部状态，而是**让播放地址指回自己**——这也是整个应用
里唯一还需要监听端口的地方，于是那个端口被压缩成了它该有的样子：

1. 客户端调 `MediaFacade.startPlayback`，`PlaybackPipe` 在 `127.0.0.1` 上临时开一个
   端口（端口号由系统分配），返回 `http://127.0.0.1:<port>/p/<会话 id>/<条目 id>/<文件名>`
2. 播放器向这个地址请求字节。于是每一次 `Range` 都看得见：
   - 有 Matroska `Cues` 时，字节偏移可以精确换算成时间戳
   - 没有索引时按「字节比例 × 时长」线性估算
3. 两次请求之间，位置按墙上时钟推进（正常 1 倍速播放时是准的）
4. 播放器停止读取超过 5 分钟，会话结束并落库；最后一个会话结束时，管道自己关掉

它不是一个服务端：只认路径里的会话 id，没有鉴权、没有 CORS、没有 JSON、不绑局域网，
播完就没有任何东西在监听。

界面会明确标注进度来源（`播放器上报` / `索引推算` / `时钟推算`），不会假装它是精确值。

PotPlayer 的续播用命令行 `/seek=hh:mm:ss`（实测有效），VLC 用 `--start-time`，mpv 用 `--start`。

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
这两条行为有单元测试覆盖：`core/src/jvmTest/.../PlaybackServiceTest.kt`，管道本身则由 `PlaybackPipeTest` 覆盖。

**已知不足**：外置播放器暂停时这边无从得知，时钟会继续走。
代理模式下会用"已下载字节对应的时间"作上限压制这个误差，但缓冲区通常领先一两分钟，
所以暂停很久后的进度会偏大。

## 快速开始

需要 JDK 17+（推荐 Android Studio 自带的 JBR 21）；Android 构建另需 SDK Platform 37
（Coil 3.6 的 AAR 要求 `compileSdk >= 37`）。

```bash
./gradlew :composeApp:run                        # 桌面端
./gradlew :composeApp:assembleDebug              # Android APK
./gradlew :composeApp:packageMsi                 # 桌面安装包（Windows）
```

安装包里带的版本号不是文件名上那个人看的标签，而是 **`MAJOR.MINOR.PATCH`**，
默认 `1.0.<提交数>`，CI 传 `"-PdaviewPackageVersion=..."` 覆盖（PowerShell 下必须带引号，否则它会在第一个点上
把参数切开）（主次号取最新 tag，
修订号取总提交数）。**它必须逐次变大**：jpackage 每次都生成新的 ProductCode，
而 ProductVersion 与已装的一样时，Windows Installer 既不当升级也不当降级，直接报
1638「已经安装了该产品的另一个版本」，只能先手动卸载。之前它永远是 `1.0.0`，
所以每一次更新都是这个下场。

`upgradeUuid` 也写死在 `build.gradle.kts` 里。jpackage 本来就会按包名推出一个稳定的
（name-based UUID），写死的就是它对 `DAView` 推出的那个值——不是为了改变行为，
是为了以后改包名时不会惄无声息地把所有已装副本变成孤儿。

没有要先启动的服务端，也没有令牌要填：应用一开就是首页。WebDAV 地址与刮削 API Key
在「设置」里填，或者用环境变量（适合无界面的容器部署）：

```bash
DAVIEW_WEBDAV_URL=https://webdav.example.com/webdav
DAVIEW_WEBDAV_USER=...
DAVIEW_WEBDAV_PASS=...
DAVIEW_TMDB_KEY=...
DAVIEW_TVDB_KEY=...
DAVIEW_BANGUMI_TOKEN=...
DAVIEW_DATA=./run           # 数据目录（config.json + daview.db + 图片缓存）
```

> 凭据只写在数据目录下的 `config.json`，该目录已在 `.gitignore` 中。

## 目录结构

```
shared/      KMP：DTO（jvm / android）
core/        KMP（jvm / android）：WebDAV、扫描、命名解析、刮削、SQLite、容器探测、
             图片缓存、播放会话、跨端同步。没有 HTTP 服务端，UI 直接调它
composeApp/  Compose Multiplatform 客户端（androidMain / desktopMain）
             src/app 同时注册进两个 JVM target——:core 的源码也是这么编的，
             所以 UI 能直接看见它，不必为「只有一个实现的接口」再加一层
docs/        架构说明与实测记录

共享源码放在 `src/core`（客户端是 `src/app`），**不能**放 `src/main`——传统 Android DSL
下那也是 AGP 自己的 main 源集，重复注册会让 `compileDebugKotlinAndroid` 一直报
UP-TO-DATE，APK 里带的是旧代码。

`core` 的两个 target 编译同一份源码。唯一真正有平台差异的是 SQL 驱动
（JDBC / Android SQLite），它是注入进 `ServerContext` 的，所以既不需要 expect/actual，
也不需要中间 source set。
```

## 已知限制

- **外置播放器的进度是推算值**：正常播放时误差在秒级（有 Matroska 索引时），但看不到暂停，
  长时间暂停后的进度会偏大。够用来续播，不能当精确计时。
- **直接关窗口会丢最多 5 秒进度**：内置播放器每 5 秒上报一次位置，而窗口的关闭按钮走的是
  `exitApplication()` 后紧跟 `exitProcess(0)`（`Main.kt`），Compose 的 `onDispose` 来不及跑，
  最后一次位置写不进去，mpv 也是被进程终止而不是正常退出。用播放页上的「结束播放」或返回键
  没有这个问题。这个退出顺序是既有行为，改它要先确认没有非守护线程会把进程挂住。
- **桌面切轨是「切到下一条」，不是「从列表里挑」**：列表画在 mpv 的 OSD 上，而 OSD 只是一张图，
  收不到鼠标点击。所以选轨道靠重复按 `#` / `j`（或重复点控件条上的按钮），轨道多的
  时候要按好几下。要改成真菜单，就得把它画成 Compose 控件，而那就会再次挤画面。
- **`keybind` 与 `show-text` 都可能被 mpv 拒绝而毫无动静**：绑不上的键和没人按的键从外面看
  一模一样。所以每个键都试两种写法（`SHARP` / `#`、`ESC` / `ESCAPE`），失败的会在起播 2.5 秒后
  用一条 OSD 消息报出来。要是那条消息本身也没出现，说明 OSD 这一层是死的，
  加 `-Ddaview.mpv.log=1` 看 mpv 的原始日志。
- **「关闭字幕」这个选择存不下来**：进度上报里 `subtitleStreamIndex = null` 在服务端
  （`PlaybackService` 的 `subtitle?.let`）和 SQL（`saveProgress` 的 `COALESCE`）两处都被当作
  「没有提供」而忽略，于是退出时写回的仍是开播时那个索引，下次播放字幕又自动打开。
  协议上缺一个能表达「显式为空」的形状，两处都要改，属于跨端协议变更，本次没有动。
- **RTX 那两项能不能生效，应用无从判断**：`d3d11vpp` 不暴露可查询的属性，超分那一路连能力探测
  都没有，只看 `HRESULT`；而且用户还得在 NVIDIA 控制面板里打开对应开关，没打开时驱动照样返回成功。
  所以界面只报告 mpv 说了什么，不承诺画面真的变了。
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
./gradlew :core:jvmTest :composeApp:desktopTest
```

`:core:jvmTest` 覆盖命名解析（季 / 集 / 双语字幕 / 噪音过滤）、外置播放器的进度推算
（末尾索引读取不得跳到片尾、静置后才锚定、下载进度作为上限）、刮削匹配的取舍、
手动指定（钉住之后不得再搜索、换条目要清掉上一个匹配的字段、id 填错不能动已有数据），
以及特典季的排序（`Season 00` 不得抢在第 1 季前面，但「没有季」不等于第 0 季）。

`:composeApp:desktopTest` 覆盖内置播放器里两块纯逻辑：RTX 滤镜串的拼装
（倍率 1 会让超分静默失效，所以被夹在 2–4；只开 HDR 时不放大）与 mpv 日志行的判读，
以及 DAView 的流索引到 mpv 轨道号的映射（外挂字幕排在内封之后、没有季的视频轨不算进去）。

需要真机与真 GPU 的部分不在自动测试里：libmpv 的加载与 `--wid` 嵌入、RTX 是否真的启用、
HDR 输出的色彩空间。这些是在一台 RTX 4060 + Radeon 780M 的机器上手工验的，
记录在 `docs/ARCHITECTURE.md` 第 5 节。
