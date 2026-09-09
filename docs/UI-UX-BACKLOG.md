# UI / UX 问题总表

把 [UX-REVIEW.md](UX-REVIEW.md)（69 条，功能与流程）和 [UI-REVIEW.md](UI-REVIEW.md)（75 条，设计与交互）合成一张清单，共 **144** 条。
每条的完整场景与代码推导写在对应的原报告里，这里是状态跟踪。

| 状态 | 条数 |
|---|---|
| 已修复 | 74 |
| 部分修复 | 15 |
| 未开始 | 55 |

标记说明：`[x]` 已修复，`[~]` 部分修复（后面写明还差什么），`[ ]` 未开始。

---

## 功能与流程（UX-REVIEW）

### 上手与配置：建库、扫描规则、刮削设置

- [x] **UX-01**（阻断）电影库里一个目录只留体积最大的那个视频，其余静默丢弃
      - 已修复：扫到目录里的每个视频，不再只留最大的那个
- [x] **UX-02**（严重）扫描没有任何排除规则，@eaDir / #recycle / .stfolder 会被建成空条目、配上 FALLBACK 海报、还删不掉
      - 已修复：@eaDir / #recycle / .stfolder 等系统目录跳过
- [ ] **UX-03**（严重）一个媒体库只能绑一个目录：电影分散在几个目录就只能建成几个互不相干的库
      - 位置：`shared/src/commonMain/kotlin/com/daview/shared/model/Models.kt:66`
      - 改法：把 LibraryDto.path 扩成 paths: List<String>（保留 path 兼容读），Scanner.scan 对每个 path 各走一遍 dav.list 后合并 records；libraryId 改成按所有路径排序拼接后推导。UI 上在路径行后加「+ 再添加一个目录」。
- [ ] **UX-04**（严重）完全不读目录里现成的 .nfo，从 Jellyfin/Emby/tinyMediaManager 搬过来的整套元数据全部作废
      - 位置：`core/src/core/com/daview/server/scraper/MetadataService.kt:37-44`
      - 改法：在 MetadataProvider 里加一档「本地 NFO」并作为所有库类型 defaultOrder 的第一位：Scanner 收集目录里的 *.nfo，用 4MB 上限的 dav.readFully 读进来解析 title/originaltitle/plot/year/premiered/genre/rating/uniqueid，命中就写 ScrapeStatus.MATCHED 并跳过网络刮削。
- [x] **UX-05**（严重）设置里的「元数据语言」是个死设置，填什么都没用
      - 已修复：库语言留空即继承；旧库迁移；设置改成四选一
- [~] **UX-06**（打磨）S01E01-E02 双集文件只算一集：解析器已解出 endEpisode 却无人读取
      - 部分修复：双集文件名显示为「第 1-2 集」；集数统计仍按文件数
- [ ] **UX-07**（打磨）建库时的「其他」类型与电影库完全等价，选它扫出 0 项时空态还在劝你「去扫描一次」
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:671-677`
      - 改法：把 OTHER 从建库对话框里去掉（显式列三项），或改名为「其他视频（不刮削）」并把 defaultOrder 设为 NONE；扫描结束时若 itemCount == 0，在进度条上写「未发现视频文件」而不是只显示「完成」。

### 找片（一）：首页与发现

- [x] **UX-08**（严重）「接下来」会推荐你还没看完的那一集的下一集，同一部剧同时占据两行
      - 已修复：首页对 resume 里已有的剧去重
- [x] **UX-09**（严重）「接下来」按集号排序而不是「最近看的剧优先」，刚追的剧排到行尾甚至被 20 条上限截掉
      - 已修复：接下来按该剧最近观看时间排
- [x] **UX-10**（严重）「最近添加」过滤掉全部分集，剧集库的新增在这一行永远不出现；24 条还跨库混排
      - 已修复：最近添加纳入分集，按剧去重
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

- [x] **UX-14**（阻断）媒体库一次最多装 500 条且没有分页，第 501 部之后的片子在浏览路径上根本不存在
      - 已修复：分页 + 触底加载，页头显示真实总数
- [~] **UX-15**（严重）没有库内搜索，全局搜索写死 60 条、按名字排序、无声截断也不能翻页
      - 部分修复：已加库内搜索；全局搜索仍写死 60 条、无总数与翻页
- [~] **UX-16**（严重）媒体库页没有任何筛选，Jellyfin 的整个筛选抽屉在这里是空白；详情页的类型标签也点不动
      - 部分修复：已加未观看/收藏筛选；类型、年代、分辨率、字幕语言仍无
- [x] **UX-17**（严重）收藏是个死胡同：后端查询早就写好了，前端一个读取点都没有，卡片上也没有收藏标记
      - 已修复：首页收藏行 + 媒体库收藏筛选
- [x] **UX-18**（严重）从详情页返回媒体库，整个网格重新查库、滚动位置回到最顶上
      - 已修复：网格保留滚动位置
- [ ] **UX-19**（打磨）只有海报网格一种视图，既不能切列表/详情视图，也不能调网格密度
      - 位置：`composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:73-83`
      - 改法：先做最有价值的一半：加一个 grid/list 切换，list 分支复用现有数据渲染成一行一条（海报缩略图 + 完整标题 + 年份 + 已看 N/M 集）。网格密度可以后置，或简单给 120/150/200dp 三档。
- [x] **UX-20**（打磨）排序方向写死，想反着排（片名 Z→A、年份从老到新）只能自己滑到列表末尾
      - 已修复：排序方向可切换并持久化
- [x] **UX-21**（打磨）手机上排序那一行放不下，最后一个「最近播放」被压成看不清的窄条
      - 已修复：排序行可横向滚动
- [ ] **UX-22**（打磨）大库里没有字母跳转，桌面端连滚动条都没加
      - 位置：`composeApp/src/app/com/daview/app/ui/LibraryScreen.kt:73-79`
      - 改法：依赖分页先解决全量加载。之后给 LazyVerticalGrid 传 rememberLazyGridState，右侧加一条字母索引（中文库按 sort_name 拼音首字母分段），点击 scrollToItem 到该段第一项，不需要改任何 SQL；桌面端顺手补一个 VerticalScrollbar。
- [ ] **UX-23**（打磨）演职人员卡片不可点击，也没有按人物检索的入口
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:575-621`
      - 改法：最小可行：Query 加 person 参数用 `people LIKE '%"name":"张三"%'`（或建 item_people 关联表更干净），PeopleRow 的名字改成 LinkText，点进去复用现有的搜索结果网格，标题写「张三 · 出演」，不必先做完整人物页。

### 看片：播放链路与播放器

- [x] **UX-24**（阻断）关掉 DAView 窗口，正在外置播放器里看的片子会立刻断流，没有任何提示
      - 已修复：关窗前会提示还有外部播放在进行
- [x] **UX-25**（阻断）外置播放器暂停超过 5 分钟，会话被回收、本地管道被关掉，之后一拖进度条就断流、后半段进度全丢
      - 已修复：播放器进程活着就给会话续命，暂停不再被回收
- [x] **UX-26**（阻断）Android 播放中屏幕会按系统超时自动熄灭
      - 已修复：播放时保持屏幕常亮
- [x] **UX-27**（严重）播完一集就到头了：两端都不自动播下一集，系列页也没有「播放全部」
      - 已修复：两端播完自动下一集，可在设置关闭
- [x] **UX-28**（严重）Android 播放器不是独立全屏形态：导航骨架常驻、不自动横屏、也没有方向锁
      - 已修复：独立全屏播放形态 + 横屏锁
- [ ] **UX-29**（严重）没有 MediaSession：切后台音频还在响却没有任何控制入口，也没有画中画和音频焦点
      - 位置：`composeApp/build.gradle.kts:51`
      - 改法：用已经在依赖里的 media3-session 建一个 MediaSessionService，把现有 ExoPlayer 实例挂上去（白拿通知控制、锁屏控制、媒体按键、音频焦点）；PiP 单独在 Manifest 加 supportsPictureInPicture 并在 onUserLeaveHint 里 enterPictureInPictureMode。
- [x] **UX-30**（严重）系统返回键/返回手势不返回上一页，直接退出整个 App
      - 已修复：系统返回键走应用返回栈
- [ ] **UX-31**（严重）完全没有离线下载，出门断网这个 App 就是个空壳
      - 位置：`composeApp/src/app/com/daview/app/ui/ItemContextMenu.kt:101-184`
      - 改法：最小可行版：详情页/长按菜单加一个「下载」，用 media3 的 DownloadManager + DownloadService（前台服务可以抄 ScanForegroundService 的现成模式），文件落在 filesDir/downloads，播放时优先命中本地文件；先不做画质选择，直接原文件下载即可，这个 App 本来就不转码。
- [~] **UX-32**（严重）外挂字幕只认「同一目录且严格同名」，Subs/ 子目录不看，`.简日双语.ass` 也会被丢，且没有手动挂载入口、没有延迟与字号
      - 部分修复：已读 Subs / 字幕 子目录，单视频目录放宽匹配；仍无手动挂载、字幕延迟与字号
- [~] **UX-33**（严重）音轨和内嵌字幕轨的选择传不给外置播放器，播放器里选的也回不来
      - 部分修复：已把音轨/字幕传给 mpv 与 VLC；播放器里改的选择仍传不回来
- [x] **UX-34**（严重）默认用哪个播放器不能选也不会记住，永远按写死的顺序挑第一个
      - 已修复：设置里可选默认外部播放器并记住
- [x] **UX-35**（严重）外部播放面板的说明文字描述的是一个已经不存在的架构，而且承诺了做不到的事
      - 已修复：外部播放面板文案改成实际行为
- [x] **UX-39**（严重）外置播放器一启动，界面就什么都不显示了——整块外部播放面板是不可达的死代码
      - 已修复：外部播放会进入播放页，整块面板可达
- [ ] **UX-40**（严重）播放失败只有详情页看得到，从首页、媒体库、搜索页点播放失败是彻底静默的；启动过程也没有任何等待反馈
      - 位置：`composeApp/src/app/com/daview/app/data/PlaybackController.kt:40-41`
      - 改法：把 error 接到 AppState.toast（已有全局 snackbar，App.kt:118-123），任何页面都能看到；starting 为真时把播放按钮换成 loading 态。
- [x] **UX-36**（打磨）没有「从头播放」：看过一半的片，播放键永远是「继续」
      - 已修复：菜单里的「从头播放」
- [~] **UX-37**（打磨）Android 播放器没有任何手势，自绘的音轨/字幕按钮和标题常驻挡画面，还与 media3 自带字幕按钮形成两套入口
      - 部分修复：叠加层已跟随控制条显隐并加 scrim；手势仍未做
- [ ] **UX-38**（打磨）mkv 里的章节从不读取，Android 内置播放器没有章节导航
      - 位置：`core/src/core/com/daview/server/library/MkvProbe.kt:54-83`
      - 改法：MkvProbe 顺手解析 Chapters（复用已有的 SeekHead 定位），详情页与播放器给章节跳转。若想要「跳过片头」的廉价版：库级设置一个片头秒数（如 90），播放开始后前 90 秒在播放器上显示一个「跳过片头」按钮，对同一季固定 OP 的番剧准确率极高。
- [x] **UX-41**（打磨）桌面播放器菜单里那条「自定义播放器」永远点不动：写入它的函数全仓库没有调用点
      - 已修复：桌面设置里可指定播放器程序；未设置时不再列出该项

### 状态、进度与跨端同步

- [x] **UX-42**（严重）「继续观看」里的条目移不掉，唯一的办法是撒谎说自己看完了，还会顺带清空进度
      - 已修复：「从继续观看中移除」，保留进度
- [~] **UX-43**（严重）扫描结束后停在首页不会自动刷新，后台同步拉回来的进度也不刷新，且没有下拉刷新
      - 部分修复：扫描结束会刷新首页；同步拉回与下拉刷新仍无
- [ ] **UX-44**（严重）停止播放不触发上传，进度只等进程内定时器——划掉 App 换设备最坏会看到十分钟前的旧进度
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:386-392`
      - 改法：在 stopPlayback 成功之后（或 Activity 的 ON_STOP）触发一次立即上传，绕开 minIntervalMinutes 节流；再用 WorkManager 排一个 OneTimeWorkRequest（约束 NETWORK_CONNECTED）兜底，这样即使进程当场被杀，系统也会稍后替你补上。
- [x] **UX-45**（严重）同步文件会静默覆写本机的存储地址与播放设置，两台设备用不同地址访问同一份存储时互相打架
      - 已修复：同步不再覆写本机存储地址与播放设置
- [ ] **UX-46**（打磨）同步其实是定时自动跑的，界面从头到尾没说，也看不到上次跑的时间和间隔
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:366-370`
      - 改法：说明文字补一句自动频率；状态行把「已上传」换成「上次上传 <时间>」「上次合并 <时间>」；给 minIntervalMinutes 加一个输入框或几个预设档（后端已完全支持，只差 UI）。

### 库维护：元数据编辑与条目管理

- [ ] **UX-47**（严重）完全没有「编辑元数据」：刮错一个字段，只能整条换刮削源或者一直忍着
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:404-424`
      - 改法：先做最小集合：MediaFacade 加 updateItem(id, name/sortName/overview/year/genres) 写入 items 表并把 scrape_status 标为 MANUAL；DetailScreen 的图标行加一个「编辑」打开字段表单。字段锁可以留到后面，先保证「改过的字段重新刮削不被覆盖」（复用现有的 lockedProvider 思路）。
- [~] **UX-48**（严重）封面/背景图完全没法换，扫描也不认目录里的 poster.jpg / fanart.jpg
      - 部分修复：扫描已读 poster.jpg / fanart.jpg；仍不能手动换封面
- [x] **UX-49**（严重）开启同步后删除媒体库不生效：最多 60 秒后被云端文件原样恢复成一个空库，而本机刮削结果已被真删
      - 已修复：删除的媒体库不会被同步恢复
- [x] **UX-50**（严重）删除媒体库没有任何二次确认，误触一次就抹掉本机全部刮削结果
      - 已修复：删除媒体库需二次确认
- [x] **UX-51**（打磨）没有单条「重新刮削」：一个 FALLBACK 匹配会写死 scraped_at，此后只能整库 REFRESH
      - 已修复：右键菜单可对单条重新刮削
- [~] **UX-52**（打磨）official_rating 是个死字段：三个刮削器一次都没填过，详情页也不显示分级
      - 部分修复：详情页已显示分级；三个刮削器仍未填 official_rating

### 家庭与多设备

- [ ] **UX-53**（严重）没有用户/档案概念：共用一个系统账户的人共享一份进度与收藏，同步文件也按 item_id 整行覆盖
      - 位置：`core/src/core/com/daview/server/db/Database.kt:122-133`
      - 改法：最小可行：user_data 加一列 user_id，迁移时把现有行统一写成 'local'，主键改成 (user_id, item_id)；AppConfig 存「当前档案名」，顶栏放一个下拉切换，Repository 的 resume/nextUp/unwatched/query 全部带上当前档案。同步载荷的键同步改成 (profileId, itemId)，BACKUP_VERSION 升 2，没有 profileId 的旧行按 'local' 处理。家庭场景里「分开记账」比「防偷看」重要，第一版可以完全不做密码。

### 凭据与隐私

- [x] **UX-54**（严重）WebDAV 密码和三个 API Key 的输入框全程明文显示，没有遮蔽也没有小眼睛
      - 已修复：密码与三个 Key 遮蔽显示
- [ ] **UX-55**（打磨）凭据只能替换不能抹除，也不告诉你配置文件在哪
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:78-86`
      - 改法：存储和刮削两块各加一个「清除」按钮，走显式的 clearStorageCredentials()/clearScraperKeys() 路径（不能复用 ifBlank 的 update），并在设置页底部显示数据目录路径。
- [x] **UX-56**（打磨）TMDB API Key 明文出现在 URL 里，任何一次请求失败都会把它整条写进日志
      - 已修复：日志里的 api_key 脱敏
- [x] **UX-57**（打磨）填 http:// 的 WebDAV 无任何提示，而明文链路上传的是网盘主账号密码本身
      - 已修复：http:// 地址给出明文传输警告
- [ ] **UX-58**（打磨）Android 未声明备份排除规则，明文 config.json（含网盘密码与 API Key）会进厂商云备份/换机迁移
      - 位置：`composeApp/src/androidMain/AndroidManifest.xml:15`
      - 改法：给 application 加 `android:dataExtractionRules` / `fullBackupContent` 把 daview 目录（至少 config.json）排除出备份；进一步把 password 与三个 Key 挪进 EncryptedSharedPreferences，config.json 只存非敏感项。
- [ ] **UX-59**（打磨）勾选「包含凭据」后导出明文密码文件没有二次确认，只有一段红字说明
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:501-512`
      - 改法：给「包含凭据导出」加一个二次确认弹窗（哪怕只是让用户再点一次「我确认导出明文密码」）。再往上可以做一个可选的 4 位 PIN / 生物识别门禁覆盖设置页，与删库确认那条合并考虑。

### 呈现、文案与性能打磨

- [~] **UX-60**（严重）首播日期、制作方、分级刮回来了却一个都不显示；全 App 看不到任何日期时间
      - 部分修复：详情页已显示首播日期与分级；上次扫描/同步时间仍未显示
- [x] **UX-61**（严重）详情页简介固定截断 5 行，既不能展开也不能选中复制
      - 已修复：简介可展开、可选中
- [~] **UX-62**（严重）海报下载失败与「压根没刮到图」在界面上无法区分，失败还不记忆——每次滚回视口都重付一次 15s/30s 超时
      - 部分修复：已加失败记忆与超时收紧；「没图」与「下不动」在界面上仍不可分
- [x] **UX-63**（打磨）扫描进度与刮削源在界面里漏出内部标识（scraping/probing、小写 tmdb），仓库里已有的中文映射只用在 Android 通知栏
      - 已修复：扫描阶段与刮削源显示中文
- [ ] **UX-64**（打磨）几处文案拿实现细节当解释，普通用户读不懂
      - 位置：`composeApp/src/app/com/daview/app/ui/DetailScreen.kt:187`
      - 改法：(a) 改成「自动匹配，把握不大 · 建议核对，可点右上角重新指定」；(b)「进度为估算值，可能有几秒误差」就够，机制留给日志；(c)「只补缺元数据的条目（不重新读文件，较快）」。
- [~] **UX-65**（打磨）回到首页/标记已看会整体重建首页数据，各媒体库「未观看」整行在补数据前会先塌陷一次
      - 部分修复：首页数据已合并成一次赋值；每库未观看仍是第二段
- [ ] **UX-66**（打磨）海报 URL 已经在列表响应里拿到过却被丢弃，每次内存缓存未命中都要为一张缩略图重跑一次整条 item 查询
      - 位置：`core/src/core/com/daview/server/api/MediaFacade.kt:452-460`
      - 改法：两条都很小：让 LocalAssetLinks.image() 把 remoteUrl 也编进 URI（或在内存里存一个 itemId+type→remoteUrl 的映射）让 imageFile 不用查库；或给 Repository 加一个只查 `SELECT poster_url, backdrop_url, logo_url FROM items WHERE id = ?` 的轻量方法。顺带给网格用的列表查询做一个不含 overview/people/media_streams 的投影。
- [ ] **UX-67**（打磨）单连接 + 全局互斥锁抵消了 WAL：扫描的写入阶段和刮削开始前的全库读会让界面数据慢一拍
      - 位置：`core/src/jvmOnly/kotlin/com/daview/server/db/JdbcSql.kt:21-23`
      - 改法：两条最小改动：Scanner.kt:70 把 records 按每 200 条 chunked 分批写入，让锁有间隙被 UI 查询抢到；itemsNeedingScrape 改成只返回 id 列表（或带 limit 分批取），刮削时再逐条读完整行。
- [x] **UX-68**（打磨）图片缓存只增不减、没有任何清理入口，Android 上落在 filesDir，系统「清除缓存」清不掉
      - 已修复：设置里可查看并清除图片缓存
- [ ] **UX-69**（打磨）界面语言不可切换、文案全部硬编码为中文
      - 位置：`composeApp/src/app/com/daview/app/ui/SettingsScreen.kt:290-296`
      - 改法：如果不打算做多语言，至少把面向用户的字符串收到一处（哪怕只是一个 object Strings），先解决 core 层错误文案和 UI 文案各写一遍、改一处漏一处的问题；真要做切换，Compose Multiplatform 自带的 composeResources 就够用。

## 设计与交互（UI-REVIEW）

### 布局与响应式

- [~] **UI-01**（严重）「继续观看」一行混排 2:3 海报与 16:9 剧照，标题错开 217.5dp，横向滚动时整行高度在 174.5↔392dp 之间持续跳动
      - 部分修复：同排卡片改为底对齐；混排比例本身仍在
- [x] **UI-02**（严重 · 桌面）全 App 只有外置播放面板有最大宽度：100% 缩放下就是 1240dp 宽的单行 URL 输入框、每行约 74 个汉字的简介
      - 已修复：宽布局内容限宽 1180dp
- [x] **UI-03**（严重）返回箭头行在 8dp 与 56dp 之间无动画硬切换，每次导航整页内容瞬跳 48dp 并重排版——而 TopRow 在转场动画之外
      - 已修复：返回栏固定高度，不再整页跳 48dp
- [x] **UI-04**（严重）导航断点只有 720dp 一档且两套导航目的地不同：跨过断点媒体库入口整个消失，桌面 200% 缩放起直接渲染成手机版底栏布局
      - 已修复：断点改 600dp，底栏加媒体库入口
- [x] **UI-05**（严重）三个对话框的正文是不可滚动的 Column + 写死 heightIn：视口不够高时尾部的候选/目录列表被测成 0 高度并静默消失
      - 已修复：对话框正文可滚动
- [x] **UI-06**（严重）分集行缩略图写死 148dp、尾部按钮 48dp：360dp 手机上正文列只剩 86dp，标题约 6 个汉字、元信息折成两行；1920dp 下缩略图又不放大
      - 已修复：分集缩略图按比例
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
- [x] **UI-11**（严重）进媒体库/详情/播放页后底栏三项同时熄灭，底栏不再指示当前位置；媒体库根本不是底栏目的地
      - 已修复：底栏有媒体库项并正确指示当前位置
- [x] **UI-12**（严重）NavigationRail 是不滚动的 Column 且只设了最小宽：溢出的媒体库被静默压成 0 高度，长库名又把 rail 从 80dp 撑到约 150dp
      - 已修复：侧栏可滚动、标签限宽

### 转场与反馈

- [x] **UI-13**（严重 · Android）Snackbar 手搓钉在根 Box 的 BottomCenter：2.6 秒内整条压住 80dp 高的底部导航栏并吃掉该处点击，无进出动画、无 liveRegion
      - 已修复：改用 SnackbarHost，不再压住底栏
- [x] **UI-14**（严重）全部 11 条提示共用一个写死的 2600ms 计时器：短于 M3 最短的 4000ms，绕过无障碍延时，同文案连发还不重新计时
      - 已修复：提示时长交给平台
- [x] **UI-15**（严重）海报卡显式 indication = null：触摸端按下零反馈、桌面焦点零指示，而同屏的分集卡/设置行走的是默认涟漪
      - 已修复：恢复默认涟漪
- [ ] **UI-16**（严重）详情页没有加载态：第一次进是整屏空白，之后进会先把上一个条目的整页端出来，连播放按钮都是活的
      - 位置：`DetailScreen.kt:89、DetailScreen.kt:359-361、DetailScreen.kt:384、AppState.kt:36、AppState.kt:115、AppState.kt:124-127、AppState.kt:143、AppState.kt:268-286、App.kt:160-163`
      - 改法：onEnter(Screen.Detail) 里先 `detailItem = null; detailChildren = emptyList(); detailEpisodes = emptyList()`（或把 screen.itemId 传给 DetailScreen，`detailItem?.id != screen.itemId` 一律按未加载处理），再把 DetailScreen.kt:89 的 `?: return` 换成 `?: run { LoadingPane(); return }`，把已有的 detailLoading 接上；数据未就绪时禁用播放按钮。
- [ ] **UI-17**（严重）三个列表屏都没有错误态：媒体库读失败后永远转圈，2.6 秒后连错误都没了，也没有重试
      - 位置：`AppState.kt:235-261、AppState.kt:197-207、AppState.kt:272、AppState.kt:293-296、LibraryScreen.kt:43、LibraryScreen.kt:47、LibraryScreen.kt:71、Components.kt:101-115、Components.kt:327-346、App.kt:123-128`
      - 改法：给 AppState 加 `libraryError: String?`（详情、搜索同理），run() 的 catch 里除了 toast 再写进这个字段；LibraryScreen 的 when 里加一个错误分支，复用 EmptyState 并传一个「重试」按钮回调 `state.loadLibrary(libraryId)`。
- [x] **UI-18**（严重）搜索无加载态：新一轮搜索的首个字符必然先闪 ≥250ms 的「没有匹配的结果」，且「正在查」与「查完为空」长得一模一样
      - 已修复：搜索有加载态，且会取消上一次查询
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

- [x] **UI-25**（阻断 · Android）系统栏图标明暗只认系统夜间模式、界面底色只认应用内开关：开箱默认组合（系统浅色 + App 深色）下状态栏图标 1.09:1，切主题时系统栏纹丝不动
      - 已修复：系统栏图标跟随应用主题
- [ ] **UI-26**（严重）设置页库卡与详情页分集卡用色阶最低的 surfaceContainerLow，压在 background 上只有 1.05:1，18dp 圆角与卡片边界等于白画
      - 位置：`SettingsScreen.kt:114-117、DetailScreen.kt:691-702、HomeScreen.kt:227-232、PlayerScreen.kt:72-76、App.kt:78-79、Theme.kt:38、Theme.kt:53-57`
      - 改法：定一条容器阶梯并全局照做：页面上的内容卡一律 surfaceContainer 起步（1.10:1 仍偏弱，可把 background 压到 #0B0A10 或把 surfaceContainer 提到 #201D2A 做到 ≥1.3:1），强调项用 surfaceContainerHigh，弹层/面板用 surfaceContainerHighest；分集行如果想保持轻量就改成无卡片 + HorizontalDivider，而不是一张看不见的卡片。
- [ ] **UI-27**（打磨）观看进度条用 secondary，而该角色在两套主题里明暗相反，EpisodeRow 的轨道还是全透明直接压在剧照上
      - 位置：`Components.kt:243-256、DetailScreen.kt:720-730、DetailScreen.kt:726、Theme.kt:28、Theme.kt:79、Theme.kt:64、Theme.kt:115`
      - 改法：两处的 `color` 从 `secondary` 换成 `secondaryFixedDim`（一行改动，两套主题都变成亮暖橙）；DetailScreen.kt:726 的 trackColor 补上和 PosterCard 一致的 `scrim.copy(alpha = 0.4f)`，让 fill 与 track 的对比不再取决于剧照。

### 排版与信息层级

- [x] **UI-28**（打磨 · 桌面）labelSmall（11sp）被当正文用了 24 处：桌面「内置播放器」设置里 5 段成句中文说明在 100% 缩放下是 11 个物理像素
      - 已修复：小字号上调一档
- [x] **UI-29**（打磨）主题从不传 typography：M3 的拉丁基线行高压过中文字体自然行高 8%，字距也全程带 tracking
      - 已修复：自定义 Typography，行高按中文字形
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

- [x] **UI-33**（阻断 · Android）开了 edge-to-edge 但全仓库零 inset 处理：内容从 y=8dp 起穿过状态栏，二级页返回箭头在 40dp 状态栏机型只露 4dp、48dp 机型完全消失
      - 已修复：状态栏 inset 由 TopRow 承担
- [ ] **UI-34**（严重 · Android）edge-to-edge 让 adjustResize 失效而应用未接手 IME inset：设置页下半屏的密码 / API Key / 元数据语言输入框会被软键盘盖住
      - 位置：`AndroidManifest.xml:24、MainActivity.kt:23、SettingsScreen.kt:78-81、SettingsScreen.kt:209-218、SettingsScreen.kt:246-264、App.kt:136、App.kt:142`
      - 改法：给 App.kt:142（以及 :136）的 Column 加 `Modifier.imePadding()`，一处解决全部输入场景；或只改设置页——把 SettingsScreen.kt:80 的 contentPadding 底部改成 `48.dp + WindowInsets.ime.asPaddingValues().calculateBottomPadding()`。
- [x] **UI-35**（严重 · Android）应用根本没有图标资源：桌面、最近任务、Android 12+ 冷启动闪屏全是系统默认的通用图标
      - 已修复：自适应启动图标
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

- [x] **UI-39**（阻断 · 桌面）默认窗口 1360×900 物理像素从不与屏幕工作区取交集：1366×768 笔记本、1080p 上开 125%/150% 缩放的机器首次启动就有一大块在工作区外
      - 已修复：窗口居中、限最小尺寸
- [ ] **UI-40**（严重 · 桌面）桌面上所有横排用滚轮推不动——纵向滚轮增量在 Horizontal scrollable 上被算成 0 并冒泡去滚整页，行两端也没有箭头或滚动条
      - 位置：`Components.kt:311-314、HomeScreen.kt:222-225、HomeScreen.kt:66、DetailScreen.kt:94、DetailScreen.kt:107-119、DetailScreen.kt:605-621、DetailScreen.kt:630-633、AppState.kt:220`
      - 改法：两件事各自独立见效：给 MediaRow 与分集行加 `Modifier.onPointerEvent(PointerEventType.Scroll)`，把 scrollDelta.y 转成对 rememberLazyListState 的 scrollBy；在行左右两端加 hover 时才出现的圆形箭头按钮（滚到头时隐藏对应一侧），每次滚一屏宽——两者都只需要把 LazyRow 的 state 提上来。
- [x] **UI-41**（严重 · 桌面）右键菜单用 DropdownMenu 的 offset 当光标坐标，而 offset 的垂直基准是锚点底边——菜单落在光标下方整整一张海报的高度
      - 已修复：右键菜单落在光标处
- [ ] **UI-42**（严重 · 桌面）桌面端一个快捷键、一条菜单栏都没有：Alt+← / Backspace 不返回、Ctrl+F 不跳搜索、F5 不刷新，唯一返回入口是左上角那个图标按钮
      - 位置：`Main.kt:19-26、App.kt:186`
      - 改法：在 Main.kt 的 Window 上挂 `onPreviewKeyEvent`：Alt+Left / Backspace → `state.back()`、Ctrl+F → `state.navigate(Screen.Search)`、F5 → 重新加载当前屏、F11 → 切 WindowState.placement。再配一条 MenuBar 把这些动作显式列出来，让快捷键可被发现。
- [x] **UI-43**（严重 · 桌面）窗口大小、位置、最大化状态、所在显示器一概不记忆，每次启动都回到主屏左上角的 1360×900
      - 已修复：记住窗口尺寸
- [x] **UI-44**（严重 · 桌面）没有任何窗口图标：标题栏、任务栏、Alt+Tab、安装后的开始菜单快捷方式全是 JDK 默认的咖啡杯
      - 已修复：代码绘制的窗口图标
- [ ] **UI-45**（打磨 · 桌面）两个「打开」对话框的 FilenameFilter 在 Windows 上是 JDK 文档化的空操作，三个对话框都不设初始目录、也没有文件类型下拉
      - 位置：`Platform.desktop.kt:152-162、Platform.desktop.kt:164-178、PlatformPlayerSettings.desktop.kt:105-109、PlatformPlayerSettings.desktop.kt:276-284、SettingsScreen.kt:542、composeApp/build.gradle.kts:125-152`
      - 改法：改成 Windows 认的写法：LOAD 时 `dialog.file = "*.json"`（libmpv 那处用 "*.dll"），保留现有 setFilenameFilter 给 macOS/Linux；三处都设 `dialog.directory`（默认 user.home 下的 Documents/Downloads，并把上次选过的目录存进已有的 Preferences 节点）；顺带把保存失败的异常信息带到「没有写入文件」后面。

### 播放器界面

- [ ] **UI-46**（阻断 · 桌面）桌面内置播放器的「音轨 / 字幕」下拉菜单整个落在 mpv 的 AWT 画布里，被原生子窗口盖住，看不见也点不到
      - 位置：`InternalPlayer.desktop.kt:248-279、InternalPlayer.desktop.kt:59、InternalPlayer.desktop.kt:333、InternalPlayer.desktop.kt:364、InternalPlayer.desktop.kt:377、MpvPlayer.kt:84、App.kt:177-189`
      - 改法：不要在这条栏上用 Popup 类组件。两个最小改法二选一：把音轨/字幕交给 mpv 自己（osc 已开，`#`、`j` 现成），顶栏只留标题与「结束播放」；或把菜单改成就地展开的内联行——点「音轨」时在 PlayerBar 下方再插一行 Compose 的 ToggleButton 列表（属于 Column 布局的一部分，会把视频往下挤，不走 popup）。若一定要保留下拉，需在 Main.kt 启动时 `System.setProperty("compose.layers.type", "WINDOW")` 并实测，代价是所有对话框都变成独立窗口。
- [x] **UI-47**（严重 · 桌面）桌面播放器把走带控制整个交给 mpv，Compose 侧既不转交按键也从不把焦点还给 Canvas——点过一次工具栏后空格变成「重新弹开刚才那个菜单」，Esc 也无人接管
      - 已修复：空格 / 方向键 / F11 / Esc 由播放页接管
- [x] **UI-48**（严重 · 桌面）桌面播放器没有任何全屏入口，却把 mpv 那两个在 --wid 内嵌下已经变成空操作的全屏控件原样摆给用户
      - 已修复：F11 与工具栏按钮切全屏
- [x] **UI-49**（严重 · 桌面）桌面 PlayerBar 是无溢出处理的单行 Row：GPU 型号排在片名前面吃满宽度，150% 缩放下字幕轨名换行把 bar 撑到 112dp、「结束播放」退化成压在字幕按钮上的 48dp 空白热区
      - 已修复：标题让位给控件，控件改用图标
- [ ] **UI-50**（严重）播放中点侧栏/底栏的「搜索」「设置」「某个媒体库」：去不了目标页，绕一圈回来重启一次解码，再自己多退一级
      - 位置：`App.kt:133-147、App.kt:230、App.kt:237、App.kt:246、App.kt:160-173、AppState.kt:124-127、PlayerScreen.kt:45-55、PlayerScreen.kt:51-54、PlaybackController.kt:176-183、InternalPlayer.desktop.kt:239-246、InternalPlayer.android.kt:116-122`
      - 改法：根因是 onClose 里的 `state.back()` 与外部 navigate 抢同一个回退栈。最小改法：PlayerScreen 的 onClose 改成只在 current 仍是 Screen.Player 时才 back()（或用 popIfCurrent(Screen.Player)）；同时把 PlaybackController.stop 里的 `info = null` 提到 scope.launch 之外先置空，避免旧 info 被重新播一次。设计上更该做的是让 Screen.Player 成为覆盖整个窗口、不带导航骨架的一层。
- [x] **UI-51**（严重 · Android）Android 播放器的标题与音轨/字幕按钮是纯白文字直接压在视频帧上，没有 scrim 也没有阴影，亮画面下低至 1.00:1
      - 已修复：标题与按钮加 scrim
- [ ] **UI-52**（严重）退出内置播放器：Android 上引擎比页面多活约 0.33 秒声音继续响，桌面则在淡出中途先闪出「没有正在播放的内容」，两端画面都不参与淡入淡出
      - 位置：`PlayerScreen.kt:37-43、PlayerScreen.kt:51-54、PlaybackController.kt:176-183、MediaFacade.kt:386-392、InternalPlayer.desktop.kt:239-246、InternalPlayer.desktop.kt:272、InternalPlayer.android.kt:116-122、App.kt:161、App.kt:186`
      - 改法：两处 onClose 回调里先关引擎再退栈（`player?.close()` / `player.release()` 放到 `state.back()` 之前，DisposableEffect 保留作兜底）；同时在 App.kt:161 的 transitionSpec 里对 `Screen.Player` 这一分支返回 `EnterTransition.None togetherWith ExitTransition.None`，让播放器进出是干净的瞬切，而不是一个做不到的假淡入淡出。
- [x] **UI-53**（打磨 · 桌面）桌面 PlayerBar 跟随应用主题：切到浅色主题后，纯黑画面上方是一条横贯全宽、64dp 高的近白色带
      - 已修复：播放条固定深色
- [x] **UI-54**（打磨 · Android）Android 播放失败时一张卡片浮在画面正中央、永不消失、没有任何出口，文案还是 ERROR_CODE_IO_BAD_HTTP_STATUS 这样的英文枚举
      - 已修复：失败卡片有返回出口

### 组件用法

- [x] **UI-55**（严重）26dp 圆角把海报底部的观看进度条两端啃掉：进度低于 8.1% 时一个像素都画不出来，而代码以为自己画了
      - 已修复：进度条内缩并自带圆角
- [x] **UI-56**（严重）缺图这一件事有四种画法：卡片给电影图标、分集缩略图给空灰块、演职人员给空灰圆、详情页海报干脆不占位——最后一种让整个头部左移 180dp
      - 已修复：统一的缺图占位
- [x] **UI-57**（严重）悬停时海报中央那个圆形播放键点下去不是播放，是进详情页；触摸端则永远看不到它
      - 已修复：播放键真的播放
- [x] **UI-66**（严重）已观看的对勾在四处用了两套互斥编码：卡片/详情页按状态画（primary 实心勾=已看），右键菜单按动作画（primary 实心勾=未看）；卡片角标还是无底衬裸图标
      - 已修复：对勾只表示状态，菜单图标表示动作
- [x] **UI-58**（打磨）设置里的开关行自己拼 Row+Switch：标签点不动（360dp 行里只有右端 52dp 可点）、桌面无 hover 反馈、读屏节点没有可访问名称
      - 已修复：开关整行可点、读屏有名称
- [~] **UI-59**（打磨）六组 ToggleButton 一套外观承担三种语义：五组互斥单选、一组独立多选，选中态还是 primary 实心、与紧邻 10dp 的主操作「导出」同一色阶
      - 部分修复：备份选项改复选、语言改分段控件；其余四组仍是 ToggleButton
- [ ] **UI-60**（打磨）仓库自带统一的 Chip 组件，桌面播放器却另用 AssistChip(enabled=false) 伪装只读状态：两套长相，读屏还念成「按钮，已停用」
      - 位置：`Components.kt:397-409、DetailScreen.kt:322-331、HomeScreen.kt:193-198、InternalPlayer.desktop.kt:396-417、InternalPlayer.desktop.kt:405-416、InternalPlayer.desktop.kt:332、Theme.kt:23、Theme.kt:57`
      - 改法：EnhancementChip 内部换成本仓库已有的 `Chip("$label · $suffix")`，或换成 `Row { Box(8dp 圆点，颜色按状态) + Text(labelSmall) }` / Badge——两条路都能去掉「已停用按钮」的语义，也让播放条和详情页的状态标签长得一样。无论选哪种，全 App 只留一套 chip 实现。
- [ ] **UI-61**（打磨）Card / Surface 的点击挂在 Modifier.clickable 上而不是用 onClick 重载：水波纹与 hover 高亮画在 clip 之前，会溢出圆角
      - 位置：`DetailScreen.kt:691-704、SettingsScreen.kt:649-653、IdentifyDialog.kt:256-259、MergeDialog.kt:155-158、Theme.kt:128-129、Components.kt:178-187`
      - 改法：SettingsScreen.kt:649、IdentifyDialog.kt:256、MergeDialog.kt:155 改用 `Surface(onClick = ...)` 重载（目录行更适合直接用 `ListItem(onClick, leadingContent = { Icon(Folder) }, headlineContent = { Text(name) })`）；DetailScreen.kt:691 因为要保留长按，把 Modifier 顺序改成 `.clip(MaterialTheme.shapes.medium).secondaryClick(...).combinedClickable(...)` 即可。
- [~] **UI-62**（打磨）分节标题有三套实现：SectionHeader 组件、六份无 vertical padding 的手写副本、以及首页那个被套两层 20dp 缩进到 40dp 的；标题到正文的间距在 0/8/12/13dp 之间随机，节尾 20/24dp 混用
      - 部分修复：四处分节标题已统一；间距仍不完全一致
- [ ] **UI-63**（打磨 · 桌面）首页顶部大图没有右键菜单，同一条目在它正下方的卡片上却有
      - 位置：`Components.kt:173、DetailScreen.kt:694、ItemContextMenu.kt:50、ItemContextMenu.kt:102-107、HomeScreen.kt:53、HomeScreen.kt:149-215`
      - 改法：HeroBanner 的最外层 Box 套上 `secondaryClick` + `DropdownMenu(ItemMenuItems)`，与 PosterCard 用同一段代码。
- [x] **UI-64**（打磨）库类型图标里「其他」与「电影」取同一个 ImageVector 完全不可分辨，「番剧」借用播放列表图标，同一个电影图标还兼任全 App 的缺图占位
      - 已修复：库类型图标各自可分辨
- [x] **UI-65**（打磨）按钮文案两套规则并存：会弹系统文件框的四个命令只有两个带省略号；一对同级动作叫「立即上传」和「从云端合并」，不对仗
      - 已修复：会弹选择器的命令统一带省略号

### 无障碍与键盘可达

- [x] **UI-67**（严重）海报卡与 LinkText 显式关掉 indication：Tab / D-pad 焦点落上去零像素变化，网格自己在滚却看不出选中了谁，按空格就盲开一个详情页
      - 已修复：焦点复用悬停处理，另加描边
- [x] **UI-68**（严重）LinkText 可点击时写死 primary 并丢弃传入的 color：同一行剧名在紫与暖橙间随数据跳色，与紧邻正文亮度比 1.00:1，触摸目标只有 20dp
      - 已修复：LinkText 尊重传入颜色、常驻下划线、48dp 触摸目标
- [x] **UI-69**（严重 · Android）TalkBack 播报的「长按」是个死动作：右键/长按菜单被 lastPointer 判断挡住，无障碍派发的 OnLongClick 永远不满足条件
      - 已修复：长按守卫改为「非鼠标」，读屏可用
- [x] **UI-70**（严重 · Android）海报卡的「看了一半」对读屏是彻底空白，还额外多出一个只念百分比的无名焦点停靠点，片名被念两遍
      - 已修复：看片状态由卡片整体播报
- [x] **UI-71**（严重）收藏与「手动指定刮削条目」两个 IconButton 的状态没进 contentDescription；后者连视觉上也只靠颜色区分
      - 已修复：收藏与手动指定的状态进入描述，并各有字形
- [ ] **UI-72**（打磨）三个对话框都不把 Enter 绑到默认按钮、打开时也不落焦点：Esc 能关但 Enter 不能确认，键盘路径是单向的
      - 位置：`IdentifyDialog.kt:130、IdentifyDialog.kt:176-183、IdentifyDialog.kt:198-211、IdentifyDialog.kt:212-217、IdentifyDialog.kt:244-249、SettingsScreen.kt:664-669、MergeDialog.kt:132-139`
      - 改法：给「片名」加 `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)` + `keyboardActions = KeyboardActions(onSearch = { search() })`；给「条目 id」加 `onDone = { if (providerId.isNotBlank()) apply(providerId) }`；对话框打开时用 FocusRequester 把焦点放到「条目 id」或「片名」上。WebDavPickerDialog 与 MergeDialog 同样处理。
- [x] **UI-73**（打磨 · Android）全应用 15 个输入框没有一个声明 imeAction：安卓上填任何一段表单都是「打一个字段 → 收一次键盘 → 点下一个字段」
      - 已修复：表单字段声明 imeAction
- [x] **UI-74**（打磨）搜索页进去不自动聚焦输入框，桌面也没有任何进入搜索的快捷键
      - 已修复：搜索页自动聚焦
- [x] **UI-75**（打磨 · Android）搜索框只有 placeholder 没有 label，输入之后读屏念不出这是什么框
      - 已修复：搜索框有 label

