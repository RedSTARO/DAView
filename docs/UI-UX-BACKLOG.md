# UI / UX 问题总表

把 [UX-REVIEW.md](UX-REVIEW.md)（69 条，功能与流程）和 [UI-REVIEW.md](UI-REVIEW.md)（75 条，设计与交互）合成一张可勾选的清单，共 **144** 条。
每条的完整场景、代码推导与对照写在对应的原报告里，这里只留标题、证据位置和改法，用于跟踪。

| | 阻断 | 严重 | 打磨 | 合计 |
|---|---|---|---|---|
| UX | 5 | 35 | 29 | 69 |
| UI | 4 | 39 | 32 | 75 |
| **合计** | **9** | **74** | **61** | **144** |

---

## 功能与流程（UX-REVIEW）

### 上手与配置：建库、扫描规则、刮削设置

- [ ] **UX-01**（阻断）电影库里一个目录只留体积最大的那个视频，其余静默丢弃
      - 位置：`core/src/core/com/daview/server/library/Scanner.kt:95-102`
      - 改法：先别做版本下拉：把同目录被丢弃的视频作为独立条目建出来（名字带文件名区分），并把「本目录有 N 个视频，只收录了 1 个」写进 Scanner.Result.warnings 让扫描完成时能点开看。之后再按 `- cd1/- part1` 后缀识别分卷。
- [ ] **UX-02**（严重）扫描没有任何排除规则，@eaDir / #recycle / .stfolder 会被建成空条目、配上 FALLBACK 海报、还删不掉
      - 位置：`core/src/core/com/daview/server/library/Scanner.kt:137-156`
      - 改法：两步：默认跳过以 @、#、. 开头的目录以及 @eaDir/#recycle/#snapshot/.stfolder/lost+found（几行）；剧集库只在目录下真的找到视频或季目录时才建 SERIES。之后给 LibraryDto 加 excludePaths 并在建库对话框放一个多行输入框。
- [ ] **UX-03**（严重）一个媒体库只能绑一个目录：电影分散在几个目录就只能建成几个互不相干的库
      - 位置：`shared/src/commonMain/kotlin/com/daview/shared/model/Models.kt:66`
      - 改法：把 LibraryDto.path 扩成 paths: List<String>（保留 path 兼容读），Scanner.scan 对每个 path 各走一遍 dav.list 后合并 records；libraryId 改成按所有路径排序拼接后推导。UI 上在路径行后加「+ 再添加一个目录」。
- [ ] **UX-04**（严重）完全不读目录里现成的 .nfo，从 Jellyfin/Emby/tinyMediaManager 搬过来的整套元数据全部作废
      - 位置：`core/src/core/com/daview/server/scraper/MetadataService.kt:37-44`
      - 改法：在 MetadataProvider 里加一档「本地 NFO」并作为所有库类型 defaultOrder 的第一位：Scanner 收集目录里的 *.nfo，用 4MB 上限的 dav.readFully 读进来解析 title/originaltitle/plot/year/premiered/genre/rating/uniqueid，命中就写 ScrapeStatus.MATCHED 并跳过网络刮削。
- [ ] **UX-05**（严重）设置里的「元数据语言」是个死设置，填什么都没用
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:684`
      - 改法：把 ScraperSection 的「元数据语言」输入框改成写当前库的 language（复用已有的 MediaFacade.updateLibrary），或在建库对话框和库卡片上各加一个语言选择；同时把自由文本换成下拉（zh-CN / zh-TW / ja-JP / en-US），现在填错一个字母 TMDB 直接返回英文也不报错。
- [ ] **UX-06**（打磨）S01E01-E02 双集文件只算一集：解析器已解出 endEpisode 却无人读取
      - 位置：`core/src/core/com/daview/server/library/Scanner.kt:299-300`
      - 改法：在 episodeRecord 里读上 endEpisode，条目名做成「第 1-2 集」并在 DTO 上带 endIndexNumber；哪怕只做到名字显示成 1-2，也能消除「文件扫漏了」的误判。进一步再把区间集数算进 playedEpisodeCount。
- [ ] **UX-07**（打磨）建库时的「其他」类型与电影库完全等价，选它扫出 0 项时空态还在劝你「去扫描一次」
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:671-677`
      - 改法：把 OTHER 从建库对话框里去掉（显式列三项），或改名为「其他视频（不刮削）」并把 defaultOrder 设为 NONE；扫描结束时若 itemCount == 0，在进度条上写「未发现视频文件」而不是只显示「完成」。

### 找片（一）：首页与发现

- [ ] **UX-08**（严重）「接下来」会推荐你还没看完的那一集的下一集，同一部剧同时占据两行
      - 位置：`core/src/core/com/daview/server/db/Repository.kt:343-366`
      - 改法：两选一：把 nextUp 候选条件从 `position_ms = 0` 改为仅 `played = 0`（让 resumable 的那一集本身作为 next up），再在 HomeScreen 对 resume 已出现的 seriesId 去重；或保持现有语义但在 WHERE 里加 `AND NOT EXISTS (该 series 下存在 position_ms>0 AND played=0 的集)`。
- [ ] **UX-09**（严重）「接下来」按集号排序而不是「最近看的剧优先」，刚追的剧排到行尾甚至被 20 条上限截掉
      - 位置：`core/src/core/com/daview/server/db/Repository.kt:365`
      - 改法：在 nextUp 的 SELECT 里带上该 series 的最近观看时间（子查询 MAX(ue.last_played_at) WHERE e.series_id = i.series_id），改成 `ORDER BY 该值 DESC, i.sort_name`。这一改动同时让 20 条上限变得可接受——被截掉的一定是最久没碰过的剧。
- [ ] **UX-10**（严重）「最近添加」过滤掉全部分集，剧集库的新增在这一行永远不出现；24 条还跨库混排
      - 位置：`core/src/core/com/daview/server/db/Repository.kt:370-374`
      - 改法：latest 改成 `kind IN ('MOVIE','SERIES','EPISODE')`，对 EPISODE 按 `GROUP BY COALESCE(i.series_id, i.id)` 取 MAX(date_created) 去重；同时把首页这一行拆成每库一行，HomeScreen.kt:107-113 已经是这个模式了。
- [ ] **UX-11**（打磨）「XX · 未观看」是固定的字母序前 24 条、永不变化，首页没有随机/推荐/类型这类帮你挑片的行
      - 位置：`core/src/core/com/daview/server/db/Repository.kt:387-403`
      - 改法：unwatched 的 ORDER BY 换成按天变化的伪随机（对 `i.id || strftime('%j','now')` 做哈希排序），行标题改成「随便看看」；LibraryScreen 的排序按钮旁加两个 ToggleButton「未观看」「收藏」，Query 里 favorite 已现成，未观看复用 unwatched 那段 NOT EXISTS。
- [ ] **UX-12**（打磨）首页版块顺序与显隐不可配置，顶部大图必然与「继续观看」第一张卡重复且关不掉
      - 位置：`composeApp/src/app/com/daview/app/ui/HomeScreen.kt:53`
      - 改法：SettingsStore 里存一个版块顺序+开关的字符串列表（如 "hero,resume,nextup,latest,unwatched"），HomeScreen 按列表 forEach 渲染，设置页给一组勾选框加上下移按钮。不需要动 core。
- [ ] **UX-13**（打磨）没有合集、没有播放列表、也没有相似推荐：看完一部就断路了
      - 位置：`shared/src/commonMain/kotlin/com/daview/shared/model/Models.kt:107-163`
      - 改法：按性价比先做相似推荐：TMDB 的 /movie/{id}/similar 或 /recommendations 只是多一个请求，结果按 provider id 在本地库里过滤一遍，命中的排成一行放在详情页底部。合集和播放列表需要新表和新导航，可以往后放。

### 找片（二）：媒体库浏览、筛选与搜索

- [ ] **UX-14**（阻断）媒体库一次最多装 500 条且没有分页，第 501 部之后的片子在浏览路径上根本不存在
      - 位置：`composeApp/src/app/com/daview/app/data/AppState.kt:180`
      - 改法：libraryItems 改成可追加的列表，记住 page.total 与当前 offset；LibraryScreen 用 rememberLazyGridState()，监听最后可见项接近末尾时 offset += 500 再取一页追加。页头改成显示 page.total。MediaFacade.kt:167 的 500 可以保留为单页大小。
- [ ] **UX-15**（严重）没有库内搜索，全局搜索写死 60 条、按名字排序、无声截断也不能翻页
      - 位置：`composeApp/src/app/com/daview/app/data/AppState.kt:223-226`
      - 改法：库页加一个搜索框，把输入透传给已有的 `items(libraryId = 当前库, search = 关键词)`，一行参数的事；全局搜索保留 page.total，在网格底部渲染「共 N 条，已显示 60 条」并给「显示更多」（offset += 60），再加一个类型筛选条复用已有的 kind 参数。
- [ ] **UX-16**（严重）媒体库页没有任何筛选，Jellyfin 的整个筛选抽屉在这里是空白；详情页的类型标签也点不动
      - 位置：`composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:49-61`
      - 改法：分两步。第一步几乎零成本：Query 补 `played: Boolean?`（COALESCE(u.played,0) = ?）和 yearFrom/yearTo，UI 加一个筛选按钮开抽屉，先只放「未观看 / 收藏 / 年代区间」；DetailScreen 的类型换成可点 Chip 带 genre 跳回库页。第二步再把 genres 拆成 item_genres 关联表、给 media_streams 抽出 height 和 subtitle_langs 冗余列。
- [ ] **UX-17**（严重）收藏是个死胡同：后端查询早就写好了，前端一个读取点都没有，卡片上也没有收藏标记
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:148-165`
      - 改法：两处二选一即可救活：refreshHome 里加一行 `favorites = library.items(links, favorite = true, limit = 24).items` 并在 HomeScreen 加一条 MediaRow；或在筛选抽屉里放一个「仅收藏」开关让 loadLibrary 透传。顺带在 PosterCard 上收藏时画个小心形。
- [ ] **UX-18**（严重）从详情页返回媒体库，整个网格重新查库、滚动位置回到最顶上
      - 位置：`composeApp/src/app/com/daview/app/data/AppState.kt:101-111`
      - 改法：两处：把 LazyGridState 提到 AppState 或用 rememberSaveable；onEnter 里判断 libraryItems 已经是这个 libraryId 的数据就不重新加载（AppState 现在连当前加载的是哪个库都没记）。
- [ ] **UX-19**（打磨）只有海报网格一种视图，既不能切列表/详情视图，也不能调网格密度
      - 位置：`composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:73-83`
      - 改法：先做最有价值的一半：加一个 grid/list 切换，list 分支复用现有数据渲染成一行一条（海报缩略图 + 完整标题 + 年份 + 已看 N/M 集）。网格密度可以后置，或简单给 120/150/200dp 三档。
- [ ] **UX-20**（打磨）排序方向写死，想反着排（片名 Z→A、年份从老到新）只能自己滑到列表末尾
      - 位置：`core/src/core/com/daview/server/db/Repository.kt:291-299`
      - 改法：Query 加 `descending: Boolean = false`，order 分支只写字段名，方向在拼 SQL 时统一加后缀（NULLS LAST 要跟着方向走），UI 在按钮排末尾加一个箭头 IconButton。顺带：AppState.kt:75 的 librarySort 只是内存里的 mutableStateOf，关掉 App 就丢，建议落到 SettingsStore 并按 libraryId 分键。
- [ ] **UX-21**（打磨）手机上排序那一行放不下，最后一个「最近播放」被压成看不清的窄条
      - 位置：`composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:49-51`
      - 改法：给这个 Row 加 `Modifier.horizontalScroll(...)`（一行），或换成「排序」下拉按钮 + DropdownMenu（顺便把升降序开关塞进去），后者可与筛选抽屉做成同一个入口。
- [ ] **UX-22**（打磨）大库里没有字母跳转，桌面端连滚动条都没加
      - 位置：`composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:73-79`
      - 改法：依赖分页先解决全量加载。之后给 LazyVerticalGrid 传 rememberLazyGridState，右侧加一条字母索引（中文库按 sort_name 拼音首字母分段），点击 scrollToItem 到该段第一项，不需要改任何 SQL；桌面端顺手补一个 VerticalScrollbar。
- [ ] **UX-23**（打磨）演职人员卡片不可点击，也没有按人物检索的入口
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:575-621`
      - 改法：最小可行：Query 加 person 参数用 `people LIKE '%"name":"张三"%'`（或建 item_people 关联表更干净），PeopleRow 的名字改成 LinkText，点进去复用现有的搜索结果网格，标题写「张三 · 出演」，不必先做完整人物页。

### 看片：播放链路与播放器

- [ ] **UX-24**（阻断）关掉 DAView 窗口，正在外置播放器里看的片子会立刻断流，没有任何提示
      - 位置：`composeApp/src/desktopMain/kotlin/com/daview/app/Main.kt:20-23`
      - 改法：onCloseRequest 里先问 PlaybackService.activeSessions() 有没有外部会话：有就弹「还有 X 正在播放，关闭会中断」的确认，或最小化到托盘而不是退出。至少把标题栏改成「DAView（正在播放：xxx）」。顺带把 trackExternalPlayers 开关放进设置页。
- [ ] **UX-25**（阻断）外置播放器暂停超过 5 分钟，会话被回收、本地管道被关掉，之后一拖进度条就断流、后半段进度全丢
      - 位置：`core/src/core/com/daview/server/media/PlaybackService.kt:281-285`
      - 改法：桌面端 followExternalSession 每轮在进程存活时给会话打一次 keepalive（facade 加个只刷 lastActivity 的方法），让空闲超时只对真正观察不到进程的端生效；另外把 externalSessionIdleTimeoutSec 放进设置页。
- [ ] **UX-26**（阻断）Android 播放中屏幕会按系统超时自动熄灭
      - 位置：`composeApp/src/androidMain/kotlin/com/daview/app/ui/InternalPlayer.android.kt:134-143`
      - 改法：在 InternalPlayer 的 AndroidView factory 里加 `keepScreenOn = true`，或更精确地在 Player.Listener 的 onIsPlayingChanged 里同步 `playerView.keepScreenOn = isPlaying`（暂停时放掉避免白烧电）。
- [ ] **UX-27**（严重）播完一集就到头了：两端都不自动播下一集，系列页也没有「播放全部」
      - 位置：`composeApp/src/app/com/daview/app/data/PlaybackController.kt:141-163`
      - 改法：在 PlaybackInfoDto 里加 nextItemId（复用 nextEpisodeUnder 的排序逻辑）：Android 监听 STATE_ENDED 后直接对它调 playInternalOrExternal；桌面 followExternalSession 判断退出时若进度过 90% 阈值就弹「5 秒后播放 第 3 集 / 取消」。系列页的播放按钮旁再加一条「从这里连播」。
- [ ] **UX-28**（严重）Android 播放器不是独立全屏形态：导航骨架常驻、不自动横屏、也没有方向锁
      - 位置：`composeApp/src/app/com/daview/app/App.kt:103-117`
      - 改法：在 App() 的 Box 里把 `state.current is Screen.Player` 单独提出来：命中时直接渲染 PlayerScreen 铺满，不走 TopRow/BottomNavigation/SideNavigation，同时用 WindowInsetsControllerCompat 隐藏系统栏；进播放页设 requestedOrientation = SENSOR_LANDSCAPE（或按 videoSize 宽高比决定），退出恢复 UNSPECIFIED，控制条加一个锁定按钮。这是一处改动同时解决两件事。
- [ ] **UX-29**（严重）没有 MediaSession：切后台音频还在响却没有任何控制入口，也没有画中画和音频焦点
      - 位置：`composeApp/build.gradle.kts:51`
      - 改法：用已经在依赖里的 media3-session 建一个 MediaSessionService，把现有 ExoPlayer 实例挂上去（白拿通知控制、锁屏控制、媒体按键、音频焦点）；PiP 单独在 Manifest 加 supportsPictureInPicture 并在 onUserLeaveHint 里 enterPictureInPictureMode。
- [ ] **UX-30**（严重）系统返回键/返回手势不返回上一页，直接退出整个 App
      - 位置：`composeApp/src/androidMain/kotlin/com/daview/app/MainActivity.kt:15-25`
      - 改法：在 App() 里加 `BackHandler(enabled = state.backStack.size > 1) { state.back() }`（androidx.activity.compose.BackHandler 在 Compose Multiplatform 的 androidMain 可直接用）；播放器页面再单独加一个优先级更高的 BackHandler，先 stop 播放再 back。
- [ ] **UX-31**（严重）完全没有离线下载，出门断网这个 App 就是个空壳
      - 位置：`composeApp/src/app/com/daview/app/ui/ItemContextMenu.kt:101-184`
      - 改法：最小可行版：详情页/长按菜单加一个「下载」，用 media3 的 DownloadManager + DownloadService（前台服务可以抄 ScanForegroundService 的现成模式），文件落在 filesDir/downloads，播放时优先命中本地文件；先不做画质选择，直接原文件下载即可，这个 App 本来就不转码。
- [ ] **UX-32**（严重）外挂字幕只认「同一目录且严格同名」，Subs/ 子目录不看，`.简日双语.ass` 也会被丢，且没有手动挂载入口、没有延迟与字号
      - 位置：`core/src/core/com/daview/server/library/Scanner.kt:332-337`
      - 改法：按代价排序：(1) 把视频所在目录下名为 subs/subtitles/字幕 的子目录也列一遍并入候选，几行改动救掉一大类片源；(2) 同名匹配失败时退化为「本目录只有一个视频且有 N 个字幕 → 全挂上」；(3) 详情页加一个「挂载字幕文件」入口；(4) 播放器加字幕延迟滑块与字号。
- [ ] **UX-33**（严重）音轨和内嵌字幕轨的选择传不给外置播放器，播放器里选的也回不来
      - 位置：`composeApp/src/commonMain/kotlin/com/daview/app/platform/Platform.kt:12-18`
      - 改法：给 ExternalPlayRequest 加 audioIndex/subtitleIndex，buildCommand 里按播放器映射（mpv --aid/--sid、VLC --audio-track/--sub-track）；详情页把只读的「音轨/字幕」行改成播放前可选的下拉，复用已经存在的 userData.audioStreamIndex 持久化。
- [ ] **UX-34**（严重）默认用哪个播放器不能选也不会记住，永远按写死的顺序挑第一个
      - 位置：`composeApp/src/app/com/daview/app/ui/HomeScreen.kt:266-269`
      - 改法：设置页加「默认外部播放器」下拉（值存 SettingsStore），playInternalOrExternal 先读它、读不到再退回探测顺序；顺便把 externalPlayers 从 val 改成每次打开菜单时刷新，省得装完播放器还要重启。
- [ ] **UX-35**（严重）外部播放面板的说明文字描述的是一个已经不存在的架构，而且承诺了做不到的事
      - 位置：`composeApp/src/app/com/daview/app/ui/PlayerScreen.kt:136-141`
      - 改法：改成说实话：「播放地址是本机临时地址（127.0.0.1），只在本次播放期间有效，其它设备用不了」，以及「进度先记在本机，开启跨端同步后按设定间隔写到 WebDAV」。「复制播放地址」按钮旁注明用途（调试／手动喂给别的本机播放器）。
- [ ] **UX-39**（严重）外置播放器一启动，界面就什么都不显示了——整块外部播放面板是不可达的死代码
      - 位置：`composeApp/src/app/com/daview/app/data/PlaybackController.kt:69,78`
      - 改法：playExternal 成功后也 navigate(Screen.Player(item.id))，PlayerScreen 的分支判断从 PlatformInfo.hasInternalPlayer 改成「这次会话用的是内置还是外置」（PlaybackController 已有 externalPlayerLabel 可判）。一处改动就能把写好的整块面板接回来。
- [ ] **UX-40**（严重）播放失败只有详情页看得到，从首页、媒体库、搜索页点播放失败是彻底静默的；启动过程也没有任何等待反馈
      - 位置：`composeApp/src/app/com/daview/app/data/PlaybackController.kt:40-41`
      - 改法：把 error 接到 AppState.toast（已有全局 snackbar，App.kt:118-123），任何页面都能看到；starting 为真时把播放按钮换成 loading 态。
- [ ] **UX-36**（打磨）没有「从头播放」：看过一半的片，播放键永远是「继续」
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:342`
      - 改法：PlaybackStartRequest 加一个可选 startPositionMs（null 表示沿用记录），MediaFacade.kt:342 改成 `request.startPositionMs ?: userData.positionMs`；UI 上在「继续」按钮右边加一个小的「从头」入口或放进右键菜单，两处都只是多传一个 0。
- [ ] **UX-37**（打磨）Android 播放器没有任何手势，自绘的音轨/字幕按钮和标题常驻挡画面，还与 media3 自带字幕按钮形成两套入口
      - 位置：`composeApp/src/androidMain/kotlin/com/daview/app/ui/InternalPlayer.android.kt:145-148`
      - 改法：把音轨/字幕菜单挪进 PlayerView 的 overlayFrameLayout 或跟随 setControllerVisibilityListener 一起显隐，并关掉重复的 setShowSubtitleButton；手势先补最常用的两条——双击左右各 ±10 秒、右半屏竖滑调音量，用 pointerInput 包一层就够。
- [ ] **UX-38**（打磨）mkv 里的章节从不读取，Android 内置播放器没有章节导航
      - 位置：`core/src/core/com/daview/server/library/MkvProbe.kt:54-83`
      - 改法：MkvProbe 顺手解析 Chapters（复用已有的 SeekHead 定位），详情页与播放器给章节跳转。若想要「跳过片头」的廉价版：库级设置一个片头秒数（如 90），播放开始后前 90 秒在播放器上显示一个「跳过片头」按钮，对同一季固定 OP 的番剧准确率极高。
- [ ] **UX-41**（打磨）桌面播放器菜单里那条「自定义播放器」永远点不动：写入它的函数全仓库没有调用点
      - 位置：`composeApp/src/desktopMain/kotlin/com/daview/app/platform/Platform.desktop.kt:71,78`
      - 改法：要么在设置页加一个「指定播放器程序…」的文件选择（setCustomPlayerPath 已经写好，缺的只是入口，可以照抄新加的「指定 libmpv…」按钮），要么在没有路径时干脆不把这条塞进列表。

### 状态、进度与跨端同步

- [ ] **UX-42**（严重）「继续观看」里的条目移不掉，唯一的办法是撒谎说自己看完了，还会顺带清空进度
      - 位置：`composeApp/src/app/com/daview/app/ui/ItemContextMenu.kt:108-184`
      - 改法：user_data 加一列 `hidden_from_resume INTEGER DEFAULT 0`，resume 查询加 `AND COALESCE(u.hidden_from_resume,0)=0`，右键菜单在该 item 出现在 resume 时多一条「从继续观看中移除」，下次真正播放时清零。
- [ ] **UX-43**（严重）扫描结束后停在首页不会自动刷新，后台同步拉回来的进度也不刷新，且没有下拉刷新
      - 位置：`composeApp/src/app/com/daview/app/data/AppState.kt:289-300`
      - 改法：两处小改：pollScanStatus 结束时把第 294 行的 refreshLibraries() 换成 refreshLibraries() + refreshHome()；SyncService.pull() 成功且有行写入时通过 ServerContext 抛一个回调，AppState 订阅后在 current is Screen.Home 时 refreshHome()。再给 HomeScreen 包一层 PullToRefreshBox 兜底。
- [ ] **UX-44**（严重）停止播放不触发上传，进度只等进程内定时器——划掉 App 换设备最坏会看到十分钟前的旧进度
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:386-392`
      - 改法：在 stopPlayback 成功之后（或 Activity 的 ON_STOP）触发一次立即上传，绕开 minIntervalMinutes 节流；再用 WorkManager 排一个 OneTimeWorkRequest（约束 NETWORK_CONNECTED）兜底，这样即使进程当场被杀，系统也会稍后替你补上。
- [ ] **UX-45**（严重）同步文件会静默覆写本机的存储地址与播放设置，两台设备用不同地址访问同一份存储时互相打架
      - 位置：`core/src/core/com/daview/server/api/Backup.kt:184-185`
      - 改法：两条：applyBackup 里把 storage.url/username/password 与本机播放设置从自动应用里摘出去，同步只合并 libraries/userData/pins；手动导入前先解析出 containsSecrets 与将被覆盖的字段，弹一个确认框。
- [ ] **UX-46**（打磨）同步其实是定时自动跑的，界面从头到尾没说，也看不到上次跑的时间和间隔
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:366-370`
      - 改法：说明文字补一句自动频率；状态行把「已上传」换成「上次上传 <时间>」「上次合并 <时间>」；给 minIntervalMinutes 加一个输入框或几个预设档（后端已完全支持，只差 UI）。

### 库维护：元数据编辑与条目管理

- [ ] **UX-47**（严重）完全没有「编辑元数据」：刮错一个字段，只能整条换刮削源或者一直忍着
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:404-424`
      - 改法：先做最小集合：MediaFacade 加 updateItem(id, name/sortName/overview/year/genres) 写入 items 表并把 scrape_status 标为 MANUAL；DetailScreen 的图标行加一个「编辑」打开字段表单。字段锁可以留到后面，先保证「改过的字段重新刮削不被覆盖」（复用现有的 lockedProvider 思路）。
- [ ] **UX-48**（严重）封面/背景图完全没法换，扫描也不认目录里的 poster.jpg / fanart.jpg
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:452-461`
      - 改法：两步都很轻：(1) Scanner 在每个电影/剧集目录里顺手认 poster.jpg|folder.jpg|cover.jpg 和 fanart.jpg|backdrop.jpg，写进 posterUrl/backdropUrl 并走已有的 StreamService 直链（顺带解决 ImageCache 不带认证的问题）；(2) 详情页海报上加「更换封面」，允许贴 URL 或选本地文件后经 WebDavClient.put 上传到条目目录。
- [ ] **UX-49**（严重）开启同步后删除媒体库不生效：最多 60 秒后被云端文件原样恢复成一个空库，而本机刮削结果已被真删
      - 位置：`core/src/core/com/daview/server/api/Backup.kt:190`
      - 改法：两条路都很小：给 libraries 表加一个 `local_only` 标志，标了就不进同步载荷也不接受来自同步文件的恢复；或在配置里存一份「本设备排除的库 id」，applyBackup 里 upsertLibrary 之前跳过它们。前者顺带解决「同一目录在不同设备上不想都挂载」。
- [ ] **UX-50**（严重）删除媒体库没有任何二次确认，误触一次就抹掉本机全部刮削结果
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:166`
      - 改法：加一个说明后果的确认框（会清空本机刮削结果和该库的观看记录，需要重新扫描）。在没有用户系统之前，给整个「设置」页加一个可选的 4 位 PIN 是唯一能挡住小孩的东西。
- [ ] **UX-51**（打磨）没有单条「重新刮削」：一个 FALLBACK 匹配会写死 scraped_at，此后只能整库 REFRESH
      - 位置：`core/src/core/com/daview/server/scraper/MetadataService.kt:177`
      - 改法：MediaFacade 加 refreshItem(id, replaceAll: Boolean)，内部就是对单个 item 调 MetadataService 那条已有的刮削路径；入口放在 ItemMenuItems 里（这样库网格、搜索页右键都能用）而不是只放详情页。
- [ ] **UX-52**（打磨）official_rating 是个死字段：三个刮削器一次都没填过，详情页也不显示分级
      - 位置：`core/src/core/com/daview/server/scraper/MetadataService.kt:283`
      - 改法：先让 TMDB 刮削多请求一次 release_dates / content_ratings，把分级填进已经存在的 official_rating 列，在 DetailScreen.kt:280 那行 chip 里加 `item.officialRating?.let { Chip(it) }`——光是「看得见分级」就解决掉一半场景。

### 家庭与多设备

- [ ] **UX-53**（严重）没有用户/档案概念：共用一个系统账户的人共享一份进度与收藏，同步文件也按 item_id 整行覆盖
      - 位置：`core/src/core/com/daview/server/db/Database.kt:122-133`
      - 改法：最小可行：user_data 加一列 user_id，迁移时把现有行统一写成 'local'，主键改成 (user_id, item_id)；AppConfig 存「当前档案名」，顶栏放一个下拉切换，Repository 的 resume/nextUp/unwatched/query 全部带上当前档案。同步载荷的键同步改成 (profileId, itemId)，BACKUP_VERSION 升 2，没有 profileId 的旧行按 'local' 处理。家庭场景里「分开记账」比「防偷看」重要，第一版可以完全不做密码。

### 凭据与隐私

- [ ] **UX-54**（严重）WebDAV 密码和三个 API Key 的输入框全程明文显示，没有遮蔽也没有小眼睛
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:212-217`
      - 改法：给这四个框加 `visualTransformation = PasswordVisualTransformation()` + `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)`，再放一个 trailingIcon 切换明暗。改动量在十行以内。
- [ ] **UX-55**（打磨）凭据只能替换不能抹除，也不告诉你配置文件在哪
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:78-86`
      - 改法：存储和刮削两块各加一个「清除」按钮，走显式的 clearStorageCredentials()/clearScraperKeys() 路径（不能复用 ifBlank 的 update），并在设置页底部显示数据目录路径。
- [ ] **UX-56**（打磨）TMDB API Key 明文出现在 URL 里，任何一次请求失败都会把它整条写进日志
      - 位置：`core/src/core/com/daview/server/scraper/Scrapers.kt:121,126`
      - 改法：打日志前把 url 里的 `api_key=...` 用正则替换成 `api_key=***`；或改用 TMDB 的 Bearer 头（v4 read access token）走 header，URL 里就不再带密钥。
- [ ] **UX-57**（打磨）填 http:// 的 WebDAV 无任何提示，而明文链路上传的是网盘主账号密码本身
      - 位置：`composeApp/src/androidMain/AndroidManifest.xml:18`
      - 改法：用 network_security_config 只对 127.0.0.1 和用户自填的域名放行明文，其余走 TLS；同时在存储设置里检测到 url 以 http:// 开头且不是回环/私网时显示一行醒目提示：密码会以可还原的形式明文传输。
- [ ] **UX-58**（打磨）Android 未声明备份排除规则，明文 config.json（含网盘密码与 API Key）会进厂商云备份/换机迁移
      - 位置：`composeApp/src/androidMain/AndroidManifest.xml:15`
      - 改法：给 application 加 `android:dataExtractionRules` / `fullBackupContent` 把 daview 目录（至少 config.json）排除出备份；进一步把 password 与三个 Key 挪进 EncryptedSharedPreferences，config.json 只存非敏感项。
- [ ] **UX-59**（打磨）勾选「包含凭据」后导出明文密码文件没有二次确认，只有一段红字说明
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:501-512`
      - 改法：给「包含凭据导出」加一个二次确认弹窗（哪怕只是让用户再点一次「我确认导出明文密码」）。再往上可以做一个可选的 4 位 PIN / 生物识别门禁覆盖设置页，与删库确认那条合并考虑。

### 呈现、文案与性能打磨

- [ ] **UX-60**（严重）首播日期、制作方、分级刮回来了却一个都不显示；全 App 看不到任何日期时间
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:277-283`
      - 改法：加一个共用的 formatDate（premiereDate 本来就是 ISO 字符串，截 yyyy-MM-dd 就够，不必引 kotlinx-datetime）。优先补三处：详情页「文件信息」块加「首播日期 / 分级 / 制作」，分集行的元信息串里加播出日期，设置页把「已上传」换成「上次上传 <时间>」并给库卡片加「上次扫描 <时间>」。
- [ ] **UX-61**（严重）详情页简介固定截断 5 行，既不能展开也不能选中复制
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:297-305`
      - 改法：给简介加点击展开（maxLines 在 5 与 Int.MAX_VALUE 之间切换），或干脆不截断——详情页本来就是 LazyColumn。顺手把 SelectionContainer 的范围扩到简介上，剧情梗概本来就是最想复制出去的一段。
- [ ] **UX-62**（严重）海报下载失败与「压根没刮到图」在界面上无法区分，失败还不记忆——每次滚回视口都重付一次 15s/30s 超时
      - 位置：`core/src/core/com/daview/server/media/ImageCache.kt:44-50`
      - 改法：给 AsyncImage 传 `error = { 画和 posterUrl==null 相同的 Movie 图标 }`；ImageCache 里记一个内存中的失败 URL 集合（带 5 分钟过期），失败后短期内直接返回 null 不再发请求；连接超时从 15s 降到 5s。
- [ ] **UX-63**（打磨）扫描进度与刮削源在界面里漏出内部标识（scraping/probing、小写 tmdb），仓库里已有的中文映射只用在 Android 通知栏
      - 位置：`composeApp/src/androidMain/kotlin/com/daview/app/ScanForegroundService.kt:145-152`
      - 改法：把 ScanForegroundService.kt:145-153 那个 when 挪到 commonMain（放 Components.kt 旁边即可），HomeScreen.kt:122 和 SettingsScreen.kt:174 改用它并补上 done/error/cancelled 三档；刮削源的两处改成 MetadataProvider.displayName。
- [ ] **UX-64**（打磨）几处文案拿实现细节当解释，普通用户读不懂
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:187`
      - 改法：(a) 改成「自动匹配，把握不大 · 建议核对，可点右上角重新指定」；(b)「进度为估算值，可能有几秒误差」就够，机制留给日志；(c)「只补缺元数据的条目（不重新读文件，较快）」。
- [ ] **UX-65**（打磨）回到首页/标记已看会整体重建首页数据，各媒体库「未观看」整行在补数据前会先塌陷一次
      - 位置：`composeApp/src/app/com/daview/app/data/AppState.kt:157-171`
      - 改法：两个小改：refreshHome 里不要先赋一个 unwatched 为空的对象，把 unwatched 一起算完再一次性赋值（或用 home.copy 保留旧的 unwatched）；refreshAfterWatchChange（AppState.kt:255-269）在首页时改成把那一条从 home.resume 里移除、其余原地替换。
- [ ] **UX-66**（打磨）海报 URL 已经在列表响应里拿到过却被丢弃，每次内存缓存未命中都要为一张缩略图重跑一次整条 item 查询
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:452-460`
      - 改法：两条都很小：让 LocalAssetLinks.image() 把 remoteUrl 也编进 URI（或在内存里存一个 itemId+type→remoteUrl 的映射）让 imageFile 不用查库；或给 Repository 加一个只查 `SELECT poster_url, backdrop_url, logo_url FROM items WHERE id = ?` 的轻量方法。顺带给网格用的列表查询做一个不含 overview/people/media_streams 的投影。
- [ ] **UX-67**（打磨）单连接 + 全局互斥锁抵消了 WAL：扫描的写入阶段和刮削开始前的全库读会让界面数据慢一拍
      - 位置：`core/src/jvmOnly/kotlin/com/daview/server/db/JdbcSql.kt:21-23`
      - 改法：两条最小改动：Scanner.kt:70 把 records 按每 200 条 chunked 分批写入，让锁有间隙被 UI 查询抢到；itemsNeedingScrape 改成只返回 id 列表（或带 limit 分批取），刮削时再逐条读完整行。
- [ ] **UX-68**（打磨）图片缓存只增不减、没有任何清理入口，Android 上落在 filesDir，系统「清除缓存」清不掉
      - 位置：`core/src/core/com/daview/server/media/ImageCache.kt:20,30`
      - 改法：短期在 SettingsScreen 加一项「图片缓存 XXX MB / 清除」，调一个新的 ImageCache.clear()（删目录重建，图片按需重下不损失数据）；顺带把 Android 的 cache 子目录挪到 context.cacheDir 让系统按钮天然生效。长期再加按总大小的 LRU 淘汰。
- [ ] **UX-69**（打磨）界面语言不可切换、文案全部硬编码为中文
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:290-296`
      - 改法：如果不打算做多语言，至少把面向用户的字符串收到一处（哪怕只是一个 object Strings），先解决 core 层错误文案和 UI 文案各写一遍、改一处漏一处的问题；真要做切换，Compose Multiplatform 自带的 composeResources 就够用。

## 设计与交互（UI-REVIEW）

### 布局与响应式

- [ ] **UI-01**（严重）「继续观看」一行混排 2:3 海报与 16:9 剧照，标题错开 217.5dp，横向滚动时整行高度在 174.5↔392dp 之间持续跳动
      - 位置：`Components.kt:158、Components.kt:190、Components.kt:273-287、Components.kt:311-314、HomeScreen.kt:84-92、HomeScreen.kt:102、Repository.kt:343-347`
      - 改法：给 MediaRow 加 `aspect: Float` 参数并透传给 PosterCard，覆盖按 kind 推导的逻辑；「继续观看」「接下来」固定 16f/9f（电影用 backdropUrl，缺图时把 poster 居中裁成 16:9），并给这两行的 LazyRow 一个固定高度 itemWidth*9/16+44dp 杜绝行高跳变。改不动图源时，最小改动是 Components.kt:311 加 `verticalAlignment = Alignment.Bottom`，至少让标题落在同一条基线上。
- [ ] **UI-02**（严重 · 桌面）全 App 只有外置播放面板有最大宽度：100% 缩放下就是 1240dp 宽的单行 URL 输入框、每行约 74 个汉字的简介
      - 位置：`PlayerScreen.kt:76、Main.kt:25、App.kt:131、App.kt:133-140、SettingsScreen.kt:206、SettingsScreen.kt:209、DetailScreen.kt:254-264、DetailScreen.kt:349-352`
      - 改法：App.kt:137 的 Content 外套一层 `Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 1120.dp)) { … } }`；设置页表单区再单独收到 `widthIn(max = 640.dp)`，详情页正文列收到 `max = 900.dp`。
- [ ] **UI-03**（严重）返回箭头行在 8dp 与 56dp 之间无动画硬切换，每次导航整页内容瞬跳 48dp 并重排版——而 TopRow 在转场动画之外
      - 位置：`App.kt:178-181、App.kt:182-189、App.kt:136-139、App.kt:142-146、App.kt:160-163`
      - 改法：TopRow 换成固定高度容器 `Box(Modifier.fillMaxWidth().height(56.dp))`，里面用 `AnimatedVisibility(state.backStack.size > 1, fadeIn(), fadeOut())` 包住 IconButton；或直接换成 M3 TopAppBar 并在无返回时给一个空的 navigationIcon（需要 hero 通铺时用 `containerColor = Color.Transparent` 叠在图上，而不是把图往下推）。
- [ ] **UI-04**（严重）导航断点只有 720dp 一档且两套导航目的地不同：跨过断点媒体库入口整个消失，桌面 200% 缩放起直接渲染成手机版底栏布局
      - 位置：`App.kt:131、App.kt:215-253、App.kt:243-251、App.kt:256-280、HomeScreen.kt:83、SettingsScreen.kt:125、LibraryScreen.kt:86-88、Components.kt:167-168、Main.kt:25`
      - 改法：断点改成 600dp（rail）与 840dp 两档；BottomNavigation 补第四项「媒体库」（多库时点进去给一个库列表页），保证两套骨架目的地相同；LibraryScreen 不再传死 width，让 PosterCard 走 fillMaxWidth 吃满 cell。
- [ ] **UI-05**（严重）三个对话框的正文是不可滚动的 Column + 写死 heightIn：视口不够高时尾部的候选/目录列表被测成 0 高度并静默消失
      - 位置：`IdentifyDialog.kt:130、IdentifyDialog.kt:134、IdentifyDialog.kt:237、IdentifyDialog.kt:246、MergeDialog.kt:91-95、MergeDialog.kt:104、MergeDialog.kt:152、SettingsScreen.kt:629-647`
      - 改法：三处外层 Column 去掉写死的 heightIn，改成 `Modifier.verticalScroll(rememberScrollState())`，内部 LazyColumn 换成普通 forEach（或反过来只留一个 LazyColumn 承载全部内容），保证短视口下能滚到底。
- [ ] **UI-06**（严重）分集行缩略图写死 148dp、尾部按钮 48dp：360dp 手机上正文列只剩 86dp，标题约 6 个汉字、元信息折成两行；1920dp 下缩略图又不放大
      - 位置：`DetailScreen.kt:690、DetailScreen.kt:705-707、DetailScreen.kt:733-740、DetailScreen.kt:751-770、DetailScreen.kt:772`
      - 改法：缩略图按容器比例取宽（BoxWithConstraints 里 `(maxWidth * 0.32f).coerceIn(96.dp, 240.dp)` 配 `aspectRatio(16f/9f)`）；给 :751-761 的元信息加 maxLines=1 + Ellipsis；窄屏把已观看 IconButton 移到卡片右上角叠加，把 48dp 还给正文列。
- [ ] **UI-07**（打磨）首页 hero 的 heightIn(min=260,max=380) 因内容永远撑不到 260dp 而等价于写死 260dp，且完全不看视口高度
      - 位置：`HomeScreen.kt:149-153、HomeScreen.kt:66、DetailScreen.kt:221`
      - 改法：把两个常量换成随 BoxWithConstraints 的 maxHeight 取值，例如 `min(380.dp, maxOf(180.dp, maxHeight * 0.45f))`；DetailScreen.kt:221 的 320dp 最小值同样按可用高度夹一次。
- [ ] **UI-08**（打磨）五个可滚动屏用了五个不同的底部留白（8/20/40/48/48dp），搜索页 8dp 最紧
      - 位置：`SearchScreen.kt:65、LibraryScreen.kt:80、HomeScreen.kt:68、DetailScreen.kt:94、SettingsScreen.kt:78-81、App.kt:142-146`
      - 改法：抽一个共用常量（例如 `val ScreenScrollPadding = PaddingValues(top = 8.dp, bottom = 24.dp)`）让五个屏统一引用；底部导航的高度另行通过 insets/padding 叠加，不要让每个屏各猜一个数。
- [ ] **UI-09**（打磨）两处头图用 24dp 内边距、其余内容一律 20dp，同一条竖线错开 4dp；详情页更反过来——窄窗口对齐、宽窗口错开
      - 位置：`HomeScreen.kt:175、HomeScreen.kt:223、Components.kt:120、DetailScreen.kt:254-257、DetailScreen.kt:267、DetailScreen.kt:525、DetailScreen.kt:607、DetailScreen.kt:631`
      - 改法：让 HeroBanner 与 DetailHeader 的文案块用与 SectionHeader 相同的值（20dp，或全局统一到 24dp），使头图标题与下方分区标题落在同一条竖线上；更彻底的做法是定义一个 LocalContentMargin 由 App.kt 的 BoxWithConstraints 提供，全部屏改读它。

### 导航与信息架构

- [ ] **UI-10**（严重）导航项是「入栈」而不是「切换 tab」：重复点当前 tab 也压一层，返回箭头点一次画面纹丝不动，栈没有上限
      - 位置：`AppState.kt:124-127、AppState.kt:129-140、AppState.kt:37-38、App.kt:222-241、App.kt:256-280、App.kt:178`
      - 改法：navigate 里加两条：目标与 current 相等时直接 return；顶级目的地（Home/Search/Settings）改走一个 switchTab()，先 popUpTo 到 Home 再 add，让栈深度恒定为 1 或 2。TopRow 的箭头条件从 `size <= 1` 改成「栈顶不是顶级目的地」。
- [ ] **UI-11**（严重）进媒体库/详情/播放页后底栏三项同时熄灭，底栏不再指示当前位置；媒体库根本不是底栏目的地
      - 位置：`AppState.kt:33-40、App.kt:256-280、App.kt:259、App.kt:266、App.kt:273、App.kt:200-204`
      - 改法：给 selected 加派生逻辑——Detail/Player 沿 backStack 往回找第一个顶级 Screen 并高亮它，Library 归到新增的「媒体库」项，让 6 个屏幕都有归属。
- [ ] **UI-12**（严重）NavigationRail 是不滚动的 Column 且只设了最小宽：溢出的媒体库被静默压成 0 高度，长库名又把 rail 从 80dp 撑到约 150dp
      - 位置：`App.kt:215-253、App.kt:220、App.kt:242、App.kt:248、AndroidManifest.xml:23`
      - 改法：`NavigationRail { Column(Modifier.verticalScroll(rememberScrollState())) { … } }`（或把媒体库那段换成 LazyColumn，三个固定项钉在顶部单独一段），并给 label 加 `overflow = TextOverflow.Ellipsis` + `Modifier.widthIn(max = 72.dp)` 锁死 rail 宽度。

### 转场与反馈

- [ ] **UI-13**（严重 · Android）Snackbar 手搓钉在根 Box 的 BottomCenter：2.6 秒内整条压住 80dp 高的底部导航栏并吃掉该处点击，无进出动画、无 liveRegion
      - 位置：`App.kt:130-155、App.kt:132、App.kt:142-147、App.kt:149-153、App.kt:256-280、MainActivity.kt:23`
      - 改法：最小改法：把 App.kt:149-153 的 Snackbar 从外层 Box 挪进 App.kt:144 的 `Box(Modifier.weight(1f))`（宽屏分支同理放进 Content 所在的 Column），它就自然停在导航栏上方。更规范的做法是把 App.kt:130-155 换成 `Scaffold(bottomBar = { if (!wide) BottomNavigation(state) }, snackbarHost = { SnackbarHost(hostState) })`，把 `AppState.toast: String?` 换成 SnackbarHostState，动画、inset、无障碍语义一并解决。
- [ ] **UI-14**（严重）全部 11 条提示共用一个写死的 2600ms 计时器：短于 M3 最短的 4000ms，绕过无障碍延时，同文案连发还不重新计时
      - 位置：`App.kt:123-128、AppState.kt:120、AppState.kt:202-204、AppState.kt:301、AppState.kt:313-393、PlaybackController.kt:209-213、IdentifyDialog.kt:118`
      - 改法：把 `toast: String?` 拆成 `(message, isError, action)` 三元组：成功类 4s、错误类 10s 或不自动消失并带一个「去设置 / 重试」按钮；同一文案连发时用一个自增序号做 LaunchedEffect 的 key。「没有可用的播放器」这类需要用户决策的错误改用 AlertDialog。
- [ ] **UI-15**（严重）海报卡显式 indication = null：触摸端按下零反馈、桌面焦点零指示，而同屏的分集卡/设置行走的是默认涟漪
      - 位置：`Components.kt:178-187、Components.kt:156-157、Components.kt:170、Components.kt:190、Components.kt:192-193、Components.kt:223-241、Components.kt:386、DetailScreen.kt:698-701、Theme.kt:38、Theme.kt:55`
      - 改法：删掉 Components.kt:180 的 `indication = null`（或改成 `ripple()`），interactionSource 继续复用同一个，hover 缩放与遮罩不受影响、涟漪画在海报之上即可。若不想要方形涟漪，至少用同一 interactionSource 的 `collectIsPressedAsState()` 给一个 pressed → scale 0.97 的按压反馈。顺手把 :193 的 tonalElevation 换成 `shadowElevation`。Components.kt:386 的 LinkText 同理。
- [ ] **UI-16**（严重）详情页没有加载态：第一次进是整屏空白，之后进会先把上一个条目的整页端出来，连播放按钮都是活的
      - 位置：`DetailScreen.kt:89、DetailScreen.kt:359-361、DetailScreen.kt:384、AppState.kt:36、AppState.kt:115、AppState.kt:124-127、AppState.kt:143、AppState.kt:268-286、App.kt:160-163`
      - 改法：onEnter(Screen.Detail) 里先 `detailItem = null; detailChildren = emptyList(); detailEpisodes = emptyList()`（或把 screen.itemId 传给 DetailScreen，`detailItem?.id != screen.itemId` 一律按未加载处理），再把 DetailScreen.kt:89 的 `?: return` 换成 `?: run { LoadingPane(); return }`，把已有的 detailLoading 接上；数据未就绪时禁用播放按钮。
- [ ] **UI-17**（严重）三个列表屏都没有错误态：媒体库读失败后永远转圈，2.6 秒后连错误都没了，也没有重试
      - 位置：`AppState.kt:235-261、AppState.kt:197-207、AppState.kt:272、AppState.kt:293-296、LibraryScreen.kt:43、LibraryScreen.kt:47、LibraryScreen.kt:71、Components.kt:101-115、Components.kt:327-346、App.kt:123-128`
      - 改法：给 AppState 加 `libraryError: String?`（详情、搜索同理），run() 的 catch 里除了 toast 再写进这个字段；LibraryScreen 的 when 里加一个错误分支，复用 EmptyState 并传一个「重试」按钮回调 `state.loadLibrary(libraryId)`。
- [ ] **UI-18**（严重）搜索无加载态：新一轮搜索的首个字符必然先闪 ≥250ms 的「没有匹配的结果」，且「正在查」与「查完为空」长得一模一样
      - 位置：`SearchScreen.kt:40-44、SearchScreen.kt:47-55、SearchScreen.kt:57-61`
      - 改法：AppState 加一个 `searching: Boolean`（search() 进出时置位），SearchScreen 改成三态：blank → 引导态；searching → 搜索框内 trailingIcon 进度圈并保留旧结果；否则才判空显示「没有匹配的结果」。清空输入时同步立刻清 searchResults。
- [ ] **UI-19**（严重）全应用唯一的页面转场是无方向的交叉溶解：进入与返回播放同一套动画，两屏叠加期约 110ms
      - 位置：`App.kt:160-164、Theme.kt:145、Theme.kt:6`
      - 改法：transitionSpec 改成方向感知的 fade through：比较 backStack 是增是减，前进 `fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.92f) togetherWith fadeOut(tween(90))`，后退把 scale 换成 1.08f 起。最低成本版本是只把 fadeOut 换成 `tween(90)`，让它先走完再淡入，双重曝光即消失。
- [ ] **UI-20**（打磨 · 桌面）悬停反馈只做了一半：遮罩与播放键一帧硬跳无淡入，tonalElevation 因色角色选错静默失效所以卡片不「抬起」，指针也仍是箭头
      - 位置：`Components.kt:157、Components.kt:134-135、Components.kt:189-193、Components.kt:223-241、Components.kt:385、Theme.kt:38、Theme.kt:55、Theme.kt:145`
      - 改法：遮罩与播放键包一层 `AnimatedVisibility(hovered, fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()), fadeOut(...))`；把 `tonalElevation` 换成 `shadowElevation = if (hovered) 6.dp else 0.dp`（或 hover 时把 color 切到 surfaceContainerHighest，用色阶表达抬升）；卡片加 `Modifier.pointerHoverIcon(PointerIcon.Hand)`。
- [ ] **UI-21**（打磨）ready 在任何数据到位前就翻转：冷启动会闪过一次「还没有媒体库」空态，且四段状态之间全是硬切
      - 位置：`AppState.kt:173-174、AppState.kt:179-183、AppState.kt:197-207、AppState.kt:75、AppState.kt:94-95、HomeScreen.kt:56-64、App.kt:80、Components.kt:101-115`
      - 改法：把 App.kt:80 换成 `Crossfade(state.ready)`；给 HomeScreen 的空态加一个「已加载过」的前提位（或在 open() 里 `opened = Opened(context)` 之前先同步取一次 libraries），让空态以「查完且为空」为前提而不是拿初始值当结论。
- [ ] **UI-22**（打磨）备份导出全程零进度：写好的进度条永远不显示，按钮还能反复点，而并排的「导入…」全程有进度条
      - 位置：`SettingsScreen.kt:483、SettingsScreen.kt:526-545、SettingsScreen.kt:547-575、SettingsScreen.kt:577-580、SettingsScreen.kt:440-443、Backup.kt:64、Backup.kt:102-110、Platform.desktop.kt:172-178`
      - 改法：把导出的 onClick 包成和导入一致的形状：`busy = true; try { … } catch (e) { error = … } finally { busy = false }`。顺便给 backupChunks 加 try/catch——现在只有 saveTextFile 被 runCatching 包着。
- [ ] **UI-23**（打磨）扫描进度用 determinate 的 LinearWavyProgressIndicator，但 queued/listing 阶段总数为 0，进度条静止在 0%
      - 位置：`SettingsScreen.kt:186-189、SettingsScreen.kt:171、SettingsScreen.kt:442、SettingsScreen.kt:579、HomeScreen.kt:128-133、ScanService.kt:24-26、ScanService.kt:54-63、Scanner.kt:42、Scanner.kt:49、Models.kt:395`
      - 改法：两处都按总数是否已知分叉：`if (status.total > 0) LinearWavyProgressIndicator(progress = { status.current.toFloat() / status.total }, …) else LinearWavyProgressIndicator(modifier)`。顺手把 Scanner.kt:42 的 total=1 改成 0，让「未知」这个状态在数据层就说得清。
- [ ] **UI-24**（打磨）首页「正在扫描」排在约 2000dp 内容之后的列表末尾，顶部/导航栏没有任何后台任务提示
      - 位置：`HomeScreen.kt:115-138、HomeScreen.kt:107-113、HomeScreen.kt:149-153、HomeScreen.kt:160-166、AppState.kt:359-369`
      - 改法：把 running 分支移到 LazyColumn 的第一个 item（hero 之上）或做成贴顶的常驻条；轮询间隔在有扫描运行时收紧到 500–1000ms。

### 配色与对比度

- [ ] **UI-25**（阻断 · Android）系统栏图标明暗只认系统夜间模式、界面底色只认应用内开关：开箱默认组合（系统浅色 + App 深色）下状态栏图标 1.09:1，切主题时系统栏纹丝不动
      - 位置：`MainActivity.kt:23、AppState.kt:97、AppState.kt:185-188、Platform.android.kt:97、SettingsScreen.kt:295、App.kt:78-79、themes.xml:4-6、libs.versions.toml:19`
      - 改法：把判定绑到应用主题：`enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { state.darkTheme })`，并在 Compose 里加 `DisposableEffect(state.darkTheme) { WindowInsetsControllerCompat(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }` 让它跟着开关走。themes.xml 拆成 values/ 与 values-night/ 两份 windowBackground，或直接用 `?android:attr/colorBackground`。
- [ ] **UI-26**（严重）设置页库卡与详情页分集卡用色阶最低的 surfaceContainerLow，压在 background 上只有 1.05:1，18dp 圆角与卡片边界等于白画
      - 位置：`SettingsScreen.kt:114-117、DetailScreen.kt:691-702、HomeScreen.kt:227-232、PlayerScreen.kt:72-76、App.kt:78-79、Theme.kt:38、Theme.kt:53-57`
      - 改法：定一条容器阶梯并全局照做：页面上的内容卡一律 surfaceContainer 起步（1.10:1 仍偏弱，可把 background 压到 #0B0A10 或把 surfaceContainer 提到 #201D2A 做到 ≥1.3:1），强调项用 surfaceContainerHigh，弹层/面板用 surfaceContainerHighest；分集行如果想保持轻量就改成无卡片 + HorizontalDivider，而不是一张看不见的卡片。
- [ ] **UI-27**（打磨）观看进度条用 secondary，而该角色在两套主题里明暗相反，EpisodeRow 的轨道还是全透明直接压在剧照上
      - 位置：`Components.kt:243-256、DetailScreen.kt:720-730、DetailScreen.kt:726、Theme.kt:28、Theme.kt:79、Theme.kt:64、Theme.kt:115`
      - 改法：两处的 `color` 从 `secondary` 换成 `secondaryFixedDim`（一行改动，两套主题都变成亮暖橙）；DetailScreen.kt:726 的 trackColor 补上和 PosterCard 一致的 `scrim.copy(alpha = 0.4f)`，让 fill 与 track 的对比不再取决于剧照。

### 排版与信息层级

- [ ] **UI-28**（打磨 · 桌面）labelSmall（11sp）被当正文用了 24 处：桌面「内置播放器」设置里 5 段成句中文说明在 100% 缩放下是 11 个物理像素
      - 位置：`PlatformPlayerSettings.desktop.kt:95、PlatformPlayerSettings.desktop.kt:121-127、PlatformPlayerSettings.desktop.kt:198-215、PlatformPlayerSettings.desktop.kt:245-271、PlatformPlayerSettings.desktop.kt:209、PlatformPlayerSettings.desktop.kt:244、Main.kt:25`
      - 改法：把 labelSmall 的用法收回到按钮/标签内的短词；所有成句说明至少用 bodySmall，「必须读懂才能配对」的播放器设置说明用 bodyMedium。若要保持 token 语义不动，也可以在传给主题的 typography 副本里把 labelSmall 提到 12sp、bodySmall 提到 13sp。
- [ ] **UI-29**（打磨）主题从不传 typography：M3 的拉丁基线行高压过中文字体自然行高 8%，字距也全程带 tracking
      - 位置：`Theme.kt:135-149、DetailScreen.kt:744-746、DetailScreen.kt:350-351`
      - 改法：在 Theme.kt 里构造一份 typography 传给 MaterialExpressiveTheme：用各 style 的 copy() 把 lineHeight 统一提到 fontSize 的 1.5–1.6 倍（bodySmall 12→18、bodyMedium 14→22、titleLarge 22→34、headlineMedium 28→44），letterSpacing 归零。改一处全 App 生效，可以和下一条的 localeList 放在同一份 copy 里一次做完。
- [ ] **UI-30**（打磨 · Android）从未设置 TextStyle.localeList：Android 系统语言非中文时，中日共用码位按日文字形回退
      - 位置：`Theme.kt:135-149、themes.xml、AndroidManifest.xml`
      - 改法：在 Theme.kt 传给 MaterialExpressiveTheme 的 typography 副本里，对每个 style 加 `localeList = LocaleList("zh-Hans")`。
- [ ] **UI-31**（打磨）分集行的观看状态与文件路径同字号同字重同色紧邻，中间连一个 Spacer 都没有，视觉上黏成一段
      - 位置：`DetailScreen.kt:735-739、DetailScreen.kt:750、DetailScreen.kt:751-761、DetailScreen.kt:762-769`
      - 改法：把 :751-761 的状态行提到 bodySmall 并挪到 :744 的简介之前（状态紧跟标题），:762-769 的路径保持 labelSmall 但换成 outline 色或只在悬停/展开时出现。
- [ ] **UI-32**（打磨 · Android）网格卡宽度与 maxLines=1 都是常量，系统字号 200% 时卡片标题退化到约 4 个汉字加省略号
      - 位置：`LibraryScreen.kt:78-91、SearchScreen.kt:63-75、Components.kt:169、Components.kt:274-287`
      - 改法：两步：把 Components.kt:278 的 `maxLines = 1` 改成 2（副标题保持 1 行，卡片高度本来就是 Column 自适应）；让网格列宽跟着字号走 —— `GridCells.Adaptive(minSize = 150.dp * LocalDensity.current.fontScale.coerceAtMost(1.6f))`，并且不再给 PosterCard 传固定 width，让它 fillMaxWidth 吃满 cell。

### Android 平台集成

- [ ] **UI-33**（阻断 · Android）开了 edge-to-edge 但全仓库零 inset 处理：内容从 y=8dp 起穿过状态栏，二级页返回箭头在 40dp 状态栏机型只露 4dp、48dp 机型完全消失
      - 位置：`MainActivity.kt:23、libs.versions.toml:25、App.kt:79、App.kt:130-155、App.kt:177-181、App.kt:182-189、HomeScreen.kt:66-68、HomeScreen.kt:165-173、AppState.kt:91`
      - 改法：最小改动：给 App.kt:136 与 App.kt:142 两个 Column 加 `Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))`（TopRow 的 Spacer 分支同样要套）。想保留海报滚到状态栏下的沉浸效果，就把 HomeScreen.kt:67 的 contentPadding 顶部改成 `WindowInsets.statusBars.asPaddingValues().calculateTopPadding()` 并在最顶部叠一条状态栏高度的渐变遮罩。更规范的做法是把 App.kt:130-155 换成 `Scaffold(bottomBar = { BottomNavigation(state) })`，让 contentWindowInsets 统一处理。
- [ ] **UI-34**（严重 · Android）edge-to-edge 让 adjustResize 失效而应用未接手 IME inset：设置页下半屏的密码 / API Key / 元数据语言输入框会被软键盘盖住
      - 位置：`AndroidManifest.xml:24、MainActivity.kt:23、SettingsScreen.kt:78-81、SettingsScreen.kt:209-218、SettingsScreen.kt:246-264、App.kt:136、App.kt:142`
      - 改法：给 App.kt:142（以及 :136）的 Column 加 `Modifier.imePadding()`，一处解决全部输入场景；或只改设置页——把 SettingsScreen.kt:80 的 contentPadding 底部改成 `48.dp + WindowInsets.ime.asPaddingValues().calculateBottomPadding()`。
- [ ] **UI-35**（严重 · Android）应用根本没有图标资源：桌面、最近任务、Android 12+ 冷启动闪屏全是系统默认的通用图标
      - 位置：`AndroidManifest.xml:13-19、themes.xml:3、themes.xml:6、composeApp/build.gradle.kts:89-110`
      - 改法：加一套 `res/mipmap-anydpi-v26/ic_launcher.xml`（foreground / background / monochrome）与各密度位图，manifest 的 `<application>` 补 `android:icon` 与 `android:roundIcon`；themes.xml 拆成 values/ 与 values-night/ 两份 windowBackground，并显式设 `android:windowSplashScreenBackground` 让闪屏与实际主题一致。
- [ ] **UI-36**（打磨 · Android）横向片架铺到屏幕左右边缘，与手势导航的返回热区抢同一条窄带，且未声明手势排除区
      - 位置：`Components.kt:309-314、HomeScreen.kt:222-225、DetailScreen.kt:107-119、DetailScreen.kt:605-621、DetailScreen.kt:630-633、MainActivity.kt:23`
      - 改法：在 androidMain 里给 MediaRow 的 LazyRow 加一层 expect/actual 的 `Modifier.systemGestureExclusion()`（desktop 侧返回 Modifier），改动只在 Components.kt:311 一行；或把行的 contentPadding 提到 24dp 以上并让行本身留出左右安全边距。
- [ ] **UI-37**（打磨 · Android）扫描通知没有品牌识别：small icon 用框架的 stat_notify_sync、取消动作用 Holo 时代的 ic_menu_close_clear_cancel，且从未 setColor
      - 位置：`ScanForegroundService.kt:95-102、ScanForegroundService.kt:98、ScanForegroundService.kt:116`
      - 改法：加一个 `res/drawable/ic_notification.xml`（纯白轮廓矢量）替换 ScanForegroundService.kt:98；在 :95 的 builder 上补 `.setColor(0xFF6C4BF6.toInt())`（与 Theme.kt 的 Violet 一致）和 `.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)`。
- [ ] **UI-38**（打磨 · Android）通知权限弹窗在 setContent 之前发出，新用户看到的第一屏是盖在空白加载页上的系统权限对话框
      - 位置：`MainActivity.kt:22-24、MainActivity.kt:27-33、MainActivity.kt:34-41、App.kt:76、App.kt:80、App.kt:87-102、AppState.kt:75`
      - 改法：把 MainActivity.kt:22 的调用从 onCreate 移走，改成在设置页第一次点「扫描」时触发：先用一句话说明「扫描要几分钟，通知用来显示进度并防止系统回收进程」，再拉起系统弹窗。

### 桌面平台惯例

- [ ] **UI-39**（阻断 · 桌面）默认窗口 1360×900 物理像素从不与屏幕工作区取交集：1366×768 笔记本、1080p 上开 125%/150% 缩放的机器首次启动就有一大块在工作区外
      - 位置：`Main.kt:19-26、Main.kt:25、InternalPlayer.desktop.kt:275-279`
      - 改法：在 Main.kt 里按当前屏幕算初始尺寸：取 `Toolkit.getDefaultToolkit().getScreenInsets(gc)` 与 `gc.bounds`，用 `DpSize(min(1360, workW − 96).dp, min(900, workH − 96).dp)`；顺手给 `window.minimumSize` 一个下限。
- [ ] **UI-40**（严重 · 桌面）桌面上所有横排用滚轮推不动——纵向滚轮增量在 Horizontal scrollable 上被算成 0 并冒泡去滚整页，行两端也没有箭头或滚动条
      - 位置：`Components.kt:311-314、HomeScreen.kt:222-225、HomeScreen.kt:66、DetailScreen.kt:94、DetailScreen.kt:107-119、DetailScreen.kt:605-621、DetailScreen.kt:630-633、AppState.kt:220`
      - 改法：两件事各自独立见效：给 MediaRow 与分集行加 `Modifier.onPointerEvent(PointerEventType.Scroll)`，把 scrollDelta.y 转成对 rememberLazyListState 的 scrollBy；在行左右两端加 hover 时才出现的圆形箭头按钮（滚到头时隐藏对应一侧），每次滚一屏宽——两者都只需要把 LazyRow 的 state 提上来。
- [ ] **UI-41**（严重 · 桌面）右键菜单用 DropdownMenu 的 offset 当光标坐标，而 offset 的垂直基准是锚点底边——菜单落在光标下方整整一张海报的高度
      - 位置：`Components.kt:261-269、Components.kt:190、ItemContextMenu.kt:61、ItemContextMenu.kt:79-80、DetailScreen.kt:786-792、DetailScreen.kt:690-704、LibraryScreen.kt:87、HomeScreen.kt:89`
      - 改法：不要让 DropdownMenu 直接挂在画面 Box 上：在 Box 里放一个零尺寸锚点 —— `Box(Modifier.offset { IntOffset(menuAt.x.toInt(), menuAt.y.toInt()) }.size(0.dp)) { DropdownMenu(expanded, onDismissRequest) { … } }`，offset 参数留 DpOffset.Zero，anchorBounds 高度为 0 时 bottom 就是光标点。Components.kt:261 与 DetailScreen.kt:786 两处改同一模式。
- [ ] **UI-42**（严重 · 桌面）桌面端一个快捷键、一条菜单栏都没有：Alt+← / Backspace 不返回、Ctrl+F 不跳搜索、F5 不刷新，唯一返回入口是左上角那个图标按钮
      - 位置：`Main.kt:19-26、App.kt:186`
      - 改法：在 Main.kt 的 Window 上挂 `onPreviewKeyEvent`：Alt+Left / Backspace → `state.back()`、Ctrl+F → `state.navigate(Screen.Search)`、F5 → 重新加载当前屏、F11 → 切 WindowState.placement。再配一条 MenuBar 把这些动作显式列出来，让快捷键可被发现。
- [ ] **UI-43**（严重 · 桌面）窗口大小、位置、最大化状态、所在显示器一概不记忆，每次启动都回到主屏左上角的 1360×900
      - 位置：`Main.kt:19-26、Main.kt:20-23、Platform.desktop.kt:190-196、Platform.desktop.kt:74-80`
      - 改法：在 onCloseRequest 里把 `state.size`、`state.position`（Absolute 时才存）、`state.placement` 写进已有的 Preferences 节点，启动时读回来；读回后先和当前屏幕工作区求交集再用，屏幕拓扑变了就退回默认。
- [ ] **UI-44**（严重 · 桌面）没有任何窗口图标：标题栏、任务栏、Alt+Tab、安装后的开始菜单快捷方式全是 JDK 默认的咖啡杯
      - 位置：`Main.kt:19-26、composeApp/build.gradle.kts:125-152`
      - 改法：做一张 PNG 放进 composeApp 资源并 `Window(icon = painterResource(...))`；同时在 nativeDistributions 里给三个平台各配一份 iconFile（Windows 用含 16/32/48/256 四档的 .ico），否则安装后的快捷方式仍是通用图标。
- [ ] **UI-45**（打磨 · 桌面）两个「打开」对话框的 FilenameFilter 在 Windows 上是 JDK 文档化的空操作，三个对话框都不设初始目录、也没有文件类型下拉
      - 位置：`Platform.desktop.kt:152-162、Platform.desktop.kt:164-178、PlatformPlayerSettings.desktop.kt:105-109、PlatformPlayerSettings.desktop.kt:276-284、SettingsScreen.kt:542、composeApp/build.gradle.kts:125-152`
      - 改法：改成 Windows 认的写法：LOAD 时 `dialog.file = "*.json"`（libmpv 那处用 "*.dll"），保留现有 setFilenameFilter 给 macOS/Linux；三处都设 `dialog.directory`（默认 user.home 下的 Documents/Downloads，并把上次选过的目录存进已有的 Preferences 节点）；顺带把保存失败的异常信息带到「没有写入文件」后面。

### 播放器界面

- [ ] **UI-46**（阻断 · 桌面）桌面内置播放器的「音轨 / 字幕」下拉菜单整个落在 mpv 的 AWT 画布里，被原生子窗口盖住，看不见也点不到
      - 位置：`InternalPlayer.desktop.kt:248-279、InternalPlayer.desktop.kt:59、InternalPlayer.desktop.kt:333、InternalPlayer.desktop.kt:364、InternalPlayer.desktop.kt:377、MpvPlayer.kt:84、App.kt:177-189`
      - 改法：不要在这条栏上用 Popup 类组件。两个最小改法二选一：把音轨/字幕交给 mpv 自己（osc 已开，`#`、`j` 现成），顶栏只留标题与「结束播放」；或把菜单改成就地展开的内联行——点「音轨」时在 PlayerBar 下方再插一行 Compose 的 ToggleButton 列表（属于 Column 布局的一部分，会把视频往下挤，不走 popup）。若一定要保留下拉，需在 Main.kt 启动时 `System.setProperty("compose.layers.type", "WINDOW")` 并实测，代价是所有对话框都变成独立窗口。
- [ ] **UI-47**（严重 · 桌面）桌面播放器把走带控制整个交给 mpv，Compose 侧既不转交按键也从不把焦点还给 Canvas——点过一次工具栏后空格变成「重新弹开刚才那个菜单」，Esc 也无人接管
      - 位置：`MpvPlayer.kt:96-98、InternalPlayer.desktop.kt:54-63、InternalPlayer.desktop.kt:74-80、InternalPlayer.desktop.kt:276-279、InternalPlayer.desktop.kt:332-392、App.kt:178-189`
      - 改法：两步：给 canvas 加一个在 mousePressed 时 `requestFocusInWindow()` 的 MouseListener，并在 `LaunchedEffect(info.sessionId)` 拿到 handle 后主动请求一次焦点，让键盘默认归 mpv；在 InternalPlayer 最外层 Column 上加 `Modifier.onPreviewKeyEvent`，至少把 Esc（调 finish()）、空格、←/→ 拦下来转成 `player?.command(...)`，这样焦点在 Compose 工具栏上也不会走丢。
- [ ] **UI-48**（严重 · 桌面）桌面播放器没有任何全屏入口，却把 mpv 那两个在 --wid 内嵌下已经变成空操作的全屏控件原样摆给用户
      - 位置：`Main.kt:19-26、MpvPlayer.kt:84、MpvPlayer.kt:96-98、InternalPlayer.desktop.kt:361、InternalPlayer.desktop.kt:374、InternalPlayer.desktop.kt:390、App.kt:160-174、App.kt:177-189`
      - 改法：给 WindowState 加 placement 开关：在播放页监听 F11 与画面双击切到 `WindowPlacement.Fullscreen`，该状态下隐藏 TopRow、SideNavigation 与 PlayerBar（hover 唤出），Esc 退出全屏；既然 mpv 的全屏按钮是死的，用 script-opts 把它从 osc 布局里去掉，别让用户点空。「结束播放」换成 PlayerBar 最左侧的一个 ✕ 或 ← 图标按钮，和右侧的轨道菜单拉开距离。
- [ ] **UI-49**（严重 · 桌面）桌面 PlayerBar 是无溢出处理的单行 Row：GPU 型号排在片名前面吃满宽度，150% 缩放下字幕轨名换行把 bar 撑到 112dp、「结束播放」退化成压在字幕按钮上的 48dp 空白热区
      - 位置：`InternalPlayer.desktop.kt:333-391、InternalPlayer.desktop.kt:338-345、InternalPlayer.desktop.kt:347-355、InternalPlayer.desktop.kt:361-376、InternalPlayer.desktop.kt:390、Models.kt:96-103、Main.kt:25`
      - 改法：三处最小改动：音轨/字幕按钮的 Text 加 `maxLines = 1, overflow = Ellipsis` 并 `widthIn(max = 180.dp)`，或干脆换成图标按钮、把轨道名放进菜单；显卡名加 `weight(0.3f, fill = false)` 或 widthIn(max) 让它先让位；把「结束播放」提到 Row 最前面测量，保证它永远存在。窄窗口时把显卡名与两个 chip 折进一个溢出菜单。
- [ ] **UI-50**（严重）播放中点侧栏/底栏的「搜索」「设置」「某个媒体库」：去不了目标页，绕一圈回来重启一次解码，再自己多退一级
      - 位置：`App.kt:133-147、App.kt:230、App.kt:237、App.kt:246、App.kt:160-173、AppState.kt:124-127、PlayerScreen.kt:45-55、PlayerScreen.kt:51-54、PlaybackController.kt:176-183、InternalPlayer.desktop.kt:239-246、InternalPlayer.android.kt:116-122`
      - 改法：根因是 onClose 里的 `state.back()` 与外部 navigate 抢同一个回退栈。最小改法：PlayerScreen 的 onClose 改成只在 current 仍是 Screen.Player 时才 back()（或用 popIfCurrent(Screen.Player)）；同时把 PlaybackController.stop 里的 `info = null` 提到 scope.launch 之外先置空，避免旧 info 被重新播一次。设计上更该做的是让 Screen.Player 成为覆盖整个窗口、不带导航骨架的一层。
- [ ] **UI-51**（严重 · Android）Android 播放器的标题与音轨/字幕按钮是纯白文字直接压在视频帧上，没有 scrim 也没有阴影，亮画面下低至 1.00:1
      - 位置：`InternalPlayer.android.kt:133-222、InternalPlayer.android.kt:150、InternalPlayer.android.kt:166、InternalPlayer.android.kt:216-220`
      - 改法：给顶部叠加层加一层高约 120dp 的 `Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))`；或者直接让这三个叠加层跟随控制条显隐，复用 media3 已经画好的 60% 黑底。
- [ ] **UI-52**（严重）退出内置播放器：Android 上引擎比页面多活约 0.33 秒声音继续响，桌面则在淡出中途先闪出「没有正在播放的内容」，两端画面都不参与淡入淡出
      - 位置：`PlayerScreen.kt:37-43、PlayerScreen.kt:51-54、PlaybackController.kt:176-183、MediaFacade.kt:386-392、InternalPlayer.desktop.kt:239-246、InternalPlayer.desktop.kt:272、InternalPlayer.android.kt:116-122、App.kt:161、App.kt:186`
      - 改法：两处 onClose 回调里先关引擎再退栈（`player?.close()` / `player.release()` 放到 `state.back()` 之前，DisposableEffect 保留作兜底）；同时在 App.kt:161 的 transitionSpec 里对 `Screen.Player` 这一分支返回 `EnterTransition.None togetherWith ExitTransition.None`，让播放器进出是干净的瞬切，而不是一个做不到的假淡入淡出。
- [ ] **UI-53**（打磨 · 桌面）桌面 PlayerBar 跟随应用主题：切到浅色主题后，纯黑画面上方是一条横贯全宽、64dp 高的近白色带
      - 位置：`InternalPlayer.desktop.kt:332、InternalPlayer.desktop.kt:334、InternalPlayer.desktop.kt:74-80、InternalPlayer.desktop.kt:361、InternalPlayer.desktop.kt:374、InternalPlayer.desktop.kt:390、Theme.kt:108、AppState.kt:97`
      - 改法：给播放页局部套一层固定的深色配色（`MaterialTheme(colorScheme = DaViewDarkColors)` 包住 PlayerScreen），让 PlayerBar 在浅色主题下也是深色，并把它做成半透明黑而不是 surfaceContainerLow。
- [ ] **UI-54**（打磨 · Android）Android 播放失败时一张卡片浮在画面正中央、永不消失、没有任何出口，文案还是 ERROR_CODE_IO_BAD_HTTP_STATUS 这样的英文枚举
      - 位置：`InternalPlayer.android.kt:67、InternalPlayer.android.kt:106-111、InternalPlayer.android.kt:198-211、InternalPlayer.desktop.kt:280-301、Theme.kt:128`
      - 改法：把 errorCodeName 映射成中文原因（网络中断 / 服务器返回 502 / 该格式无法解码 / 文件不存在），原始码收进一行 labelSmall 次要文本；卡片改成占满画面的错误态并带「重试」「返回」两个按钮，重试时 `player.prepare()` 并把 playbackError 清空；错误态下隐藏音轨/字幕入口。

### 组件用法

- [ ] **UI-55**（严重）26dp 圆角把海报底部的观看进度条两端啃掉：进度低于 8.1% 时一个像素都画不出来，而代码以为自己画了
      - 位置：`Components.kt:189-194、Components.kt:243-256、Theme.kt:129、DetailScreen.kt:708、LibraryScreen.kt:88`
      - 改法：把海报 Surface 的圆角降到 12dp 左右；如果要保留大圆角，就把进度条移出被裁剪的 Surface（画在卡片下方的文字区），或改成整宽 track + indicator，让 track 先把这段几何吃掉，用户至少能看出「有进度条，只是很短」。
- [ ] **UI-56**（严重）缺图这一件事有四种画法：卡片给电影图标、分集缩略图给空灰块、演职人员给空灰圆、详情页海报干脆不占位——最后一种让整个头部左移 180dp
      - 位置：`Components.kt:203-211、DetailScreen.kt:282、DetailScreen.kt:295、DetailScreen.kt:254-269、DetailScreen.kt:639-651、DetailScreen.kt:706-719、Scrapers.kt:230、Scrapers.kt:241、Scrapers.kt:380`
      - 改法：抽一个 `ArtworkPlaceholder(kind, shape, size)`：按 ItemKind 给图标（MOVIE→Movie、SERIES/SEASON→Tv、EPISODE→Slideshow、人物→Person 或姓名首字），四个调用点全部换成它。HeroPoster 去掉 `?: return`，无图时画同尺寸占位（gap 也保住），让详情页头部版式与有图时一致。顺手把 `.clip(RoundedCornerShape(50))` 改成给 Surface 传 `shape = CircleShape`。
- [ ] **UI-57**（严重）悬停时海报中央那个圆形播放键点下去不是播放，是进详情页；触摸端则永远看不到它
      - 位置：`Components.kt:153、Components.kt:167-187、Components.kt:223-241、Components.kt:232、Components.kt:243-256、LibraryScreen.kt:90、SearchScreen.kt:74、HomeScreen.kt:91-112、DetailScreen.kt:619、DetailScreen.kt:384、ItemContextMenu.kt:109-121`
      - 改法：把播放键做成真正的子按钮：外层 Column 的 onClick 保持导航，在 Components.kt:232 的 Surface 上加 IconButton/clickable 并回调一个新的 `onPlay` 参数（item.isPlayable 时才画）。若暂不想接播放链路，就把这个圆形播放键换成一个不承诺动作的记号（只加深 scrim 或显示片名），别用 PlayArrow。
- [ ] **UI-66**（严重）已观看的对勾在四处用了两套互斥编码：卡片/详情页按状态画（primary 实心勾=已看），右键菜单按动作画（primary 实心勾=未看）；卡片角标还是无底衬裸图标
      - 位置：`Components.kt:214-221、DetailScreen.kt:443-450、DetailScreen.kt:774、ItemContextMenu.kt:144-148、Theme.kt:23、AppState.kt:97`
      - 改法：定一条规则：对勾只表示「已观看」这一个状态，全 App 用同一个字形（建议保留 CheckCircle），未看不画图标；菜单项的 leading icon 换成中性的 `Icons.Filled.Done` 或干脆去掉，让文案承担动作语义。角标改成 `Surface(shape = CircleShape, color = primary)` 包一层、图标用 onPrimary，尺寸不变。
- [ ] **UI-58**（打磨）设置里的开关行自己拼 Row+Switch：标签点不动（360dp 行里只有右端 52dp 可点）、桌面无 hover 反馈、读屏节点没有可访问名称
      - 位置：`SettingsScreen.kt:290、SettingsScreen.kt:293-296、SettingsScreen.kt:376-396、PlatformPlayerSettings.desktop.kt:207-223、PlatformPlayerSettings.desktop.kt:242-258`
      - 改法：两处都换成 `ListItem(checked = ..., onCheckedChange = ..., headlineContent = { Text("深色主题") }, trailingContent = { Switch(checked = ..., onCheckedChange = null) })`。不想换组件的话，最小改法是给 Row 加 `Modifier.fillMaxWidth().toggleable(value = ..., role = Role.Switch, onValueChange = ...).padding(vertical = 8.dp)` 并把 Switch 的 onCheckedChange 置 null。这个「一行标签 + 一个开关」的组合出现四次，抽成一个 SettingRow 一次改完。
- [ ] **UI-59**（打磨）六组 ToggleButton 一套外观承担三种语义：五组互斥单选、一组独立多选，选中态还是 primary 实心、与紧邻 10dp 的主操作「导出」同一色阶
      - 位置：`LibraryScreen.kt:61、DetailScreen.kt:112-115、SettingsScreen.kt:499-504、SettingsScreen.kt:524、SettingsScreen.kt:526、SettingsScreen.kt:547、SettingsScreen.kt:673、PlatformPlayerSettings.desktop.kt:133-150、PlatformPlayerSettings.desktop.kt:232-236、Theme.kt:128`
      - 改法：五个单选组换成 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`（连体外观自带互斥暗示）；备份的两个选项换成带标签的 Checkbox 行或 ListItem(checked, onCheckedChange, trailingContent = { Checkbox(onCheckedChange = null) })，与同页「深色主题」Switch 行的表达统一，也就顺手和下方的主按钮拉开了色阶。
- [ ] **UI-60**（打磨）仓库自带统一的 Chip 组件，桌面播放器却另用 AssistChip(enabled=false) 伪装只读状态：两套长相，读屏还念成「按钮，已停用」
      - 位置：`Components.kt:397-409、DetailScreen.kt:322-331、HomeScreen.kt:193-198、InternalPlayer.desktop.kt:396-417、InternalPlayer.desktop.kt:405-416、InternalPlayer.desktop.kt:332、Theme.kt:23、Theme.kt:57`
      - 改法：EnhancementChip 内部换成本仓库已有的 `Chip("$label · $suffix")`，或换成 `Row { Box(8dp 圆点，颜色按状态) + Text(labelSmall) }` / Badge——两条路都能去掉「已停用按钮」的语义，也让播放条和详情页的状态标签长得一样。无论选哪种，全 App 只留一套 chip 实现。
- [ ] **UI-61**（打磨）Card / Surface 的点击挂在 Modifier.clickable 上而不是用 onClick 重载：水波纹与 hover 高亮画在 clip 之前，会溢出圆角
      - 位置：`DetailScreen.kt:691-704、SettingsScreen.kt:649-653、IdentifyDialog.kt:256-259、MergeDialog.kt:155-158、Theme.kt:128-129、Components.kt:178-187`
      - 改法：SettingsScreen.kt:649、IdentifyDialog.kt:256、MergeDialog.kt:155 改用 `Surface(onClick = ...)` 重载（目录行更适合直接用 `ListItem(onClick, leadingContent = { Icon(Folder) }, headlineContent = { Text(name) })`）；DetailScreen.kt:691 因为要保留长按，把 Modifier 顺序改成 `.clip(MaterialTheme.shapes.medium).secondaryClick(...).combinedClickable(...)` 即可。
- [ ] **UI-62**（打磨）分节标题有三套实现：SectionHeader 组件、六份无 vertical padding 的手写副本、以及首页那个被套两层 20dp 缩进到 40dp 的；标题到正文的间距在 0/8/12/13dp 之间随机，节尾 20/24dp 混用
      - 位置：`Components.kt:118-131、SettingsScreen.kt:82、SettingsScreen.kt:115、SettingsScreen.kt:207-208、SettingsScreen.kt:239-240、SettingsScreen.kt:291-292、SettingsScreen.kt:364-365、SettingsScreen.kt:488-489、SettingsScreen.kt:226、SettingsScreen.kt:468、HomeScreen.kt:118-119、PlatformPlayerSettings.desktop.kt:76`
      - 改法：六处手写标题全部换成 SectionHeader 并删掉各自的首个 Spacer；HomeScreen.kt:118 去掉外层 Column 的 horizontal padding（进度文本改用自己的 20dp padding）；节尾间距统一交给 LazyColumn 的 `verticalArrangement = Arrangement.spacedBy(24.dp)`，让每节自己不再管前后间距。页面标题（LibraryScreen.kt:46 也复用了 SectionHeader）另起一个组件或用 TopAppBar。
- [ ] **UI-63**（打磨 · 桌面）首页顶部大图没有右键菜单，同一条目在它正下方的卡片上却有
      - 位置：`Components.kt:173、DetailScreen.kt:694、ItemContextMenu.kt:50、ItemContextMenu.kt:102-107、HomeScreen.kt:53、HomeScreen.kt:149-215`
      - 改法：HeroBanner 的最外层 Box 套上 `secondaryClick` + `DropdownMenu(ItemMenuItems)`，与 PosterCard 用同一段代码。
- [ ] **UI-64**（打磨）库类型图标里「其他」与「电影」取同一个 ImageVector 完全不可分辨，「番剧」借用播放列表图标，同一个电影图标还兼任全 App 的缺图占位
      - 位置：`App.kt:207-212、App.kt:247-248、HomeScreen.kt:240-247、Components.kt:205-210、SettingsScreen.kt:593-598`
      - 改法：OTHER 换成 `Icons.Filled.FolderOpen`（或 Inventory2），ANIME 换成能与 Tv 区分又不误导的字形（如 Animation / MovieFilter）；把两份 when 合并成一个公共 `libraryIcon(kind)` 放进 ui 包供两处调用；缺图占位按 ItemKind 取图标，不要复用电影图标。
- [ ] **UI-65**（打磨）按钮文案两套规则并存：会弹系统文件框的四个命令只有两个带省略号；一对同级动作叫「立即上传」和「从云端合并」，不对仗
      - 位置：`SettingsScreen.kt:88-91、SettingsScreen.kt:108-110、SettingsScreen.kt:225、SettingsScreen.kt:283、SettingsScreen.kt:425、SettingsScreen.kt:437、SettingsScreen.kt:545、SettingsScreen.kt:574、Platform.desktop.kt:152-169、Platform.android.kt:77-79、PlatformPlayerSettings.desktop.kt:104-109`
      - 改法：「导出」改「导出…」，「从 WebDAV 添加媒体库」改「添加媒体库…」（路径来源写在按钮下方说明里）；同步区改成「上传到云端」/「从云端合并」或「立即上传」/「立即合并」，让两个词干对齐；保存类按钮统一成不带宾语的「保存」（分节标题已经说明作用域）或统一带宾语，别混。

### 无障碍与键盘可达

- [ ] **UI-67**（严重）海报卡与 LinkText 显式关掉 indication：Tab / D-pad 焦点落上去零像素变化，网格自己在滚却看不出选中了谁，按空格就盲开一个详情页
      - 位置：`Components.kt:178-187、Components.kt:156-157、Components.kt:190、Components.kt:193、Components.kt:223-241、Components.kt:386、Components.kt:390、DetailScreen.kt:698-701、Theme.kt:142`
      - 改法：两处各加一行 `val focused by interaction.collectIsFocusedAsState()`，把 Components.kt:157/:193/:223 的 `hovered` 换成 `hovered || focused`、把 :390 的下划线条件同样改成 `hovered || focused`——scale 与遮罩都是现成的，等于零新增视觉设计。想更贴 M3 就再给 Surface 加 `if (focused) Modifier.border(3.dp, colorScheme.primary, shapes.large)`。
- [ ] **UI-68**（严重）LinkText 可点击时写死 primary 并丢弃传入的 color：同一行剧名在紫与暖橙间随数据跳色，与紧邻正文亮度比 1.00:1，触摸目标只有 20dp
      - 位置：`Components.kt:357-394、Components.kt:361、Components.kt:381-393、Components.kt:388、Components.kt:390、HomeScreen.kt:178-183、DetailScreen.kt:303-308、SettingsScreen.kt:121-126、PlayerScreen.kt:87`
      - 改法：可点击分支尊重 `color` 参数，把「可点」交给下划线表达而不是颜色，并在非鼠标平台常驻下划线（Components.kt:164 已经在维护 lastPointer）；在 clickable 之前插入 `Modifier.heightIn(min = 48.dp)` 或 `padding(vertical = 14.dp)`（padding 必须写在 clickable 之前才算进命中区）；补 `Modifier.semantics { role = Role.Button }`，现在读屏把它当纯文本。
- [ ] **UI-69**（严重 · Android）TalkBack 播报的「长按」是个死动作：右键/长按菜单被 lastPointer 判断挡住，无障碍派发的 OnLongClick 永远不满足条件
      - 位置：`Components.kt:164、Components.kt:184-186、ItemContextMenu.kt:50-58、ItemContextMenu.kt:56、DetailScreen.kt:698-701`
      - 改法：把条件从「上次按下的是触摸」改成「上次按下的不是鼠标」：`{ if (lastPointer != PointerType.Mouse) menuAt = Offset.Zero }`——初值 Unknown 就能通过，鼠标长按仍然不会开菜单。同时给 combinedClickable 补上 `onLongClickLabel = "打开菜单"`。Components.kt 与 DetailScreen.kt 两处各改一行。
- [ ] **UI-70**（严重 · Android）海报卡的「看了一半」对读屏是彻底空白，还额外多出一个只念百分比的无名焦点停靠点，片名被念两遍
      - 位置：`Components.kt:167、Components.kt:199、Components.kt:214-221、Components.kt:243-256、Components.kt:274-280、DetailScreen.kt:720-730`
      - 改法：把 Components.kt:199 改成 `contentDescription = null`（图片是装饰，名字下面已经有了）；给 Components.kt:167 的 Column 加 `Modifier.semantics { stateDescription = when { item.userData.played -> "已观看"; progress > 0.01f -> "已看 ${(progress*100).toInt()}%"; else -> "未观看" } }`；给进度条加 `Modifier.clearAndSetSemantics {}` 去掉那个多余的焦点点。DetailScreen.kt:720-730 的分集进度条同样处理。
- [ ] **UI-71**（严重）收藏与「手动指定刮削条目」两个 IconButton 的状态没进 contentDescription；后者连视觉上也只靠颜色区分
      - 位置：`DetailScreen.kt:429-436、DetailScreen.kt:432、DetailScreen.kt:445、DetailScreen.kt:467-476、DetailScreen.kt:775、ItemContextMenu.kt:167、Theme.kt:23、Theme.kt:41`
      - 改法：最小改法把 DetailScreen.kt:432 换成 `contentDescription = if (item.userData.favorite) "取消收藏" else "收藏"`（与 :445、ItemContextMenu.kt:167 保持一致）；更正确的做法是改用 `IconToggleButton(checked = ..., onCheckedChange = ...)` 并加 `Modifier.semantics { stateDescription = ... }`。Edit 按钮除了动态描述（加上「（已锁定 tmdb）」这类后缀）还要给两态不同的字形，别只换 tint。
- [ ] **UI-72**（打磨）三个对话框都不把 Enter 绑到默认按钮、打开时也不落焦点：Esc 能关但 Enter 不能确认，键盘路径是单向的
      - 位置：`IdentifyDialog.kt:130、IdentifyDialog.kt:176-183、IdentifyDialog.kt:198-211、IdentifyDialog.kt:212-217、IdentifyDialog.kt:244-249、SettingsScreen.kt:664-669、MergeDialog.kt:132-139`
      - 改法：给「片名」加 `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)` + `keyboardActions = KeyboardActions(onSearch = { search() })`；给「条目 id」加 `onDone = { if (providerId.isNotBlank()) apply(providerId) }`；对话框打开时用 FocusRequester 把焦点放到「条目 id」或「片名」上。WebDavPickerDialog 与 MergeDialog 同样处理。
- [ ] **UI-73**（打磨 · Android）全应用 15 个输入框没有一个声明 imeAction：安卓上填任何一段表单都是「打一个字段 → 收一次键盘 → 点下一个字段」
      - 位置：`SettingsScreen.kt:209-218、SettingsScreen.kt:246-264、SettingsScreen.kt:399、SettingsScreen.kt:664、IdentifyDialog.kt:176、IdentifyDialog.kt:198、IdentifyDialog.kt:205、MergeDialog.kt:132、SearchScreen.kt:47、PlatformPlayerSettings.desktop.kt:155`
      - 改法：给每段表单里除最后一个字段外的都加 `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)`，最后一个用 `ImeAction.Done` + `keyboardActions = KeyboardActions(onDone = { 保存() })`；地址字段顺带 `keyboardType = KeyboardType.Uri`，密码字段 `KeyboardType.Password`。
- [ ] **UI-74**（打磨）搜索页进去不自动聚焦输入框，桌面也没有任何进入搜索的快捷键
      - 位置：`SearchScreen.kt:40-44、SearchScreen.kt:47-55`
      - 改法：加 `val focusRequester = remember { FocusRequester() }` 挂到 modifier 上，配 `LaunchedEffect(Unit) { focusRequester.requestFocus() }`（进入 Screen.Search 时触发一次）；桌面端顺手在 Main.kt 的 Window 上加 onPreviewKeyEvent 处理 Ctrl+F → `state.navigate(Screen.Search)`。
- [ ] **UI-75**（打磨 · Android）搜索框只有 placeholder 没有 label，输入之后读屏念不出这是什么框
      - 位置：`SearchScreen.kt:47-55、SearchScreen.kt:50、SearchScreen.kt:52`
      - 改法：给 SearchScreen.kt:47 的 OutlinedTextField 加 `label = { Text("搜索") }`（M3 会把它上浮成持久标签）；若不想要浮动标签，就在 modifier 上加 `.semantics { contentDescription = "搜索媒体库" }`。

