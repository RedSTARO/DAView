# DAView 体验审查

把 DAView 当成一个每天要用的媒体库跑一遍：配库、找片、看片、记进度、维护库，对照 Jellyfin / Emby / Plex 找不方便和不合适的地方。
只记用户能感知的问题，不记纯代码质量问题。

| | |
| --- | --- |
| 审查树状态 | `d64e12e` + 当时未提交的桌面 libmpv 改动（现已是 `01c13e4`） |
| 条目 | 提出 121 条，经对抗验证存活 66 条，复核追加 3 条，共 **69** 条 |
| 严重度 | 阻断 **5** · 严重 **35** · 打磨 **29** |
| 方法 | 16 个维度各派一个审查者读代码，每条结论由另一个审查者尝试推翻 |

严重度的定义：

- **阻断** — 常见路径下功能直接不可用、卡死或丢数据。
- **严重** — 每天都会碰到的明显不便，三家成熟实现都有而这里没有。
- **打磨** — 碰到会皱眉，但能绕过。

---

## 只改五件事的话

按「日常使用频次 × 痛感」排，不是按严重度排。

1. Android 播放时保持屏幕常亮（InternalPlayer.android.kt:134-143 加 keepScreenOn）：手机上每看一部片都会撞到，屏幕自己黑掉等于核心功能不可用，而修复是一行代码——投入产出比全表最高。
2. 媒体库分页/触底加载 + 页头用 page.total（AppState.kt:180、LibraryScreen.kt:73-79）：500 条以外的片在浏览路径上根本不存在，而唯一的退路——全局搜索——自己也被砍在 60 条且不告知，两条路同时断，库越大越致命。
3. 外置播放器会话不因空闲被回收，关窗前先问一句（PlaybackService.kt:281-285、Main.kt:20-23）：桌面端两个最常见的中断点都发生在「正在看」的时候，暂停去接个电话回来就断流、后半段进度全丢，而且用户完全不知道为什么。
4. 片尾自动播下一集（PlaybackController.kt:141-163、InternalPlayer.android.kt:106-111，nextEpisodeUnder 已现成）：追剧一晚上要手动重复五六次「退出播放器→回详情页→找下一集→点播放」，是日常操作次数最多的一件事，两端都缺。
5. 把收藏和「继续观看」的出口补上（refreshHome 加一行 favorite 查询；user_data 加 hidden_from_resume）：一个是存进去取不出来（后端 Repository.kt:284 早就写好了，前端零调用），一个是进去了删不掉、只能撒谎说看完并丢掉进度——两个每天都在用的状态位都是单向的。

---

## 目录

### 上手与配置：建库、扫描规则、刮削设置（阻断 1 · 严重 4 · 打磨 2）

- [阻断] [电影库里一个目录只留体积最大的那个视频，其余静默丢弃](#电影库里一个目录只留体积最大的那个视频其余静默丢弃)
- [严重] [扫描没有任何排除规则，@eaDir / #recycle / .stfolder 会被建成空条目、配上 FALLBACK 海报、还删不掉](#扫描没有任何排除规则eadir--recycle--stfolder-会被建成空条目配上-fallback-海报还删不掉)
- [严重] [一个媒体库只能绑一个目录：电影分散在几个目录就只能建成几个互不相干的库](#一个媒体库只能绑一个目录电影分散在几个目录就只能建成几个互不相干的库)
- [严重] [完全不读目录里现成的 .nfo，从 Jellyfin/Emby/tinyMediaManager 搬过来的整套元数据全部作废](#完全不读目录里现成的-nfo从-jellyfinembytinymediamanager-搬过来的整套元数据全部作废)
- [严重] [设置里的「元数据语言」是个死设置，填什么都没用](#设置里的元数据语言是个死设置填什么都没用)
- [打磨] [S01E01-E02 双集文件只算一集：解析器已解出 endEpisode 却无人读取](#s01e01-e02-双集文件只算一集解析器已解出-endepisode-却无人读取)
- [打磨] [建库时的「其他」类型与电影库完全等价，选它扫出 0 项时空态还在劝你「去扫描一次」](#建库时的其他类型与电影库完全等价选它扫出-0-项时空态还在劝你去扫描一次)

### 找片（一）：首页与发现（严重 3 · 打磨 3）

- [严重] [「接下来」会推荐你还没看完的那一集的下一集，同一部剧同时占据两行](#接下来会推荐你还没看完的那一集的下一集同一部剧同时占据两行)
- [严重] [「接下来」按集号排序而不是「最近看的剧优先」，刚追的剧排到行尾甚至被 20 条上限截掉](#接下来按集号排序而不是最近看的剧优先刚追的剧排到行尾甚至被-20-条上限截掉)
- [严重] [「最近添加」过滤掉全部分集，剧集库的新增在这一行永远不出现；24 条还跨库混排](#最近添加过滤掉全部分集剧集库的新增在这一行永远不出现24-条还跨库混排)
- [打磨] [「XX · 未观看」是固定的字母序前 24 条、永不变化，首页没有随机/推荐/类型这类帮你挑片的行](#xx--未观看是固定的字母序前-24-条永不变化首页没有随机推荐类型这类帮你挑片的行)
- [打磨] [首页版块顺序与显隐不可配置，顶部大图必然与「继续观看」第一张卡重复且关不掉](#首页版块顺序与显隐不可配置顶部大图必然与继续观看第一张卡重复且关不掉)
- [打磨] [没有合集、没有播放列表、也没有相似推荐：看完一部就断路了](#没有合集没有播放列表也没有相似推荐看完一部就断路了)

### 找片（二）：媒体库浏览、筛选与搜索（阻断 1 · 严重 4 · 打磨 5）

- [阻断] [媒体库一次最多装 500 条且没有分页，第 501 部之后的片子在浏览路径上根本不存在](#媒体库一次最多装-500-条且没有分页第-501-部之后的片子在浏览路径上根本不存在)
- [严重] [没有库内搜索，全局搜索写死 60 条、按名字排序、无声截断也不能翻页](#没有库内搜索全局搜索写死-60-条按名字排序无声截断也不能翻页)
- [严重] [媒体库页没有任何筛选，Jellyfin 的整个筛选抽屉在这里是空白；详情页的类型标签也点不动](#媒体库页没有任何筛选jellyfin-的整个筛选抽屉在这里是空白详情页的类型标签也点不动)
- [严重] [收藏是个死胡同：后端查询早就写好了，前端一个读取点都没有，卡片上也没有收藏标记](#收藏是个死胡同后端查询早就写好了前端一个读取点都没有卡片上也没有收藏标记)
- [严重] [从详情页返回媒体库，整个网格重新查库、滚动位置回到最顶上](#从详情页返回媒体库整个网格重新查库滚动位置回到最顶上)
- [打磨] [只有海报网格一种视图，既不能切列表/详情视图，也不能调网格密度](#只有海报网格一种视图既不能切列表详情视图也不能调网格密度)
- [打磨] [排序方向写死，想反着排（片名 Z→A、年份从老到新）只能自己滑到列表末尾](#排序方向写死想反着排片名-za年份从老到新只能自己滑到列表末尾)
- [打磨] [手机上排序那一行放不下，最后一个「最近播放」被压成看不清的窄条](#手机上排序那一行放不下最后一个最近播放被压成看不清的窄条)
- [打磨] [大库里没有字母跳转，桌面端连滚动条都没加](#大库里没有字母跳转桌面端连滚动条都没加)
- [打磨] [演职人员卡片不可点击，也没有按人物检索的入口](#演职人员卡片不可点击也没有按人物检索的入口)

### 看片：播放链路与播放器（阻断 3 · 严重 11 · 打磨 4）

- [阻断] [关掉 DAView 窗口，正在外置播放器里看的片子会立刻断流，没有任何提示](#关掉-daview-窗口正在外置播放器里看的片子会立刻断流没有任何提示)
- [阻断] [外置播放器暂停超过 5 分钟，会话被回收、本地管道被关掉，之后一拖进度条就断流、后半段进度全丢](#外置播放器暂停超过-5-分钟会话被回收本地管道被关掉之后一拖进度条就断流后半段进度全丢)
- [阻断] [Android 播放中屏幕会按系统超时自动熄灭](#android-播放中屏幕会按系统超时自动熄灭)
- [严重] [播完一集就到头了：两端都不自动播下一集，系列页也没有「播放全部」](#播完一集就到头了两端都不自动播下一集系列页也没有播放全部)
- [严重] [Android 播放器不是独立全屏形态：导航骨架常驻、不自动横屏、也没有方向锁](#android-播放器不是独立全屏形态导航骨架常驻不自动横屏也没有方向锁)
- [严重] [没有 MediaSession：切后台音频还在响却没有任何控制入口，也没有画中画和音频焦点](#没有-mediasession切后台音频还在响却没有任何控制入口也没有画中画和音频焦点)
- [严重] [系统返回键/返回手势不返回上一页，直接退出整个 App](#系统返回键返回手势不返回上一页直接退出整个-app)
- [严重] [完全没有离线下载，出门断网这个 App 就是个空壳](#完全没有离线下载出门断网这个-app-就是个空壳)
- [严重] [外挂字幕只认「同一目录且严格同名」，Subs/ 子目录不看，`.简日双语.ass` 也会被丢，且没有手动挂载入口、没有延迟与字号](#外挂字幕只认同一目录且严格同名subs-子目录不看简日双语ass-也会被丢且没有手动挂载入口没有延迟与字号)
- [严重] [音轨和内嵌字幕轨的选择传不给外置播放器，播放器里选的也回不来](#音轨和内嵌字幕轨的选择传不给外置播放器播放器里选的也回不来)
- [严重] [默认用哪个播放器不能选也不会记住，永远按写死的顺序挑第一个](#默认用哪个播放器不能选也不会记住永远按写死的顺序挑第一个)
- [严重] [外部播放面板的说明文字描述的是一个已经不存在的架构，而且承诺了做不到的事](#外部播放面板的说明文字描述的是一个已经不存在的架构而且承诺了做不到的事)
- [严重] [外置播放器一启动，界面就什么都不显示了——整块外部播放面板是不可达的死代码](#外置播放器一启动界面就什么都不显示了整块外部播放面板是不可达的死代码)
- [严重] [播放失败只有详情页看得到，从首页、媒体库、搜索页点播放失败是彻底静默的；启动过程也没有任何等待反馈](#播放失败只有详情页看得到从首页媒体库搜索页点播放失败是彻底静默的启动过程也没有任何等待反馈)
- [打磨] [没有「从头播放」：看过一半的片，播放键永远是「继续」](#没有从头播放看过一半的片播放键永远是继续)
- [打磨] [Android 播放器没有任何手势，自绘的音轨/字幕按钮和标题常驻挡画面，还与 media3 自带字幕按钮形成两套入口](#android-播放器没有任何手势自绘的音轨字幕按钮和标题常驻挡画面还与-media3-自带字幕按钮形成两套入口)
- [打磨] [mkv 里的章节从不读取，Android 内置播放器没有章节导航](#mkv-里的章节从不读取android-内置播放器没有章节导航)
- [打磨] [桌面播放器菜单里那条「自定义播放器」永远点不动：写入它的函数全仓库没有调用点](#桌面播放器菜单里那条自定义播放器永远点不动写入它的函数全仓库没有调用点)

### 状态、进度与跨端同步（严重 4 · 打磨 1）

- [严重] [「继续观看」里的条目移不掉，唯一的办法是撒谎说自己看完了，还会顺带清空进度](#继续观看里的条目移不掉唯一的办法是撒谎说自己看完了还会顺带清空进度)
- [严重] [扫描结束后停在首页不会自动刷新，后台同步拉回来的进度也不刷新，且没有下拉刷新](#扫描结束后停在首页不会自动刷新后台同步拉回来的进度也不刷新且没有下拉刷新)
- [严重] [停止播放不触发上传，进度只等进程内定时器——划掉 App 换设备最坏会看到十分钟前的旧进度](#停止播放不触发上传进度只等进程内定时器划掉-app-换设备最坏会看到十分钟前的旧进度)
- [严重] [同步文件会静默覆写本机的存储地址与播放设置，两台设备用不同地址访问同一份存储时互相打架](#同步文件会静默覆写本机的存储地址与播放设置两台设备用不同地址访问同一份存储时互相打架)
- [打磨] [同步其实是定时自动跑的，界面从头到尾没说，也看不到上次跑的时间和间隔](#同步其实是定时自动跑的界面从头到尾没说也看不到上次跑的时间和间隔)

### 库维护：元数据编辑与条目管理（严重 4 · 打磨 2）

- [严重] [完全没有「编辑元数据」：刮错一个字段，只能整条换刮削源或者一直忍着](#完全没有编辑元数据刮错一个字段只能整条换刮削源或者一直忍着)
- [严重] [封面/背景图完全没法换，扫描也不认目录里的 poster.jpg / fanart.jpg](#封面背景图完全没法换扫描也不认目录里的-posterjpg--fanartjpg)
- [严重] [开启同步后删除媒体库不生效：最多 60 秒后被云端文件原样恢复成一个空库，而本机刮削结果已被真删](#开启同步后删除媒体库不生效最多-60-秒后被云端文件原样恢复成一个空库而本机刮削结果已被真删)
- [严重] [删除媒体库没有任何二次确认，误触一次就抹掉本机全部刮削结果](#删除媒体库没有任何二次确认误触一次就抹掉本机全部刮削结果)
- [打磨] [没有单条「重新刮削」：一个 FALLBACK 匹配会写死 scraped_at，此后只能整库 REFRESH](#没有单条重新刮削一个-fallback-匹配会写死-scraped_at此后只能整库-refresh)
- [打磨] [official_rating 是个死字段：三个刮削器一次都没填过，详情页也不显示分级](#official_rating-是个死字段三个刮削器一次都没填过详情页也不显示分级)

### 家庭与多设备（严重 1）

- [严重] [没有用户/档案概念：共用一个系统账户的人共享一份进度与收藏，同步文件也按 item_id 整行覆盖](#没有用户档案概念共用一个系统账户的人共享一份进度与收藏同步文件也按-item_id-整行覆盖)

### 凭据与隐私（严重 1 · 打磨 5）

- [严重] [WebDAV 密码和三个 API Key 的输入框全程明文显示，没有遮蔽也没有小眼睛](#webdav-密码和三个-api-key-的输入框全程明文显示没有遮蔽也没有小眼睛)
- [打磨] [凭据只能替换不能抹除，也不告诉你配置文件在哪](#凭据只能替换不能抹除也不告诉你配置文件在哪)
- [打磨] [TMDB API Key 明文出现在 URL 里，任何一次请求失败都会把它整条写进日志](#tmdb-api-key-明文出现在-url-里任何一次请求失败都会把它整条写进日志)
- [打磨] [填 http:// 的 WebDAV 无任何提示，而明文链路上传的是网盘主账号密码本身](#填-http-的-webdav-无任何提示而明文链路上传的是网盘主账号密码本身)
- [打磨] [Android 未声明备份排除规则，明文 config.json（含网盘密码与 API Key）会进厂商云备份/换机迁移](#android-未声明备份排除规则明文-configjson含网盘密码与-api-key会进厂商云备份换机迁移)
- [打磨] [勾选「包含凭据」后导出明文密码文件没有二次确认，只有一段红字说明](#勾选包含凭据后导出明文密码文件没有二次确认只有一段红字说明)

### 呈现、文案与性能打磨（严重 3 · 打磨 7）

- [严重] [首播日期、制作方、分级刮回来了却一个都不显示；全 App 看不到任何日期时间](#首播日期制作方分级刮回来了却一个都不显示全-app-看不到任何日期时间)
- [严重] [详情页简介固定截断 5 行，既不能展开也不能选中复制](#详情页简介固定截断-5-行既不能展开也不能选中复制)
- [严重] [海报下载失败与「压根没刮到图」在界面上无法区分，失败还不记忆——每次滚回视口都重付一次 15s/30s 超时](#海报下载失败与压根没刮到图在界面上无法区分失败还不记忆每次滚回视口都重付一次-15s30s-超时)
- [打磨] [扫描进度与刮削源在界面里漏出内部标识（scraping/probing、小写 tmdb），仓库里已有的中文映射只用在 Android 通知栏](#扫描进度与刮削源在界面里漏出内部标识scrapingprobing小写-tmdb仓库里已有的中文映射只用在-android-通知栏)
- [打磨] [几处文案拿实现细节当解释，普通用户读不懂](#几处文案拿实现细节当解释普通用户读不懂)
- [打磨] [回到首页/标记已看会整体重建首页数据，各媒体库「未观看」整行在补数据前会先塌陷一次](#回到首页标记已看会整体重建首页数据各媒体库未观看整行在补数据前会先塌陷一次)
- [打磨] [海报 URL 已经在列表响应里拿到过却被丢弃，每次内存缓存未命中都要为一张缩略图重跑一次整条 item 查询](#海报-url-已经在列表响应里拿到过却被丢弃每次内存缓存未命中都要为一张缩略图重跑一次整条-item-查询)
- [打磨] [单连接 + 全局互斥锁抵消了 WAL：扫描的写入阶段和刮削开始前的全库读会让界面数据慢一拍](#单连接--全局互斥锁抵消了-wal扫描的写入阶段和刮削开始前的全库读会让界面数据慢一拍)
- [打磨] [图片缓存只增不减、没有任何清理入口，Android 上落在 filesDir，系统「清除缓存」清不掉](#图片缓存只增不减没有任何清理入口android-上落在-filesdir系统清除缓存清不掉)
- [打磨] [界面语言不可切换、文案全部硬编码为中文](#界面语言不可切换文案全部硬编码为中文)

---

## 上手与配置：建库、扫描规则、刮削设置

### 电影库里一个目录只留体积最大的那个视频，其余静默丢弃

**阻断** · `core/src/core/com/daview/server/library/Scanner.kt:95-102`

Scanner.kt:100 `val main = videos.maxByOrNull { it.size ?: 0 } ?: videos.first()`，Scanner.kt:101-102 只 return 这一条记录，同目录其余视频既不建条目也不进 Result.warnings（warnings 只在 Scanner.kt:57-59 的异常分支写入）。最常见的触发不是 4K/1080p 双版本，而是分类目录：Scanner.kt:95-99 只在目录内一个视频都没有时才递归子目录，所以 `/电影/漫威系列/钢铁侠1.mkv、钢铁侠2.mkv、钢铁侠3.mkv` 只进一条，标题还取自文件夹名（Scanner.kt:101 parseTitle(folder.name)），另两部在 App 里彻底不存在。CD1/CD2 分卷同理，点播放看到一半直接结束。App 内无法补救：MediaFacade 只有 merge/unmerge（MediaFacade.kt:224、235），没有手动添加条目，而 merge 要求两个条目都已存在；只有把文件平铺在库根目录时（Scanner.kt:63-68 looseVideos 分支）两个文件才都会进库。Jellyfin/Emby 的多版本折叠需要 `电影 (2021) - 4K.mkv` 这类命名，Plex 叫 Versions，但无论命名合不合规，文件都不会消失——差距在这里。

> **改法**：先别做版本下拉：把同目录被丢弃的视频作为独立条目建出来（名字带文件名区分），并把「本目录有 N 个视频，只收录了 1 个」写进 Scanner.Result.warnings 让扫描完成时能点开看。之后再按 `- cd1/- part1` 后缀识别分卷。

### 扫描没有任何排除规则，@eaDir / #recycle / .stfolder 会被建成空条目、配上 FALLBACK 海报、还删不掉

**严重** · `core/src/core/com/daview/server/library/Scanner.kt:137-156`

把 WebDAV 指向群晖/威联通共享目录（自托管用户的默认场景），每层目录下的 @eaDir、共享根的 #recycle、Syncthing 的 .stfolder 都会被扫进来。剧集/番剧库最明显：Scanner.kt:137-156 对每个非季、非花絮目录无条件写一条 SERIES 记录，不检查里面有没有视频；顶层过滤只有 Scanner.kt:41 + NameParser.kt:24-28 的硬编码花絮名单。这些空壳条目还会进刮削队列（Repository.kt:434-439），MetadataService.kt:125-138、190-198 的 fallbackMatch 只按年份 ±1 过滤，文件夹名无年份时等于直接取 provider 第一条，于是配上一张莫名其妙的海报和简介。电影库的 #recycle 若落在库路径内（Scanner.kt:95-99 无视频时递归），被你删掉的片子又会回到库里。最后没有退路：MediaFacade 只有 merge/unmerge（MediaFacade.kt:224/235），没有 hide/delete item，query 也不过滤空 SERIES（Repository.kt:277-314），它们还计入库条目数（Repository.kt:55），重扫又会回来。Jellyfin/Emby 的库设置有「排除的文件/文件夹路径」两栏并支持目录下放 `.ignore`，Plex 内置忽略 @eaDir、#recycle 等 NAS 系统目录，都在添加媒体库的第一屏。

> **改法**：两步：默认跳过以 @、#、. 开头的目录以及 @eaDir/#recycle/#snapshot/.stfolder/lost+found（几行）；剧集库只在目录下真的找到视频或季目录时才建 SERIES。之后给 LibraryDto 加 excludePaths 并在建库对话框放一个多行输入框。

### 一个媒体库只能绑一个目录：电影分散在几个目录就只能建成几个互不相干的库

**严重** · `shared/src/commonMain/kotlin/com/daview/shared/model/Models.kt:66`

网盘上的电影通常在 /电影、/4K电影、/新入库 几处，或两块盘各挂一个子路径。LibraryDto.path 是单个字符串（Models.kt:66），libraries 表也是单列（Database.kt:78），建库对话框只维护一个 path 并单值提交（SettingsScreen.kt:604、685），Scanner.scan 从单一路径起步（Scanner.kt:39-40），MediaFacade.createLibrary/updateLibrary 只接受一个 path（MediaFacade.kt:115-133）。碎片化没有想象中彻底——首页的继续观看/接下来/最近添加与搜索都是跨库的——真正碎的是每库一行的未观看（HomeScreen.kt:107-113）和媒体库页只能在单库内排序（LibraryScreen.kt:37-61），永远没有「全部电影按年份排」。事后补救更麻烦：库 id 由路径推导（MediaFacade.kt:121、Scanner.kt:365），条目 id 由「库 id + 文件路径」推导（Scanner.kt:356），改路径等于整库条目 id 全变。Jellyfin 添加媒体库时「文件夹」区有一个「+」可加任意多个媒体位置，Emby 的媒体文件夹、Plex 的 Add Folders 同样支持，这是三家添加流程第一屏的能力。

> **改法**：把 LibraryDto.path 扩成 paths: List<String>（保留 path 兼容读），Scanner.scan 对每个 path 各走一遍 dav.list 后合并 records；libraryId 改成按所有路径排序拼接后推导。UI 上在路径行后加「+ 再添加一个目录」。

### 完全不读目录里现成的 .nfo，从 Jellyfin/Emby/tinyMediaManager 搬过来的整套元数据全部作废

**严重** · `core/src/core/com/daview/server/scraper/MetadataService.kt:37-44`

从其它软件迁过来的用户，网盘上每部片旁边已经躺着 movie.nfo / tvshow.nfo / S01E01.nfo，里面是中文标题、简介、演员，还有两年里手工改对的条目。DAView 完全不认：`grep -rn -i 'nfo' --include=*.kt` 除 Info 子串外只命中 NameParser.kt:277 的 `.nfo.bak`——nfo 只在垃圾文件名单里出现过一次。MetadataProvider 只有 tmdb/tvdb/bangumi/none（Models.kt:34-38），defaultOrder 四种库类型全是网络源（MetadataService.kt:37-44），Scanner 只 list 视频与字幕（Scanner.kt:42-44、93-94、242-244），不读任何文本文件，也不读同目录的 poster.jpg/fanart.jpg。而 API Key 是硬门槛（SettingsScreen.kt:241 提示需自行申请，README.md:332 承认没 Key 时只按文件名建库）。Jellyfin 添加库时 Nfo 读取器默认勾选并优先采信本地 nfo，Emby 同样有 Nfo 读写开关，这是三家互相迁移的事实标准载体，中文圈的 tinyMediaManager/MediaElch/一键刮削脚本产出的也是 nfo + poster.jpg。对一个无服务端、只读网盘、还要用户自备 Key 的应用，读 nfo 是最划算的一条路：nfo 与视频同在一次 dav.list 结果里。

> **改法**：在 MetadataProvider 里加一档「本地 NFO」并作为所有库类型 defaultOrder 的第一位：Scanner 收集目录里的 *.nfo，用 4MB 上限的 dav.readFully 读进来解析 title/originaltitle/plot/year/premiered/genre/rating/uniqueid，命中就写 ScrapeStatus.MATCHED 并跳过网络刮削。

### 设置里的「元数据语言」是个死设置，填什么都没用

**严重** · `composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:684`

设置 → 刮削源 → 把 zh-CN 改成 ja-JP → 保存 → 重新刮削全部 → 等十几分钟，标题简介仍然全是简体中文，界面没有任何提示，用户只会怀疑自己填错格式。全仓库对 scraper 语言的使用只有 5 处，其中 ScanService.kt:92 `config.scraper.copy(language = library.language)`、MetadataService.kt:68 `config.copy(language = library.language)`、MediaFacade.kt:483-484 的 scraperConfigFor（手动识别的搜索也走它）全部用 library.language 覆盖掉全局值。而 library.language 的唯一写入点是 SettingsScreen.kt:684 硬编码 `language = "zh-CN"`，MediaFacade.kt:129 的 updateLibrary 在 composeApp 里零调用，库卡片（SettingsScreen.kt:125-170）也没有编辑入口。scrapers 确实按 language 发请求（Scrapers.kt:191-215、273-280），所以这不是「填了没差别」而是「值根本传不进去」。Jellyfin 把「显示语言」和 per-library 的「首选下载语言」拆成两套设置，Emby 的 Library Setup 同样有 Preferred download language。

> **改法**：把 ScraperSection 的「元数据语言」输入框改成写当前库的 language（复用已有的 MediaFacade.updateLibrary），或在建库对话框和库卡片上各加一个语言选择；同时把自由文本换成下拉（zh-CN / zh-TW / ja-JP / en-US），现在填错一个字母 TMDB 直接返回英文也不报错。

### S01E01-E02 双集文件只算一集：解析器已解出 endEpisode 却无人读取

**打磨** · `core/src/core/com/daview/server/library/Scanner.kt:299-300`

番剧和美剧常见 `Show - S01E01-E02.mkv`（前后篇、总集篇）。NameParser.kt:149 的正则确实捕获第三组并填进 EpisodeInfo.endEpisode（NameParser.kt:143、171），但 Scanner.kt:299-300 只读 parsed?.episode，全仓库 grep endEpisode 只有 NameParser.kt 那三处，没有任何读取点。结果剧集列表变成 1、3、4、5 缺号，用户第一反应是「文件没扫全」去重扫、去检查网盘。集数统计按 COUNT(*) 一个文件算一集（Repository.kt:885-890），整季进度永远差一集，「整季已看」判定（Models.kt:179-182 playedEpisodeCount == episodeCount）也对不上。注意 nextUp 取的是最早未看集（Repository.kt:367-394 按 runOrderSlot 取 MIN），E01 标记已看后直接给 E03，症状是跳过而不是循环。Jellyfin/Emby 支持 multi-episode 文件、列表里显示为「1-2」，Plex 的 Stacking 同样识别。

> **改法**：在 episodeRecord 里读上 endEpisode，条目名做成「第 1-2 集」并在 DTO 上带 endIndexNumber；哪怕只做到名字显示成 1-2，也能消除「文件扫漏了」的误判。进一步再把区间集数算进 playedEpisodeCount。

### 建库时的「其他」类型与电影库完全等价，选它扫出 0 项时空态还在劝你「去扫描一次」

**打磨** · `composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:671-677`

第一次配库看到「电影 / 电视剧 / 番剧 / 其他」四个类型，很自然地用「其他」把有声书或音乐目录加进来。扫描正常跑完显示「完成」，媒体库里 0 项，界面没有一句话解释。实际上 LibraryKind.OTHER 的 isSeriesLike 为 false（Models.kt:14）因而走 scanMovieFolder（Scanner.kt:52），刮削源是 TMDB（MetadataService.kt:43），而 videoExtensions 全是视频容器、仓库无音频扩展名集合（NameParser.kt:17-20），Scanner.kt:42/93/243 一律用 isVideoFile 过滤。ScanService.kt:110-116 只报「完成」不报「未发现视频文件」，LibraryScreen.kt:68-71 的空态还在劝用户「在设置里对它执行一次扫描」——用户刚扫完。做真正的音乐/有声书库不在这个应用的定位内（ItemKind 只有 MOVIE/SERIES/SEASON/EPISODE），但「其他」这个选项名与实际语义不符是文案设计问题。

> **改法**：把 OTHER 从建库对话框里去掉（显式列三项），或改名为「其他视频（不刮削）」并把 defaultOrder 设为 NONE；扫描结束时若 itemCount == 0，在进度条上写「未发现视频文件」而不是只显示「完成」。

## 找片（一）：首页与发现

### 「接下来」会推荐你还没看完的那一集的下一集，同一部剧同时占据两行

**严重** · `core/src/core/com/daview/server/db/Repository.kt:343-366`

S01E01-E04 看完、E05 看到 40% 退出，回到首页：「继续观看」是 E05（进度条 40%），紧挨着的「接下来」是同一部剧的 E06。Repository.kt:349 把 nextUp 候选写死为 `AND COALESCE(u.position_ms, 0) = 0`，把「看了一半的集」排除在候选之外；第 355-362 行的 NOT EXISTS 又用同一组 `played=0 AND position_ms=0` 判断「是否存在更早的未看集」，所以 E05（position>0）既不是候选也不构成阻挡，E06 必然入选。而 resume 的条件恰好是 `position_ms > 0 AND played=0`（Repository.kt:330-333），两个查询对「看一半」的处理互斥。AppState.kt:160-164 只是把两者并列塞进 HomeData，全链路无去重。两张卡还共用同一张剧集海报（Repository.kt:798-800 episode 无剧照时回落剧集海报），只有副标题的 S01E05/S01E06 能区分。Jellyfin 的 /Shows/NextUp 有 enableResumable 且默认 true——正在看的那一集本身就是 Next Up 的返回值；Plex 更彻底，只有一个 On Deck，不区分「看一半」与「下一集」。

> **改法**：两选一：把 nextUp 候选条件从 `position_ms = 0` 改为仅 `played = 0`（让 resumable 的那一集本身作为 next up），再在 HomeScreen 对 resume 已出现的 seriesId 去重；或保持现有语义但在 WHERE 里加 `AND NOT EXISTS (该 series 下存在 position_ms>0 AND played=0 的集)`。

### 「接下来」按集号排序而不是「最近看的剧优先」，刚追的剧排到行尾甚至被 20 条上限截掉

**严重** · `core/src/core/com/daview/server/db/Repository.kt:365`

同时追 5 部剧，昨晚刚看完 A 剧 S01E12，今天打开首页想接着看 E13，行首却是三个月前只点开过一集的 B 剧 S01E02、C 剧 S01E03。Repository.kt:365 是 `ORDER BY i.sort_name LIMIT ?`，而 episode 的 sort_name 由 Scanner.kt:312 `"%04d%04d".format(season, episode)` 生成，纯季号+集号，与剧名和观看时间无关，所以 "00010002" 排在 "00010013" 前面。而且 sort_name 不会被刮削覆盖：Repository.kt:911 的 UPSERT 用 `CASE WHEN items.scraped_at IS NULL` 保护，Repository.kt:407-411 的 itemsNeedingScrape 只取 MOVIE/SERIES。同一文件里 resume 用的却是 `ORDER BY u.last_played_at DESC`（Repository.kt:333）。上限 20 来自 AppState.kt:162，追的剧超过 20 部时集号大的干脆不出现；Components.kt:274 的 MediaRow 有 trailing 槽位，但 HomeScreen.kt:88-105 三个调用一个都没传，没有「查看全部」。Jellyfin/Emby 的 Next Up 按剧集最近观看时间倒序，Plex 的 On Deck 同样按最近活动排且 hub 右侧有 View All。

> **改法**：在 nextUp 的 SELECT 里带上该 series 的最近观看时间（子查询 MAX(ue.last_played_at) WHERE e.series_id = i.series_id），改成 `ORDER BY 该值 DESC, i.sort_name`。这一改动同时让 20 条上限变得可接受——被截掉的一定是最久没碰过的剧。

### 「最近添加」过滤掉全部分集，剧集库的新增在这一行永远不出现；24 条还跨库混排

**严重** · `core/src/core/com/daview/server/db/Repository.kt:370-374`

追一部周更番，这周网盘多了 E09，扫描完成。回到首页「最近添加」里还是上个月的几部电影——因为 Repository.kt:370-374 的 WHERE 是 `i.kind IN ('MOVIE','SERIES')`，EPISODE 被明确排除，而剧集条目本身是三个月前入库的：Repository.kt:905-936 的 ON CONFLICT SET 列表里没有 date_created（只有第 933 行的 date_modified），老剧不会因为多一集被顶上来。AppState.kt:163 `library.latest(null, 24, links)` 传 null，所有库混在一行 24 条里，会被电影挤掉。用户并非完全看不到 E09（它满足 nextUp 候选条件会进「接下来」），但受制于那一行按 sort_name 排序 + LIMIT 20 而不可靠。真正失效的是「这个库进了什么新东西」这个语义。Jellyfin 首页的「最新媒体」对电视库显示的就是最近添加的分集（同剧折叠成一张卡、角标写第几集）且每库一行，Emby 的 Latest 与 Plex 的 Recently Added 同理。

> **改法**：latest 改成 `kind IN ('MOVIE','SERIES','EPISODE')`，对 EPISODE 按 `GROUP BY COALESCE(i.series_id, i.id)` 取 MAX(date_created) 去重；同时把首页这一行拆成每库一行，HomeScreen.kt:107-113 已经是这个模式了。

### 「XX · 未观看」是固定的字母序前 24 条、永不变化，首页没有随机/推荐/类型这类帮你挑片的行

**打磨** · `core/src/core/com/daview/server/db/Repository.kt:387-403`

库里 300 部没看过的电影，首页「电影 · 未观看」今天是《A…》到《B…》这 24 部，下个月依然是。Repository.kt:400 是 `ORDER BY i.sort_name LIMIT ?`，没有随机也没有时间维度，条数来自 AppState.kt:168 写死的 24，第 25 部之后的 276 部在首页永远不会露面。而媒体库页只有五个排序按钮没有「未观看」筛选（LibraryScreen.kt:27-33、49-60），loadLibrary 也不带任何观看状态条件（AppState.kt:173-191），要找它们只能把整个网格拉一遍靠肉眼看哪张卡右上角没有勾（勾绘制在 Components.kt:185-192）。类型也帮不上忙：composeApp 里 genres 只出现在 DetailScreen.kt:288、291 两行的纯文本展示。Jellyfin 的电影库顶部有「推荐」和「类型」标签页，Emby 首页有可开关的 Suggestions 版块，Plex 首页有会轮换的推荐 hub。

> **改法**：unwatched 的 ORDER BY 换成按天变化的伪随机（对 `i.id || strftime('%j','now')` 做哈希排序），行标题改成「随便看看」；LibraryScreen 的排序按钮旁加两个 ToggleButton「未观看」「收藏」，Query 里 favorite 已现成，未观看复用 unwatched 那段 NOT EXISTS。

### 首页版块顺序与显隐不可配置，顶部大图必然与「继续观看」第一张卡重复且关不掉

**打磨** · `composeApp/src/app/com/daview/app/ui/HomeScreen.kt:53`

HomeScreen.kt:70-113 是 hero(70-80) / 媒体库快捷(82) / 继续观看(84-91) / 接下来(92-99) / 最近添加(100-102) / 每库未观看(106-113) 六个硬编码块，没有任何来自设置的输入；AppState.kt 唯一持久化的偏好是 KEY_THEME（setTheme 在 129-132），SettingsScreen.kt 全文搜「首页/主页」只命中第 433 行的 refreshHome()。真正能感知的代价只有两点：HeroBanner（HomeScreen.kt:152 heightIn 260-380dp）取的就是 home.resume.firstOrNull()（第 53 行），与紧接着「继续观看」的第一张卡必然重复且无法关闭；以及行序不可调。纯电影党不会看到空的「接下来」——Components.kt:279 `if (items.isEmpty()) return` 让空版块整块不渲染。Jellyfin 的 设置 → 显示 → 主页 有 7 个可下拉的版块槽位，Emby 几乎相同，Plex 有「自定义主页」。

> **改法**：SettingsStore 里存一个版块顺序+开关的字符串列表（如 "hero,resume,nextup,latest,unwatched"），HomeScreen 按列表 forEach 渲染，设置页给一组勾选框加上下移按钮。不需要动 core。

### 没有合集、没有播放列表、也没有相似推荐：看完一部就断路了

**打磨** · `shared/src/commonMain/kotlin/com/daview/shared/model/Models.kt:107-163`

库里 8 部哈利波特在网格里就是散落的 8 张海报，没法归成一个盒子；想攒一个「周末要看的」清单没有播放列表可加；某部片的详情页底部除了扫描时恰好挂在同一剧集文件夹下的衍生电影，没有任何「你可能还想看」。全仓库 grep collection/belongs_to/playlist/similar/recommend 只命中 Scanner.kt:96 的注释、WebDavClient.kt:146 的 WebDAV resourcetype、MetadataService.kt:395-436 的字符串相似度函数（匹配算法不是推荐接口）和 App.kt:182 拿 PlaylistPlay 当动画库图标用，没有一处是功能实现。MediaItemDto（Models.kt:119-163）无 collectionId/playlist 字段，UserDataDto（Models.kt:107-116）除 favorite 外无分组维度。DetailScreen.kt:164-170 的「相关影片」来自 detailChildren.filter { kind == MOVIE }，是 Scanner.kt:187-208 把剧集目录下嵌套的 `Title (Year)` 文件夹当衍生电影挂上去的产物，是文件结构不是推荐。Jellyfin 读 TMDB 的 belongs_to_collection 自动生成合集、详情页底部有 More Like This、侧栏有 Playlists；Emby 有 Collections + Playlists；Plex 有 Collections、Playlists 和 Related 轮播。

> **改法**：按性价比先做相似推荐：TMDB 的 /movie/{id}/similar 或 /recommendations 只是多一个请求，结果按 provider id 在本地库里过滤一遍，命中的排成一行放在详情页底部。合集和播放列表需要新表和新导航，可以往后放。

## 找片（二）：媒体库浏览、筛选与搜索

### 媒体库一次最多装 500 条且没有分页，第 501 部之后的片子在浏览路径上根本不存在

**阻断** · `composeApp/src/app/com/daview/app/data/AppState.kt:180`

1200 部电影的库，首页卡片写「1200 项」，点进去页头写「500 项」，网格滑到底就没了。AppState.kt:180 `library.items(..., limit = 500)` 是唯一一次带 libraryId 的查询，offset 始终为默认 0（MediaFacade.kt:157），MediaFacade.kt:167 `limit.coerceIn(1, 500)` 把上限钉死；LibraryScreen.kt 全文 90 行，第 73-79 行的 LazyVerticalGrid 既没有 state 也没有触底回调，全仓 grep rememberLazyGridState/Scrollbar 零命中，没有第二页的入口。两个数字必然打架：LibraryScreen.kt:43 显示 libraryItems.size，HomeScreen.kt:252 显示 library.itemCount（来自 Repository.kt:55 的 COUNT，且没有 merged_into IS NULL 条件，合并过重复条目的库还会更高）。Repository.kt:306 的 LIMIT/OFFSET 在 ORDER BY 之后，所以换排序就是换一批，同一部片凭空出现又消失。剧集库天花板是 700（500 SERIES + AppState.kt:184 另取 200 MOVIE）。Jellyfin/Emby 的 /Items 用 StartIndex+Limit 分页、响应带 TotalRecordCount 且页头显示的是它；Plex 用 X-Plex-Container-Start/Size。三家都不存在「库里有的东西翻不到」。

> **改法**：libraryItems 改成可追加的列表，记住 page.total 与当前 offset；LibraryScreen 用 rememberLazyGridState()，监听最后可见项接近末尾时 offset += 500 再取一页追加。页头改成显示 page.total。MediaFacade.kt:167 的 500 可以保留为单页大小。

### 没有库内搜索，全局搜索写死 60 条、按名字排序、无声截断也不能翻页

**严重** · `composeApp/src/app/com/daview/app/data/AppState.kt:223-226`

在电影库里想找某个片名，库页上没有搜索框（LibraryScreen.kt 全文 90 行无任何输入框），只能退回底部导航的「搜索」，那里搜的是全部库、电影剧集番剧混在一起。AppState.kt:225 `library.items(links, search = query, limit = 60).items` 既不传 libraryId 也不传 kind，还把 ItemPage.total 直接扔掉；Repository.kt:281、285-289 说明 libraryId 与 search 本来可以同时给，是调用方没用。排序仍是默认 `else -> "i.sort_name"`（Repository.kt:291-299），所以截掉的恰恰是名字排在后面的那些，而 SearchScreen.kt 全文 79 行只有空/非空两种状态（第 58-77 行），界面上没有任何提示说结果被砍了。搜索列范围也窄：只 LIKE i.name/i.original_name/i.sort_name，演员名在 Repository.kt:754 序列化进 JSON 列，进不了 WHERE。这条和 500 上限叠加后更要紧：库页只装前 500 条时搜索是唯一通路，而这条通路自己也是断的。Plex 每个库右上角有只作用于当前库的搜索框，Jellyfin/Emby 的库页搜索默认限定在当前 parentId 内，三家的全局搜索还会按电影/剧集/演员/集分组并显示总数。

> **改法**：库页加一个搜索框，把输入透传给已有的 `items(libraryId = 当前库, search = 关键词)`，一行参数的事；全局搜索保留 page.total，在网格底部渲染「共 N 条，已显示 60 条」并给「显示更多」（offset += 60），再加一个类型筛选条复用已有的 kind 参数。

### 媒体库页没有任何筛选，Jellyfin 的整个筛选抽屉在这里是空白；详情页的类型标签也点不动

**严重** · `composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:49-61`

想在一千多部里挑「今晚看点没看过的」「只看 2020 年以后的」，媒体库页顶上只有五个排序按钮（LibraryScreen.kt:49-61 那个 Row 里除了 5 个 ToggleButton 没有别的控件），第 63-88 行直接就是 loading/empty/grid 三态。唯一的「未观看」是首页那一行 24 条横向列表。后端连筛选维度的数据出口都没有：Repository.Query 只有 libraryId/parentId/kind/search/favorite/sort/limit/offset（Repository.kt:266-275），MediaFacade 全部公开方法（61-452 行）里没有 genres()/years()/tags() 这类取候选值的 API，而 genres 和 mediaStreams 都是 JSON 文本列（Repository.kt:752、766），SQL 侧没法按类型/分辨率/字幕语言过滤。详情页 DetailScreen.kt:288-295 是 `Text(item.genres.joinToString(" · "))`，无 Chip 无 clickable——而同文件 281-285 连用了五个 Chip，组件就在手边。Jellyfin 库页右上角漏斗打开的筛选抽屉里有已播放/未播放/收藏、类型、标签、年份、分级、视频质量、有字幕；Emby 和 Plex 是顶部 Filter 和 Sort 两个下拉，Filter 里同样有 Unwatched/Genre/Year/Resolution。

> **改法**：分两步。第一步几乎零成本：Query 补 `played: Boolean?`（COALESCE(u.played,0) = ?）和 yearFrom/yearTo，UI 加一个筛选按钮开抽屉，先只放「未观看 / 收藏 / 年代区间」；DetailScreen 的类型换成可点 Chip 带 genre 跳回库页。第二步再把 genres 拆成 item_genres 关联表、给 media_streams 抽出 height 和 subtitle_langs 冗余列。

### 收藏是个死胡同：后端查询早就写好了，前端一个读取点都没有，卡片上也没有收藏标记

**严重** · `core/src/core/com/daview/server/api/MediaFacade.kt:148-165`

在详情页点心形或右键选「收藏」，提示「已收藏」。然后就再也找不到了：首页没有收藏行，媒体库页没有筛选，搜索页也筛不出来，侧栏底栏都没有入口。后端能力完全现成——MediaFacade.kt:154 有 `favorite: Boolean? = null` 并在 165 行透传，Repository.kt:284 会拼 `AND COALESCE(u.favorite, 0) = ?`，Repository.kt:616 有 setFavorite——但 composeApp 全量 grep favorite 只有三处写入（AppState.kt:228-232、DetailScreen.kt:377-381、ItemContextMenu.kt:158-172），没有任何一处把 favorite 传进 items() 查询。HomeData（AppState.kt:37-43）只有 resume/nextUp/latest/unwatched 四个字段，Screen 密封接口（AppState.kt:28-34）没有 Favorites，PosterCard 只画已观看勾（Components.kt:185-192）和进度条（214-224），收藏之后连卡片上都看不出痕迹。一个在两个入口暴露、还给「已收藏」toast 的功能却没有回访路径，比压根不做更容易让人放弃。Jellyfin 左侧导航有固定的 Favorites 页、首页有收藏行、库筛选里有 Is Favorite；Emby 一致；Plex 的 Watchlist 在一级菜单。

> **改法**：两处二选一即可救活：refreshHome 里加一行 `favorites = library.items(links, favorite = true, limit = 24).items` 并在 HomeScreen 加一条 MediaRow；或在筛选抽屉里放一个「仅收藏」开关让 loadLibrary 透传。顺带在 PosterCard 上收藏时画个小心形。

### 从详情页返回媒体库，整个网格重新查库、滚动位置回到最顶上

**严重** · `composeApp/src/app/com/daview/app/data/AppState.kt:101-111`

在电影库里滚到第 300 部，点进去看眼简介，按返回——转圈几百毫秒然后回到网格最顶端，想接着刚才的位置往下翻得重新滚 300 个格子。挑片本身就是「看一眼、退出来、再看下一个」，一晚上要做十几次。AppState.kt:101-106 的 back() 末尾调 onEnter(current)，第 111 行 `is Screen.Library -> loadLibrary(screen.libraryId)`，loadLibrary 第 174 行置 libraryLoading = true，而 LibraryScreen.kt:63-66 在 loading 为真时用 ContainedLoadingIndicator 整个替换掉网格（when 分支互斥），所以是「转圈 → 重建」。滚动位置更彻底：LibraryScreen.kt:73 的 LazyVerticalGrid 没传 state，整个 composeApp/src 下 grep rememberSaveable/rememberLazyGridState/rememberScrollState 只命中 DetailScreen.kt:24 和 522（剧集横排），媒体库网格一处都没有；App.kt:132-145 的 AnimatedContent 每次切屏重建 composition，即使不重查库内部 remember 的 grid state 也会丢。Jellyfin Web/Android、Emby、Plex 从详情页返回都恢复滚动位置，Plex 还会把刚才那张海报高亮出来。

> **改法**：两处：把 LazyGridState 提到 AppState 或用 rememberSaveable；onEnter 里判断 libraryItems 已经是这个 libraryId 的数据就不重新加载（AppState 现在连当前加载的是哪个库都没记）。

### 只有海报网格一种视图，既不能切列表/详情视图，也不能调网格密度

**打磨** · `composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:73-83`

剧集库里几十部剧全是海报方块，标题被截成一行，想快速扫一眼「每部剧有几季、看到第几集了」就得一个个点进去。LibraryScreen.kt:73-83 是写死的 `GridCells.Adaptive(minSize = 150.dp)` + `PosterCard(item, width = 150.dp)`，全文件没有 viewMode 之类的状态；PosterCard（Components.kt:110-260）虽然 width 可传参，形态只有海报一种，宽高比由 kind 决定（Components.kt:129，剧集 16:9 其余 2:3），标题与副标题在 Components.kt:245-258 都是 maxLines=1 + Ellipsis。androidMain/desktopMain 下没有第二个库页实现。Jellyfin 库页工具栏有布局按钮可选海报/缩略图/横幅/列表，列表视图一行显示名称+年份+时长+已播状态；Emby 的视图设置里能调图片类型和每行数量。

> **改法**：先做最有价值的一半：加一个 grid/list 切换，list 分支复用现有数据渲染成一行一条（海报缩略图 + 完整标题 + 年份 + 已看 N/M 集）。网格密度可以后置，或简单给 120/150/200dp 三档。

### 排序方向写死，想反着排（片名 Z→A、年份从老到新）只能自己滑到列表末尾

**打磨** · `core/src/core/com/daview/server/db/Repository.kt:291-299`

点「年份」只能看到最新的在前，想按年代从老往新捋一遍做不到；点「评分」只能高分在前，想找刮削出来评分最低的（通常就是匹配错了的那批）也没办法。Repository.kt:291-299 的 when 分支把方向写死在 SQL 字符串里（"year" -> "i.year DESC, i.sort_name"、"rating" -> "i.community_rating DESC NULLS LAST"），Query（Repository.kt:266-275）没有 descending 字段，MediaFacade.items（MediaFacade.kt:148-157）也只有 `sort: String`，UI 侧 LibraryScreen.kt:27-33 是五对 key→label、53-60 互斥单选。五个字段的默认方向恰好是常用的那个，而且整库一次性装进 libraryItems 再铺开，想看最老的滑到末尾就行，所以有成本不高的绕行方式。Emby/Plex 的排序控件是「字段下拉 + 升降箭头」两件套，Jellyfin 的排序弹层里字段下面另有一组升序/降序单选。

> **改法**：Query 加 `descending: Boolean = false`，order 分支只写字段名，方向在拼 SQL 时统一加后缀（NULLS LAST 要跟着方向走），UI 在按钮排末尾加一个箭头 IconButton。顺带：AppState.kt:75 的 librarySort 只是内存里的 mutableStateOf，关掉 App 就丢，建议落到 SettingsStore 并按 libraryId 分键。

### 手机上排序那一行放不下，最后一个「最近播放」被压成看不清的窄条

**打磨** · `composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:49-51`

LibraryScreen.kt:49-51 是 `Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp))`，没有 horizontalScroll、不是 FlowRow、子项无 weight；App.kt:100-101 的 `maxWidth >= 720.dp` 只切换导航栏形态。按实际使用的 material3（gradle/libs.versions.toml:6 composeMaterial3 1.12.0-alpha03）反编译核对：ToggleButtonDefaults 的 ContentPadding 取自 ButtonSmallTokens.LeadingSpace/TrailingSpace，两者都是 16.0dp，即每个按钮固定吃 32dp，ToggleButtonKt 只设了 minHeight 没有最小宽度。360dp 屏减去左右 20dp 剩 320dp，五个按钮 5×32 + 4×8 间距 + 约 196dp 中文标签 ≈ 388dp，放不下；前四个累计到 292dp 还在范围内，真正被压坏的只有最后一个「最近播放」。它不会跑出屏幕（Row 用剩余空间测量末尾子项），minimumInteractiveComponentSize 也保证 48dp 触摸区，所以点得到只是读不出。Jellyfin/Emby 移动端把排序收进弹出菜单或底部抽屉，Plex 手机端是一个「Sort」下拉。

> **改法**：给这个 Row 加 `Modifier.horizontalScroll(...)`（一行），或换成「排序」下拉按钮 + DropdownMenu（顺便把升降序开关塞进去），后者可与筛选抽屉做成同一个入口。

### 大库里没有字母跳转，桌面端连滚动条都没加

**打磨** · `composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:73-79`

按名称排序的电影库里想跳到「王」字头，只能从头开始滑。LibraryScreen.kt:73-79 的 LazyVerticalGrid 没有传 state，在 composeApp/src 全目录 grep `rememberLazyGridState|rememberScrollState|Scrollbar` 零命中——也就是说桌面端连一个 VerticalScrollbar 都没有加（Compose Desktop 的 lazy 容器需要显式加），也没有任何 scrollToItem 调用点和字母索引条。Jellyfin 库页右侧固定有一条 A-Z 的 alphaPicker，点哪个字母直接跳到那一段；Plex 网格右边缘有带字母提示的快速滚动条；Emby 的 A-Z 索引在移动端是右侧竖条。由于 500 条上限，实际能滑的最多 500 格，痛感有限。

> **改法**：依赖分页先解决全量加载。之后给 LazyVerticalGrid 传 rememberLazyGridState，右侧加一条字母索引（中文库按 sort_name 拼音首字母分段），点击 scrollToItem 到该段第一项，不需要改任何 SQL；桌面端顺手补一个 VerticalScrollbar。

### 演职人员卡片不可点击，也没有按人物检索的入口

**打磨** · `composeApp/src/app/com/daview/app/ui/DetailScreen.kt:575-621`

在详情页看到演员表，想知道这个演员在库里还有哪几部——点头像没反应，点名字没反应，只能记住名字跑到搜索页手打，然后发现搜索只匹配片名也搜不到。DetailScreen.kt:575-621 的 PeopleRow 内层只有 Column+Surface+AsyncImage+两个 Text，无 clickable，而同文件 263 行给 seriesName 用了 LinkText，作者知道这个模式。后端同样无路：Repository.Query（265-274）没有 person 维度，search 只 LIKE i.name/i.original_name/i.sort_name（285-288），people 在 Repository.kt:754 整段 JSON 存列。演员头像图都已经刮下来占了一整行版面，却是纯装饰。Jellyfin 的演员卡片可点开进入人物页列出库内全部参演，Emby 有 Person 详情页 + Appears In，Plex 点演员进 Cast & Crew 筛选结果。考虑到 DAView 面向个人 WebDAV 收藏、一个演员往往只有一两部，价值被稀释。

> **改法**：最小可行：Query 加 person 参数用 `people LIKE '%"name":"张三"%'`（或建 item_people 关联表更干净），PeopleRow 的名字改成 LinkText，点进去复用现有的搜索结果网格，标题写「张三 · 出演」，不必先做完整人物页。

## 看片：播放链路与播放器

### 关掉 DAView 窗口，正在外置播放器里看的片子会立刻断流，没有任何提示

**阻断** · `composeApp/src/desktopMain/kotlin/com/daview/app/Main.kt:20-23`

点「用 PotPlayer 播放」，PotPlayer 全屏起来。Alt+Tab 回到 DAView 发现它停在详情页什么也不显示，顺手点 X 关掉——正在播的视频立刻卡死，因为字节是从 DAView 进程里的本地管道流出来的：LocalAssetLinks.kt:25-26 把 stream() 转成 context.pipe.urlFor(...)，PlaybackPipe.kt:96-104 起的是 127.0.0.1 的 ServerSocket，进程一没字节源就没。Main.kt:20-23 的 onCloseRequest 是 exitApplication() + exitProcess(0)，没有任何活动会话检查，全仓库 grep 无 addShutdownHook，也没有托盘图标和「仍在播放」提示。进度不会丢（PlaybackService.kt:276-289 每 2 秒 persist），只是流断了。直连存储的退路其实存在——关掉 AppConfig.kt:58 的 trackExternalPlayers 就会走 PlaybackPipe.kt:206-214 的 302 直连分支——但 SettingsScreen.kt 里没有这个开关的任何入口，只能靠导出备份改。Plex/Jellyfin/Emby 的服务端是独立进程或系统服务，关掉客户端窗口不影响正在播的流。

> **改法**：onCloseRequest 里先问 PlaybackService.activeSessions() 有没有外部会话：有就弹「还有 X 正在播放，关闭会中断」的确认，或最小化到托盘而不是退出。至少把标题栏改成「DAView（正在播放：xxx）」。顺带把 trackExternalPlayers 开关放进设置页。

### 外置播放器暂停超过 5 分钟，会话被回收、本地管道被关掉，之后一拖进度条就断流、后半段进度全丢

**阻断** · `core/src/core/com/daview/server/media/PlaybackService.kt:281-285`

看到 40 分钟按空格去接个电话，6 分钟后回来：播放器缓冲区满了不再要字节，DAView 判定空闲把会话退休。PlaybackService.kt:281-285 `now - lastActivity > timeout` 就 sessions.remove + persist(finished=true) + notifyEnded，而 lastActivity 只有 onRangeRequest(209) 和 onBytesRead(222) 会刷新，两者都由 PlaybackPipe.kt:204、248 触发——248 行在 output.write 之后调用，播放器停止读取后 write 被 TCP 反压阻塞，两者都不再发生。桌面端的轮询不会续命：PlaybackController.kt:145 的 sessions() 落到 PlaybackService.kt:259 activeSessions()，只是 map 成 DTO 完全不碰 lastActivity。超时后 notifyEnded → PlaybackPipe.kt:57 注册的 release → 90-93 行 sessions 空则 close ServerSocket，已建立的连接不受影响（所以不拖进度条还能继续放），新连接被拒；若还有别的会话在，PlaybackPipe.kt:181 对未知 sessionId 一律 404。进度也停记（207-208、221-222 在 session 为 null 时直接 return），这部片永远停在 40 分钟、永远到不了 Repository.kt:576-581 的 90% 已看阈值。默认 300 秒（AppConfig.kt:64）且 SettingsScreen.kt 无任何入口。Jellyfin/Emby 的会话有播放器主动上报的 PlaybackProgress 心跳，Plex 的 timeline 里 state=paused 是一等公民。

> **改法**：桌面端 followExternalSession 每轮在进程存活时给会话打一次 keepalive（facade 加个只刷 lastActivity 的方法），让空闲超时只对真正观察不到进程的端生效；另外把 externalSessionIdleTimeoutSec 放进设置页。

### Android 播放中屏幕会按系统超时自动熄灭

**阻断** · `composeApp/src/androidMain/kotlin/com/daview/app/ui/InternalPlayer.android.kt:134-143`

手机上点开一部电影全屏看，中间不碰屏幕，到系统自动锁定时间（默认 30 秒或 1 分钟）屏幕就黑掉，想看完一部两小时的片必须每半分钟点一下。InternalPlayer.android.kt:134-143 的 PlayerView 只设了 useController 与 setShowSubtitleButton，没有 setKeepScreenOn 也没有拿 Window 加 flag；仓库根目录 grep `keepScreenOn|FLAG_KEEP_SCREEN_ON|WakeLock|setWakeMode`（排除 /build/）零命中，AndroidManifest.xml:6 声明了 WAKE_LOCK 却无人使用；themes.xml 全文只有 statusBarColor/navigationBarColor/windowBackground；MainActivity.kt:15-25 只有 enableEdgeToEdge()+setContent。解包 media3-ui-1.11.0.aar 后在 classes.jar、res/、R.txt 中 grep keepScreenOn 同样零命中——media3 不会代劳。Jellyfin Android 的 PlayerActivity 在 onCreate 里 addFlags(FLAG_KEEP_SCREEN_ON)，Emby/Plex Android 同样在播放期间置位、暂停时释放，这是三家播放器 Activity 的第一行标配代码。

> **改法**：在 InternalPlayer 的 AndroidView factory 里加 `keepScreenOn = true`，或更精确地在 Player.Listener 的 onIsPlayingChanged 里同步 `playerView.keepScreenOn = isPlaying`（暂停时放掉避免白烧电）。

### 播完一集就到头了：两端都不自动播下一集，系列页也没有「播放全部」

**严重** · `composeApp/src/app/com/daview/app/data/PlaybackController.kt:141-163`

追番一集 24 分钟，每集结束都要：等播放器关掉 → Alt+Tab 找到 DAView → 它还停在详情页 → 找到下一集 → 点播放，看六集重复六次。InternalPlayer.android.kt:96-101 是 setMediaItem(单条)，106-111 的 Player.Listener 只覆写 onPlayerError，全仓库 grep STATE_ENDED / onPlaybackStateChanged 零命中，116-122 的 DisposableEffect 只在销毁时 onClose(position)，放完就停在最后一帧。桌面端 PlaybackController.kt:141-163 的 followExternalSession 在进程退出后只做 stopPlayback + 清状态 + refreshHome/loadDetail，没有续播分支；PlayerScreen.kt:123-133 的外置面板只有「复制播放地址」和「结束播放」两个按钮。能力其实现成：nextEpisodeUnder（Repository.kt:647）全仓库只被 MediaFacade.kt:323 的 startPlayback 调用（在系列上按播放时挑第一集未看）和四处测试引用，播放结束路径上无人调用；PlaybackInfoDto（Models.kt:283-296）也没有任何 nextItem 字段。Jellyfin/Emby 在片尾弹 Up Next 倒计时卡，Plex 的 Post-Play 默认自动续播，Infuse 有 Auto Play Next；三家还都支持加入播放队列。

> **改法**：在 PlaybackInfoDto 里加 nextItemId（复用 nextEpisodeUnder 的排序逻辑）：Android 监听 STATE_ENDED 后直接对它调 playInternalOrExternal；桌面 followExternalSession 判断退出时若进度过 90% 阈值就弹「5 秒后播放 第 3 集 / 取消」。系列页的播放按钮旁再加一条「从这里连播」。

### Android 播放器不是独立全屏形态：导航骨架常驻、不自动横屏、也没有方向锁

**严重** · `composeApp/src/app/com/daview/app/App.kt:103-117`

点播放，视频被塞进导航骨架中间：App.kt:111-117 竖屏是 Column { TopRow; Box(weight 1f){ Content }; BottomNavigation }，App.kt:103-110 宽屏是 Row { SideNavigation; Column { TopRow; Content } }，App.kt:137-144 的 Screen.Player 只是 AnimatedContent 的一个普通分支，两条布局都没有对它做例外；App.kt:150 的 TopRow 只在 backStack.size<=1 时退化成 Spacer，而进播放器必然 >1（PlaybackController.kt:69 是 navigate），所以返回箭头行一定在。App.kt:101 的 `wide = maxWidth >= 720.dp` 对 1080x2400/2.75x 的普通手机横屏（约 873dp）成立，左边还会杵着 NavigationRail。grep systemBars|WindowInsets|immersive 零命中，系统状态栏/导航栏也不隐藏；实际可用面积约七到八成。同一个根因还造成方向问题：AndroidManifest.xml:20-29 没有 android:screenOrientation，全仓库 grep requestedOrientation/SCREEN_ORIENTATION 零命中，系统自动旋转关着时（很多人为躺着看而关掉）App 内没有任何办法把画面横过来；InternalPlayer.android.kt:145-196 叠加的自绘控件只有「音轨」「字幕」两个按钮，javap media3-ui 1.11.0 的 PlayerControlView 也确认没有任何方向控制，且 InternalPlayer.android.kt:134-143 从没调过 setFullscreenButtonClickListener 所以全屏按钮默认不显示。Jellyfin/Emby/Plex 的 Android 播放都是独立的全屏 Activity，进入即 immersive、直接 setRequestedOrientation 到 SENSOR_LANDSCAPE，并在控制条上给一个方向锁按钮。

> **改法**：在 App() 的 Box 里把 `state.current is Screen.Player` 单独提出来：命中时直接渲染 PlayerScreen 铺满，不走 TopRow/BottomNavigation/SideNavigation，同时用 WindowInsetsControllerCompat 隐藏系统栏；进播放页设 requestedOrientation = SENSOR_LANDSCAPE（或按 videoSize 宽高比决定），退出恢复 UNSPECIFIED，控制条加一个锁定按钮。这是一处改动同时解决两件事。

### 没有 MediaSession：切后台音频还在响却没有任何控制入口，也没有画中画和音频焦点

**严重** · `composeApp/build.gradle.kts:51`

看剧看到一半按 Home 回消息，声音继续响：InternalPlayer.android.kt 全文没有任何 LocalLifecycleOwner/LifecycleEventEffect，player 只在 DisposableEffect(Unit) 的 onDispose（116-122）里释放，而 MainActivity 用 setContent，Composition 在 Activity 仅 onStop 时不会销毁。此时下拉通知栏没有播放控制卡片，锁屏也没有，蓝牙耳机按暂停没反应——composeApp/build.gradle.kts:51 引了 androidx.media3:media3-session，而全仓库 grep `MediaSession|PictureInPicture|AudioFocus|MediaButton|supportsPictureInPicture` 零命中，依赖引了没用。AndroidManifest.xml:20-29 也没有 android:supportsPictureInPicture，想边看边回消息不行。来电不会自动暂停：InternalPlayer.android.kt:80-84 的 ExoPlayer.Builder 只设了 MediaSourceFactory 就 build()，没有 setAudioAttributes(attrs, handleAudioFocus=true)。Jellyfin Android 播放器右上角有 PiP 按钮，Plex/Emby 都用 MediaSession 发媒体通知、锁屏有封面和播放键、耳机按键有效、来电自动暂停——这几样在成熟 Android 播放器里是同一个 MediaSession 顺带给的。

> **改法**：用已经在依赖里的 media3-session 建一个 MediaSessionService，把现有 ExoPlayer 实例挂上去（白拿通知控制、锁屏控制、媒体按键、音频焦点）；PiP 单独在 Manifest 加 supportsPictureInPicture 并在 onUserLeaveHint 里 enterPictureInPictureMode。

### 系统返回键/返回手势不返回上一页，直接退出整个 App

**严重** · `composeApp/src/androidMain/kotlin/com/daview/app/MainActivity.kt:15-25`

首页 → 点某部剧 → 详情页 → 按返回键或用全面屏返回手势，App 直接关闭回到桌面；播放中按返回同样是整个 App 消失。MainActivity.kt:15-25 是裸 ComponentActivity，setContent 之外没有注册任何返回回调，全仓库 grep `BackHandler|onBackPressed|OnBackPressedDispatcher|predictiveBack` 零命中，所以系统返回走默认 finish()。AppState.kt:101-106 的 back() 有调用方，但都是应用内按钮（App.kt:158 的 IconButton、PlayerScreen.kt:40/53/129），不接系统返回键。导航功能没有不可用（返回箭头始终在），丢数据也被 InternalPlayer.android.kt:124-131 的 5 秒心跳兜住，但 Android 上返回手势是主导航手势，这条等于把最常用的导航方式变成了误关闭。Jellyfin/Emby/Plex 的 Android 客户端都走标准返回栈：播放器 → 详情 → 库 → 首页逐层退，退到首页再按才离开（Plex 还给一次「再按一次退出」提示）。

> **改法**：在 App() 里加 `BackHandler(enabled = state.backStack.size > 1) { state.back() }`（androidx.activity.compose.BackHandler 在 Compose Multiplatform 的 androidMain 可直接用）；播放器页面再单独加一个优先级更高的 BackHandler，先 stop 播放再 back。

### 完全没有离线下载，出门断网这个 App 就是个空壳

**严重** · `composeApp/src/app/com/daview/app/ui/ItemContextMenu.kt:101-184`

出门前想缓存两集在地铁上看，翻遍详情页、长按菜单、设置页都找不到「下载」。ItemContextMenu.kt:101-184 的 ItemMenuItems 全部条目是播放/继续、每个外部播放器的「用 X 播放」、标记已观看、收藏、查看详情；全仓库 grep `download|offline|离线|下载` 只命中图片缓存（ImageCache.kt:46、MediaFacade.kt:451）、备份导出（Backup.kt:49、SettingsScreen.kt:472）和 PlaybackService 里「播放器已下载到多少字节」的进度推算注释，没有任何媒体文件落盘 API。composeApp/build.gradle.kts:50-52 只有 media3-exoplayer/session/ui，没引 media3 的 Cache 或 DownloadManager；InternalPlayer.android.kt:74-83 构造的是裸 DefaultHttpDataSource.Factory 没挂 CacheDataSource；PlaybackController.kt:46-76 每次都新建 session 实时拉流。用移动流量看一部 1080p 按 GB 计。Jellyfin Android 详情页右上角有下载图标、底部导航有独立 Downloads 标签；Emby 有 Download 按钮和 Downloads 区、支持整季批量与看完自动删除；Plex 有 Download 和 Library > Downloads（后两家需 Premiere/Plex Pass，只有 Jellyfin 免费）。

> **改法**：最小可行版：详情页/长按菜单加一个「下载」，用 media3 的 DownloadManager + DownloadService（前台服务可以抄 ScanForegroundService 的现成模式），文件落在 filesDir/downloads，播放时优先命中本地文件；先不做画质选择，直接原文件下载即可，这个 App 本来就不转码。

### 外挂字幕只认「同一目录且严格同名」，Subs/ 子目录不看，`.简日双语.ass` 也会被丢，且没有手动挂载入口、没有延迟与字号

**严重** · `core/src/core/com/daview/server/library/Scanner.kt:332-337`

国内片源常把字幕单独放在 Subs//字幕//CHS&JPN/ 子目录里，这在 DAView 里就是「这片没字幕」：Scanner.kt:333 `val base = video.name.substringBeforeLast('.')`、Scanner.kt:337 要求 `parsed.videoBaseName.equals(base, ignoreCase = true)` 完全同名，候选只来自同一次 dav.list（Scanner.kt:94、166、244），而 Scanner.kt:95-99 只在目录内没有视频时才递归子目录，所以有视频的目录下的 Subs/ 永远不进候选。比这更常见的一个坑是：即使严格同名，只要中间夹了解析器不认识的 token，NameParser.kt:232-247 的 while 循环走 else 分支返回未截断的 base，Scanner.kt:337 的全等比较随即失败——`简日双语`、`chs&jpn`、`简体&日语` 全部中招，而这是中文压制组最常见的字幕命名。此时无路可走：MediaFacade 全部方法里没有任何字幕上传/搜索/挂载接口，涉及字幕的只有 startPlayback 挑默认轨（MediaFacade.kt:336、474-479）和转 URL（368-372）。就算挂上了也调不了：Android 字幕菜单（InternalPlayer.android.kt:165-195）与桌面内置播放器（InternalPlayer.desktop.kt:275-279）都只有关闭+切轨，没有字幕延迟和字号；只有走外置播放器时由 PotPlayer/mpv 自己兜住（Platform.desktop.kt:93-111 只传一个 /sub= 或 --sub-file=）。Jellyfin/Emby/Plex 都支持字幕放在同级 Subs/ 子目录，客户端设置里有字幕外观与延迟。

> **改法**：按代价排序：(1) 把视频所在目录下名为 subs/subtitles/字幕 的子目录也列一遍并入候选，几行改动救掉一大类片源；(2) 同名匹配失败时退化为「本目录只有一个视频且有 N 个字幕 → 全挂上」；(3) 详情页加一个「挂载字幕文件」入口；(4) 播放器加字幕延迟滑块与字号。

### 音轨和内嵌字幕轨的选择传不给外置播放器，播放器里选的也回不来

**严重** · `composeApp/src/commonMain/kotlin/com/daview/app/platform/Platform.kt:12-18`

一集内封日语+国语双音轨、简繁双字幕的番，在手机上选了日语+简体，进度同步到桌面用 PotPlayer 接着看——它按自己的默认逻辑选轨很可能给国语；你在 PotPlayer 里手动切成日语，这个选择也写不回 DAView，下次换设备又得重选。ExternalPlayRequest（Platform.kt:12-18）只有 player/streamUrl/title/startPositionMs/subtitleUrl 五个字段；Platform.desktop.kt:74-100 的 buildCommand 只发 /seek、/sub、/title（VLC 是 --start-time/--sub-file/--meta-title，mpv 是 --start/--sub-file/--force-media-title），没有 --aid/--sid、--audio-track/--sub-track。而 MediaFacade.kt:333-336 明明算出了 audio/subtitle 序号并在 343-344 存进 session、经 PlaybackService.kt:320-326 写进 user_data，外置链路上没有任何消费方。桌面端界面上也没有选轨的地方：PlaybackController.kt:103-107 只自动挑一条外挂字幕，DetailScreen.kt:459-460 的「音轨/字幕」是拼成字符串塞进 InfoRow 的只读文本。安卓端反而有完整的选轨菜单（InternalPlayer.android.kt:148-193）。Jellyfin 详情页播放按钮旁就有音轨和字幕两个下拉并按剧集记住，Plex 的 pre-play 页有 Audio/Subtitles 选择器和「为整季应用」，Emby 有每用户的音轨/字幕语言偏好。

> **改法**：给 ExternalPlayRequest 加 audioIndex/subtitleIndex，buildCommand 里按播放器映射（mpv --aid/--sid、VLC --audio-track/--sub-track）；详情页把只读的「音轨/字幕」行改成播放前可选的下拉，复用已经存在的 userData.audioStreamIndex 持久化。

### 默认用哪个播放器不能选也不会记住，永远按写死的顺序挑第一个

**严重** · `composeApp/src/app/com/daview/app/ui/HomeScreen.kt:266-269`

机器上 PotPlayer 和 mpv 都装了，只想用 mpv（配了 shader、字幕渲染好）。结果首页 hero 的播放（HomeScreen.kt:77）、右键菜单的播放（ItemContextMenu.kt:119）、详情页那个大「继续 12:34」按钮（DetailScreen.kt:332）每一次都启动 PotPlayer：HomeScreen.kt:266-269 的 playInternalOrExternal 就是 `externalPlayers.firstOrNull{...}`，顺序由 Platform.desktop.kt:25-39 的 windowsCandidates 写死为 PotPlayer > VLC > mpv；PlaybackController.kt:42 的 externalPlayers 是构造期一次性 val，运行期不重算也不持久化。绕过成本是每次多点一步右键→「用 mpv 播放」，但主播放按钮永远学不会。SettingsScreen.kt 里没有任何播放器设置，而 Platform.desktop.kt:174-180 已有现成的 Preferences/SettingsStore。Jellyfin Media Player 的 Settings→Video/Audio、Plex 桌面端的 Settings→Player、Jellyfin Android 交给系统选择器后由系统记住默认、Infuse 记住上次用的播放引擎，都是「这个选择会被记住」。

> **改法**：设置页加「默认外部播放器」下拉（值存 SettingsStore），playInternalOrExternal 先读它、读不到再退回探测顺序；顺便把 externalPlayers 从 val 改成每次打开菜单时刷新，省得装完播放器还要重启。

### 外部播放面板的说明文字描述的是一个已经不存在的架构，而且承诺了做不到的事

**严重** · `composeApp/src/app/com/daview/app/ui/PlayerScreen.kt:136-141`

桌面端每一次播放都会看到这块面板（PlayerScreen.kt:56 在 !PlatformInfo.hasInternalPlayer 时渲染），底部写着「播放地址指向 DAView 服务器，服务器再跳转到存储直链；保持这个页面打开，进度会自动同步到所有客户端」（PlayerScreen.kt:136-141）。两句都不成立：streamUrl 的链条是 MediaFacade.kt:360 → LocalAssetLinks.kt:25-26 → PlaybackPipe.kt:74 返回 `http://127.0.0.1:$port/...`，端口来自 PlaybackPipe.kt:98 的 `ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))`，会话清空即 close，所以 PlayerScreen.kt:124「复制播放地址」发给手机必然打不开；同一个 App 的 SettingsScreen.kt:310 还写着「媒体库在本机，没有需要连接的服务器」，两屏文案互相打架。「自动同步到所有客户端」也是过度承诺：AppConfig.kt:38 的 sync enabled 默认 false，开启后也是 SyncService.kt:51 的 60 秒 tick + 上传下限 10 分钟（AppConfig.kt:42）、拉取取一半。默认关同步的新用户读到这句会直接认为跨端续播已经在工作，换设备发现进度不在会判断成丢数据。

> **改法**：改成说实话：「播放地址是本机临时地址（127.0.0.1），只在本次播放期间有效，其它设备用不了」，以及「进度先记在本机，开启跨端同步后按设定间隔写到 WebDAV」。「复制播放地址」按钮旁注明用途（调试／手动喂给别的本机播放器）。

### 外置播放器一启动，界面就什么都不显示了——整块外部播放面板是不可达的死代码

**严重** · `composeApp/src/app/com/daview/app/data/PlaybackController.kt:69,78`　`复核追加`

右键任意卡片选「用 PotPlayer 播放」（ItemContextMenu.kt:130）、详情页的「外部播放器」菜单（DetailScreen.kt:369）、或桌面上没装 libmpv 时的主播放按钮（HomeScreen.kt:269），三条路都走 PlaybackController.playExternal。播放器起来了，但 DAView 停在原来那一页，没有任何变化：没有「正在通过 PotPlayer 播放」，没有进度，没有「结束播放」。原因是全仓库只有一处 navigate(Screen.Player)，在 PlaybackController.kt:69 的 playInternal 里，playExternal 从不导航。于是 PlayerScreen.kt:68-143 那整块 ExternalPlaybackPanel——进度条、「进度由播放器上报／索引推算／时钟推算」的来源标注、「复制播放地址」、「结束播放」——用户永远看不到一眼，README 里说的「保持这个页面打开」这个页面并不存在。副作用是外部会话只能靠播放器进程退出来收尾（PlaybackController.kt:148-152），App 里没有手动结束的入口。Jellyfin/Emby/Plex 把播放交给外部播放器或投屏设备时，客户端上都会留一条正在播放的面板，带进度和停止。

> **改法**：playExternal 成功后也 navigate(Screen.Player(item.id))，PlayerScreen 的分支判断从 PlatformInfo.hasInternalPlayer 改成「这次会话用的是内置还是外置」（PlaybackController 已有 externalPlayerLabel 可判）。一处改动就能把写好的整块面板接回来。

### 播放失败只有详情页看得到，从首页、媒体库、搜索页点播放失败是彻底静默的；启动过程也没有任何等待反馈

**严重** · `composeApp/src/app/com/daview/app/data/PlaybackController.kt:40-41`　`复核追加`

PlaybackController 有 error 和 starting 两个状态字段（PlaybackController.kt:40-41），全仓库只有 DetailScreen.kt:427 一处渲染 error，starting 零处渲染。所以在首页 hero 点「播放」、在媒体库网格右键播放、在搜索结果里播放，只要 startPlayback 抛异常（WebDAV 401、直链解析失败、文件已被删）或外置播放器起不来（PlaybackController.kt:117 的「无法启动 X」），界面上完全没有反应，用户只会以为自己没点中，再点几次。starting 没人渲染是另一半：startPlayback 要走一次 WebDAV 取直链（MediaFacade.kt:318 起），按下播放到画面出现之间的空白期没有任何指示。这两个字段本来就是为这两件事准备的，只是没接上 UI。Jellyfin/Emby/Plex 点播放后立刻给加载态，失败弹明确错误。

> **改法**：把 error 接到 AppState.toast（已有全局 snackbar，App.kt:118-123），任何页面都能看到；starting 为真时把播放按钮换成 loading 态。

### 没有「从头播放」：看过一半的片，播放键永远是「继续」

**打磨** · `core/src/core/com/daview/server/api/MediaFacade.kt:342`

一部电影上次看到 40 分钟，现在想从头重看：详情页按钮写「继续 40:12」（DetailScreen.kt:336-341），右键菜单也只有「继续 40:12」（ItemContextMenu.kt:112-115），没有第二个选项。MediaFacade.kt:342（session start）与 362（PlaybackInfoDto）都硬取 userData.positionMs，PlaybackStartRequest（Models.kt:274-279）只有 itemId/player/deviceName/trackThroughProxy，没有起播位置字段。外置播放器更麻烦：Platform.desktop.kt:75-94 把 startPositionMs 直接编成 PotPlayer 的 /seek=hh:mm:ss、mpv 的 --start-time= 和 VLC 的 --start=，播放器一开就跳到中途，得自己拖回去。唯一的绕法是先点「标记为未观看」，而 Repository.kt:672-690 的 setPlayed 在 INSERT 与 ON CONFLICT 两侧都写死 position_ms = 0，等于自己把书签撕了。Jellyfin 在有进度时同时给「继续播放」和「从头开始播放」两个按钮，Emby 是 Resume / Play from start 并排，Plex 是主按钮 + ⋯ 里的「从头播放」。

> **改法**：PlaybackStartRequest 加一个可选 startPositionMs（null 表示沿用记录），MediaFacade.kt:342 改成 `request.startPositionMs ?: userData.positionMs`；UI 上在「继续」按钮右边加一个小的「从头」入口或放进右键菜单，两处都只是多传一个 0。

### Android 播放器没有任何手势，自绘的音轨/字幕按钮和标题常驻挡画面，还与 media3 自带字幕按钮形成两套入口

**打磨** · `composeApp/src/androidMain/kotlin/com/daview/app/ui/InternalPlayer.android.kt:145-148`

看片时想调亮度或音量在画面左右滑没反应，只能拉系统状态栏；想快退十秒双击屏幕左侧也没反应，只能唤出控制条点小按钮——InternalPlayer.android.kt:133-143 直接用 stock PlayerView，整个文件没有任何 pointerInput/detectTapGestures/detectDragGestures。第 145-148 的 Row 用 `Modifier.align(Alignment.TopEnd)` 与 AndroidView 平级挂在同一个 Box 里，和控制条显隐毫无关联，所以控制条自动隐藏后「音轨」「字幕」两个白字按钮以及 213-221 的标题 Column 依然挂在画面上。同时第 139 行 setShowSubtitleButton(true) 会在 media3 控制条里另开一个字幕入口，而自绘菜单列的是 info.item.mediaStreams（服务端的流索引），两套入口的列表与选择语义不同。Jellyfin Android 支持左侧上下滑调亮度、右侧调音量、双击快退快进、长按倍速，Plex/Emby 至少有双击快进和音量亮度滑动，音轨与字幕都收在控制条的同一个设置入口里、跟控制条一起隐藏。

> **改法**：把音轨/字幕菜单挪进 PlayerView 的 overlayFrameLayout 或跟随 setControllerVisibilityListener 一起显隐，并关掉重复的 setShowSubtitleButton；手势先补最常用的两条——双击左右各 ±10 秒、右半屏竖滑调音量，用 pointerInput 包一层就够。

### mkv 里的章节从不读取，Android 内置播放器没有章节导航

**打磨** · `core/src/core/com/daview/server/library/MkvProbe.kt:54-83`

看一部两小时的电影想跳到某一章、或想知道正片什么时候开始，详情页和播放器里都没有章节列表——mkv 里明明写了章节。MkvProbe.kt:54-83 的 EBML ID 表里没有 Chapters（0x1043A770），MkvInfo（MkvProbe.kt:34-41）没有 chapters 字段，MediaItemDto（Models.kt:119-163）同样没有。成本很低：SeekHead 已经在解析，多加一个 ID 的代价很小。影响面比看上去小：桌面默认把播放交给 PotPlayer/VLC/mpv（Platform.desktop.kt:42-59、93-111），它们自己读章节；只有 Android 内置播放器真的缺章节导航，而它用的是 media3 PlayerView 默认控件（InternalPlayer.android.kt:133-143），自带快退/快进按钮。附带说明：Plex 的 Skip Intro、Emby 的 Intro Skip、Jellyfin 的 Media Segments + Intro Skipper 都建立在服务端对文件本体做音频指纹分析之上，本应用无服务端且探测只按 range 读 512KB 文件头（MkvProbe.kt:85），做指纹要把整季片子拉下来，不在产品定位内。

> **改法**：MkvProbe 顺手解析 Chapters（复用已有的 SeekHead 定位），详情页与播放器给章节跳转。若想要「跳过片头」的廉价版：库级设置一个片头秒数（如 90），播放开始后前 90 秒在播放器上显示一个「跳过片头」按钮，对同一季固定 OP 的番剧准确率极高。

### 桌面播放器菜单里那条「自定义播放器」永远点不动：写入它的函数全仓库没有调用点

**打磨** · `composeApp/src/desktopMain/kotlin/com/daview/app/platform/Platform.desktop.kt:71,78`　`复核追加`

availableExternalPlayers() 无条件在探测结果后面追加一条 ExternalPlayerInfo("custom", "自定义播放器", customPlayerPath())（Platform.desktop.kt:71），所以每个播放菜单里都列着它。但 setCustomPlayerPath（Platform.desktop.kt:78）在 composeApp 全仓库零调用点——设置页没有任何指定播放器路径的入口，新加的 PlatformPlayerSettings.desktop.kt 也只有 libmpv 路径和 RTX 开关。于是 customPlayerPath() 恒为 null，launchExternalPlayer 第一行 executablePath ?: return null 直接失败，PlaybackController.kt:117 置 error = 「无法启动 自定义播放器」，而这条 error 在非详情页还看不到（见上一条）。结果是一个每次都出现、每次都无声失败的菜单项。顺带：externalPlayers 是 PlaybackController 构造期的一次性 val（PlaybackController.kt:42），装完播放器要重启 App 才认。

> **改法**：要么在设置页加一个「指定播放器程序…」的文件选择（setCustomPlayerPath 已经写好，缺的只是入口，可以照抄新加的「指定 libmpv…」按钮），要么在没有路径时干脆不把这条塞进列表。

## 状态、进度与跨端同步

### 「继续观看」里的条目移不掉，唯一的办法是撒谎说自己看完了，还会顺带清空进度

**严重** · `composeApp/src/app/com/daview/app/ui/ItemContextMenu.kt:108-184`

随手点开一部电影看了 3 分钟发现不喜欢，它从此钉在「继续观看」第一位，还因为 HomeScreen.kt:53 的 hero 逻辑霸占首页顶部整块大图。右键卡片，ItemContextMenu.kt:108-184 的菜单项只有播放/继续、每个外部播放器、标记已观看、收藏、查看详情，没有「从继续观看中移除」；DetailScreen.kt:377-415 的顶栏按钮也只有收藏/已观看/合并/手动识别，没有重置进度。数据层同样没有第三态：Database.kt:122-132 的 user_data 只有 position_ms/played/play_count/favorite/last_played_at/两个流索引/updated_at，无 hidden 列；Repository.kt:330-333 判定 resume 成员的唯一依据就是 position_ms>0 且 played=0。于是只能点「标记为已观看」——而 Repository.kt:676-681 的 setPlayed 在 INSERT 与 ON CONFLICT 两侧都写死 position_ms = 0，那 3 分钟的进度被抹掉，取消已观看也拿不回来。Plex 的 Continue Watching 卡片「…」菜单第一项就是 Remove from Continue Watching（移除后仍是未观看、进度保留），Emby 和 Jellyfin 10.9+ 也都有；三家都把「不想在首页看到它」和「我看完了」当成两件事。

> **改法**：user_data 加一列 `hidden_from_resume INTEGER DEFAULT 0`，resume 查询加 `AND COALESCE(u.hidden_from_resume,0)=0`，右键菜单在该 item 出现在 resume 时多一条「从继续观看中移除」，下次真正播放时清零。

### 扫描结束后停在首页不会自动刷新，后台同步拉回来的进度也不刷新，且没有下拉刷新

**严重** · `composeApp/src/app/com/daview/app/data/AppState.kt:289-300`

两个典型场景。(1) 对新建库点扫描，切回首页看着进度块跑完消失、媒体库快捷卡的数字从「0 项」变成「128 项」，但「最近添加」和「未观看」几行还是空的——用户以为扫描失败了。原因是 AppState.kt:289-300 的 pollScanStatus 在扫描结束时只调 refreshLibraries()（第 294 行），而它在第 153 行只是 `libraries = library.libraries()`，home 字段完全没动；而 libraries 里带 itemCount（Models.kt:67），HomeScreen.kt:250 显示的正是它。(2) 昨晚在桌面看了 40 分钟，今天在手机上打开 App，「继续观看」还是旧位置；SyncService.kt:51 每 60 秒 tick、67-72 自动 pull 并写库，但整个文件没有 listener/callback/Flow，无任何 UI 通知路径。refreshHome 的六个调用点（AppState.kt:110/126/268、PlaybackController.kt:160/181、SettingsScreen.kt:433）没有一处由同步触发。composeApp 全仓 grep PullToRefresh 零命中，androidMain 也没有 onResume 钩子。唯一出路是重新点一次导航栏的「首页」（replaceAll → onEnter → refreshHome），而当前已在首页时再点没有任何提示，属于「入口存在但没人会想到」。Jellyfin/Emby 通过 WebSocket 接收 LibraryChanged/UserDataChanged 事件，Plex 用 EventSource 且移动端有下拉刷新兜底。

> **改法**：两处小改：pollScanStatus 结束时把第 294 行的 refreshLibraries() 换成 refreshLibraries() + refreshHome()；SyncService.pull() 成功且有行写入时通过 ServerContext 抛一个回调，AppState 订阅后在 current is Screen.Home 时 refreshHome()。再给 HomeScreen 包一层 PullToRefreshBox 兜底。

### 停止播放不触发上传，进度只等进程内定时器——划掉 App 换设备最坏会看到十分钟前的旧进度

**严重** · `core/src/core/com/daview/server/api/MediaFacade.kt:386-392`

手机上看完第 5 集随手从最近任务划掉 App，走到电脑前打开桌面端，「继续观看」还停在第 4 集。MediaFacade.kt:386-392 的 stopPlayback 只做 playback.stop + pipe.release，不触发任何 sync.upload；上传只靠 SyncService.kt:37-39 起的 daemon 线程按 TICK_SECONDS=60 跑 tick（第 51 行），而第 74-75 行要求 `now - lastUploadAt >= minIntervalMinutes*60_000`，默认 10 分钟（AppConfig.kt:42）。所以最坏情况是「最多 10 分钟未上传的进度 + 最多 60 秒 tick 延迟」，不是永久丢失（下次在手机上开着 App 满一分钟会自动补传），但国产 ROM 的后台清理更激进，退到后台几分钟进程就没了。Android 端 grep WorkManager/JobScheduler/AlarmManager 零命中，没有任何兜底。跨设备对齐进度是这个 App 的招牌功能，最坏情况下换设备就是看到旧集数。Jellyfin/Emby/Plex 的客户端在停止播放时同步 POST 一次 PlaybackStopped，进度在按退出那一刻就落到服务端，与 App 之后是死是活无关。

> **改法**：在 stopPlayback 成功之后（或 Activity 的 ON_STOP）触发一次立即上传，绕开 minIntervalMinutes 节流；再用 WorkManager 排一个 OneTimeWorkRequest（约束 NETWORK_CONNECTED）兜底，这样即使进程当场被杀，系统也会稍后替你补上。

### 同步文件会静默覆写本机的存储地址与播放设置，两台设备用不同地址访问同一份存储时互相打架

**严重** · `core/src/core/com/daview/server/api/Backup.kt:184-185`

最常撞上的是自伤场景：桌面填 `http://192.168.1.x:5005/dav`、手机填 `https://公网域名/dav`，两边每 5 分钟互相把对方的地址覆盖掉，界面完全无提示。Backup.kt:238 的 backupSettings 里 url 不受 secrets 开关约束（对比 241/242 的 username/password 是 if(secrets)），而 SYNC_SECTIONS 里 settings=true、secrets=false（SyncService.kt:244-251），所以同步文件必然带着 storage.url；SyncService.kt:180-197 的 mergeRemote 无条件调用 applyBackup，Backup.kt:173 远端 url 非空即胜出；SyncService.kt:65-72 的 tick 每 60 秒跑一次、拉取间隔是上传间隔的一半（默认 5 分钟），而 154-161 的 pull 返回消息只报观看记录与媒体库数量、不提设置。更硬的一条是 Backup.kt:184-185：trackExternalPlayers 和 externalSessionIdleTimeoutSec 连 ifBlank 保护都没有，是无条件覆盖，本机的播放追踪开关也会被另一台设备悄悄改掉。手动「导入…」是同一条路：SettingsScreen.kt 的导入按钮 pickTextFile 后立刻 importBackup，无预览无确认，BackupFileDto.containsSecrets（Backup.kt:68）从未在 UI 出现。Jellyfin/Emby 的跨设备状态走服务端 API，服务端配置只有管理员能改；Jellyfin 10.10 内置备份在还原前会列出将覆盖哪些部分并要求确认。

> **改法**：两条：applyBackup 里把 storage.url/username/password 与本机播放设置从自动应用里摘出去，同步只合并 libraries/userData/pins；手动导入前先解析出 containsSecrets 与将被覆盖的字段，弹一个确认框。

### 同步其实是定时自动跑的，界面从头到尾没说，也看不到上次跑的时间和间隔

**打磨** · `composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:366-370`

在设置里打开「启用同步」，看到并排两个按钮「立即上传」「从云端合并」，自然理解成手动同步，于是每次看完剧都惦记着回来点一下。实际 SyncService.kt:51 在 init 里 `scheduleWithFixedDelay(::tick, 60s, 60s)`，tick 内 70-78 行自动 pull + 自动 upload，间隔是 AppConfig.kt:42 的 10 分钟上传、拉取取一半。SettingsScreen.kt 全文 grep「自动」零命中，同步区说明（366-370）只讲「写成一个文件放在 WebDAV 上，其它设备读回来合并」，控件只有开关、路径框和两个手动按钮（409-435）。反过来若你以为它一直在自动跑也无从确认：状态行（SettingsScreen.kt:447）拿到了 lastUploadAt 却只写「已上传 · 存储可写」，不说什么时候上传的，出门前想确认「进度传上去了没」只能盲点一次「立即上传」。间隔也没有入口：minIntervalMinutes 在 UI 层 grep 零命中，只有 Models.kt:498 定义和 MediaFacade.kt:419 接受写入。Jellyfin 控制台 → 计划任务把每个任务的上次运行时间和触发器都列出来并可编辑，Emby 的 Scheduled Tasks 一样。

> **改法**：说明文字补一句自动频率；状态行把「已上传」换成「上次上传 <时间>」「上次合并 <时间>」；给 minIntervalMinutes 加一个输入框或几个预设档（后端已完全支持，只差 UI）。

## 库维护：元数据编辑与条目管理

### 完全没有「编辑元数据」：刮错一个字段，只能整条换刮削源或者一直忍着

**严重** · `composeApp/src/app/com/daview/app/ui/DetailScreen.kt:404-424`

某部片刮到了同名翻拍版，标题简介年份全错但海报是对的；或者一部没有任何刮削源覆盖的冷门片，标题永远停在文件夹名 "XXX.2019.1080p.WEB-DL"。用户想做的只是改一行标题，但详情页上没有任何输入框——DetailScreen.kt:404-424 只有「合并重复条目」和「手动指定刮削条目」两个图标按钮。MediaFacade 全文 518 行的写入口只有 setFavorite(190)、setPlayed(198)、merge(224)/unmerge(235)、identify(290)、updateSettings 与库 CRUD、importBackup，没有任何 updateItem/setName/setOverview。Repository.kt:910-931 的 UPSERT_SCANNED_ITEM 是 `name = CASE WHEN items.scraped_at IS NULL THEN excluded.name ELSE items.name END`，同样的写法覆盖 overview/year/genres/people/poster_url 等全部展示字段，只有刮削器路径（MetadataService.kt:152-179）能写它们；identify 是整条按 providerId 重建，不能改单字段。改不了标题就意味着排序和搜索也一直是错的（sort_name 同样被锁）。唯一理论旁路是导出备份手改 JSON 再导入。Jellyfin 的条目 ⋯ 菜单 →「编辑元数据」是多标签对话框（常规/详情/图片/字幕），标题、排序标题、原名、年份、首播日期、评分、分级、类型、工作室、演职人员逐字段可改，每个字段旁还有一把锁，锁上后重新刮削不覆盖；Emby 的 Edit Metadata 结构几乎一样。这是自建媒体库最常用的功能之一。

> **改法**：先做最小集合：MediaFacade 加 updateItem(id, name/sortName/overview/year/genres) 写入 items 表并把 scrape_status 标为 MANUAL；DetailScreen 的图标行加一个「编辑」打开字段表单。字段锁可以留到后面，先保证「改过的字段重新刮削不被覆盖」（复用现有的 lockedProvider 思路）。

### 封面/背景图完全没法换，扫描也不认目录里的 poster.jpg / fanart.jpg

**严重** · `core/src/core/com/daview/server/api/MediaFacade.kt:452-461`

没配 TMDB key 或所有刮削源都没匹配上时，条目在库里就是一个永远的灰色占位图标（Components.kt:174-183 只在 posterUrl == null 时画 Icons.Filled.Movie）。用户明明在同一个 WebDAV 目录里放了 poster.jpg，App 里却没有任何地方能指定它——既不会自动读，也不能上传、不能贴 URL。MediaFacade.kt:452-461 的 imageFile() 只认 posterUrl/backdropUrl/logoUrl 三个远程地址，这三列被 Repository.kt:925-928 的 CASE WHEN 锁给刮削器；`grep -rni "jpg|jpeg|png|image" core/.../library/` 返回空——Scanner 与 NameParser 完全不认识图片文件。比这更封闭一层：ImageCache.kt:29-50 的 get(remoteUrl) 是裸 OkHttp GET 不带 WebDAV 认证，所以即便硬把 posterUrl 填成存储里的路径也取不到。WebDavClient.kt:247 的 put() 和 264 的 delete() 存在但无 UI 接入。Jellyfin/Emby 详情页的编辑对话框有一整个 Images 标签页（从候选图挑、本地上传、按 Primary/Backdrop/Logo 分类），两家的扫描器还默认读同目录的 poster.jpg/folder.jpg/fanart.jpg，Plex 读 poster.jpg 同理——这是没有 API key 时唯一的救命稻草。

> **改法**：两步都很轻：(1) Scanner 在每个电影/剧集目录里顺手认 poster.jpg|folder.jpg|cover.jpg 和 fanart.jpg|backdrop.jpg，写进 posterUrl/backdropUrl 并走已有的 StreamService 直链（顺带解决 ImageCache 不带认证的问题）；(2) 详情页海报上加「更换封面」，允许贴 URL 或选本地文件后经 WebDavClient.put 上传到条目目录。

### 开启同步后删除媒体库不生效：最多 60 秒后被云端文件原样恢复成一个空库，而本机刮削结果已被真删

**严重** · `core/src/core/com/daview/server/api/Backup.kt:190`

在设置 → 媒体库里点删除，最多 60 秒后自动 pull 跑一次，那个库就从云端同步文件里原样回来了（条目已被删光所以是个空库），扫描一下内容全回来。Backup.kt:190 `backup.libraries.forEach { context.repository.upsertLibrary(it) }` 只 upsert，全仓库 grep `tombstone|deleted_at|deletedLibraries` 零命中，没有任何删除传播机制；SYNC_SECTIONS libraries=true（SyncService.kt:244-251）。单设备也一样失效：SyncService.kt:102 的 upload() 里 `when (val merged = mergeRemote(dav, path))` 在 PUT 之前先把云端文件合并进本地，刚删掉的库在这次上传前就被恢复、然后原样传回去。而 Repository.kt:91-98 的 deleteLibrary 是真删 user_data + items + libraries 三张表，SYNC_SECTIONS items=false 所以刮削结果不会随同步回来——库回来了是空的，必须重扫一遍 WebDAV。这是一个与用户数无关的功能性 bug：开着同步就删不掉媒体库，且本机刮削数据不可恢复地丢失。Jellyfin/Emby/Plex 里删除一个媒体库是会生效并保持生效的，不会在一分钟后自己长回来。

> **改法**：两条路都很小：给 libraries 表加一个 `local_only` 标志，标了就不进同步载荷也不接受来自同步文件的恢复；或在配置里存一份「本设备排除的库 id」，applyBackup 里 upsertLibrary 之前跳过它们。前者顺带解决「同一目录在不同设备上不想都挂载」。

### 删除媒体库没有任何二次确认，误触一次就抹掉本机全部刮削结果

**严重** · `composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:166`

「设置」是底栏第三项（App.kt:240-250），媒体库卡片右边那个垃圾桶图标 SettingsScreen.kt:166 `IconButton(onClick = { state.deleteLibrary(library.id) })` 直连——我在 SettingsScreen.kt 里 grep AlertDialog 只有 105 和 628 两处，都属于 WebDavPickerDialog，删库路径上没有任何确认；AppState.kt:320-324 的 deleteLibrary 也只是删完弹个 toast；Repository.kt:91-98 一起 DELETE user_data / items / libraries 三张表。孩子拿平板乱点、或自己手滑一次，整个媒体库定义和本机几千条刮削结果当场没了，要重扫一遍 WebDAV（几十分钟起）。Jellyfin 和 Emby 的媒体库管理在 Dashboard 下、只有 admin 能进，普通用户的设置页里根本没有这一节，删除时有确认对话框；Plex 只有服务器所有者能管理库。

> **改法**：加一个说明后果的确认框（会清空本机刮削结果和该库的观看记录，需要重新扫描）。在没有用户系统之前，给整个「设置」页加一个可选的 4 位 PIN 是唯一能挡住小孩的东西。

### 没有单条「重新刮削」：一个 FALLBACK 匹配会写死 scraped_at，此后只能整库 REFRESH

**打磨** · `core/src/core/com/daview/server/scraper/MetadataService.kt:177`

详情页明明写着「次级来源顶替（未可靠匹配，建议核对）」（DetailScreen.kt:187），想让它重试一次却没有「刷新这条的元数据」。可选的只有：对整个库跑 REFRESH（几百部全部重刮，WebDAV 上几十分钟到几小时），或打开手动指定自己挑候选。卡住的情形比想象中窄：MetadataService.kt:139-146 在没匹配上时故意把 scraped_at 留 null（注释写明「新 API key 或修正过的文件夹名应该在下次扫描时再有一次机会」），而 :101-102 没配任何 key 时直接 return false 不产生 FALLBACK——所以「补上 key → MISSING」这条路是通的。真正永远捡不到的只有：此前已配过某个源并落到 FALLBACK，MetadataService.kt:177 写了 scrapedAt=now，Repository.kt:408 的 `AND i.scraped_at IS NULL` 就再也捡不到它。替代路径也没那么贵：IdentifyDialog.kt:73-89 打开时已预填 provider、片名和年份，点一下 212-217 的搜索图标列出候选、点 239 的候选行即完成，是三次点击不是查网站；差别只在需要用户自己判断哪个候选对，且 MediaFacade.kt:290-307 会把结果钉成 lockedProvider/MANUAL 以后不再自动刮。Jellyfin 的条目 ⋯ →「刷新元数据」可选「搜索缺失的元数据」或「替换全部元数据」，作用域是这一条；Emby 的 Refresh 一致；Plex 的 Fix Match / Refresh Metadata 同样是单条粒度。

> **改法**：MediaFacade 加 refreshItem(id, replaceAll: Boolean)，内部就是对单个 item 调 MetadataService 那条已有的刮削路径；入口放在 ItemMenuItems 里（这样库网格、搜索页右键都能用）而不是只放详情页。

### official_rating 是个死字段：三个刮削器一次都没填过，详情页也不显示分级

**打磨** · `core/src/core/com/daview/server/scraper/MetadataService.kt:283`

晚上想给孩子挑一部片，点进详情页只看到年份、时长、TMDB 评分、类型和观看状态五个 chip（DetailScreen.kt:279-285），没有 PG-13 / R / 16+ 这类信息，判断合不合适只能自己去搜。字段是齐的：Database.kt:101 建了 official_rating 列，Models.kt:134 DTO 有 officialRating，Repository.kt:751 写、789 读、920-921 upsert 都老实带着它。但 Scrapers.kt 三个构造点（TMDB ~250-262、TVDB ~366-376、bangumi ~498-508）全都只赋 communityRating，全文件 officialRating 只有第 52 行那个默认 null 的声明；MetadataService.kt:283 还显式写了 `officialRating = null`，所以 MetadataService.kt:164 的 `metadata.officialRating ?: item.officialRating` 永远是 null。「按分级挡掉孩子看到的内容」需要先有用户档案才有地方安放，那是另一条；这里剩下的是纯展示缺失。Jellyfin 刮削时从 TMDB 的 release_dates（电影）和 content_ratings（剧集）取分级，详情页顶部有分级角标，用户设置里还有 Maximum parental rating 下拉。

> **改法**：先让 TMDB 刮削多请求一次 release_dates / content_ratings，把分级填进已经存在的 official_rating 列，在 DetailScreen.kt:280 那行 chip 里加 `item.officialRating?.let { Chip(it) }`——光是「看得见分级」就解决掉一半场景。

## 家庭与多设备

### 没有用户/档案概念：共用一个系统账户的人共享一份进度与收藏，同步文件也按 item_id 整行覆盖

**严重** · `core/src/core/com/daview/server/db/Database.kt:122-133`

客厅一台电脑或一台共用平板：爸爸把某剧看到 S03E05，孩子打开首页第一条就是爸爸的进度，点进去从 S01E01 开始看，PlaybackService 每 2 秒 persist 一次直接覆盖同一行。user_data 的主键就是 `item_id TEXT PRIMARY KEY`（Database.kt:122-133），position_ms/played/play_count/favorite 全挂这一行；Database.kt:71-181 的六条迁移里没有任何 users/profiles 表；resume（Repository.kt:330-334）、nextUp（343-367）、unwatched（387-405）都不带用户维度；App.kt:186-251 的导航里没有档案切换。同步是同一个根因的第二个表现：Backup.kt:213-222 逐行 `local >= row.updatedAt` 才跳过、否则整行 restoreUserData（Repository.kt:535-560 把 position_ms/played/play_count/favorite 一起换掉），键只有 itemId，两个人共用一个同步文件时后写的那台把另一台整行覆盖，而 SyncService 每 60 秒 tick、默认 5 分钟自动拉 10 分钟自动传。要打两个折扣：桌面数据目录是 %LOCALAPPDATA%\DAView（AppConfig.kt:126-127）、安卓是 filesDir/daview（CoreContext.android.kt:24），都按操作系统账户隔离，各人用各自的系统登录本来就互不干扰——代价是每人要把整个 WebDAV 库重扫重刮一遍；同步路径也是每台设备自己填的（Models.kt:497、SettingsScreen.kt:398），两个人各填一个路径就不会碰面。Jellyfin 的 UserData 按 (UserId, ItemId) 存、登录页是一排用户头像，Emby 一致且多一栏 Library Access，Plex Home 最多 15 个成员、viewOffset 按档案分。

> **改法**：最小可行：user_data 加一列 user_id，迁移时把现有行统一写成 'local'，主键改成 (user_id, item_id)；AppConfig 存「当前档案名」，顶栏放一个下拉切换，Repository 的 resume/nextUp/unwatched/query 全部带上当前档案。同步载荷的键同步改成 (profileId, itemId)，BACKUP_VERSION 升 2，没有 profileId 的旧行按 'local' 处理。家庭场景里「分开记账」比「防偷看」重要，第一版可以完全不做密码。

## 凭据与隐私

### WebDAV 密码和三个 API Key 的输入框全程明文显示，没有遮蔽也没有小眼睛

**严重** · `composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:212-217`

第一次配置时在办公室、地铁、客厅电视旁敲网盘密码，全程以明文大字显示在屏幕上直到保存；刮削源那三个 Key 粘贴进去也一直摊着，手机端还会被输入法记进学习词库。SettingsScreen.kt:212-217 的密码框是裸 OutlinedTextField，只有 value/onValueChange/label/singleLine/modifier 五个参数，既无 visualTransformation 也无 keyboardOptions（因此 IME 会当普通文本做联想与学习）；245-262 三个 Key 框写法完全相同；208/210 的地址、账号框与之无差别，说明作者没有区分对待密码字段。全仓 grep visualTransformation|PasswordVisualTransformation 在三个源码树下零命中，也没有平台特化实现。一点修正：字段初值是空串（SettingsScreen.kt:203/232-234，标签写「（已设置）」「留空保持不变」），已保存的密码不会回显，明文暴露只发生在本次输入到点保存之间；但账号字段（SettingsScreen.kt:202/210）确实回显。Jellyfin Web 与 Android 的登录框、Dashboard 的 API Key 输入都是 type=password，Emby 的网络存储凭据与用户密码、Plex 的登录与 Claim Token 同样默认打码——三家都做了十年。

> **改法**：给这四个框加 `visualTransformation = PasswordVisualTransformation()` + `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)`，再放一个 trailingIcon 切换明暗。改动量在十行以内。

### 凭据只能替换不能抹除，也不告诉你配置文件在哪

**打磨** · `core/src/core/com/daview/server/api/MediaFacade.kt:78-86`

要把笔记本卖掉/借给别人，或只是想退回未配置状态、暂时不让 App 再连网盘：把密码框留空点保存什么也不会发生——MediaFacade.kt:78-86 是清一色 `ifBlank { current }`，空串被解释为「保持原值」。SettingsScreen.kt 全文只有第 167 行一个 Icons.Filled.Delete（删除媒体库），grep 清除/重置/注销/断开/移除在 composeApp/src/app 下零命中；grep config.json|LOCALAPPDATA|dataDir|数据目录 同样零命中，App 里没有任何地方告诉你文件在 %LOCALAPPDATA%\DAView\config.json（路径逻辑在 AppConfig.kt:123-129）。要说明的是：换 WebDAV 账号或换 TMDB Key 都能做到（填新的非空值即可，MediaFacade.kt:78-80/84 的非空分支正常生效），真正做不到的只有「抹成空」这一件事，不是日常操作。Jellyfin 控制台的 API 密钥每个有删除按钮、设备可逐台撤销 token，Emby 有 Revoke + Sign Out，Plex 在 plex.tv/devices 逐设备移除；不过那些对应的是客户端持有的服务端令牌，与本机单用户配置文件不完全同层。

> **改法**：存储和刮削两块各加一个「清除」按钮，走显式的 clearStorageCredentials()/clearScraperKeys() 路径（不能复用 ifBlank 的 update），并在设置页底部显示数据目录路径。

### TMDB API Key 明文出现在 URL 里，任何一次请求失败都会把它整条写进日志

**打磨** · `core/src/core/com/daview/server/scraper/Scrapers.kt:121,126`

刮削时网络抖一下或撞上 429，日志里就出现一整行 `请求 https://api.themoviedb.org/3/search/movie?api_key=<你的真 key>&... 返回 HTTP 429`。Scrapers.kt:190、212、273、279 四处 TMDB 请求都把 key 拼在 query string 里；同文件 getJson 的两条失败分支 121 `log.warn("请求 {} 失败: {}", url, it.message)` 与 126 `log.warn("请求 {} 返回 HTTP {}", url, it.code)` 直接把完整 url 打出来，postJson 在 148/153 同样两行，全无脱敏。core/build.gradle.kts:62 在 Android 侧绑定 slf4j-simple（输出 System.err 即 logcat），且全仓没有任何日志查看/导出入口，用户对泄露无从察觉——去提 issue 贴日志时顺手就把 Key 送人了。现代 Android 上第三方读不到别家 logcat，需要开 USB 调试跑 adb logcat 或走系统错误报告，所以是 minor。Jellyfin 在日志里对 api_key 这类查询参数做过滤后再落盘，Emby 同样对 token 打码，两家的「下载日志」按钮前提就是日志已脱敏。

> **改法**：打日志前把 url 里的 `api_key=...` 用正则替换成 `api_key=***`；或改用 TMDB 的 Bearer 头（v4 read access token）走 header，URL 里就不再带密钥。

### 填 http:// 的 WebDAV 无任何提示，而明文链路上传的是网盘主账号密码本身

**打磨** · `composeApp/src/androidMain/AndroidManifest.xml:18`

家里 NAS 或路由挂的 WebDAV 常是 `http://192.168.1.x:5005/dav`，很多人也会照教程把公网地址写成 http。DAView 照单全收：SettingsScreen.kt:208 的地址框没有任何 scheme 校验或提示，WebDavClient.kt:62-64 的 authHeader 是 Base64 Basic、66-70 通过 request(url, useAuth=true) 无条件附加、不看 scheme，同一个 Wi-Fi 下抓一次包就是完整账号密码。AndroidManifest.xml:18 是全局 usesCleartextTraffic="true"，仓库内没有 network_security_config.xml。要公平地说：Jellyfin 自家的 Android 客户端同样必须放开明文流量（否则连不上局域网 http:// 服务端），连接 http 服务端时也不给警告，首次登录本身就是明文 POST 用户名密码——「只传可撤销 token」只对登录之后成立。而且本项目的内置播放器就是从 127.0.0.1 管道取流（InternalPlayer.android.kt:98、LocalAssetLinks.kt:25-26），明文不能一刀切禁掉。真正是 DAView 独有的差别是：它在明文链路上传的是网盘主账号密码本身而不是可撤销令牌，而 UI 对此零提示。

> **改法**：用 network_security_config 只对 127.0.0.1 和用户自填的域名放行明文，其余走 TLS；同时在存储设置里检测到 url 以 http:// 开头且不是回环/私网时显示一行醒目提示：密码会以可还原的形式明文传输。

### Android 未声明备份排除规则，明文 config.json（含网盘密码与 API Key）会进厂商云备份/换机迁移

**打磨** · `composeApp/src/androidMain/AndroidManifest.xml:15`

AndroidManifest.xml:15 是 allowBackup="true"，整份 manifest 没有 fullBackupContent/dataExtractionRules，仓库内也找不到任何 backup_rules 或 data_extraction_rules 文件；CoreContext.android.kt:24 的数据目录是 `File(context.filesDir, "daview")` 落在自动备份范围内；AppConfig.kt:15-30 的 password 与三个 key 都是原文 String，114-119 的 persist() 直接明文 JSON 落盘，全仓无 Keystore/EncryptedSharedPreferences。两处要收窄：gradle/libs.versions.toml:24 androidTargetSdk=37，Android 12 起 adb backup 已不含应用数据；Google 自动备份自 Android 9 起对应用数据做基于锁屏凭据的端到端加密，Google 侧拿不到明文。剩下的真实风险面是厂商云备份/换机迁移的实现质量参差。凭据形态才是关键差别：Jellyfin/Emby/Plex 客户端本地存的是服务端签发的 access token，漏出去点一下就作废，DAView 存的是网盘主账号密码本身，而且如上一条所说 App 里连让它失效的按钮都没有。

> **改法**：给 application 加 `android:dataExtractionRules` / `fullBackupContent` 把 daview 目录（至少 config.json）排除出备份；进一步把 password 与三个 Key 挪进 EncryptedSharedPreferences，config.json 只存非敏感项。

### 勾选「包含凭据」后导出明文密码文件没有二次确认，只有一段红字说明

**打磨** · `composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:501-512`

SettingsScreen.kt:501-504 的「包含凭据」是普通 ToggleButton，505-512 只有一段红字告诉你文件里有什么，导出 onClick（约 524-544）直接 backupChunks + saveTextFile，没有确认对话框；grep Biometric|FLAG_SECURE|PIN 在三个源码树下零命中。所以任何拿到解锁设备的人切开关 → 点导出 → 走系统保存对话框，就能拿到一个明文带 WebDAV 密码和 API Key 的 json。要修正原报告的对照：Plex Home 的 Managed User PIN 与 Emby 的 Easy PIN 都是「多用户服务端上切换用户资料」的凭据，不是打开客户端 App 需要过的锁；Jellyfin 的每用户密码同理，客户端登录后持久保持，谁拿起手机都能直接浏览片库；三家的 Android 客户端在浏览界面也都不设 FLAG_SECURE。所以「三家都要校验谁在用这台设备」不成立，真正剩下的问题只有导出明文凭据没有二次确认这一条。

> **改法**：给「包含凭据导出」加一个二次确认弹窗（哪怕只是让用户再点一次「我确认导出明文密码」）。再往上可以做一个可选的 4 位 PIN / 生物识别门禁覆盖设置页，与删库确认那条合并考虑。

## 呈现、文案与性能打磨

### 首播日期、制作方、分级刮回来了却一个都不显示；全 App 看不到任何日期时间

**严重** · `composeApp/src/app/com/daview/app/ui/DetailScreen.kt:277-283`

看剧时想知道某一集哪天播的、电影具体几月上映，详情页只有一个孤零零的年份数字；想确认「这个库上次扫描是什么时候」「这条上次刮削是什么时候」「同步上一次跑是什么时候」也全查不到，判断某次扫描有没有生效只能凭感觉。数据都在：Scrapers.kt:254/369/500 写 premiereDate、258/373/504 写 studios，MetadataService.kt:161/164/166 合并 premiereDate/officialRating/studios、352 每集取 airDate，落库 Repository.kt:748/751/753、读回 786/789/791，DTO 在 Models.kt:131/134/136。客户端侧 grep `premiereDate|officialRating|studios|lastPlayedAt|lastScanAt|scrapedAt` 在 composeApp/src 里只有 DetailScreen.kt:189 一处且只做判空；grep `LocalDate|DateTimeFormatter|SimpleDateFormat|Instant|kotlinx.datetime|java.time` 在 composeApp/src 与 shared/src 零命中——整个客户端没有任何日期格式化能力。详情页头部 chip 行（277-283）只有 year/runtime/评分/季数/观看状态，分集行（697-710）只有观看状态、时长、体积、字幕条数、路径，LibraryDto.lastScanAt（Models.kt:68）在 composeApp 零命中，同步状态行（SettingsScreen.kt:447）拿到 lastUploadAt 只写「已上传」。Jellyfin 详情页标题下一行就是首播日期、分级是标题旁的徽章、工作室在下方信息块，剧集列表每行右侧带播出日期；Emby 的 Dashboard 逐条显示计划任务的上次运行时间。

> **改法**：加一个共用的 formatDate（premiereDate 本来就是 ISO 字符串，截 yyyy-MM-dd 就够，不必引 kotlinx-datetime）。优先补三处：详情页「文件信息」块加「首播日期 / 分级 / 制作」，分集行的元信息串里加播出日期，设置页把「已上传」换成「上次上传 <时间>」并给库卡片加「上次扫描 <时间>」。

### 详情页简介固定截断 5 行，既不能展开也不能选中复制

**严重** · `composeApp/src/app/com/daview/app/ui/DetailScreen.kt:297-305`

打开一部番的详情页，bangumi/TMDB 的简介动辄十几行，DetailScreen.kt:297-305 是 `Text(it, style = bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)`，往下滚没有第二处显示简介——全文件 grep overview 只有 297（主简介）与 689（分集简介，maxLines = 2）两处渲染，没有展开开关也没有点击回调，ItemContextMenu/Components 里也没有「查看完整简介」入口。鼠标拖也选不中：SelectionContainer 在 DetailScreen.kt:26 只 import 一次，唯一使用点是第 471 行，包住的是 FileInfoSection 的 rows（439-479，注释写明「路径才是值得从 App 里拷出去的部分」），简介在 DetailHeader 里不在其内。安卓窄屏下 5 行 bodyMedium 基本必截断，这是每次挑片都撞到的。Jellyfin 详情页的 overview 完整铺开，Plex 和 Emby 给「…更多 / MORE」点开全文，三家都不存在「简介永远看不到全文」。

> **改法**：给简介加点击展开（maxLines 在 5 与 Int.MAX_VALUE 之间切换），或干脆不截断——详情页本来就是 LazyColumn。顺手把 SelectionContainer 的范围扩到简介上，剧情梗概本来就是最想复制出去的一段。

### 海报下载失败与「压根没刮到图」在界面上无法区分，失败还不记忆——每次滚回视口都重付一次 15s/30s 超时

**严重** · `core/src/core/com/daview/server/media/ImageCache.kt:44-50`

这个应用支持 bangumi、面向中文用户，而 TMDB 的图片 CDN（image.tmdb.org）在国内经常直连不通，刮削走的 API 域名与图片是两条链路。结果是元数据全对但整屏海报是灰色空方块，用户分不清是没刮到图、网络问题还是缓存坏了：Components.kt:167-181 只在 `item.posterUrl == null` 时画 Movie 图标兜底，posterUrl 非空但下载失败走 AsyncImage（168 行）且没传 placeholder/error/fallback；App.kt:74-79 的 ImageLoader 只配了 crossfade，没有全局 error painter；LocalImageFetcher.kt:33 的 `?: return null` 把「没有这张图」和「下不下来」压成同一个 null。更实的是失败不记忆：ImageCache.kt:44-50 失败直接 return null，全文件 68 行没有任何失败记录或退避，而 24-25 行是 15s 连接 + 30s 读超时，所以每次滚回视口都重走一次完整 HTTP 尝试，格子一直在转。我也确认了 core/src 里除播放代理外没有任何 HTTP 代理设置，SettingsScreen.kt 也搜不到代理项，而 ImageCache.kt:13-17 那句「设备够不到 CDN 但服务端够得到」的设计理由在无服务端形态下已经失效。一点修正：Components.kt:243-257 每张卡片下方恒定渲染标题和副标题，所以不会「什么都认不出来」。Jellyfin 服务端为每张图存 BlurHash 先渲染模糊色块，Emby 在没图时画带标题的占位卡，Plex 加载失败显示带片名的灰底卡。

> **改法**：给 AsyncImage 传 `error = { 画和 posterUrl==null 相同的 Movie 图标 }`；ImageCache 里记一个内存中的失败 URL 集合（带 5 分钟过期），失败后短期内直接返回 null 不再发请求；连接超时从 15s 降到 5s。

### 扫描进度与刮削源在界面里漏出内部标识（scraping/probing、小写 tmdb），仓库里已有的中文映射只用在 Android 通知栏

**打磨** · `composeApp/src/androidMain/kotlin/com/daview/app/ScanForegroundService.kt:145-152`

任何一次扫描都会碰到：首页和设置页的库卡片上写着「动画 · scraping 37/412 某某剧场版」「listing 0/1 读取 /Ani」「probing 12/400 …」。阶段名是裸字符串：Scanner.kt:39 "listing"、50 "scanning"、69 "saving"，ScanService.kt:97 "scraping"、105 "probing"、112 "done"，DTO 里就是 `val phase: String`（Models.kt:391），UI 原样拼中文句子（HomeScreen.kt:122 `"${status.libraryName} · ${status.phase} ${status.current}/${status.total} ${status.message}"`、SettingsScreen.kt:174）。中文映射表其实已经写了，但只存在于 Android 通知：ScanForegroundService.kt:145-152（"scraping" -> "刮削"、"probing" -> "解析容器"），composeApp 其它地方 grep phaseLabel 零命中——通知栏中文、App 内英文。另外两处漏出：SettingsScreen.kt:132 `library.providerOrder.joinToString(" → ") { it.name.lowercase() }` 打出 tmdb → tvdb → bangumi，而 Models.kt:40-46 就有 displayName；DetailScreen.kt:179-190 的 scrapeLabel 用 `"${it.key} ${it.value}"` 拼 providerIds，key 来自 MetadataService.kt:92/114/132 的 `provider.name.lowercase()`。Jellyfin 控制台的扫描任务显示为「扫描媒体库」加百分比且翻译随界面语言走。

> **改法**：把 ScanForegroundService.kt:145-153 那个 when 挪到 commonMain（放 Components.kt 旁边即可），HomeScreen.kt:122 和 SettingsScreen.kt:174 改用它并补上 done/error/cancelled 三档；刮削源的两处改成 MetadataProvider.displayName。

### 几处文案拿实现细节当解释，普通用户读不懂

**打磨** · `composeApp/src/app/com/daview/app/ui/DetailScreen.kt:187`

三处每天都可能撞见：(a) DetailScreen.kt:187 `ScrapeStatus.FALLBACK -> "次级来源顶替（未可靠匹配，建议核对） · $ids"`（渲染在第 458 行的「刮削」行，$ids 还是小写 provider key）——什么叫次级来源、顶替了谁、要核对什么，得先懂刮削器内部有个 fallback 概念才读得通；(b) PlayerScreen.kt:112-117 的「进度依据 Matroska 索引与播放时长推算，可能有数秒误差」，用户不知道 Matroska 就是 mkv，更不关心索引；(c) SettingsScreen.kt:150-152 扫描菜单第二项「仅刮削未刮削的条目 / 不走文件与容器探测，只补没有元数据的」，「容器探测」是内部术语，用户看不出这项和第一项差在哪。Jellyfin/Emby 的 Identify 与字段锁不向用户解释匹配置信度，播放进度来自哪种估算三家一律不显示（保留意见：Plex 的 Fix Match 对话框会显示候选匹配分数，所以不能说三家完全不暴露匹配内部判断；但「详情页常驻一行解释 fallback 机制」确实是三家都没有的）。愿意告诉用户这些是好事，但话要说成结果而不是机制。

> **改法**：(a) 改成「自动匹配，把握不大 · 建议核对，可点右上角重新指定」；(b)「进度为估算值，可能有几秒误差」就够，机制留给日志；(c)「只补缺元数据的条目（不重新读文件，较快）」。

### 回到首页/标记已看会整体重建首页数据，各媒体库「未观看」整行在补数据前会先塌陷一次

**打磨** · `composeApp/src/app/com/daview/app/data/AppState.kt:157-171`

从详情页返回或在首页右键把某部片标成「已观看」时，各库的「XX · 未观看」行会先整体消失再一行行冒回来。AppState.kt:157-171 的 refreshHome 先在第 160 行用 `home = HomeData(resume=…, nextUp=…, latest=…)` 赋一个 unwatched 为空 map 的新对象，再在 167-170 行 `home.copy(unwatched = …)` 补回，中间是 N 次串行的 unwatched 查询；Components.kt:279 的 `if (items.isEmpty()) return` 让 MediaRow 连 SectionHeader 一起不渲染，所以是整行消失而不是变空。触发路径是 AppState.kt:110 和 268。要打三个折扣：resume/nextUp/latest 是 HomeData 构造的实参，先求值后赋值所以那三行是原子替换不会闪；未观看行排在 HomeScreen.kt:66-111 的一屏之后、其下只有 114-116 的扫描块，正常情况下用户看不到内容上跳；查询本身也不慢（Database.kt:48-53 的注释说的是加 ANALYZE 之后 nextUp 从 6.3s 降到 30ms，而 refreshStatistics() 每次启动和每次扫描后都会跑）。Jellyfin 首页各 section 是各自独立请求各自刷新的，标记已观看只把那张卡从「继续观看」里移除并做动画，不重建整页。

> **改法**：两个小改：refreshHome 里不要先赋一个 unwatched 为空的对象，把 unwatched 一起算完再一次性赋值（或用 home.copy 保留旧的 unwatched）；refreshAfterWatchChange（AppState.kt:255-269）在首页时改成把那一条从 home.resume 里移除、其余原地替换。

### 海报 URL 已经在列表响应里拿到过却被丢弃，每次内存缓存未命中都要为一张缩略图重跑一次整条 item 查询

**打磨** · `core/src/core/com/daview/server/api/MediaFacade.kt:452-460`

在网格里上下滚动，Coil 的内存缓存装不下整库海报（150dp 宽 2:3 的图按手机 3x 密度解码约 450×675×4 ≈ 1.2MB/张），滚回来必然未命中。每一次未命中都会为那一张海报重新跑一遍 MediaFacade.kt:452-460 的 `context.repository.item(itemId)`，而 Repository.kt:149-152 的 item() 用 SELECT_ITEM，Repository.kt:851-868 的 SELECT_ITEM 带着 child_count/episode_count/played_episode_count/series_name/series_poster 五个相关子查询，Repository.kt:774-820 的 readItem 还要反序列化 people、media_streams、genres、studios 四段 JSON——只为读 posterUrl 一列。而 LocalAssetLinks.kt:22-23 只把 remoteUrl hash 进 ?v= 就把真实 URL 丢掉了。实际症状只是「滚回去的海报要多等一拍才填上」而不是卡顿：所有 DB 访问都在 MediaFacade.kt:57 的 withContext(Dispatchers.IO) 上，主线程不参与，且 Database.kt:12-15 启动时、Scanner.kt:84 扫描后都调 refreshStatistics()。Jellyfin/Emby 的 /Items 响应里就带 ImageTags、图片 URL 完全在客户端拼出来，Plex 的 thumb 字段同理。

> **改法**：两条都很小：让 LocalAssetLinks.image() 把 remoteUrl 也编进 URI（或在内存里存一个 itemId+type→remoteUrl 的映射）让 imageFile 不用查库；或给 Repository 加一个只查 `SELECT poster_url, backdrop_url, logo_url FROM items WHERE id = ?` 的轻量方法。顺带给网格用的列表查询做一个不含 overview/people/media_streams 的投影。

### 单连接 + 全局互斥锁抵消了 WAL：扫描的写入阶段和刮削开始前的全库读会让界面数据慢一拍

**打磨** · `core/src/jvmOnly/kotlin/com/daview/server/db/JdbcSql.kt:21-23`

给 3000+ 条目的目录做首次全量扫描时想去首页翻翻已刮好的片，在「写入数据库」阶段和刮削开始那一刻界面数据会停顿几秒。JdbcSql.kt:21-23 是全进程一个 Connection + 一把 ReentrantLock，第 42 行 read 和 44 行 transaction 共用它，第 32 行开的 WAL 被这把应用层互斥锁抵消，AndroidSql.kt:30/54/56 同构。Scanner.kt:36 把整库 records 攒在一个 List、第 70 行一次性 upsertScannedItems（Repository.kt:136-147 是单个 db.transaction + executeBatch）；Repository.kt:407-413 的 itemsNeedingScrape 没有 LIMIT，会持锁把全库 MOVIE/SERIES 的完整 DTO（含 790-818 的 JSON 反序列化）一次读进内存。要修正三点：进度条照常走（MediaFacade.kt:142 → ScanService.kt:31 读的是 27 行的 ConcurrentHashMap，完全不碰数据库）；主线程从不被阻塞（所有 DB 访问经 MediaFacade.kt:57 的 Dispatchers.IO，扫描本身在 ScanService.kt:23-26 的专用单线程上），所以是「转圈/数据慢一拍」不是冻结；扫描最长的两段（WebDAV 遍历、逐条刮削网络往返）都不持锁。Jellyfin/Emby 的扫描是后台计划任务，扫描中媒体库照常可浏览可播放。

> **改法**：两条最小改动：Scanner.kt:70 把 records 按每 200 条 chunked 分批写入，让锁有间隙被 UI 查询抢到；itemsNeedingScrape 改成只返回 id 列表（或带 limit 分批取），刮削时再逐条读完整行。

### 图片缓存只增不减、没有任何清理入口，Android 上落在 filesDir，系统「清除缓存」清不掉

**打磨** · `core/src/core/com/daview/server/media/ImageCache.kt:20,30`

浏览过几千部片之后，海报（TMDB w500 每张 60-100KB）加上看过详情页的背景图（w1280 每张 300-500KB）在应用私有目录里越堆越多，几百 MB 起。ImageCache.kt 全文 68 行，公开方法只有第 30 行的 get，没有 size 统计、LRU、TTL 或任何删除逻辑，缓存目录在第 20 行 `dataDir/cache/images`；ServerContext.kt:30 构造它，grep 整个 core/src 除 MediaFacade.kt:460 的 `context.images.get(remote)` 外再无第二处引用——全仓库没有任何清理路径。SettingsScreen.kt（691 行）grep 缓存/cache/清理/清除 零命中，看不到占了多少也没法清。CoreContext.android.kt 的 dataDir 是 `File(context.filesDir, "daview")` 而非 cacheDir，系统「清除缓存」按钮无效、存储紧张时也不会被回收，唯一能清掉的「清除存储」会连同 SQLite、账号密码和全部观看进度一起删。最实的一点是孤儿文件：LocalAssetLinks.kt:22-23 的 ?v= 来自 remoteUrl.hashCode，配合 ImageCache.kt:31-36 的 sha1(remoteUrl) 文件名，执行一次「重新刮削全部」换一批 URL 就换一批文件名，旧文件永久留下。Jellyfin 的 Dashboard → 计划任务里有一项就叫「清理缓存目录」默认每天跑，Plex 在疑难解答里有 Clean Bundles 和 Empty Trash。

> **改法**：短期在 SettingsScreen 加一项「图片缓存 XXX MB / 清除」，调一个新的 ImageCache.clear()（删目录重建，图片按需重下不损失数据）；顺带把 Android 的 cache 子目录挪到 context.cacheDir 让系统按钮天然生效。长期再加按总大小的 LRU 淘汰。

### 界面语言不可切换、文案全部硬编码为中文

**打磨** · `composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:290-296`

设置页的「客户端」一节只有「深色主题」一个开关（SettingsScreen.kt:290-296），没有语言项。仓库里没有任何 i18n 设施：`find composeApp/src -name strings.xml -o -type d -name composeResources` 无结果（androidMain/res/values 下只有 themes.xml），`grep -rn 'Locale|stringResource|Res.string' composeApp/src core/src shared/src` 零命中；core 层的中文错误串（SyncService、WebDavClient 的 message）会经 SyncResultDto 直接进 snackbar（SettingsScreen.kt:437-439）。想让界面跟系统语言走、或装给不认识中文的家人用只能改代码重编译。要澄清一个常见的错误归因：App 内显示英文扫描阶段名跟有没有 i18n 设施无关——Android 那份中文映射（ScanForegroundService.kt:145-152）本身就是一段硬编码的 when，UI 侧调同样的函数就中文了。对中文用户来说这条日常影响接近于零，接近「作者已明确放弃的形态选择」。Jellyfin 把「显示语言」和媒体库的「首选下载语言」拆成两个独立设置，Emby、Plex 同样把 UI 语言放在客户端设置里。

> **改法**：如果不打算做多语言，至少把面向用户的字符串收到一处（哪怕只是一个 object Strings），先解决 core 层错误文案和 UI 文案各写一遍、改一处漏一处的问题；真要做切换，Compose Multiplatform 自带的 composeResources 就够用。

---

## 做得对的地方

一份只列问题的报告会失真。以下几处是三家成熟实现里也没有、或做得不如这里的。

- 外置播放器的进度追踪是这份代码里最有含金量的部分，三家成熟实现都不做这件事。Jellyfin/Emby/Plex 依赖播放器主动上报 PlaybackProgress；DAView 面对的是完全不合作的 PotPlayer/VLC，只能从 HTTP Range 请求和字节读取速率反推播放位置（PlaybackService.kt:207-227、PlaybackPipe.kt:204/248），还用 mkv 的 Cues 索引把字节偏移换算成时间（MkvProbe.kt:78-84 的 CUE_TIME/CUE_CLUSTER_POSITION），并用 ANCHOR_SETTLE_MS 和 SEEK_TOLERANCE_MS 区分「真拖动」和「播放器读文件尾的索引」（PlaybackService.kt:288-300）。这是在无服务端约束下解决了一个真实难题。
- 数据库性能不是拍脑袋做的。Database.kt:43-66 的注释写明了 PRAGMA optimize + analysis_limit 把 nextUp 从 6.3 秒降到 30 毫秒的实测结论，并解释了为什么它不是迁移步骤而是每次启动和每次扫描后都跑（统计信息会随库增长变陈旧）。Repository 里 SELECT_ITEM 的相关子查询、runOrderSlot 对特殊集的排序处理（Repository.kt:337、373-384）也都能看出是按真实数据量调过的。
- 刮削源的选择对中文/番剧用户是对症的，而且边界想清楚了：defaultOrder 给 ANIME 库把 bangumi.tv 排在 TMDB 之前（MetadataService.kt:38），同时明确注释「bangumi 只收录动画，所以刻意不作为真人剧的回退源，否则它会返回同名动画」（MetadataService.kt:40-41）。Jellyfin/Emby/Plex 原生都没有 bangumi，要装第三方插件。
- 对远端 WebDAV 的克制是对的：MkvProbe.kt:85 只按 range 读 512KB 文件头就解析出时长和轨道列表，而不是把整个文件拉下来；PlaybackPipe.kt:206-214 在可以拿到签名直链时直接回 302 让播放器绕开本机管道。这些都是针对「文件在网盘上、带宽有限」这个前提做的正确取舍。
- 细节上有真正的用户视角。LibraryScreen.kt:33-40 的 LoadingPane 延迟 150ms 才画 spinner，注释解释「库页 20 毫秒就回来了，在这个时间里出现又消失的转圈读起来是内容在闪，不是反馈」——这个层次的打磨在自托管项目里并不常见。合并重复条目（merge/unmerge）和手动识别对话框预填片名年份（IdentifyDialog.kt:73-89）也说明作者真的用这个东西整理过自己的库。
- 整体形态的取舍是自洽的：没有服务端、每台设备各跑一份 core、用 WebDAV 上一个文件对齐进度，省掉了 Jellyfin/Emby/Plex 都要求的「先装一个常驻服务」这一步。桌面端把播放交给 PotPlayer/VLC/mpv 而不是自己造播放器，也避免了转码和解码器这个最大的坑。这些不是缺陷，是对「个人 + 网盘」这个场景的合理简化。

---

## 这份报告是怎么来的，以及它的边界

**16 个维度各派一个审查者，逐个读代码。**
上手配置、首页、媒体库浏览、搜索、Android 播放器、桌面播放链路、观看状态与同步、扫描与维护、错误处理、遥控器与无障碍、凭据隐私、多用户、大库性能、文案本地化、Android 平台特性，外加一轮专门找「前面全漏了什么」的补充审查。

**每条结论都被另一个审查者尝试推翻。**
验证者的任务是证伪：重新打开代码核对这个功能是不是其实在别处实现了、对 Jellyfin/Emby/Plex 的对照是不是记错了、严重度有没有夸大，不确定就判推翻。121 条提出、66 条存活，被推翻的多数是「其实在 `ItemContextMenu` 或 `MediaFacade` 里已经有了」和「三家其实也没有」。

**标着 `复核追加` 的三条是人工核对后补的。**
都在桌面外置播放链路上，是维度划分之间漏掉的缝：整块外部播放面板不可达、播放失败在详情页以外静默、「自定义播放器」菜单项的写入函数没有调用点。

**审查期间工作树变过一次。**
过程中落地了桌面 libmpv 内置播放器（含 RTX 超分／HDR 与 libmpv 路径设置，现为 `01c13e4`）。因此「桌面把播放交给外置播放器」这个前提在部分条目里已经过时——桌面现在能加载到 libmpv 就走内置播放。涉及外置播放器的条目仍然成立（右键「用 X 播放」和加载不到 libmpv 时都还走那条路），但行号请按当前代码再核一眼。

**没有跑起来看。**
全部结论来自读代码，没有实际构建运行。涉及运行期表现的判断（滚动是否卡、扫描时界面是否停顿、大库首屏耗时）标的是代码上的成因，实际观感需要在真机上确认。
