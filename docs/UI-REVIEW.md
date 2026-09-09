# DAView 界面设计与交互审查

接着 [UX-REVIEW.md](UX-REVIEW.md) 的第二轮：那一轮看的是「缺什么功能、什么流程走不通」，这一轮只看**设计与交互**——配色对比度、排版层级、间距节奏、形状与海拔、响应式、转场与反馈、平台惯例（Android 的 edge-to-edge 与系统栏，桌面的窗口/指针/键盘）、Material 3 组件用法、一致性与无障碍。
凡是 UX-REVIEW.md 里已经报过的，这一轮一律不再重复。

| | |
| --- | --- |
| 审查树状态 | `01c13e4`（桌面 libmpv 内置播放器已落地） |
| 条目 | 17 个维度提出 129 条，对抗验证后存活 109 条，合并重复后 **75** 条 |
| 严重度 | 阻断 **4** · 严重 **39** · 打磨 **32** |
| 影响端 | 两端 **43** · Android **16** · 桌面 **16** |
| 方法 | 每条结论先读代码推导渲染结果，再由另一个审查者复算数值、核对规范、判定是否重复 |

严重度的定义：

- **阻断** — 常见配置下界面不可读、不可用，或内容被系统 UI 遮挡。
- **严重** — 每天都会看到的设计缺陷或交互别扭。
- **打磨** — 会皱眉，但不挡路。

---

## 只改五件事的话

1. 返回箭头行的 48dp 跳变（2/24/35）：两端、每天几十次，每一次导航都能看到正在淡出的旧屏被凭空推下 48dp，而修法是把 App.kt:176-190 的 TopRow 换成固定 56dp 容器 + AnimatedVisibility 包住图标，一处代码、零新增设计。
2. Android edge-to-edge 零 inset（31/59）：MainActivity.kt:23 开了 enableEdgeToEdge 而全仓无任何 WindowInsets，Android 上每一屏每一刻都在——首页内容从 y=8dp 起直接穿过状态栏，二级页的返回箭头在 40dp 状态栏机型只露 4dp、48dp 机型完全消失、可点高度只剩 11dp，而修法是给 App.kt:136/142 两个 Column 各加一行 windowInsetsPadding。
3. 海报卡 indication = null（15/21/27/52/72）：这是整个应用的主控件，Android 上每次点海报从按下到换页零反馈、桌面上 Tab 焦点完全隐形，而同屏的分集卡、设置行都有默认涟漪——修法是删掉 Components.kt:180 那一个参数，interactionSource 和 hover 缩放都不用动。
4. 系统栏图标只认系统夜间模式（37/58）：开箱默认组合（系统浅色 + App 默认深色）就是黑色状态栏图标压在 #0E0D14 上、1.09:1，时钟电量信号全部看不见，且在设置里切主题时系统栏纹丝不动——修法是给 enableEdgeToEdge 传一个读 state.darkTheme 的 detectDarkMode lambda。
5. Snackbar 手搓钉在根 Box 的 BottomCenter（11/29/55/62/66）：每一次「已收藏」「扫描已开始」提示都会在 2.6 秒内整条压住 80dp 高的底部导航栏并吃掉那片区域的点击，三键导航下还钻进系统导航键条——最小修法只是把 App.kt:149-153 从外层 Box 挪进 App.kt:144 的 Box(weight(1f)) 里。

---

## 目录

### 布局与响应式（严重 6 · 打磨 3）

- [严重·两端] [「继续观看」一行混排 2:3 海报与 16:9 剧照，标题错开 217.5dp，横向滚动时整行高度在 174.5↔392dp 之间持续跳动](#继续观看一行混排-23-海报与-169-剧照标题错开-2175dp横向滚动时整行高度在-1745392dp-之间持续跳动)
- [严重·桌面] [全 App 只有外置播放面板有最大宽度：100% 缩放下就是 1240dp 宽的单行 URL 输入框、每行约 74 个汉字的简介](#全-app-只有外置播放面板有最大宽度100-缩放下就是-1240dp-宽的单行-url-输入框每行约-74-个汉字的简介)
- [严重·两端] [返回箭头行在 8dp 与 56dp 之间无动画硬切换，每次导航整页内容瞬跳 48dp 并重排版——而 TopRow 在转场动画之外](#返回箭头行在-8dp-与-56dp-之间无动画硬切换每次导航整页内容瞬跳-48dp-并重排版而-toprow-在转场动画之外)
- [严重·两端] [导航断点只有 720dp 一档且两套导航目的地不同：跨过断点媒体库入口整个消失，桌面 200% 缩放起直接渲染成手机版底栏布局](#导航断点只有-720dp-一档且两套导航目的地不同跨过断点媒体库入口整个消失桌面-200-缩放起直接渲染成手机版底栏布局)
- [严重·两端] [三个对话框的正文是不可滚动的 Column + 写死 heightIn：视口不够高时尾部的候选/目录列表被测成 0 高度并静默消失](#三个对话框的正文是不可滚动的-column--写死-heightin视口不够高时尾部的候选目录列表被测成-0-高度并静默消失)
- [严重·两端] [分集行缩略图写死 148dp、尾部按钮 48dp：360dp 手机上正文列只剩 86dp，标题约 6 个汉字、元信息折成两行；1920dp 下缩略图又不放大](#分集行缩略图写死-148dp尾部按钮-48dp360dp-手机上正文列只剩-86dp标题约-6-个汉字元信息折成两行1920dp-下缩略图又不放大)
- [打磨·两端] [首页 hero 的 heightIn(min=260,max=380) 因内容永远撑不到 260dp 而等价于写死 260dp，且完全不看视口高度](#首页-hero-的-heightinmin260max380-因内容永远撑不到-260dp-而等价于写死-260dp且完全不看视口高度)
- [打磨·两端] [五个可滚动屏用了五个不同的底部留白（8/20/40/48/48dp），搜索页 8dp 最紧](#五个可滚动屏用了五个不同的底部留白820404848dp搜索页-8dp-最紧)
- [打磨·两端] [两处头图用 24dp 内边距、其余内容一律 20dp，同一条竖线错开 4dp；详情页更反过来——窄窗口对齐、宽窗口错开](#两处头图用-24dp-内边距其余内容一律-20dp同一条竖线错开-4dp详情页更反过来窄窗口对齐宽窗口错开)

### 导航与信息架构（严重 3）

- [严重·两端] [导航项是「入栈」而不是「切换 tab」：重复点当前 tab 也压一层，返回箭头点一次画面纹丝不动，栈没有上限](#导航项是入栈而不是切换-tab重复点当前-tab-也压一层返回箭头点一次画面纹丝不动栈没有上限)
- [严重·两端] [进媒体库/详情/播放页后底栏三项同时熄灭，底栏不再指示当前位置；媒体库根本不是底栏目的地](#进媒体库详情播放页后底栏三项同时熄灭底栏不再指示当前位置媒体库根本不是底栏目的地)
- [严重·两端] [NavigationRail 是不滚动的 Column 且只设了最小宽：溢出的媒体库被静默压成 0 高度，长库名又把 rail 从 80dp 撑到约 150dp](#navigationrail-是不滚动的-column-且只设了最小宽溢出的媒体库被静默压成-0-高度长库名又把-rail-从-80dp-撑到约-150dp)

### 转场与反馈（严重 7 · 打磨 5）

- [严重·Android] [Snackbar 手搓钉在根 Box 的 BottomCenter：2.6 秒内整条压住 80dp 高的底部导航栏并吃掉该处点击，无进出动画、无 liveRegion](#snackbar-手搓钉在根-box-的-bottomcenter26-秒内整条压住-80dp-高的底部导航栏并吃掉该处点击无进出动画无-liveregion)
- [严重·两端] [全部 11 条提示共用一个写死的 2600ms 计时器：短于 M3 最短的 4000ms，绕过无障碍延时，同文案连发还不重新计时](#全部-11-条提示共用一个写死的-2600ms-计时器短于-m3-最短的-4000ms绕过无障碍延时同文案连发还不重新计时)
- [严重·两端] [海报卡显式 indication = null：触摸端按下零反馈、桌面焦点零指示，而同屏的分集卡/设置行走的是默认涟漪](#海报卡显式-indication--null触摸端按下零反馈桌面焦点零指示而同屏的分集卡设置行走的是默认涟漪)
- [严重·两端] [详情页没有加载态：第一次进是整屏空白，之后进会先把上一个条目的整页端出来，连播放按钮都是活的](#详情页没有加载态第一次进是整屏空白之后进会先把上一个条目的整页端出来连播放按钮都是活的)
- [严重·两端] [三个列表屏都没有错误态：媒体库读失败后永远转圈，2.6 秒后连错误都没了，也没有重试](#三个列表屏都没有错误态媒体库读失败后永远转圈26-秒后连错误都没了也没有重试)
- [严重·两端] [搜索无加载态：新一轮搜索的首个字符必然先闪 ≥250ms 的「没有匹配的结果」，且「正在查」与「查完为空」长得一模一样](#搜索无加载态新一轮搜索的首个字符必然先闪-250ms-的没有匹配的结果且正在查与查完为空长得一模一样)
- [严重·两端] [全应用唯一的页面转场是无方向的交叉溶解：进入与返回播放同一套动画，两屏叠加期约 110ms](#全应用唯一的页面转场是无方向的交叉溶解进入与返回播放同一套动画两屏叠加期约-110ms)
- [打磨·桌面] [悬停反馈只做了一半：遮罩与播放键一帧硬跳无淡入，tonalElevation 因色角色选错静默失效所以卡片不「抬起」，指针也仍是箭头](#悬停反馈只做了一半遮罩与播放键一帧硬跳无淡入tonalelevation-因色角色选错静默失效所以卡片不抬起指针也仍是箭头)
- [打磨·两端] [ready 在任何数据到位前就翻转：冷启动会闪过一次「还没有媒体库」空态，且四段状态之间全是硬切](#ready-在任何数据到位前就翻转冷启动会闪过一次还没有媒体库空态且四段状态之间全是硬切)
- [打磨·两端] [备份导出全程零进度：写好的进度条永远不显示，按钮还能反复点，而并排的「导入…」全程有进度条](#备份导出全程零进度写好的进度条永远不显示按钮还能反复点而并排的导入全程有进度条)
- [打磨·两端] [扫描进度用 determinate 的 LinearWavyProgressIndicator，但 queued/listing 阶段总数为 0，进度条静止在 0%](#扫描进度用-determinate-的-linearwavyprogressindicator但-queuedlisting-阶段总数为-0进度条静止在-0)
- [打磨·两端] [首页「正在扫描」排在约 2000dp 内容之后的列表末尾，顶部/导航栏没有任何后台任务提示](#首页正在扫描排在约-2000dp-内容之后的列表末尾顶部导航栏没有任何后台任务提示)

### 配色与对比度（阻断 1 · 严重 1 · 打磨 1）

- [阻断·Android] [系统栏图标明暗只认系统夜间模式、界面底色只认应用内开关：开箱默认组合（系统浅色 + App 深色）下状态栏图标 1.09:1，切主题时系统栏纹丝不动](#系统栏图标明暗只认系统夜间模式界面底色只认应用内开关开箱默认组合系统浅色--app-深色下状态栏图标-1091切主题时系统栏纹丝不动)
- [严重·两端] [设置页库卡与详情页分集卡用色阶最低的 surfaceContainerLow，压在 background 上只有 1.05:1，18dp 圆角与卡片边界等于白画](#设置页库卡与详情页分集卡用色阶最低的-surfacecontainerlow压在-background-上只有-105118dp-圆角与卡片边界等于白画)
- [打磨·两端] [观看进度条用 secondary，而该角色在两套主题里明暗相反，EpisodeRow 的轨道还是全透明直接压在剧照上](#观看进度条用-secondary而该角色在两套主题里明暗相反episoderow-的轨道还是全透明直接压在剧照上)

### 排版与信息层级（打磨 5）

- [打磨·桌面] [labelSmall（11sp）被当正文用了 24 处：桌面「内置播放器」设置里 5 段成句中文说明在 100% 缩放下是 11 个物理像素](#labelsmall11sp被当正文用了-24-处桌面内置播放器设置里-5-段成句中文说明在-100-缩放下是-11-个物理像素)
- [打磨·两端] [主题从不传 typography：M3 的拉丁基线行高压过中文字体自然行高 8%，字距也全程带 tracking](#主题从不传-typographym3-的拉丁基线行高压过中文字体自然行高-8字距也全程带-tracking)
- [打磨·Android] [从未设置 TextStyle.localeList：Android 系统语言非中文时，中日共用码位按日文字形回退](#从未设置-textstylelocalelistandroid-系统语言非中文时中日共用码位按日文字形回退)
- [打磨·两端] [分集行的观看状态与文件路径同字号同字重同色紧邻，中间连一个 Spacer 都没有，视觉上黏成一段](#分集行的观看状态与文件路径同字号同字重同色紧邻中间连一个-spacer-都没有视觉上黏成一段)
- [打磨·Android] [网格卡宽度与 maxLines=1 都是常量，系统字号 200% 时卡片标题退化到约 4 个汉字加省略号](#网格卡宽度与-maxlines1-都是常量系统字号-200-时卡片标题退化到约-4-个汉字加省略号)

### Android 平台集成（阻断 1 · 严重 2 · 打磨 3）

- [阻断·Android] [开了 edge-to-edge 但全仓库零 inset 处理：内容从 y=8dp 起穿过状态栏，二级页返回箭头在 40dp 状态栏机型只露 4dp、48dp 机型完全消失](#开了-edge-to-edge-但全仓库零-inset-处理内容从-y8dp-起穿过状态栏二级页返回箭头在-40dp-状态栏机型只露-4dp48dp-机型完全消失)
- [严重·Android] [edge-to-edge 让 adjustResize 失效而应用未接手 IME inset：设置页下半屏的密码 / API Key / 元数据语言输入框会被软键盘盖住](#edge-to-edge-让-adjustresize-失效而应用未接手-ime-inset设置页下半屏的密码--api-key--元数据语言输入框会被软键盘盖住)
- [严重·Android] [应用根本没有图标资源：桌面、最近任务、Android 12+ 冷启动闪屏全是系统默认的通用图标](#应用根本没有图标资源桌面最近任务android-12-冷启动闪屏全是系统默认的通用图标)
- [打磨·Android] [横向片架铺到屏幕左右边缘，与手势导航的返回热区抢同一条窄带，且未声明手势排除区](#横向片架铺到屏幕左右边缘与手势导航的返回热区抢同一条窄带且未声明手势排除区)
- [打磨·Android] [扫描通知没有品牌识别：small icon 用框架的 stat_notify_sync、取消动作用 Holo 时代的 ic_menu_close_clear_cancel，且从未 setColor](#扫描通知没有品牌识别small-icon-用框架的-stat_notify_sync取消动作用-holo-时代的-ic_menu_close_clear_cancel且从未-setcolor)
- [打磨·Android] [通知权限弹窗在 setContent 之前发出，新用户看到的第一屏是盖在空白加载页上的系统权限对话框](#通知权限弹窗在-setcontent-之前发出新用户看到的第一屏是盖在空白加载页上的系统权限对话框)

### 桌面平台惯例（阻断 1 · 严重 5 · 打磨 1）

- [阻断·桌面] [默认窗口 1360×900 物理像素从不与屏幕工作区取交集：1366×768 笔记本、1080p 上开 125%/150% 缩放的机器首次启动就有一大块在工作区外](#默认窗口-1360900-物理像素从不与屏幕工作区取交集1366768-笔记本1080p-上开-125150-缩放的机器首次启动就有一大块在工作区外)
- [严重·桌面] [桌面上所有横排用滚轮推不动——纵向滚轮增量在 Horizontal scrollable 上被算成 0 并冒泡去滚整页，行两端也没有箭头或滚动条](#桌面上所有横排用滚轮推不动纵向滚轮增量在-horizontal-scrollable-上被算成-0-并冒泡去滚整页行两端也没有箭头或滚动条)
- [严重·桌面] [右键菜单用 DropdownMenu 的 offset 当光标坐标，而 offset 的垂直基准是锚点底边——菜单落在光标下方整整一张海报的高度](#右键菜单用-dropdownmenu-的-offset-当光标坐标而-offset-的垂直基准是锚点底边菜单落在光标下方整整一张海报的高度)
- [严重·桌面] [桌面端一个快捷键、一条菜单栏都没有：Alt+← / Backspace 不返回、Ctrl+F 不跳搜索、F5 不刷新，唯一返回入口是左上角那个图标按钮](#桌面端一个快捷键一条菜单栏都没有alt--backspace-不返回ctrlf-不跳搜索f5-不刷新唯一返回入口是左上角那个图标按钮)
- [严重·桌面] [窗口大小、位置、最大化状态、所在显示器一概不记忆，每次启动都回到主屏左上角的 1360×900](#窗口大小位置最大化状态所在显示器一概不记忆每次启动都回到主屏左上角的-1360900)
- [严重·桌面] [没有任何窗口图标：标题栏、任务栏、Alt+Tab、安装后的开始菜单快捷方式全是 JDK 默认的咖啡杯](#没有任何窗口图标标题栏任务栏alttab安装后的开始菜单快捷方式全是-jdk-默认的咖啡杯)
- [打磨·桌面] [两个「打开」对话框的 FilenameFilter 在 Windows 上是 JDK 文档化的空操作，三个对话框都不设初始目录、也没有文件类型下拉](#两个打开对话框的-filenamefilter-在-windows-上是-jdk-文档化的空操作三个对话框都不设初始目录也没有文件类型下拉)

### 播放器界面（阻断 1 · 严重 6 · 打磨 2）

- [阻断·桌面] [桌面内置播放器的「音轨 / 字幕」下拉菜单整个落在 mpv 的 AWT 画布里，被原生子窗口盖住，看不见也点不到](#桌面内置播放器的音轨--字幕下拉菜单整个落在-mpv-的-awt-画布里被原生子窗口盖住看不见也点不到)
- [严重·桌面] [桌面播放器把走带控制整个交给 mpv，Compose 侧既不转交按键也从不把焦点还给 Canvas——点过一次工具栏后空格变成「重新弹开刚才那个菜单」，Esc 也无人接管](#桌面播放器把走带控制整个交给-mpvcompose-侧既不转交按键也从不把焦点还给-canvas点过一次工具栏后空格变成重新弹开刚才那个菜单esc-也无人接管)
- [严重·桌面] [桌面播放器没有任何全屏入口，却把 mpv 那两个在 --wid 内嵌下已经变成空操作的全屏控件原样摆给用户](#桌面播放器没有任何全屏入口却把-mpv-那两个在---wid-内嵌下已经变成空操作的全屏控件原样摆给用户)
- [严重·桌面] [桌面 PlayerBar 是无溢出处理的单行 Row：GPU 型号排在片名前面吃满宽度，150% 缩放下字幕轨名换行把 bar 撑到 112dp、「结束播放」退化成压在字幕按钮上的 48dp 空白热区](#桌面-playerbar-是无溢出处理的单行-rowgpu-型号排在片名前面吃满宽度150-缩放下字幕轨名换行把-bar-撑到-112dp结束播放退化成压在字幕按钮上的-48dp-空白热区)
- [严重·两端] [播放中点侧栏/底栏的「搜索」「设置」「某个媒体库」：去不了目标页，绕一圈回来重启一次解码，再自己多退一级](#播放中点侧栏底栏的搜索设置某个媒体库去不了目标页绕一圈回来重启一次解码再自己多退一级)
- [严重·Android] [Android 播放器的标题与音轨/字幕按钮是纯白文字直接压在视频帧上，没有 scrim 也没有阴影，亮画面下低至 1.00:1](#android-播放器的标题与音轨字幕按钮是纯白文字直接压在视频帧上没有-scrim-也没有阴影亮画面下低至-1001)
- [严重·两端] [退出内置播放器：Android 上引擎比页面多活约 0.33 秒声音继续响，桌面则在淡出中途先闪出「没有正在播放的内容」，两端画面都不参与淡入淡出](#退出内置播放器android-上引擎比页面多活约-033-秒声音继续响桌面则在淡出中途先闪出没有正在播放的内容两端画面都不参与淡入淡出)
- [打磨·桌面] [桌面 PlayerBar 跟随应用主题：切到浅色主题后，纯黑画面上方是一条横贯全宽、64dp 高的近白色带](#桌面-playerbar-跟随应用主题切到浅色主题后纯黑画面上方是一条横贯全宽64dp-高的近白色带)
- [打磨·Android] [Android 播放失败时一张卡片浮在画面正中央、永不消失、没有任何出口，文案还是 ERROR_CODE_IO_BAD_HTTP_STATUS 这样的英文枚举](#android-播放失败时一张卡片浮在画面正中央永不消失没有任何出口文案还是-error_code_io_bad_http_status-这样的英文枚举)

### 组件用法（严重 4 · 打磨 8）

- [严重·两端] [26dp 圆角把海报底部的观看进度条两端啃掉：进度低于 8.1% 时一个像素都画不出来，而代码以为自己画了](#26dp-圆角把海报底部的观看进度条两端啃掉进度低于-81-时一个像素都画不出来而代码以为自己画了)
- [严重·两端] [缺图这一件事有四种画法：卡片给电影图标、分集缩略图给空灰块、演职人员给空灰圆、详情页海报干脆不占位——最后一种让整个头部左移 180dp](#缺图这一件事有四种画法卡片给电影图标分集缩略图给空灰块演职人员给空灰圆详情页海报干脆不占位最后一种让整个头部左移-180dp)
- [严重·两端] [悬停时海报中央那个圆形播放键点下去不是播放，是进详情页；触摸端则永远看不到它](#悬停时海报中央那个圆形播放键点下去不是播放是进详情页触摸端则永远看不到它)
- [严重·两端] [已观看的对勾在四处用了两套互斥编码：卡片/详情页按状态画（primary 实心勾=已看），右键菜单按动作画（primary 实心勾=未看）；卡片角标还是无底衬裸图标](#已观看的对勾在四处用了两套互斥编码卡片详情页按状态画primary-实心勾已看右键菜单按动作画primary-实心勾未看卡片角标还是无底衬裸图标)
- [打磨·两端] [设置里的开关行自己拼 Row+Switch：标签点不动（360dp 行里只有右端 52dp 可点）、桌面无 hover 反馈、读屏节点没有可访问名称](#设置里的开关行自己拼-rowswitch标签点不动360dp-行里只有右端-52dp-可点桌面无-hover-反馈读屏节点没有可访问名称)
- [打磨·两端] [六组 ToggleButton 一套外观承担三种语义：五组互斥单选、一组独立多选，选中态还是 primary 实心、与紧邻 10dp 的主操作「导出」同一色阶](#六组-togglebutton-一套外观承担三种语义五组互斥单选一组独立多选选中态还是-primary-实心与紧邻-10dp-的主操作导出同一色阶)
- [打磨·两端] [仓库自带统一的 Chip 组件，桌面播放器却另用 AssistChip(enabled=false) 伪装只读状态：两套长相，读屏还念成「按钮，已停用」](#仓库自带统一的-chip-组件桌面播放器却另用-assistchipenabledfalse-伪装只读状态两套长相读屏还念成按钮已停用)
- [打磨·两端] [Card / Surface 的点击挂在 Modifier.clickable 上而不是用 onClick 重载：水波纹与 hover 高亮画在 clip 之前，会溢出圆角](#card--surface-的点击挂在-modifierclickable-上而不是用-onclick-重载水波纹与-hover-高亮画在-clip-之前会溢出圆角)
- [打磨·两端] [分节标题有三套实现：SectionHeader 组件、六份无 vertical padding 的手写副本、以及首页那个被套两层 20dp 缩进到 40dp 的；标题到正文的间距在 0/8/12/13dp 之间随机，节尾 20/24dp 混用](#分节标题有三套实现sectionheader-组件六份无-vertical-padding-的手写副本以及首页那个被套两层-20dp-缩进到-40dp-的标题到正文的间距在-081213dp-之间随机节尾-2024dp-混用)
- [打磨·桌面] [首页顶部大图没有右键菜单，同一条目在它正下方的卡片上却有](#首页顶部大图没有右键菜单同一条目在它正下方的卡片上却有)
- [打磨·两端] [库类型图标里「其他」与「电影」取同一个 ImageVector 完全不可分辨，「番剧」借用播放列表图标，同一个电影图标还兼任全 App 的缺图占位](#库类型图标里其他与电影取同一个-imagevector-完全不可分辨番剧借用播放列表图标同一个电影图标还兼任全-app-的缺图占位)
- [打磨·两端] [按钮文案两套规则并存：会弹系统文件框的四个命令只有两个带省略号；一对同级动作叫「立即上传」和「从云端合并」，不对仗](#按钮文案两套规则并存会弹系统文件框的四个命令只有两个带省略号一对同级动作叫立即上传和从云端合并不对仗)

### 无障碍与键盘可达（严重 5 · 打磨 4）

- [严重·两端] [海报卡与 LinkText 显式关掉 indication：Tab / D-pad 焦点落上去零像素变化，网格自己在滚却看不出选中了谁，按空格就盲开一个详情页](#海报卡与-linktext-显式关掉-indicationtab--d-pad-焦点落上去零像素变化网格自己在滚却看不出选中了谁按空格就盲开一个详情页)
- [严重·两端] [LinkText 可点击时写死 primary 并丢弃传入的 color：同一行剧名在紫与暖橙间随数据跳色，与紧邻正文亮度比 1.00:1，触摸目标只有 20dp](#linktext-可点击时写死-primary-并丢弃传入的-color同一行剧名在紫与暖橙间随数据跳色与紧邻正文亮度比-1001触摸目标只有-20dp)
- [严重·Android] [TalkBack 播报的「长按」是个死动作：右键/长按菜单被 lastPointer 判断挡住，无障碍派发的 OnLongClick 永远不满足条件](#talkback-播报的长按是个死动作右键长按菜单被-lastpointer-判断挡住无障碍派发的-onlongclick-永远不满足条件)
- [严重·Android] [海报卡的「看了一半」对读屏是彻底空白，还额外多出一个只念百分比的无名焦点停靠点，片名被念两遍](#海报卡的看了一半对读屏是彻底空白还额外多出一个只念百分比的无名焦点停靠点片名被念两遍)
- [严重·两端] [收藏与「手动指定刮削条目」两个 IconButton 的状态没进 contentDescription；后者连视觉上也只靠颜色区分](#收藏与手动指定刮削条目两个-iconbutton-的状态没进-contentdescription后者连视觉上也只靠颜色区分)
- [打磨·两端] [三个对话框都不把 Enter 绑到默认按钮、打开时也不落焦点：Esc 能关但 Enter 不能确认，键盘路径是单向的](#三个对话框都不把-enter-绑到默认按钮打开时也不落焦点esc-能关但-enter-不能确认键盘路径是单向的)
- [打磨·Android] [全应用 15 个输入框没有一个声明 imeAction：安卓上填任何一段表单都是「打一个字段 → 收一次键盘 → 点下一个字段」](#全应用-15-个输入框没有一个声明-imeaction安卓上填任何一段表单都是打一个字段--收一次键盘--点下一个字段)
- [打磨·两端] [搜索页进去不自动聚焦输入框，桌面也没有任何进入搜索的快捷键](#搜索页进去不自动聚焦输入框桌面也没有任何进入搜索的快捷键)
- [打磨·Android] [搜索框只有 placeholder 没有 label，输入之后读屏念不出这是什么框](#搜索框只有-placeholder-没有-label输入之后读屏念不出这是什么框)

---

## 布局与响应式

### 「继续观看」一行混排 2:3 海报与 16:9 剧照，标题错开 217.5dp，横向滚动时整行高度在 174.5↔392dp 之间持续跳动

**严重** · 两端 · `Components.kt:158、Components.kt:190、Components.kt:273-287、Components.kt:311-314、HomeScreen.kt:84-92、HomeScreen.kt:102、Repository.kt:343-347`

Components.kt:158 `val aspect = if (item.kind == ItemKind.EPISODE) 16f/9f else 2f/3f`，Components.kt:190 `aspectRatio(aspect)`；Repository.kt:343-347 的 resume 查询是 `kind IN ('MOVIE','EPISODE')` 按 last_played_at 排序，电影与分集必然交叉出现在同一行。HomeScreen.kt:84-92 传 itemWidth=232dp：电影 232÷(2/3)=348dp，分集 232×9/16=130.5dp，差 217.5dp；文字块 Components.kt:273-287 = Spacer 8 + bodyMedium 行高 20 + bodySmall 行高 16 = 44dp，卡片总高 392 vs 174.5dp。Components.kt:311-314 的 LazyRow 未设 verticalAlignment，卡片顶对齐，两张卡的标题一个落在 y≈356、一个在 y≈138。更重的是 LazyRow 的交叉轴尺寸取的是当前可见项的最大高度而非全表最大值——把唯一那张电影卡滑出视口，整行从 392dp 缩到 174.5dp，下面「接下来」「最近添加」跟着上跳 217dp，反向滚回来再跳一次。首页同屏一共三种卡片几何：232×348、232×130.5、152×228（HomeScreen.kt:102/105-112 走 Components.kt:302 的默认 152dp），四行卡片三种尺寸两种比例。Jellyfin/Plex/Emby 的 Continue Watching 都强制整行统一 16:9。

> **改法**：给 MediaRow 加 `aspect: Float` 参数并透传给 PosterCard，覆盖按 kind 推导的逻辑；「继续观看」「接下来」固定 16f/9f（电影用 backdropUrl，缺图时把 poster 居中裁成 16:9），并给这两行的 LazyRow 一个固定高度 itemWidth*9/16+44dp 杜绝行高跳变。改不动图源时，最小改动是 Components.kt:311 加 `verticalAlignment = Alignment.Bottom`，至少让标题落在同一条基线上。

### 全 App 只有外置播放面板有最大宽度：100% 缩放下就是 1240dp 宽的单行 URL 输入框、每行约 74 个汉字的简介

**严重** · 桌面 · `PlayerScreen.kt:76、Main.kt:25、App.kt:131、App.kt:133-140、SettingsScreen.kt:206、SettingsScreen.kt:209、DetailScreen.kt:254-264、DetailScreen.kt:349-352`

全仓库 UI 层 `widthIn|requiredWidth|sizeIn` 只有 PlayerScreen.kt:76 一处（max 560dp，外置播放面板），App/Home/Library/Detail/Search/Settings 全部 fillMaxWidth。桌面窗口 Main.kt:25 `rememberWindowState(size = DpSize(1360.dp, 900.dp))` 的 Dp 数值被 Compose Desktop 直接当 AWT 像素用，所以窗口恒为 1360×900 物理像素，BoxWithConstraints 的 maxWidth = 1360 / 系统缩放：100%→1360dp、125%→1088dp、150%→907dp、175%→777dp，全部 ≥ App.kt:131 的 720dp 阈值，一启动就走 App.kt:133-140 的宽分支且 Content 无任何宽度约束。100% 缩放下内容区 = 1360−80(NavigationRail) = 1280dp：SettingsScreen.kt:206/238/291/363/487 的 `padding(horizontal=20.dp)` 内宽 1240dp，:209 的 WebDAV 地址框就是一个 1240dp 宽的 singleLine；DetailScreen.kt:254-264 宽屏分支正文列 = 1280−48−160−20 = 1052dp，:349-352 的简介是 bodyMedium 14sp + 0.2sp tracking ≈ 14.2dp/汉字 → 每行约 74 个汉字（maxLines=5）。最大化到 1920dp 时正文列 1612dp ≈ 113 汉字/行。中文舒适行长 25–45 字，这是上限的 1.7–2.5 倍。网格类屏（媒体库/搜索）不在此列，它们靠 GridCells.Adaptive 自适应列数。

> **改法**：App.kt:137 的 Content 外套一层 `Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 1120.dp)) { … } }`；设置页表单区再单独收到 `widthIn(max = 640.dp)`，详情页正文列收到 `max = 900.dp`。

### 返回箭头行在 8dp 与 56dp 之间无动画硬切换，每次导航整页内容瞬跳 48dp 并重排版——而 TopRow 在转场动画之外

**严重** · 两端 · `App.kt:178-181、App.kt:182-189、App.kt:136-139、App.kt:142-146、App.kt:160-163`

App.kt:178-181 根屏 TopRow 退化为 `Spacer(Modifier.height(8.dp))`；App.kt:182-189 二级屏是 `Row(Modifier.padding(start=8.dp, top=8.dp)) { IconButton { Icon(ArrowBack) } }`，M3 IconButton 经 minimumInteractiveComponentSize（LocalMinimumInteractiveComponentSize 默认 48dp）布局高 48dp，合计 56dp，内容起点 8dp vs 56dp，差正好 48dp。关键机制：TopRow 位于 AnimatedContent 之外——宽屏 App.kt:136-139、窄屏 App.kt:142-146 都把 TopRow 与 Content 平级，AnimatedContent 在 App.kt:160-163——所以不是两屏各占一个 y，而是 TopRow 无动画地瞬时变高、把正在 fadeOut 的旧屏一起推下 48dp，而 App.kt:161-162 的 `fadeIn() togetherWith fadeOut()` 没有任何位移或尺寸动画掩盖。窄屏更重：`Column { TopRow; Box(weight 1f){Content}; BottomNavigation }` 里 TopRow 长高会同时把内容区压短 48dp，是平移加重排版。触发面极广：App.kt:222-227/259-264 首页走 replaceAll（栈深 1）、App.kt:228-241/266-278 搜索与设置走 navigate（栈深 2），三个平级 tab 互切也会上下跳 48dp。M3 small top app bar 是固定 64dp 容器、navigationIcon 槽位为空时仍占位，存在的理由正是避免这种基线跳动。

> **改法**：TopRow 换成固定高度容器 `Box(Modifier.fillMaxWidth().height(56.dp))`，里面用 `AnimatedVisibility(state.backStack.size > 1, fadeIn(), fadeOut())` 包住 IconButton；或直接换成 M3 TopAppBar 并在无返回时给一个空的 navigationIcon（需要 hero 通铺时用 `containerColor = Color.Transparent` 叠在图上，而不是把图往下推）。

### 导航断点只有 720dp 一档且两套导航目的地不同：跨过断点媒体库入口整个消失，桌面 200% 缩放起直接渲染成手机版底栏布局

**严重** · 两端 · `App.kt:131、App.kt:215-253、App.kt:243-251、App.kt:256-280、HomeScreen.kt:83、SettingsScreen.kt:125、LibraryScreen.kt:86-88、Components.kt:167-168、Main.kt:25`

App.kt:131 `val wide = maxWidth >= 720.dp` 是导航层唯一断点。App.kt:215-253 的 SideNavigation 在首页/搜索/设置之后有 :243-251 `state.libraries.forEach { NavigationRailItem(...) }`，而 App.kt:256-280 的 BottomNavigation 只有三项——跨过断点，全部媒体库入口在导航里凭空出现/消失，窄态进库要先回首页点快捷卡（HomeScreen.kt:83）或绕设置页（SettingsScreen.kt:125），从 1 次点击变 2 次。桌面这一侧被系统缩放直接命中：窗口是 1360×900 物理像素，maxWidth = 1360/缩放，100%→1360dp、125%→1088dp、150%→907dp、175%→777dp 仍走宽布局，但 200%→680dp、250%→544dp、300%→453dp 全部低于 720dp，桌面端会渲染成手机版底栏布局、导航里的媒体库入口整个消失。Win+← 半屏同理：1366×768@100% 半屏 683dp、1920×1080@150% 半屏 640dp 都落进窄分支。还有一处宽度倒挂：721dp 窗口内容区 = 721−80 = 641dp，比 719dp 窗口的 719dp 还窄，媒体库网格从 4 列（cell 159dp）掉到 3 列（cell 191dp），而 LibraryScreen.kt:86-88 给 PosterCard 传死的 `width = 150.dp`（Components.kt:167-168 `Column(modifier.width(width))`），每格右侧凭空多出 41dp 死白。M3 的档位是 compact <600 / medium 600-839 / expanded ≥840，medium 起就该用 rail。

> **改法**：断点改成 600dp（rail）与 840dp 两档；BottomNavigation 补第四项「媒体库」（多库时点进去给一个库列表页），保证两套骨架目的地相同；LibraryScreen 不再传死 width，让 PosterCard 走 fillMaxWidth 吃满 cell。

### 三个对话框的正文是不可滚动的 Column + 写死 heightIn：视口不够高时尾部的候选/目录列表被测成 0 高度并静默消失

**严重** · 两端 · `IdentifyDialog.kt:130、IdentifyDialog.kt:134、IdentifyDialog.kt:237、IdentifyDialog.kt:246、MergeDialog.kt:91-95、MergeDialog.kt:104、MergeDialog.kt:152、SettingsScreen.kt:629-647`

IdentifyDialog.kt:130 的 AlertDialog → :134 `Column(Modifier.heightIn(max = 520.dp))`，外层无 verticalScroll，末尾 :237 是 `LazyColumn(Modifier.heightIn(max = 220.dp))` 的搜索候选列表；MergeDialog.kt:91/:95/:152 同构（:104 的已合并列表还是无上限的 forEach）；SettingsScreen.kt:629/:633/:647 的 WebDAV 目录列表用 `weight(1f, fill = false).heightIn(max = 240.dp)`。material3 1.12.0-alpha03 的 AlertDialogKt 整个类零个 verticalScroll 引用，正文槽唯一的布局修饰是 `ColumnScope.weight(...)`——正文只拿到「对话框剩余高度」这个有界 maxHeight，自身不滚动。Compose 的 Column 对无 weight 子项按剩余空间递减测量并 coerceAtLeast(0)，空间耗尽后续子项 maxHeight=0。以 IdentifyDialog 为例，列表之前的固定内容 ≈253dp（说明 16 + 12 + ToggleButton 行 40 + 12 + 带 supportingText 的 id 框 76 + 8 + 分隔线 1 + 8 + 说明 16 + 8 + 搜索行 56），加标题/按钮/内边距约 150dp，需要约 400dp；横屏手机对话框可用高度只有约 220dp，LazyColumn 被测成 0 高，搜索结果一条都看不到、也没有滚动条提示还有内容，而 :246 的「应用」按钮又要求先填 id，整条路走死。桌面把窗口拖矮到 600dp 以下同样触发。

> **改法**：三处外层 Column 去掉写死的 heightIn，改成 `Modifier.verticalScroll(rememberScrollState())`，内部 LazyColumn 换成普通 forEach（或反过来只留一个 LazyColumn 承载全部内容），保证短视口下能滚到底。

### 分集行缩略图写死 148dp、尾部按钮 48dp：360dp 手机上正文列只剩 86dp，标题约 6 个汉字、元信息折成两行；1920dp 下缩略图又不放大

**严重** · 两端 · `DetailScreen.kt:690、DetailScreen.kt:705-707、DetailScreen.kt:733-740、DetailScreen.kt:751-770、DetailScreen.kt:772`

DetailScreen.kt:690 `Box(padding(horizontal=20.dp, vertical=5.dp))` → Card fillMaxWidth → :705 `Row(padding(12.dp))` → :707 `Surface(width(148.dp).height(84.dp))` → :733 Spacer 14dp → :734 `Column(weight(1f))` → :772 IconButton（minimumInteractiveComponentSize 48dp）。正文列复算：360dp 屏 = 360−40−24−148−14−48 = 86dp；412dp Pixel = 138dp。:735-740 的标题是 titleSmall 14sp maxLines=1，86dp 下约 6 个汉字；:751-761 的元信息 Text 没有 maxLines，labelSmall 11sp 下「已看完 · 24:31 · 1.4 GB · 2 条字幕」约 160dp，在 86dp 列内折 2 行，:762-770 的相对路径再占 1 行。反方向也不对：1920dp 最大化时缩略图仍是硬编码 148dp，正文列拉到约 1566dp。这是追剧用户每天都会停留的那一屏。

> **改法**：缩略图按容器比例取宽（BoxWithConstraints 里 `(maxWidth * 0.32f).coerceIn(96.dp, 240.dp)` 配 `aspectRatio(16f/9f)`）；给 :751-761 的元信息加 maxLines=1 + Ellipsis；窄屏把已观看 IconButton 移到卡片右上角叠加，把 48dp 还给正文列。

### 首页 hero 的 heightIn(min=260,max=380) 因内容永远撑不到 260dp 而等价于写死 260dp，且完全不看视口高度

**打磨** · 两端 · `HomeScreen.kt:149-153、HomeScreen.kt:66、DetailScreen.kt:221`

HomeScreen.kt:149-153 `Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 380.dp))`；内部 Column 按 M3 默认字号复算约 240dp（padding 24×2 + labelLarge 20 + headlineLarge 两行 80 + 6 + Chip 行 32 + 14 + Button 40），恒小于 260dp，所以 max=380dp 这一档永远够不到，整个 heightIn 等价于 `height(260.dp)`——写了一个响应式区间，只有一个值生效。DetailScreen.kt:221 的 `heightIn(min = 320.dp)` 同理不看可用高度。横屏手机可用高度约 412dp 时 hero 占掉 63%；桌面 175% 缩放下窗口高只有 514dp，hero 仍是 260dp。因为容器是 LazyColumn（HomeScreen.kt:66），内容不会不可达，只是首屏密度过低。

> **改法**：把两个常量换成随 BoxWithConstraints 的 maxHeight 取值，例如 `min(380.dp, maxOf(180.dp, maxHeight * 0.45f))`；DetailScreen.kt:221 的 320dp 最小值同样按可用高度夹一次。

### 五个可滚动屏用了五个不同的底部留白（8/20/40/48/48dp），搜索页 8dp 最紧

**打磨** · 两端 · `SearchScreen.kt:65、LibraryScreen.kt:80、HomeScreen.kt:68、DetailScreen.kt:94、SettingsScreen.kt:78-81、App.kt:142-146`

SearchScreen.kt:65 `PaddingValues(horizontal = 20.dp, vertical = 8.dp)`、LibraryScreen.kt:80 `PaddingValues(20.dp)`（四边 20，含 top）、HomeScreen.kt:68 `PaddingValues(bottom = 40.dp)`、DetailScreen.kt:94 `PaddingValues(bottom = 48.dp)`、SettingsScreen.kt:78-81 `PaddingValues(bottom = 48.dp)`。窄屏下 App.kt:142-146 的 NavigationBar 是 Column 的兄弟节点而非覆盖层，所以内容不会被遮挡，只是留白不足——搜索结果最后一行的副标题几乎顶在 80dp 高的导航栏上沿；桌面宽窗口没有底部栏，8dp 就是直接贴窗口下边缘。同一 App 内同类容器取五个值，是纯粹的不一致。

> **改法**：抽一个共用常量（例如 `val ScreenScrollPadding = PaddingValues(top = 8.dp, bottom = 24.dp)`）让五个屏统一引用；底部导航的高度另行通过 insets/padding 叠加，不要让每个屏各猜一个数。

### 两处头图用 24dp 内边距、其余内容一律 20dp，同一条竖线错开 4dp；详情页更反过来——窄窗口对齐、宽窗口错开

**打磨** · 两端 · `HomeScreen.kt:175、HomeScreen.kt:223、Components.kt:120、DetailScreen.kt:254-257、DetailScreen.kt:267、DetailScreen.kt:525、DetailScreen.kt:607、DetailScreen.kt:631`

HomeScreen.kt:175 的 HeroBanner 文案块是 `Column(Modifier.align(Alignment.BottomStart).padding(24.dp))`，紧随其下的 HomeScreen.kt:223 LibraryShortcuts 与 Components.kt:120 SectionHeader 都是 `padding(horizontal = 20.dp)`——首页大图里的片名左边缘在 x=24，下面的「媒体库」小标题在 x=20。详情页是同一个组件的两个分支自相矛盾：DetailScreen.kt:254 `val sideBySide = maxWidth >= 600.dp`，:257 宽屏用 `Row(Modifier.padding(24.dp))`、:267 窄屏用 `Column(Modifier.padding(20.dp))`，而同屏下方的 PeopleRow(:631)、FileInfo(:525)、SeasonEpisodesRow(:607) 一律 20dp——窗口 <600dp 时头图与内容对齐，≥600dp 时反而错开 4dp。20dp 这个全局值本身是自洽的风格选择，问题只在这两处 24dp。

> **改法**：让 HeroBanner 与 DetailHeader 的文案块用与 SectionHeader 相同的值（20dp，或全局统一到 24dp），使头图标题与下方分区标题落在同一条竖线上；更彻底的做法是定义一个 LocalContentMargin 由 App.kt 的 BoxWithConstraints 提供，全部屏改读它。

## 导航与信息架构

### 导航项是「入栈」而不是「切换 tab」：重复点当前 tab 也压一层，返回箭头点一次画面纹丝不动，栈没有上限

**严重** · 两端 · `AppState.kt:124-127、AppState.kt:129-140、AppState.kt:37-38、App.kt:222-241、App.kt:256-280、App.kt:178`

AppState.kt:124-127 `fun navigate(screen: Screen) { backStack.add(screen); onEnter(screen) }`，91-140 行内没有任何去重、popUpTo 或深度上限。App.kt:228-234/235-241（侧栏搜索、设置）与 App.kt:266-271/272-278（底栏同两项）调 navigate，只有首页 App.kt:222-227/259-264 调 replaceAll（AppState.kt:129-133 clear+add）——同一排导航里并存两种语义。AppState.kt:37-38 的 Screen.Search/Settings 是 data object，重复入栈是同一实例，App.kt:161 AnimatedContent 的 targetState 不变（无过渡），AppState.kt:135-140 的 back() pop 掉重复项后 current 仍是同一个对象，UI 零变化，构成一次「死按」。在设置↔搜索之间来回切 20 次栈就是 21 层，要点 20 次返回才回得到首页。另外 App.kt:178 用 `backStack.size > 1` 决定是否显示 Up 箭头，导致「搜索/设置」这两个顶级目的地也带返回箭头，与 M3「顶级目的地不显示 Up」相悖。

> **改法**：navigate 里加两条：目标与 current 相等时直接 return；顶级目的地（Home/Search/Settings）改走一个 switchTab()，先 popUpTo 到 Home 再 add，让栈深度恒定为 1 或 2。TopRow 的箭头条件从 `size <= 1` 改成「栈顶不是顶级目的地」。

### 进媒体库/详情/播放页后底栏三项同时熄灭，底栏不再指示当前位置；媒体库根本不是底栏目的地

**严重** · 两端 · `AppState.kt:33-40、App.kt:256-280、App.kt:259、App.kt:266、App.kt:273、App.kt:200-204`

Screen 一共 6 种（AppState.kt:33-40），App.kt:256-280 的底栏只覆盖三种：selected 条件分别是 App.kt:259 `state.current is Screen.Home`、:266 Search、:273 Settings。Screen.Library/Detail/Player——日常停留时间最长的三个屏——不匹配任何一条，三项同时 unselected；App.kt:200-204 的 barColors() 只覆盖 indicator/selectedIcon/selectedText，未覆盖 unselected*，NavigationBarTokens 的 ItemInactiveIconColor 与 ItemInactiveLabelTextColor 都是 OnSurfaceVariant，于是三个图标一律同一个灰，用户看不出自己在哪个 tab 下。M3 明确要求底栏「必须始终指示当前所处位置」，Jellyfin/Plex/Emby 的底栏都含「媒体库」且从库进详情时对应 tab 保持高亮。

> **改法**：给 selected 加派生逻辑——Detail/Player 沿 backStack 往回找第一个顶级 Screen 并高亮它，Library 归到新增的「媒体库」项，让 6 个屏幕都有归属。

### NavigationRail 是不滚动的 Column 且只设了最小宽：溢出的媒体库被静默压成 0 高度，长库名又把 rail 从 80dp 撑到约 150dp

**严重** · 两端 · `App.kt:215-253、App.kt:220、App.kt:242、App.kt:248、AndroidManifest.xml:23`

App.kt:215-253 直接 forEach 铺 state.libraries，无 verticalScroll；material3-desktop 1.12.0-alpha03 的 DefaultNavigationRailOverride 是 `Surface { Column(fillMaxHeight().windowInsetsPadding(insets).widthIn(min = NarrowContainerWidth).padding(vertical = 4.dp).selectableGroup(), verticalArrangement = spacedBy(4.dp)) }`，整个 NavigationRailKt 零个 verticalScroll 引用，Surface 自带 clip。所需高度 = 12(App.kt:220) + 3×56 + 16(App.kt:242) + 56N + 4×(4+N) + 8 = 220 + 60N。溢出不是滚动而是被压扁：RowColumnMeasurePolicy 对非 weight 子项给 `mainAxisMax = (max−已消费).coerceAtLeast(0)`，NavigationRailItem 最外层 Box 会 constrainHeight，maxHeight=0 的项高度为 0——没有滚动条、没有渐隐、没有任何提示。Android 横屏 412dp 高（可用约 372dp）时 N ≤ 2，第 3 个库就进不去，而 wide 分支下没有底部栏兜底（AndroidManifest.xml:23 未锁方向）；桌面 175% 缩放窗口高只有 900/1.75 = 514dp，N ≤ 4.9，第 5 个库起被吃掉。宽度另一头也失控：App.kt:248 `Text(library.name, maxLines = 1)` 既无 overflow=Ellipsis 也无宽度上限，NavigationRailItem 的 `widthIn(min = NavigationRailItemWidth)` 其 max 参数取 Dp.Unspecified，placeLabelAndIcon 取 `constrainWidth(max(max(labelW, iconW), indicatorW))`，12 个汉字的 labelMedium（12sp/+0.5sp tracking）≈149.5dp，rail 从 80dp 撑到约 150dp，而 56dp 的选中指示器居中后左右各留约 47dp 空白。

> **改法**：`NavigationRail { Column(Modifier.verticalScroll(rememberScrollState())) { … } }`（或把媒体库那段换成 LazyColumn，三个固定项钉在顶部单独一段），并给 label 加 `overflow = TextOverflow.Ellipsis` + `Modifier.widthIn(max = 72.dp)` 锁死 rail 宽度。

## 转场与反馈

### Snackbar 手搓钉在根 Box 的 BottomCenter：2.6 秒内整条压住 80dp 高的底部导航栏并吃掉该处点击，无进出动画、无 liveRegion

**严重** · Android · `App.kt:130-155、App.kt:132、App.kt:142-147、App.kt:149-153、App.kt:256-280、MainActivity.kt:23`

App.kt:130-155 全程无 Scaffold（全仓 grep `Scaffold|SnackbarHost` 零命中）：App.kt:132 的 `Box(Modifier.fillMaxSize())` 里，含 BottomNavigation 的 Column（App.kt:142-147）与 App.kt:149-153 的 `Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))` 是兄弟节点，Snackbar 后声明所以画在上层、对齐窗口底边而不是导航栏上沿。尺寸复算：NavigationBarKt 的 NavigationBarHeight 取自 NavigationBarTokens.TallContainerHeight = 80dp（不是旧的 64dp），SnackbarTokens.SingleLineContainerHeight = 48dp；Snackbar 距底 24dp、高 ≥48dp → 顶边在距底 72dp，完整落在 [0,80dp] 的导航带内。手势导航 24dp inset 下导航项在 y∈[H−104,H−24]、Snackbar 在 [H−72,H−24]，完全重叠；三键导航 48dp inset 时 Snackbar 下 24dp 还会钻进系统导航键条。点击也被吃掉：非 clickable 的 M3 Surface 修饰符链上带一次 `pointerInput(Unit) {}`，覆盖区域内的点击不下传（该区域是 Snackbar 自身矩形，短文案约等于盖住中间那一项，长文案盖住三项）。此外 Snackbar 是裸组件，全仓无 AnimatedVisibility，显隐由 `state.toast?.let {}` 的组合进出决定，硬出硬消，也拿不到 SnackbarHost 的 FadeInFadeOutWithScale 与 liveRegion(Polite) 语义——TalkBack 完全不播报。

> **改法**：最小改法：把 App.kt:149-153 的 Snackbar 从外层 Box 挪进 App.kt:144 的 `Box(Modifier.weight(1f))`（宽屏分支同理放进 Content 所在的 Column），它就自然停在导航栏上方。更规范的做法是把 App.kt:130-155 换成 `Scaffold(bottomBar = { if (!wide) BottomNavigation(state) }, snackbarHost = { SnackbarHost(hostState) })`，把 `AppState.toast: String?` 换成 SnackbarHostState，动画、inset、无障碍语义一并解决。

### 全部 11 条提示共用一个写死的 2600ms 计时器：短于 M3 最短的 4000ms，绕过无障碍延时，同文案连发还不重新计时

**严重** · 两端 · `App.kt:123-128、AppState.kt:120、AppState.kt:202-204、AppState.kt:301、AppState.kt:313-393、PlaybackController.kt:209-213、IdentifyDialog.kt:118`

App.kt:123-128 是全局唯一计时器 `LaunchedEffect(state.toast) { if (state.toast != null) { delay(2600); state.toast = null } }`。`grep "toast = "` 得到写入点恰好 11 处：AppState.kt:202/204（run() 的两个 catch，把任意长度的 FacadeException.message+detail 拼进来）、301/313/347/374/381/387/393，PlaybackController.kt:213，IdentifyDialog.kt:118——「已收藏」和一条 36 个汉字（总长约 70 字符）的「没有可用的播放器…」说明共用同一外观、同一 2.6 秒，后者按 4–5 字/秒需要 9–11 秒才读得完，而且没有任何地方能重看。SnackbarHostKt 的常量是 Short=4000ms、Long=10000ms，且会调 `AccessibilityManager.calculateRecommendedTimeoutMillis` 为读屏用户自动延长——手写 delay 把这条无障碍通路整个绕过。不重新计时的机制：AppState.kt:120 `var toast by mutableStateOf<String?>(null)` 用默认 structuralEqualityPolicy，第二次写入同一个字符串常量（如 AppState.kt:301 的「已收藏」）不产生状态变更，LaunchedEffect 的 key 不变、计时不重启，第二次确认可能只显示几百毫秒。

> **改法**：把 `toast: String?` 拆成 `(message, isError, action)` 三元组：成功类 4s、错误类 10s 或不自动消失并带一个「去设置 / 重试」按钮；同一文案连发时用一个自增序号做 LaunchedEffect 的 key。「没有可用的播放器」这类需要用户决策的错误改用 AlertDialog。

### 海报卡显式 indication = null：触摸端按下零反馈、桌面焦点零指示，而同屏的分集卡/设置行走的是默认涟漪

**严重** · 两端 · `Components.kt:178-187、Components.kt:156-157、Components.kt:170、Components.kt:190、Components.kt:192-193、Components.kt:223-241、Components.kt:386、DetailScreen.kt:698-701、Theme.kt:38、Theme.kt:55`

Components.kt:178-187 是 `combinedClickable(interactionSource = interaction, indication = null, onClick = …, onLongClick = …)`——第 180 行显式关闭（只有不传该参数的重载才回落 LocalIndication）。这个 interactionSource 的消费者只有 Components.kt:170 的 `.hoverable(interaction)` 与 :156 的 `collectIsHoveredAsState()`，press 状态无任何订阅者；:157 的 scale 1.04、:190 的 .scale()、:193 的 tonalElevation 6dp、:223-241 的渐变遮罩与播放键，四处视觉反馈全部只读 hovered。而 hover 只认鼠标：InternalPointerEvent.activeHoverEvent 的判定就是 `type == PointerType.Mouse`，触摸永不产生 Enter，所以 Android 上从手指按下到详情页出现之间卡片没有任何像素变化；桌面上鼠标按下时 hovered 早已为 true，按下前后画面也完全一致。同一个 App 里两套规则：DetailScreen.kt:698-701 的 EpisodeRow、SettingsScreen.kt:653、IdentifyDialog.kt:259 用的都是不传 indication 的重载，拿到 MaterialExpressiveTheme 提供的 LocalIndication = ripple，按下有状态层。附带一处：Components.kt:192-193 的 tonalElevation 是空转——ColorSchemeKt.applyTonalElevation 只在 backgroundColor == colorScheme.surface 时换色，这里传的是 surfaceContainerHigh（Theme.kt:38 surface=#0E0D14 ≠ Theme.kt:55 #24212D），Surface 又没传 shadowElevation（默认 0dp），所以既无色调变化也无投影。

> **改法**：删掉 Components.kt:180 的 `indication = null`（或改成 `ripple()`），interactionSource 继续复用同一个，hover 缩放与遮罩不受影响、涟漪画在海报之上即可。若不想要方形涟漪，至少用同一 interactionSource 的 `collectIsPressedAsState()` 给一个 pressed → scale 0.97 的按压反馈。顺手把 :193 的 tonalElevation 换成 `shadowElevation`。Components.kt:386 的 LinkText 同理。

### 详情页没有加载态：第一次进是整屏空白，之后进会先把上一个条目的整页端出来，连播放按钮都是活的

**严重** · 两端 · `DetailScreen.kt:89、DetailScreen.kt:359-361、DetailScreen.kt:384、AppState.kt:36、AppState.kt:115、AppState.kt:124-127、AppState.kt:143、AppState.kt:268-286、App.kt:160-163`

DetailScreen.kt:89 `val item = state.detailItem ?: return`——数据未到就整屏不发射任何内容。AppState.kt:268-286 的 loadDetail 里 detailItem 只在 :272 查询成功后被覆盖，进入新页面前没有任何清空；导航路径 AppState.kt:124-127 navigate → backStack.add → onEnter → :143 `is Screen.Detail -> loadDetail(screen.itemId)`，而 App.kt:160-163 的 AnimatedContent 立刻切到 DetailScreen 分支，DetailScreen 读的是 state 而不是 screen.itemId，于是整个转场期间渲染的是上一条目的 detailItem/detailChildren/detailEpisodes：A 的头图、A 的简介、A 的分集列表，播放按钮（DetailScreen.kt:359-361/384）上写的是 A 的「继续 12:34」且可点，按下去播的是 A。:273-275 查 children、:279-282 查 episodes 是串行的，所以还存在「B 的标题 + A 的分集列表」这个中间态。Detail→Detail（点「相关影片」或本季某一集）更荒谬：AppState.kt:36 `data class Detail(val itemId: String)` 会触发 AnimatedContent，进出两屏共读同一份旧数据，0.33 秒的转场演的是「什么都没变」，真正的内容替换反而没有任何动画。而 AppState.kt:115 的 detailLoading 全仓只有 :269/:284 两处写、零处读，加载态的开关早就写好却没接上。

> **改法**：onEnter(Screen.Detail) 里先 `detailItem = null; detailChildren = emptyList(); detailEpisodes = emptyList()`（或把 screen.itemId 传给 DetailScreen，`detailItem?.id != screen.itemId` 一律按未加载处理），再把 DetailScreen.kt:89 的 `?: return` 换成 `?: run { LoadingPane(); return }`，把已有的 detailLoading 接上；数据未就绪时禁用播放按钮。

### 三个列表屏都没有错误态：媒体库读失败后永远转圈，2.6 秒后连错误都没了，也没有重试

**严重** · 两端 · `AppState.kt:235-261、AppState.kt:197-207、AppState.kt:272、AppState.kt:293-296、LibraryScreen.kt:43、LibraryScreen.kt:47、LibraryScreen.kt:71、Components.kt:101-115、Components.kt:327-346、App.kt:123-128`

AppState.kt:235-261 的 loadLibrary：:236 `libraryLoading = true`，:237 `if (libraryItemsOf != libraryId) libraryItemsOf = null`（进新库必然置 null），:257 `libraryItemsOf = libraryId` 是 try 块最后一行、异常时不执行，:258-260 的 finally 只复位 libraryLoading。于是 LibraryScreen.kt:43 的 `val loaded = state.libraryItemsOf == libraryId` 恒为 false → LibraryScreen.kt:71 `!loaded -> LoadingPane()`，而 Components.kt:101-115 的 LoadingPane 只有 150ms 延迟，没有超时也没有终止条件，确实会永远转下去；LibraryScreen.kt:47 的 `if (loaded)` 还让页头连「N 项」都不显示。错误的唯一出口是 AppState.kt:197-207 的 run() → toast，App.kt:123-128 在 2600ms 后清掉，此后屏幕上再无任何说明、也没有任何可点的东西。同构失败还有两处：AppState.kt:272（详情停留在旧条目）、:293-296（搜索保留旧结果）。Components.kt:327-346 的 EmptyState 没有错误/重试形态。

> **改法**：给 AppState 加 `libraryError: String?`（详情、搜索同理），run() 的 catch 里除了 toast 再写进这个字段；LibraryScreen 的 when 里加一个错误分支，复用 EmptyState 并传一个「重试」按钮回调 `state.loadLibrary(libraryId)`。

### 搜索无加载态：新一轮搜索的首个字符必然先闪 ≥250ms 的「没有匹配的结果」，且「正在查」与「查完为空」长得一模一样

**严重** · 两端 · `SearchScreen.kt:40-44、SearchScreen.kt:47-55、SearchScreen.kt:57-61`

SearchScreen.kt:40-44 `LaunchedEffect(query) { state.searchQuery = query; delay(250); state.search(query) }`——第 41 行立刻把 state.searchQuery 写成新值并触发重组，查询要等 250ms 去抖之后才发出。SearchScreen.kt:57-61 的分支只判 `state.searchResults.isEmpty()` 与 `state.searchQuery.isBlank()`，所以「已输入但还没查」这段窗口必然渲染 EmptyState("没有匹配的结果" / "换个关键词试试。")：每一轮搜索的首个字符、以及此后每一次结果暂时为空，这句假话都会再闪一次。全屏没有任何进行中指示——SearchScreen.kt:47-55 的 OutlinedTextField 只有 leadingIcon，没有 trailingIcon 进度圈，整个文件不引用 LoadingPane。反向空窗同样存在：清空输入后 searchQuery 立刻 blank 而 searchResults 仍是旧的，旧结果继续挂 250ms。

> **改法**：AppState 加一个 `searching: Boolean`（search() 进出时置位），SearchScreen 改成三态：blank → 引导态；searching → 搜索框内 trailingIcon 进度圈并保留旧结果；否则才判空显示「没有匹配的结果」。清空输入时同步立刻清 searchResults。

### 全应用唯一的页面转场是无方向的交叉溶解：进入与返回播放同一套动画，两屏叠加期约 110ms

**严重** · 两端 · `App.kt:160-164、Theme.kt:145、Theme.kt:6`

App.kt:160-164 `AnimatedContent(targetState = state.current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen")`——transitionSpec 是不读 initialState/targetState 的常量 lambda，Home→Detail 与 Detail→Home 生成同一个 ContentTransform，深链跳三层再退回来完全靠记忆。`fadeIn()` 的默认 spec 是 `spring(dampingRatio = 1.0, stiffness = 400f)`，ω=20 rad/s，残差 (1+ωt)e^(−ωt) 落到 0.01 需 t≈0.332s；50/50 交点在 t≈0.084s，两层同时高于 20% 的区间是 t∈[0.041s, 0.150s]，即约 110ms 的可感知双重曝光——首页海报网格与详情页头图互相穿透、两层文字重影。M3 的 fade through 规定两段不重叠（出屏 0–90ms 淡出、入屏 90–300ms 淡入并从 92% 缩放到 100%），父子层级则用 shared axis 或 container transform。Theme.kt:145 已经声明了 `MotionScheme.expressive()`，但全仓 grep motionScheme 只有 Theme.kt:6/145 两处，没有任何自定义动效消费它。

> **改法**：transitionSpec 改成方向感知的 fade through：比较 backStack 是增是减，前进 `fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.92f) togetherWith fadeOut(tween(90))`，后退把 scale 换成 1.08f 起。最低成本版本是只把 fadeOut 换成 `tween(90)`，让它先走完再淡入，双重曝光即消失。

### 悬停反馈只做了一半：遮罩与播放键一帧硬跳无淡入，tonalElevation 因色角色选错静默失效所以卡片不「抬起」，指针也仍是箭头

**打磨** · 桌面 · `Components.kt:157、Components.kt:134-135、Components.kt:189-193、Components.kt:223-241、Components.kt:385、Theme.kt:38、Theme.kt:55、Theme.kt:145`

Components.kt:157 的 `animateFloatAsState(if (hovered) 1.04f else 1f)` 用默认 spring（dampingRatio 1.0、stiffness 1500），ω≈38.73，0.04·(1+ωt)e^(−ωt)=0.01 → t≈69ms，这段位移是真实生效的；但 Components.kt:223-241 的渐变遮罩与圆形播放键是裸 `if (hovered) { … }`，没有 AnimatedVisibility，一帧出一帧没。Components.kt:189-193 传 `color = surfaceContainerHigh` 且 `tonalElevation = if (hovered) 6.dp else 0.dp`，而 ColorSchemeKt.applyTonalElevation 只在 `backgroundColor == colorScheme.surface` 时才换色（Theme.kt:38 surface=#0E0D14 ≠ Theme.kt:55 surfaceContainerHigh=#24212D），Surface 又未传 shadowElevation（默认 0dp）——6dp 完全无效，既无色调变化也无投影，与 Components.kt:134-135 注释里写的 "Hovering lifts the card" 不符。指针也没做：全文只有 Components.kt:385 的 LinkText 设了 `pointerHoverIcon(PointerIcon.Hand)`，PosterCard 未设，鼠标划过主控件全程是箭头。Theme.kt:145 已声明 MotionScheme.expressive()，却没有任何自定义动效读它。

> **改法**：遮罩与播放键包一层 `AnimatedVisibility(hovered, fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()), fadeOut(...))`；把 `tonalElevation` 换成 `shadowElevation = if (hovered) 6.dp else 0.dp`（或 hover 时把 color 切到 surfaceContainerHighest，用色阶表达抬升）；卡片加 `Modifier.pointerHoverIcon(PointerIcon.Hand)`。

### ready 在任何数据到位前就翻转：冷启动会闪过一次「还没有媒体库」空态，且四段状态之间全是硬切

**打磨** · 两端 · `AppState.kt:173-174、AppState.kt:179-183、AppState.kt:197-207、AppState.kt:75、AppState.kt:94-95、HomeScreen.kt:56-64、App.kt:80、Components.kt:101-115`

AppState.kt:173-174 的 open() 先 `opened = Opened(context)`（使 AppState.kt:75 的 ready 变 true）再调 start()，而 AppState.kt:179-183 的 start() 是 `run {}`（即 AppState.kt:197-207 的 scope.launch）另起协程，`library.info()` 与 `library.libraries()` 都是 suspend 的 io{}，从 UI 调度器切走必然挂起。所以 ready 翻转的那一帧 libraries 仍是 AppState.kt:94 的 emptyList、home 仍是 :95 的空 HomeData，HomeScreen.kt:56-64 的 `if (state.libraries.isEmpty() && home.latest.isEmpty())` 成立，整屏渲染「还没有媒体库 / 前往设置」——在告诉一个库配好了的用户「你什么都没有」。因为 ready 翻转发生在 createCoreContext 之后、后续是已开库的进程内 SQLite 读，这个空态实际只存在 2–4 帧（60Hz 下约 30–60ms）的闪烁。App.kt:80 `if (state.ready) Library(...) else Opening(...)` 是裸 if，全仓无 Crossfade/AnimatedContent 覆盖此处，「空白 → 转圈 → 空态 → 首页」四段之间全是硬切。

> **改法**：把 App.kt:80 换成 `Crossfade(state.ready)`；给 HomeScreen 的空态加一个「已加载过」的前提位（或在 open() 里 `opened = Opened(context)` 之前先同步取一次 libraries），让空态以「查完且为空」为前提而不是拿初始值当结论。

### 备份导出全程零进度：写好的进度条永远不显示，按钮还能反复点，而并排的「导入…」全程有进度条

**打磨** · 两端 · `SettingsScreen.kt:483、SettingsScreen.kt:526-545、SettingsScreen.kt:547-575、SettingsScreen.kt:577-580、SettingsScreen.kt:440-443、Backup.kt:64、Backup.kt:102-110、Platform.desktop.kt:172-178`

SettingsScreen.kt:483 `var busy by remember { mutableStateOf(false) }`，:577-580 `if (busy) { Spacer(8.dp); LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }` 写好了却用不上：导出分支 SettingsScreen.kt:526-545 的 onClick 里只有 `error = null; message = null`，通读到 :543 没有任何一处 `busy = true`，因此 :527 的 `enabled = !busy` 恒为 true（可以反复点、反复弹保存对话框、反复跑导出），进度条恒不显示。并排的导入分支 :547-575 则在 :557 置位、:570 finally 复位，同屏的同步区 :440-443 也用同一套 busy 模式——三条路径两种行为。这确实是长耗时路径：Backup.kt:64 的 backupChunks 返回惰性 Sequence、:102-110 分页拉取，真正的读库与写盘在 Platform.desktop.kt:172-178 的 `withContext(Dispatchers.IO)` 里、发生在保存对话框关闭之后。

> **改法**：把导出的 onClick 包成和导入一致的形状：`busy = true; try { … } catch (e) { error = … } finally { busy = false }`。顺便给 backupChunks 加 try/catch——现在只有 saveTextFile 被 runCatching 包着。

### 扫描进度用 determinate 的 LinearWavyProgressIndicator，但 queued/listing 阶段总数为 0，进度条静止在 0%

**打磨** · 两端 · `SettingsScreen.kt:186-189、SettingsScreen.kt:171、SettingsScreen.kt:442、SettingsScreen.kt:579、HomeScreen.kt:128-133、ScanService.kt:24-26、ScanService.kt:54-63、Scanner.kt:42、Scanner.kt:49、Models.kt:395`

SettingsScreen.kt:186-189 与 HomeScreen.kt:128-133 都用带 progress lambda 的 determinate 重载，total=0 时走 else 给 0f。ScanService.kt:54-63 的 initial 是 `phase="queued", current=0, total=0`，Models.kt:395 的 running 默认 true，SettingsScreen.kt:171 的 running 判定立刻为真；Scanner.kt:42 报 `listing 0/1`，直到 Scanner.kt:49/53 的 scanning 阶段才有真分母。determinate 的 wavy indicator 在 progress=0 时振幅为 0、没有活动段，屏幕上就是一条灰色轨道加右端一个停止点，看不出程序是在干活还是卡死；ScanService.kt:24-26 是单线程 executor，后一个库整段排队时会一直停在这个形态。讽刺的是同一个文件 SettingsScreen.kt:442 与 :579 已经在用无参的 indeterminate 重载。

> **改法**：两处都按总数是否已知分叉：`if (status.total > 0) LinearWavyProgressIndicator(progress = { status.current.toFloat() / status.total }, …) else LinearWavyProgressIndicator(modifier)`。顺手把 Scanner.kt:42 的 total=1 改成 0，让「未知」这个状态在数据层就说得清。

### 首页「正在扫描」排在约 2000dp 内容之后的列表末尾，顶部/导航栏没有任何后台任务提示

**打磨** · 两端 · `HomeScreen.kt:115-138、HomeScreen.kt:107-113、HomeScreen.kt:149-153、HomeScreen.kt:160-166、AppState.kt:359-369`

HomeScreen.kt:115-138 的 running 分支是 LazyColumn 的最后一个 item，排在 HomeScreen.kt:107-113 的「每库一行未观看」之后。高度复算：HomeScreen.kt:149-153 的 hero 因子项 fillMaxSize（:160/163/166）恒取上界，2 个库时全页约 1990dp、可视区约 700dp——在设置里点了「重新刮削全部」再回首页想看进度，得越过大图、媒体库快捷卡、「继续观看」「接下来」「最近添加」三行以及每库一行，一路滚到底。首页顶部、导航栏、返回键区域没有任何一处提示后台正在跑。AppState.kt:359-369 的轮询是 `delay(2000)`，扫描进行中也是 2 秒一跳。（该分区标题多缩进 20dp 的问题归入「设置页/首页分节标题三套实现」一条。）

> **改法**：把 running 分支移到 LazyColumn 的第一个 item（hero 之上）或做成贴顶的常驻条；轮询间隔在有扫描运行时收紧到 500–1000ms。

## 配色与对比度

### 系统栏图标明暗只认系统夜间模式、界面底色只认应用内开关：开箱默认组合（系统浅色 + App 深色）下状态栏图标 1.09:1，切主题时系统栏纹丝不动

**阻断** · Android · `MainActivity.kt:23、AppState.kt:97、AppState.kt:185-188、Platform.android.kt:97、SettingsScreen.kt:295、App.kt:78-79、themes.xml:4-6、libs.versions.toml:19`

MainActivity.kt:23 是无参 `enableEdgeToEdge()`。androidx.activity 1.13.0（libs.versions.toml:19）的 `EdgeToEdge.enable$default` 默认 statusBarStyle = `SystemBarStyle.auto(0,0)`，其 detectDarkMode 字节码就是 `configuration.uiMode & 48 == 32`——只读系统夜间模式，EdgeToEdgeApi29/Api35.setUp 据此调 `setAppearanceLightStatusBars(!isDark)`。而界面底色是另一条线：AppState.kt:97 `darkTheme = settings.getString(KEY_THEME) != "light"`，Platform.android.kt:97 未设置时返回 null ⇒ 首次安装默认深色，只被 SettingsScreen.kt:295 改；App.kt:78-79 的 Surface 用 colorScheme.background 铺满。两个必现场景：①手机保持系统浅色（Android 出厂默认），装上就是黑色系统图标压在 #0E0D14 上，L=0.004315 → (0.004315+0.05)/0.05 = 1.086:1，时钟、电量、信号全部不可辨；②在设置里关掉深色主题，界面立刻变 #FDF8FF（L=0.95238），系统图标仍是白色，1.05:1。全仓 grep `WindowCompat|WindowInsetsController|setAppearanceLight` 零命中，应用侧从未设置过系统栏外观。WCAG 1.4.11 对非文本内容要求 3:1。附带：themes.xml:6 的 windowBackground 硬编码 #0E0D14，浅色主题用户冷启动有一次 18.5:1 的黑→白闪。

> **改法**：把判定绑到应用主题：`enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { state.darkTheme })`，并在 Compose 里加 `DisposableEffect(state.darkTheme) { WindowInsetsControllerCompat(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }` 让它跟着开关走。themes.xml 拆成 values/ 与 values-night/ 两份 windowBackground，或直接用 `?android:attr/colorBackground`。

### 设置页库卡与详情页分集卡用色阶最低的 surfaceContainerLow，压在 background 上只有 1.05:1，18dp 圆角与卡片边界等于白画

**严重** · 两端 · `SettingsScreen.kt:114-117、DetailScreen.kt:691-702、HomeScreen.kt:227-232、PlayerScreen.kt:72-76、App.kt:78-79、Theme.kt:38、Theme.kt:53-57`

SettingsScreen.kt:114-117 与 DetailScreen.kt:691-702 都是 `Card(colors = cardColors(containerColor = surfaceContainerLow))`，父层是 App.kt:78-79 的 `Surface(color = background)`。复算：深色 background #0E0D14 L=0.004315 vs surfaceContainerLow #15131C L=0.007082 → 1.051:1；浅色 #FDF8FF vs #F7F2FA → 1.053:1。Card 默认 shadowElevation=0（FilledCardTokens.ContainerElevation = Level0），tonalElevation 又因 containerColor≠surface 而失效，所以 18dp 圆角完全看不出来，卡与卡只靠 5dp 外边距硬分——设置页看起来是一片纯黑上散着几组文字，分集列表读起来是一堆挤在一起的文本块。M3 的 FilledCardTokens.ContainerColor 是 SurfaceContainerHighest（本主题下 1.40:1 / 1.24:1），M3 基线深色同位置是 #141218 vs #36343B = 1.51:1。同一份媒体库列表在首页却是 surfaceContainerHigh 的卡片（HomeScreen.kt:227-232，1.225:1，明显能看见），于是「媒体库」在两屏里是两种视觉重量。

> **改法**：定一条容器阶梯并全局照做：页面上的内容卡一律 surfaceContainer 起步（1.10:1 仍偏弱，可把 background 压到 #0B0A10 或把 surfaceContainer 提到 #201D2A 做到 ≥1.3:1），强调项用 surfaceContainerHigh，弹层/面板用 surfaceContainerHighest；分集行如果想保持轻量就改成无卡片 + HorizontalDivider，而不是一张看不见的卡片。

### 观看进度条用 secondary，而该角色在两套主题里明暗相反，EpisodeRow 的轨道还是全透明直接压在剧照上

**打磨** · 两端 · `Components.kt:243-256、DetailScreen.kt:720-730、DetailScreen.kt:726、Theme.kt:28、Theme.kt:79、Theme.kt:64、Theme.kt:115`

Components.kt:243-256（PosterCard，4dp，`trackColor = scrim.copy(alpha = 0.4f)`）与 DetailScreen.kt:720-730（EpisodeRow，3dp，`trackColor = Color.Transparent`）都用 `colorScheme.secondary`。Theme.kt:28 深色 secondary = #FFC98A（L=0.6487），Theme.kt:79 浅色 = #8A5000（L=0.1114）——同一语义角色在两套主题里明暗相反。EpisodeRow 没有轨道，进度条直接画在剧照像素上：#FFC98A 对亮底剧照 #F0F0F0 是 1.32:1、对 #B0B0B0 是 1.44:1；浅色主题的 #8A5000 对中灰 #404040 是 1.59:1。Theme.kt:64/115 已经把两套都亮的 secondaryFixedDim（#FFC98A / #FFB35C）填好了却没被使用。PosterCard 那一处有 40% 黑轨道兜底，且 EpisodeRow 同行还有 DetailScreen.kt:751-761 的「看到 12:34」文字与 :772-781 的状态图标，所以进度条并非唯一线索——严重度限于 minor。

> **改法**：两处的 `color` 从 `secondary` 换成 `secondaryFixedDim`（一行改动，两套主题都变成亮暖橙）；DetailScreen.kt:726 的 trackColor 补上和 PosterCard 一致的 `scrim.copy(alpha = 0.4f)`，让 fill 与 track 的对比不再取决于剧照。

## 排版与信息层级

### labelSmall（11sp）被当正文用了 24 处：桌面「内置播放器」设置里 5 段成句中文说明在 100% 缩放下是 11 个物理像素

**打磨** · 桌面 · `PlatformPlayerSettings.desktop.kt:95、PlatformPlayerSettings.desktop.kt:121-127、PlatformPlayerSettings.desktop.kt:198-215、PlatformPlayerSettings.desktop.kt:245-271、PlatformPlayerSettings.desktop.kt:209、PlatformPlayerSettings.desktop.kt:244、Main.kt:25`

`grep -o 'MaterialTheme\.typography\.[a-zA-Z]*'` 全仓 100 处调用点里 bodySmall 38 + labelSmall 24 = 62 处，labelSmall 从 TypeScaleTokens 确认是 11sp / FontWeight.Medium / +0.5sp tracking。PlatformPlayerSettings.desktop.kt 的说明文字在 :95/:125/:186/:201/:213/:248/:269 共 7 处全是 labelSmall，其中 :122-124（显卡说明 67 汉字）、:199-200、:211-212、:246-247、:263-265（前置条件 72 汉字）是完整中文长句。桌面 density 取自 GraphicsConfiguration 的缩放比，Windows 100% 缩放下 density=1.0 且没有系统字体缩放，所以 11sp = 11 物理像素，低于 Windows 中文界面 9pt≈12px 的基准（微软雅黑的 hinting 目标是 12px 以上）。同一块里 :209/:244 的开关标题走的是默认 bodyLarge 16sp，16sp 标题配 11sp 说明，跨度过大。M3 的 label 系列定位是「部件内部的短文本」，正文与辅助说明属于 body 系列。

> **改法**：把 labelSmall 的用法收回到按钮/标签内的短词；所有成句说明至少用 bodySmall，「必须读懂才能配对」的播放器设置说明用 bodyMedium。若要保持 token 语义不动，也可以在传给主题的 typography 副本里把 labelSmall 提到 12sp、bodySmall 提到 13sp。

### 主题从不传 typography：M3 的拉丁基线行高压过中文字体自然行高 8%，字距也全程带 tracking

**打磨** · 两端 · `Theme.kt:135-149、DetailScreen.kt:744-746、DetailScreen.kt:350-351`

Theme.kt:135-149 的 DaViewTheme 只向 MaterialExpressiveTheme 传了 colorScheme/shapes/motionScheme，typography 缺省，拿到的是 tokens 版 Typography()：bodySmall 12/16/+0.4、bodyMedium 14/20/+0.2、bodyLarge 16/24/+0.5、titleLarge 22/28/0、headlineMedium 28/36/0、labelSmall 11/16/+0.5。TypographyTokensKt 的 DefaultLineHeightStyle = (Alignment.Center, Trim.None) 会把行基线距强制成 token 值，而 Noto Sans CJK 的自然行高是 1.448em——12sp 时应为 17.4sp，被压到 16sp（−8%），汉字墨迹间距只剩 4dp。落点是 DetailScreen.kt:744-746 的分集 2 行简介这类地方。W3C《中文排版需求》建议正文行距为字号的 50%–100%（即 1.5–2.0em），而汉字之间加 tracking 在中文排版里是刻意的「疏排」而非默认。同页 DetailScreen.kt:350-351 的 5 行简介用 bodyMedium 14/20，与自然行高 20.3sp 基本相等，不在此列。

> **改法**：在 Theme.kt 里构造一份 typography 传给 MaterialExpressiveTheme：用各 style 的 copy() 把 lineHeight 统一提到 fontSize 的 1.5–1.6 倍（bodySmall 12→18、bodyMedium 14→22、titleLarge 22→34、headlineMedium 28→44），letterSpacing 归零。改一处全 App 生效，可以和下一条的 localeList 放在同一份 copy 里一次做完。

### 从未设置 TextStyle.localeList：Android 系统语言非中文时，中日共用码位按日文字形回退

**打磨** · Android · `Theme.kt:135-149、themes.xml、AndroidManifest.xml`

整仓库（composeApp/src、core/src、shared/src）grep `localeList|LocaleList|FontFamily|fontFamily|typeface` 零命中；Theme.kt:135-149 未传 typography，而 TypefaceTokens 的 Brand 与 Plain 都是 `FontFamily.SansSerif`，所有 token 的汉字都交给系统 fallback；androidMain/res/values/themes.xml 只有三项颜色，没有 fontFamily；AndroidManifest 也未声明 localeConfig。界面文案 100% 是硬编码中文，但 Android 在 locale 列表不含中文时按 fonts.xml 顺序取第一个覆盖该码位的 CJK 字体，落到 Noto Sans CJK 的日文实例——「直、骨、令、每、兑、画」等上千个码位会用日文写法画出来，与同屏其它中文软件不一致。中文系统上不出现；桌面端 Skia/DirectWrite 的实际回退目标未经验证，不计入。

> **改法**：在 Theme.kt 传给 MaterialExpressiveTheme 的 typography 副本里，对每个 style 加 `localeList = LocaleList("zh-Hans")`。

### 分集行的观看状态与文件路径同字号同字重同色紧邻，中间连一个 Spacer 都没有，视觉上黏成一段

**打磨** · 两端 · `DetailScreen.kt:735-739、DetailScreen.kt:750、DetailScreen.kt:751-761、DetailScreen.kt:762-769`

DetailScreen.kt:751-761 的状态行（watchedLabel · 时长 · 体积 · 字幕数）与 :762-769 的相对路径（`pathUnder(...)`，maxLines=1）style、fontWeight、color 三项完全相同（都是 labelSmall + onSurfaceVariant），两者之间没有任何 Spacer 或分隔——:750 的 `Spacer(Modifier.height(4.dp))` 位于状态行之前。于是「我上次看到哪一集、停在几分几秒」这条要用来做决定的信息，和一条纯技术性的相对路径挤成同一块灰字。层级应当按「用来做决定的程度」排，而不是按技术性排。

> **改法**：把 :751-761 的状态行提到 bodySmall 并挪到 :744 的简介之前（状态紧跟标题），:762-769 的路径保持 labelSmall 但换成 outline 色或只在悬停/展开时出现。

### 网格卡宽度与 maxLines=1 都是常量，系统字号 200% 时卡片标题退化到约 4 个汉字加省略号

**打磨** · Android · `LibraryScreen.kt:78-91、SearchScreen.kt:63-75、Components.kt:169、Components.kt:274-287`

LibraryScreen.kt:78-91 是 `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 150.dp), contentPadding = PaddingValues(20.dp), horizontalArrangement = spacedBy(14.dp))` 且卡片显式 `width = 150.dp`；SearchScreen.kt:63-75 是同一套参数；Components.kt:169 `.width(width)`、:274-280 标题 bodyMedium + maxLines=1 + Ellipsis。全树无 Density/fontScale 覆盖。复算 360dp 屏：320+14 除以 150+14 = 2.04 → 2 列，槽宽 153dp 而卡片只占 150dp（每列左对齐还留 3dp 死白），文字可用宽 150dp；bodyMedium 14sp 在 2.0x 下 ≥28dp，150/28 = 5.36 → 含省略号 5 个字符、可读 4 字，「复仇者联盟」「复仇者联盟2」「复仇者联盟3」在标题行上无法区分。缓解在于 Components.kt:281-287 的副标题（年份 / N 季 / S01E05）在 12sp→24dp 下仍能露出年份与集号，所以续集并非完全不可分辨。

> **改法**：两步：把 Components.kt:278 的 `maxLines = 1` 改成 2（副标题保持 1 行，卡片高度本来就是 Column 自适应）；让网格列宽跟着字号走 —— `GridCells.Adaptive(minSize = 150.dp * LocalDensity.current.fontScale.coerceAtMost(1.6f))`，并且不再给 PosterCard 传固定 width，让它 fillMaxWidth 吃满 cell。

## Android 平台集成

### 开了 edge-to-edge 但全仓库零 inset 处理：内容从 y=8dp 起穿过状态栏，二级页返回箭头在 40dp 状态栏机型只露 4dp、48dp 机型完全消失

**阻断** · Android · `MainActivity.kt:23、libs.versions.toml:25、App.kt:79、App.kt:130-155、App.kt:177-181、App.kt:182-189、HomeScreen.kt:66-68、HomeScreen.kt:165-173、AppState.kt:91`

MainActivity.kt:23 `enableEdgeToEdge()` + gradle/libs.versions.toml:25 targetSdk=37（Android 15+ 强制 edge-to-edge，Android 16 起连 windowOptOutEdgeToEdgeEnforcement 都不再生效）。composeApp/ 与 core/ 全量 grep `WindowInsets|statusBars|systemBars|safeDrawing|statusBarsPadding|navigationBarsPadding|imePadding|displayCutout|contentWindowInsets|Scaffold` 零命中——连 Scaffold 都没有。链路 App.kt:79 裸 Surface(fillMaxSize) → :130 BoxWithConstraints → :132 Box → :136/142 Column → :137/143 TopRow 全程无 inset 补偿，内容起点即窗口 y=0：首页 TopRow 退化成 8dp Spacer（App.kt:177-181，AppState.kt:91 初始栈深 1），HomeScreen.kt:66-68 的 contentPadding 顶部为 0，于是 22sp 的分区标题和海报卡直接从状态栏时钟底下钻过去，HomeScreen.kt:165-173 的渐变顶端是 Transparent，没有任何遮罩。二级页更硬：App.kt:182-189 的 IconButton 布局高 48dp、24dp 图标居中占 y=20..44dp，状态栏 40dp 时只露 4dp、48dp 时整个箭头看不见；而且状态栏窗口吞掉触摸，33dp 状态栏下可点高度只剩 11dp，远低于 48dp 最小触摸目标，这条竖直区间还是下拉通知栏的手势区。

> **改法**：最小改动：给 App.kt:136 与 App.kt:142 两个 Column 加 `Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))`（TopRow 的 Spacer 分支同样要套）。想保留海报滚到状态栏下的沉浸效果，就把 HomeScreen.kt:67 的 contentPadding 顶部改成 `WindowInsets.statusBars.asPaddingValues().calculateTopPadding()` 并在最顶部叠一条状态栏高度的渐变遮罩。更规范的做法是把 App.kt:130-155 换成 `Scaffold(bottomBar = { BottomNavigation(state) })`，让 contentWindowInsets 统一处理。

### edge-to-edge 让 adjustResize 失效而应用未接手 IME inset：设置页下半屏的密码 / API Key / 元数据语言输入框会被软键盘盖住

**严重** · Android · `AndroidManifest.xml:24、MainActivity.kt:23、SettingsScreen.kt:78-81、SettingsScreen.kt:209-218、SettingsScreen.kt:246-264、App.kt:136、App.kt:142`

AndroidManifest.xml:24 声明了 `windowSoftInputMode="adjustResize"`，但 MainActivity.kt:23 的 enableEdgeToEdge 内部第一步就是 `WindowCompat.setDecorFitsSystemWindows(window, false)`（EdgeToEdgeApi29/Api35 的 setUp 第 25-27 条），而 SOFT_INPUT_ADJUST_RESIZE 自 API 30 起在 decorFitsSystemWindows=false 时不再让窗口变矮。应用侧 grep `imePadding|WindowInsets.ime` 零命中，SettingsScreen.kt:78-81 的 LazyColumn 也没有任何 ime 处理。几何复算（360×800dp）：TopRow 56dp + NavigationBar 104dp ⇒ 视口 [56,696] 高 640dp，Gboard 含候选栏 260-280dp 占 [520..540,800]，与视口交集 156-176dp，即视口的 24%–27%——WebDAV 密码框（SettingsScreen.kt:213-218）和刮削源那四个框（:246/252/258/264）一旦停在下半屏，点进去就被盖住，在看不见输入框和已输入内容的情况下敲网盘密码。缓解：IME 弹出后 LazyColumn 仍可手动滚动把焦点框推到键盘上方，是别扭而非完全盲输。

> **改法**：给 App.kt:142（以及 :136）的 Column 加 `Modifier.imePadding()`，一处解决全部输入场景；或只改设置页——把 SettingsScreen.kt:80 的 contentPadding 底部改成 `48.dp + WindowInsets.ime.asPaddingValues().calculateBottomPadding()`。

### 应用根本没有图标资源：桌面、最近任务、Android 12+ 冷启动闪屏全是系统默认的通用图标

**严重** · Android · `AndroidManifest.xml:13-19、themes.xml:3、themes.xml:6、composeApp/build.gradle.kts:89-110`

三重交叉确认：`find composeApp/src/androidMain -type f` 只有 10 个文件，res 下仅 values/themes.xml，没有任何 mipmap/drawable/values-night；AndroidManifest.xml:13-19 的 `<application>` 只有 name/allowBackup/label/supportsRtl/usesCleartextTraffic/theme，确无 `android:icon`、`android:roundIcon`；全仓库 grep `android:icon|ic_launcher|mipmap|adaptive-icon|monochrome|composeResources` 零命中，composeApp/build.gradle.kts:89-110 的 android{} 块也无任何图标配置（:80 虽引了 compose.components.resources，但没有 composeResources 目录，是空引用）。themes.xml:3 的父主题是框架深色 Theme.Material.NoActionBar，themes.xml:6 windowBackground=#0E0D14，且无 windowSplashScreen* ⇒ Android 12+ 冷启动闪屏 = #0E0D14 + 默认图标；浅色主题用户还会额外看到一次 18.46:1 的黑→白亮度跳变。Android 8.0 起 adaptive icon 是硬要求，Android 13 起建议补 monochrome 层。（桌面端同根因的窗口/安装包图标缺失见「桌面平台惯例」一条。）

> **改法**：加一套 `res/mipmap-anydpi-v26/ic_launcher.xml`（foreground / background / monochrome）与各密度位图，manifest 的 `<application>` 补 `android:icon` 与 `android:roundIcon`；themes.xml 拆成 values/ 与 values-night/ 两份 windowBackground，并显式设 `android:windowSplashScreenBackground` 让闪屏与实际主题一致。

### 横向片架铺到屏幕左右边缘，与手势导航的返回热区抢同一条窄带，且未声明手势排除区

**打磨** · Android · `Components.kt:309-314、HomeScreen.kt:222-225、DetailScreen.kt:107-119、DetailScreen.kt:605-621、DetailScreen.kt:630-633、MainActivity.kt:23`

Components.kt:309-314 的 MediaRow：Column 是 fillMaxWidth，LazyRow 无任何宽度约束、contentPadding 只有 horizontal=20.dp（contentPadding 在视口内侧，不缩小可拖拽区），滚动手势区从 x=0 一直到屏幕右缘；HomeScreen.kt:222-225、DetailScreen.kt:107-119 / 605-621 / 630-633 同构。MainActivity.kt:23 的 enableEdgeToEdge 让窗口铺满，而 composeApp/src 全目录 grep `systemGestureExclusion|systemGestures` 零命中。手势导航的手机上，起手点落在最左/最右约 20dp 内时系统吃掉这一划当成返回，行不动。未滚动时那条带是 contentPadding 空白，冲突主要出现在行滚动后卡片进入边缘 20dp 之后；绕开成本只是把起手点右移二三十 dp，所以是 minor。

> **改法**：在 androidMain 里给 MediaRow 的 LazyRow 加一层 expect/actual 的 `Modifier.systemGestureExclusion()`（desktop 侧返回 Modifier），改动只在 Components.kt:311 一行；或把行的 contentPadding 提到 24dp 以上并让行本身留出左右安全边距。

### 扫描通知没有品牌识别：small icon 用框架的 stat_notify_sync、取消动作用 Holo 时代的 ic_menu_close_clear_cancel，且从未 setColor

**打磨** · Android · `ScanForegroundService.kt:95-102、ScanForegroundService.kt:98、ScanForegroundService.kt:116`

ScanForegroundService.kt:98 `setSmallIcon(android.R.drawable.stat_notify_sync)` 用的是框架资产，和账号同步、系统备份的通知长得一模一样；:116 的取消动作用 `android.R.drawable.ic_menu_close_clear_cancel`（Holo 时代资产）；:95-102 的 builder 链只有 setContentTitle/setContentText/setSmallIcon/setContentIntent/setOngoing/setOnlyAlertOnce/setPriority，确无 `setColor`，应用名旁边没有品牌色。Android 通知规范要求 small icon 是应用自己的白色透明底单色轮廓（系统按 alpha 遮罩着色），setColor 是通知的品牌识别位；框架 drawable 不属于稳定的公开 UI 表面，各版本/各 OEM 长相不一致。配合上一条「仓库零图标资源」，现在连能拿来当 small icon 的单色 logo 都不存在。

> **改法**：加一个 `res/drawable/ic_notification.xml`（纯白轮廓矢量）替换 ScanForegroundService.kt:98；在 :95 的 builder 上补 `.setColor(0xFF6C4BF6.toInt())`（与 Theme.kt 的 Violet 一致）和 `.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)`。

### 通知权限弹窗在 setContent 之前发出，新用户看到的第一屏是盖在空白加载页上的系统权限对话框

**打磨** · Android · `MainActivity.kt:22-24、MainActivity.kt:27-33、MainActivity.kt:34-41、App.kt:76、App.kt:80、App.kt:87-102、AppState.kt:75`

MainActivity.kt:22-24 的顺序是 `requestNotificationPermission(); enableEdgeToEdge(); setContent { App() }`，:34-41 在未授予时直接 `registerForActivityResult(...).launch(...)`，回调是空 lambda、无 rationale（全仓 grep `shouldShowRequestPermissionRationale` 零命中）。而 App.kt:76 `LaunchedEffect(Unit) { state.open() }` + App.kt:80 `if (state.ready)`、AppState.kt:75 ready 初始 false ⇒ 首帧必然是 App.kt:87-102 的 Opening()/LoadingPane——用户对这个应用一无所知、也不知道通知拿来干嘛时就被要求授权，大概率直接拒绝。需要说明的是 MainActivity.kt:27-33 的注释表明「提前要权限」是作者写明理由的主动取舍，所以这里报的是时机而非疏忽。

> **改法**：把 MainActivity.kt:22 的调用从 onCreate 移走，改成在设置页第一次点「扫描」时触发：先用一句话说明「扫描要几分钟，通知用来显示进度并防止系统回收进程」，再拉起系统弹窗。

## 桌面平台惯例

### 默认窗口 1360×900 物理像素从不与屏幕工作区取交集：1366×768 笔记本、1080p 上开 125%/150% 缩放的机器首次启动就有一大块在工作区外

**阻断** · 桌面 · `Main.kt:19-26、Main.kt:25、InternalPlayer.desktop.kt:275-279`

Main.kt:19-26 只给了 `state = rememberWindowState(size = DpSize(1360.dp, 900.dp))`，没给 position/placement。rememberWindowState 的默认值是 `WindowPlacement.Floating` + `WindowPosition.PlatformDefault`；Windows_desktopKt.setSizeImpl 在两维都给定时只做 `roundToInt().coerceAtLeast(0)` 后 `Window.setSize`，全程没有乘 density、也没有任何屏幕边界运算——所以 1360×900 是直接落到 AWT 的物理像素。WindowLocationTracker 的 cascadeOffset 是 Point(48,48)，越界兜底只把位置换成工作区左上 +(48,48)，对首个窗口是恒等变换。实算：1366×768@100% 工作区 1366×720 → 窗口 x∈[48,1408] 右越界 42px、y∈[48,948] 越出工作区 228px（其中 180px 在屏幕外）；1920×1080@125% 用户空间 1536×864、工作区高 816 → 底部越界 132px；@150% 用户空间 1280×720 → 窗口比屏幕还宽 80px、底部越界 276px。窗口底边够不着就拖不动尺寸，只能靠最大化或 Win+↑；播放页被切掉的正是 InternalPlayer.desktop.kt:275-279 视频 Box 的底边，也就是 mpv 自带 OSC（播放/进度条）所在处。1920×1080@100%（工作区高 1032，窗口下缘 948）完全正常，本条只在 ≤768 高面板或 1080p 上 ≥125% 缩放时成立。

> **改法**：在 Main.kt 里按当前屏幕算初始尺寸：取 `Toolkit.getDefaultToolkit().getScreenInsets(gc)` 与 `gc.bounds`，用 `DpSize(min(1360, workW − 96).dp, min(900, workH − 96).dp)`；顺手给 `window.minimumSize` 一个下限。

### 桌面上所有横排用滚轮推不动——纵向滚轮增量在 Horizontal scrollable 上被算成 0 并冒泡去滚整页，行两端也没有箭头或滚动条

**严重** · 桌面 · `Components.kt:311-314、HomeScreen.kt:222-225、HomeScreen.kt:66、DetailScreen.kt:94、DetailScreen.kt:107-119、DetailScreen.kt:605-621、DetailScreen.kt:630-633、AppState.kt:220`

ComposeSceneMediator_desktopKt.onMouseWheelEvent 的转换是 `if (isShiftDown) Offset(preciseWheelRotation, 0) else Offset(0, preciseWheelRotation)`——普通滚轮的量只进 y。消费判定在 MouseWheelScrollingLogic.canConsumeDelta → ScrollingLogic.toSingleAxisDeltaFromAngle：该方法算 `atan2(|y|,|x|)`，≥π/4（偏纵向）时只有 orientation==Vertical 才返回 y，否则返回 0f；纯纵向滚轮 atan2(|d|,0)=π/2，横向 scrollable 拿到 0f，canConsumeDelta 返回 false，事件冒泡给外层 LazyColumn。受影响的横排：Components.kt:311-314（MediaRow）、HomeScreen.kt:222-225（库快捷卡）、DetailScreen.kt:107-119（季选择）/605-621（本季剧集）/630-633（演职人员）；外层 LazyColumn 在 HomeScreen.kt:66 与 DetailScreen.kt:94。默认 1360dp 窗口减 80dp rail = 1280dp，「继续观看」20+5×232+4×14=1236dp 只完整露出 5 张（数据 20 条，AppState.kt:220），其余只能靠 Shift+滚轮或按住左键拖——两者都没有任何可见入口。全仓无 Scrollbar/rememberScrollbarAdapter，行两端也没有翻页箭头。

> **改法**：两件事各自独立见效：给 MediaRow 与分集行加 `Modifier.onPointerEvent(PointerEventType.Scroll)`，把 scrollDelta.y 转成对 rememberLazyListState 的 scrollBy；在行左右两端加 hover 时才出现的圆形箭头按钮（滚到头时隐藏对应一侧），每次滚一屏宽——两者都只需要把 LazyRow 的 state 提上来。

### 右键菜单用 DropdownMenu 的 offset 当光标坐标，而 offset 的垂直基准是锚点底边——菜单落在光标下方整整一张海报的高度

**严重** · 桌面 · `Components.kt:261-269、Components.kt:190、ItemContextMenu.kt:61、ItemContextMenu.kt:79-80、DetailScreen.kt:786-792、DetailScreen.kt:690-704、LibraryScreen.kt:87、HomeScreen.kt:89`

Components.kt:261-269 的 DropdownMenu 挂在 :195 那个包住整张海报的 Box 里，`offset = menuAt.toDpOffset(density)`，而 menuAt 是 ItemContextMenu.kt:61/79-80 报上来的、相对卡片左上角的光标坐标。Popup 的 parentBounds 由 `IntRect(positionInWindow(coords), coords.getSize())` 构成，即父布局的位置与尺寸（这里就是整张海报）；MenuKt 取 `MenuAnchorPosition.Below`，其首个纵向候选是 `topToAnchorBottom`；DropdownMenuPositionProvider.positioningLogic 对每个候选再加 contentOffset.y。合起来就是 `menu.top = anchorBounds.bottom + offset.y`。1360×900 默认窗口、150dp 网格里第一行卡顶边 y=168dp、海报 150×225、底边 393dp，在海报正中右键 → 菜单顶边落在 505dp，即光标正下方 225dp，正好压住第二行海报；如果那个位置放不下，菜单又整体翻到卡片上方。DetailScreen.kt:786-792 的分集列表同构，位移约 112–117dp（卡片自身高度 + 5dp）。Windows/macOS 的上下文菜单一律把左上角放在光标处。

> **改法**：不要让 DropdownMenu 直接挂在画面 Box 上：在 Box 里放一个零尺寸锚点 —— `Box(Modifier.offset { IntOffset(menuAt.x.toInt(), menuAt.y.toInt()) }.size(0.dp)) { DropdownMenu(expanded, onDismissRequest) { … } }`，offset 参数留 DpOffset.Zero，anchorBounds 高度为 0 时 bottom 就是光标点。Components.kt:261 与 DetailScreen.kt:786 两处改同一模式。

### 桌面端一个快捷键、一条菜单栏都没有：Alt+← / Backspace 不返回、Ctrl+F 不跳搜索、F5 不刷新，唯一返回入口是左上角那个图标按钮

**严重** · 桌面 · `Main.kt:19-26、App.kt:186`

composeApp/src 下 grep `onKeyEvent|onPreviewKeyEvent|KeyShortcut|MenuBar|Tray` 零命中，Main.kt:19-26 的 Window 也没挂任何键盘处理，唯一的返回入口是 App.kt:186 的 `IconButton(onClick = { state.back() })`——鼠标侧键同样无效。Windows 应用惯例是 Alt+← 后退、Ctrl+F 搜索、F11 全屏，Jellyfin Desktop 与 Plex 桌面端都有成套快捷键。这是纯增量成本极低的一处：Window 上挂一个 onPreviewKeyEvent 就能接住全部。（同一处 grep 结果导致的「键盘焦点完全不可见」归入无障碍组的焦点条。）

> **改法**：在 Main.kt 的 Window 上挂 `onPreviewKeyEvent`：Alt+Left / Backspace → `state.back()`、Ctrl+F → `state.navigate(Screen.Search)`、F5 → 重新加载当前屏、F11 → 切 WindowState.placement。再配一条 MenuBar 把这些动作显式列出来，让快捷键可被发现。

### 窗口大小、位置、最大化状态、所在显示器一概不记忆，每次启动都回到主屏左上角的 1360×900

**严重** · 桌面 · `Main.kt:19-26、Main.kt:20-23、Platform.desktop.kt:190-196、Platform.desktop.kt:74-80`

Main.kt 全文 30 行：:19-26 每次都新建 `rememberWindowState(size = DpSize(1360.dp, 900.dp))`，:20-23 的 onCloseRequest 只有 `exitApplication()` + `exitProcess(0)`；composeApp/src 下 grep `WindowPlacement|placement|minimumSize|Tray|MenuBar` 零命中，state 的 size/position/placement 从未被读出持久化。WindowLocationTracker.getCascadeLocationFor 对首个窗口没有同屏前驱，落点就是该设备（默认主显示器）工作区左上 + cascadeOffset(48,48)——上次最大化在副屏看片，这次启动又是主屏左上角一个 1360×900 的浮动窗口，每天开一次就要重新拖一次、再最大化一次。存储设施早就在用：Platform.desktop.kt:190-196 的 createSettingsStore 就是 `Preferences.userRoot().node("com/daview/app")`，:74-80 的 customPlayerPath 走的正是这个节点。

> **改法**：在 onCloseRequest 里把 `state.size`、`state.position`（Absolute 时才存）、`state.placement` 写进已有的 Preferences 节点，启动时读回来；读回后先和当前屏幕工作区求交集再用，屏幕拓扑变了就退回默认。

### 没有任何窗口图标：标题栏、任务栏、Alt+Tab、安装后的开始菜单快捷方式全是 JDK 默认的咖啡杯

**严重** · 桌面 · `Main.kt:19-26、composeApp/build.gradle.kts:125-152`

Main.kt:19-26 的 Window 只传了 onCloseRequest / title / state，没有 `icon` 参数；Windows_desktopKt.setIcon 在 painter 为 null 时直接 `Window.setIconImage(null)`，AWT 退回内置默认帧图标。打包侧：composeApp/build.gradle.kts:125-152 的 nativeDistributions 只有 targetFormats(Msi/Deb/Dmg)、packageName、packageVersion、appResourcesRootDir、modules，没有 windows{}/macOS{}/linux{} 块，自然没有 iconFile；仓库排除 build 后 find *.ico / *.icns 零命中，composeApp/nativeResources 下只有 windows/libmpv-2.dll。运行时窗口图标（AWT 默认帧图标）与 MSI 快捷方式图标（jpackage 默认 .ico）是两个不同的默认资源，要各配一份。与 Android 端的图标缺失是同一根因（仓库零图标资产），但修法完全不同。

> **改法**：做一张 PNG 放进 composeApp 资源并 `Window(icon = painterResource(...))`；同时在 nativeDistributions 里给三个平台各配一份 iconFile（Windows 用含 16/32/48/256 四档的 .ico），否则安装后的快捷方式仍是通用图标。

### 两个「打开」对话框的 FilenameFilter 在 Windows 上是 JDK 文档化的空操作，三个对话框都不设初始目录、也没有文件类型下拉

**打磨** · 桌面 · `Platform.desktop.kt:152-162、Platform.desktop.kt:164-178、PlatformPlayerSettings.desktop.kt:105-109、PlatformPlayerSettings.desktop.kt:276-284、SettingsScreen.kt:542、composeApp/build.gradle.kts:125-152`

JDK 17 的 java.desktop/java/awt/FileDialog.java:582-585 原文就写着 filename filter 在 Windows 参考实现里不起作用。三处调用点：Platform.desktop.kt:152-162（LOAD，:154 setFilenameFilter 收 .json，未设 directory）、:164-178（SAVE，:167 只设 `file = suggestedName`，根本没有 setFilenameFilter，未设 directory）、PlatformPlayerSettings.desktop.kt:276-284（LOAD，:278-281 过滤 .dll/.so/.dylib，未设 directory，入口在 :105-109 的「指定 libmpv…」）。于是「指定 libmpv…」会把目录里全部文件都列出来、文件类型只有「所有文件」，用户得自己在一堆 dll/exe 里认出 libmpv-2.dll。初始目录方面，lpstrInitialDir 为 NULL 时 Windows 先用该 exe 的 ComDlg32 MRU 记录，首次使用才落到安装目录（build.gradle.kts:125-152 无 windows{ perUserInstall }，jpackage 默认 per-machine），非管理员在那里保存会失败，而 Platform.desktop.kt:174-178 吞掉异常返回 null，界面只回一句 SettingsScreen.kt:542 的「没有写入文件」。

> **改法**：改成 Windows 认的写法：LOAD 时 `dialog.file = "*.json"`（libmpv 那处用 "*.dll"），保留现有 setFilenameFilter 给 macOS/Linux；三处都设 `dialog.directory`（默认 user.home 下的 Documents/Downloads，并把上次选过的目录存进已有的 Preferences 节点）；顺带把保存失败的异常信息带到「没有写入文件」后面。

## 播放器界面

### 桌面内置播放器的「音轨 / 字幕」下拉菜单整个落在 mpv 的 AWT 画布里，被原生子窗口盖住，看不见也点不到

**阻断** · 桌面 · `InternalPlayer.desktop.kt:248-279、InternalPlayer.desktop.kt:59、InternalPlayer.desktop.kt:333、InternalPlayer.desktop.kt:364、InternalPlayer.desktop.kt:377、MpvPlayer.kt:84、App.kt:177-189`

InternalPlayer.desktop.kt:248-279 是 `Column { PlayerBar(:249); Box(fillMaxSize().weight(1f)) { SwingPanel(canvas, fillMaxSize) } }`，画面靠 MpvPlayer.kt:84 `option("wid", surfaceHandle)` 成为重量级 java.awt.Canvas 的原生子窗口，而两个菜单（:364、:377）是普通的 Compose Popup。渲染层判定：`LayerType.parse(System.getProperty("compose.layers.type"))` 只认 "COMPONENT"/"WINDOW"，null 落到 OnSameCanvas；`useInteropBlending = Boolean.parseBoolean(null) = false`；`ComposeSceneMediator.getShouldPlaceInteropAbove() = !useInteropBlending || metalOrderHack` 恒为 true，SwingPanel 组件必然置于 Skia 画布之上，仓库里也没有任何 System.setProperty。几何：TopRow 56dp + PlayerBar 内 8dp + TextButton 40dp ⇒ 菜单锚点底边在客户区 104dp，画布顶边在 112dp，DropdownMenu 上内边距 8dp 使首条菜单项顶边正好落在 112dp——只有 8dp 的圆角边露在 PlayerBar 上，菜单主体 100% 被画布遮住，再点视频区又会 dismiss，视觉上像按钮坏了。文件自己的 KDoc（InternalPlayer.desktop.kt:59）已经写明「no Compose surface can be drawn over the video」。多音轨日番、外挂多字幕的电影每次都撞。

> **改法**：不要在这条栏上用 Popup 类组件。两个最小改法二选一：把音轨/字幕交给 mpv 自己（osc 已开，`#`、`j` 现成），顶栏只留标题与「结束播放」；或把菜单改成就地展开的内联行——点「音轨」时在 PlayerBar 下方再插一行 Compose 的 ToggleButton 列表（属于 Column 布局的一部分，会把视频往下挤，不走 popup）。若一定要保留下拉，需在 Main.kt 启动时 `System.setProperty("compose.layers.type", "WINDOW")` 并实测，代价是所有对话框都变成独立窗口。

### 桌面播放器把走带控制整个交给 mpv，Compose 侧既不转交按键也从不把焦点还给 Canvas——点过一次工具栏后空格变成「重新弹开刚才那个菜单」，Esc 也无人接管

**严重** · 桌面 · `MpvPlayer.kt:96-98、InternalPlayer.desktop.kt:54-63、InternalPlayer.desktop.kt:74-80、InternalPlayer.desktop.kt:276-279、InternalPlayer.desktop.kt:332-392、App.kt:178-189`

MpvPlayer.kt:96-98 开了 `osc/input-default-bindings/input-vo-keyboard`，InternalPlayer.desktop.kt:54-63 的注释也明说 transport controls 交给 mpv 的 OSC；PlayerBar（:332-392）逐行读完只有标题、adapter 文本、两个 EnhancementChip、音轨/字幕/结束播放三个 TextButton，没有播放、暂停、快进、快退、音量任何一个。整个 composeApp grep `requestFocus|FocusRequester|onKeyEvent|onPreviewKeyEvent|focusable` 零命中：AWT Canvas 虽然默认 focusable=true（:74-80 创建、:276-279 塞进 SwingPanel），但从来没有人请求过焦点。于是点一次「音轨」之后，ClickableNode 在鼠标输入模式下会 requestFocusWhenInMouseInputMode（RequestFocusOnClick_desktop 的开关常量为 true），焦点留在那个 Compose 按钮上，而 ClickableKt.isEnter 包含 Key.Spacebar——再按空格是重新触发这个按钮、把菜单又弹一次，不是暂停；Esc 则被 BackNavigationEventInput 映射到 dismissOnBackPress，播放页无人接管。唯一的退出路径是鼠标点「结束播放」或 App.kt:178-189 的返回箭头，或者先把焦点弄回 mpv 再按 q。

> **改法**：两步：给 canvas 加一个在 mousePressed 时 `requestFocusInWindow()` 的 MouseListener，并在 `LaunchedEffect(info.sessionId)` 拿到 handle 后主动请求一次焦点，让键盘默认归 mpv；在 InternalPlayer 最外层 Column 上加 `Modifier.onPreviewKeyEvent`，至少把 Esc（调 finish()）、空格、←/→ 拦下来转成 `player?.command(...)`，这样焦点在 Compose 工具栏上也不会走丢。

### 桌面播放器没有任何全屏入口，却把 mpv 那两个在 --wid 内嵌下已经变成空操作的全屏控件原样摆给用户

**严重** · 桌面 · `Main.kt:19-26、MpvPlayer.kt:84、MpvPlayer.kt:96-98、InternalPlayer.desktop.kt:361、InternalPlayer.desktop.kt:374、InternalPlayer.desktop.kt:390、App.kt:160-174、App.kt:177-189`

composeApp/src 下 grep `onKeyEvent|onPreviewKeyEvent|KeyShortcut|WindowPlacement|placement|Fullscreen` 零命中，Main.kt 从不碰 placement，PlayerBar 上也没有全屏按钮。同时 MpvPlayer.kt:84 `option("wid", handle)` 让画面成为 AWT canvas 的子窗口（mpv w32_common.c 的 reinit_window_state 在有 parent 时直接 return，全屏/置顶/边框切换全被忽略），而 MpvPlayer.kt:96-97 又同时打开 osc 与 input-default-bindings——于是 osc.lua 右下角那个全屏图标和默认的 f 键都摆在用户面前，点了、按了都没反应。播放页还是 App.kt:160-174 AnimatedContent 的普通分支，TopRow（App.kt:177-189，56dp）在 backStack.size>1 时必渲染，宽布局照常挂 80dp 的 SideNavigation，PlayerBar 再占 64dp——1920×1080 最大化时视频区只剩约 1264×741dp。三个退出入口（InternalPlayer.desktop.kt:390 的「结束播放」TextButton、App.kt:186 的返回箭头、mpv 的 q 键）里，:390 与 :361/:374 两个下拉触发器同为 TextButton、同色同字号、间距 8dp，终止性操作与常规操作毫无视觉层级。

> **改法**：给 WindowState 加 placement 开关：在播放页监听 F11 与画面双击切到 `WindowPlacement.Fullscreen`，该状态下隐藏 TopRow、SideNavigation 与 PlayerBar（hover 唤出），Esc 退出全屏；既然 mpv 的全屏按钮是死的，用 script-opts 把它从 osc 布局里去掉，别让用户点空。「结束播放」换成 PlayerBar 最左侧的一个 ✕ 或 ← 图标按钮，和右侧的轨道菜单拉开距离。

### 桌面 PlayerBar 是无溢出处理的单行 Row：GPU 型号排在片名前面吃满宽度，150% 缩放下字幕轨名换行把 bar 撑到 112dp、「结束播放」退化成压在字幕按钮上的 48dp 空白热区

**严重** · 桌面 · `InternalPlayer.desktop.kt:333-391、InternalPlayer.desktop.kt:338-345、InternalPlayer.desktop.kt:347-355、InternalPlayer.desktop.kt:361-376、InternalPlayer.desktop.kt:390、Models.kt:96-103、Main.kt:25`

InternalPlayer.desktop.kt:333-391 是裸 `Row(fillMaxWidth().padding(horizontal=16.dp), horizontalArrangement = spacedBy(8.dp))`，无 FlowRow、无溢出菜单，只有片名（:338-345）带 weight(1f) 且有 maxLines=1+Ellipsis；:347-355 的 GPU 名、两个 EnhancementChip、:361-363 音轨按钮、:374-376 字幕按钮（标签是 Models.kt:96-103 拼出的 displayTitle，外挂字幕的 title 来自文件名，无 maxLines/overflow）、:390 结束播放全部定宽。Compose 的 Row 对无 weight 子项按顺序用剩余空间测量，weight 子项最后拿残值——信息优先级完全倒置。100% 缩放（内容约 1344dp，减 rail 80、padding 16、间距 56+4）可分 1172dp，定宽项按样例字幕轨名约 976dp，片名只剩 196dp ≈ 14 个汉字。换算到系统缩放：125%→1088dp 已低于片名归零阈值 ≈1164dp，150%→907dp 低于字幕按钮换行阈值 ≈1068dp——此时字幕按钮换 2 行、Row 高约 96dp、bar 总高 112dp，而「结束播放」被测成 maxWidth=0（Text 被 Clip 成空白），minimumInteractiveComponentSize 把它抬回 48dp 布局宽并与字幕按钮重叠，成了一块点不到的空白热区。

> **改法**：三处最小改动：音轨/字幕按钮的 Text 加 `maxLines = 1, overflow = Ellipsis` 并 `widthIn(max = 180.dp)`，或干脆换成图标按钮、把轨道名放进菜单；显卡名加 `weight(0.3f, fill = false)` 或 widthIn(max) 让它先让位；把「结束播放」提到 Row 最前面测量，保证它永远存在。窄窗口时把显卡名与两个 chip 折进一个溢出菜单。

### 播放中点侧栏/底栏的「搜索」「设置」「某个媒体库」：去不了目标页，绕一圈回来重启一次解码，再自己多退一级

**严重** · 两端 · `App.kt:133-147、App.kt:230、App.kt:237、App.kt:246、App.kt:160-173、AppState.kt:124-127、PlayerScreen.kt:45-55、PlayerScreen.kt:51-54、PlaybackController.kt:176-183、InternalPlayer.desktop.kt:239-246、InternalPlayer.android.kt:116-122`

App.kt:133-147 两套布局都把导航与 Content 并列，播放期间导航项始终可点。走 navigate 的项（App.kt:230 搜索、:237 设置、:246 各媒体库；底栏 :266/:274）→ AppState.kt:124-127 push → App.kt:160-173 的 AnimatedContent 转场结束后释放 PlayerScreen → InternalPlayer.desktop.kt:239-246 / InternalPlayer.android.kt:116-122 的 onDispose → PlayerScreen.kt:51-54 的 `playback.stop(position); state.back()`。而 PlaybackController.kt:176-183 把 `info = null` 放在 scope.launch 里、在 stopPlayback 之后，所以 back() 生效那一刻 info 仍非空 → PlayerScreen.kt:45-55 重造一个 InternalPlayer → 桌面 :104 的 `LaunchedEffect(info.sessionId)` 重新建 mpv 并从 info.startPositionMs 起播，Android :103 seekTo 同一位置。协程返回后 info=null，EmptyState「没有正在播放的内容」闪一帧，第二个 InternalPlayer 实例再次 finish → 又弹一层栈，最终停在播放页下面那一层。首页项例外：App.kt:223/:260 用 replaceAll，back() 在 size<=1 时 return false，能正常落到首页。

> **改法**：根因是 onClose 里的 `state.back()` 与外部 navigate 抢同一个回退栈。最小改法：PlayerScreen 的 onClose 改成只在 current 仍是 Screen.Player 时才 back()（或用 popIfCurrent(Screen.Player)）；同时把 PlaybackController.stop 里的 `info = null` 提到 scope.launch 之外先置空，避免旧 info 被重新播一次。设计上更该做的是让 Screen.Player 成为覆盖整个窗口、不带导航骨架的一层。

### Android 播放器的标题与音轨/字幕按钮是纯白文字直接压在视频帧上，没有 scrim 也没有阴影，亮画面下低至 1.00:1

**严重** · Android · `InternalPlayer.android.kt:133-222、InternalPlayer.android.kt:150、InternalPlayer.android.kt:166、InternalPlayer.android.kt:216-220`

InternalPlayer.android.kt:216-220 的标题是 titleMedium(16sp/Medium) + `color = Color.White`；:150 与 :166 两个 TextButton 的 Text 同样写死 Color.White，而 M3 TextButton 的 containerColor 是 Transparent。整个 Box(:133-222) 里没有任何 Brush.verticalGradient 背景、没有 Surface/scrim，Text 也没有 TextStyle.shadow。唯一的深色垫底来自 media3 的 exo_controls_background（60% 黑），只在控制条可见的那 5 秒里存在。按 WCAG 算：白色 L=1.0，正文 4.5:1 要求背景 L ≤ 1.05/4.5−0.05 = 0.1833，反推 sRGB 约 #777777——画面里任何比中灰更亮的区域（雪景、白墙、日光外景、动画白底转场）白字都不达标，纯白帧时是 1.00:1 完全不可读。有 60% 黑底时白帧被压成 #666666，对比度 5.74:1 达标，正好说明缺的就是这层垫底。titleMedium 16sp/Medium 不满足 WCAG 大字标准（需 ≥24px 常规或 ≥18.66px 粗体），必须按 4.5:1 判。

> **改法**：给顶部叠加层加一层高约 120dp 的 `Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))`；或者直接让这三个叠加层跟随控制条显隐，复用 media3 已经画好的 60% 黑底。

### 退出内置播放器：Android 上引擎比页面多活约 0.33 秒声音继续响，桌面则在淡出中途先闪出「没有正在播放的内容」，两端画面都不参与淡入淡出

**严重** · 两端 · `PlayerScreen.kt:37-43、PlayerScreen.kt:51-54、PlaybackController.kt:176-183、MediaFacade.kt:386-392、InternalPlayer.desktop.kt:239-246、InternalPlayer.desktop.kt:272、InternalPlayer.android.kt:116-122、App.kt:161、App.kt:186`

Android 路径：退出走 App.kt:186 的 `state.back()`，全程无人置 info=null，InternalPlayer.android.kt:116-122 的 onDispose 要等退出动画结束（fadeOut 的弹簧沉降约 0.33s）才 `player.release()`，音频确实多响三分之一秒。桌面路径相反：InternalPlayer.desktop.kt:272/390 的「结束播放」→ PlayerScreen.kt:51-54 `playback.stop(position); state.back()`，PlaybackController.kt:180 在 launch 中很快置 `info = null`（MediaFacade.kt:386-392 的 stopPlayback 只是进程内 io{}，毫秒级），而出屏内容仍在组合中并订阅快照状态，于是 PlayerScreen.kt:37-43 立刻落入 `if (info == null) EmptyState("没有正在播放的内容")` 分支——淡出剩余的约 300ms 显示的是一个空态文案。两端共同的一点是画面无法淡入淡出：桌面 mpv 绘制在 --wid 的原生子窗口上、Android 是 SurfaceView，都不跟随 graphicsLayer 变换，所以黑画布是一帧盖住、一帧消失。

> **改法**：两处 onClose 回调里先关引擎再退栈（`player?.close()` / `player.release()` 放到 `state.back()` 之前，DisposableEffect 保留作兜底）；同时在 App.kt:161 的 transitionSpec 里对 `Screen.Player` 这一分支返回 `EnterTransition.None togetherWith ExitTransition.None`，让播放器进出是干净的瞬切，而不是一个做不到的假淡入淡出。

### 桌面 PlayerBar 跟随应用主题：切到浅色主题后，纯黑画面上方是一条横贯全宽、64dp 高的近白色带

**打磨** · 桌面 · `InternalPlayer.desktop.kt:332、InternalPlayer.desktop.kt:334、InternalPlayer.desktop.kt:74-80、InternalPlayer.desktop.kt:361、InternalPlayer.desktop.kt:374、InternalPlayer.desktop.kt:390、Theme.kt:108、AppState.kt:97`

InternalPlayer.desktop.kt:332 的容器色写死 `MaterialTheme.colorScheme.surfaceContainerLow`，随全局主题走；浅色下是 Theme.kt:108 的 #F7F2FA（相对亮度 0.90187），紧贴 InternalPlayer.desktop.kt:74-80 中 `background = AwtColor.BLACK` 的画面区。bar 高度 64dp = Row 上下各 8dp + 三个 TextButton 经 minimumInteractiveComponentSize 抬到的 48dp（:334、:361/374/390）。浅色需要用户主动切换（AppState.kt:97 深色是默认），但一旦切了，夜里看片时屏幕顶上就是一条常亮的白光。Plex 播放器纯黑、Jellyfin 的 videoOsd 黑色半透明、Emby 同理——三家的播放 chrome 都不跟随应用主题，理由是暗环境观看时亮色 UI 破坏瞳孔适应。

> **改法**：给播放页局部套一层固定的深色配色（`MaterialTheme(colorScheme = DaViewDarkColors)` 包住 PlayerScreen），让 PlayerBar 在浅色主题下也是深色，并把它做成半透明黑而不是 surfaceContainerLow。

### Android 播放失败时一张卡片浮在画面正中央、永不消失、没有任何出口，文案还是 ERROR_CODE_IO_BAD_HTTP_STATUS 这样的英文枚举

**打磨** · Android · `InternalPlayer.android.kt:67、InternalPlayer.android.kt:106-111、InternalPlayer.android.kt:198-211、InternalPlayer.desktop.kt:280-301、Theme.kt:128`

InternalPlayer.android.kt:106-111 的 onPlayerError 把消息拼成 `"${error.errorCodeName}: ${error.message ?: "播放失败"}"`——errorCodeName 是 media3 的常量名、message 是 ExoPlayer 的英文串，中文兜底只在 message 为 null 时出现。:198-211 的渲染是 `Surface(align(Alignment.Center), shape = shapes.medium（Theme.kt:128 = 18dp）, color = errorContainer)` 内部只有一个 Text，没有按钮、没有 onDismiss；playbackError 全文件只在 :67 初始化为 null 与 :109 赋值，没有任何清回 null 的路径。M3 非可点击 Surface 自带 `pointerInput(Unit) {}` 会吞掉触摸，所以点它没反应、连带点不出 media3 控制条。配色本身可读（onErrorContainer #FFDAD6 对 errorContainer #93000A = 7.24:1），问题在位置、不可操作与文案语言：播放中途失败时卡片直接盖在最后一帧上，而顶部的「音轨」「字幕」仍可点、菜单照开。桌面同处设计相同（InternalPlayer.desktop.kt:280-301），但 :162 的文案至少带中文前缀「播放失败: 」。

> **改法**：把 errorCodeName 映射成中文原因（网络中断 / 服务器返回 502 / 该格式无法解码 / 文件不存在），原始码收进一行 labelSmall 次要文本；卡片改成占满画面的错误态并带「重试」「返回」两个按钮，重试时 `player.prepare()` 并把 playbackError 清空；错误态下隐藏音轨/字幕入口。

## 组件用法

### 26dp 圆角把海报底部的观看进度条两端啃掉：进度低于 8.1% 时一个像素都画不出来，而代码以为自己画了

**严重** · 两端 · `Components.kt:189-194、Components.kt:243-256、Theme.kt:129、DetailScreen.kt:708、LibraryScreen.kt:88`

Components.kt:243-256 的进度条是 `align(BottomCenter).fillMaxWidth().height(4.dp)`，绘制门槛是 `progress > 0.01f`（1% 就该出现）。但它被画在 Components.kt:189-194 那个 `shape = MaterialTheme.shapes.large`（Theme.kt:129 = 26dp）的 Surface 内部，而 M3 Surface 的修饰符链尾部有 `clip(shape)`，内容被圆角裁剪。以媒体库网格卡 150×225dp 计，左下角圆弧圆心 (26,199)、半径 26，进度条占 y∈[221,225]：y=221 时 dx=√(26²−22²)=13.86 → 形状左边界 x=12.14dp，y=223 时 16dp，y=225 时 26dp。进度 p 的右端在 150p 处，`150p ≤ 12.14` 即 p ≤ 8.1% 时整条被裁光；p=10% 只剩一个约 2.9dp 宽的三角形碎片。2 小时电影看了 5 分钟（4%）在卡片上与「完全没看过」视觉上无法区分，要看到 15 分钟以上才勉强显形。首页 232dp 宽的卡阈值 5.2%。对照 DetailScreen.kt:708 的分集缩略图（148×84，shapes.small=12dp、条高 3dp）左边界只有 4.06dp、阈值 2.7%——同一段逻辑在小圆角下正常，证明问题出在 26dp 这个 token 值。

> **改法**：把海报 Surface 的圆角降到 12dp 左右；如果要保留大圆角，就把进度条移出被裁剪的 Surface（画在卡片下方的文字区），或改成整宽 track + indicator，让 track 先把这段几何吃掉，用户至少能看出「有进度条，只是很短」。

### 缺图这一件事有四种画法：卡片给电影图标、分集缩略图给空灰块、演职人员给空灰圆、详情页海报干脆不占位——最后一种让整个头部左移 180dp

**严重** · 两端 · `Components.kt:203-211、DetailScreen.kt:282、DetailScreen.kt:295、DetailScreen.kt:254-269、DetailScreen.kt:639-651、DetailScreen.kt:706-719、Scrapers.kt:230、Scrapers.kt:241、Scrapers.kt:380`

Components.kt:203-211：posterUrl 为 null 时画居中 40dp 的 `Icons.Filled.Movie`，tint=onSurfaceVariant 压 surfaceContainerHigh，对比度 9.15:1，是四处里唯一做对的。DetailScreen.kt:706-719：`Surface(width(148.dp).height(84.dp), color = surfaceContainerHighest)` 内只有 `episode.posterUrl?.let { AsyncImage }`，为空就是一块纯灰矩形。DetailScreen.kt:639-651：`Surface(size(80.dp).clip(RoundedCornerShape(50)), color = surfaceContainerHigh)` 内只有 `person.imageUrl?.let {}`，为空是一个 1.22:1 的空色圆——而缺图是常态（Scrapers.kt:230/241 的 TMDB profile_path 常为 null，:380 的 bangumi person.image 同理），一整行看起来像图还在加载。最刺眼的是 DetailScreen.kt:282 `val poster = item.posterUrl ?: return`，它早于 :295 的 `Spacer(Modifier.width(gap))`：有海报时标题从 24+160+20=204dp 处开始，没海报时海报位连同间距一起消失、标题/简介/播放按钮整体左移 180dp（窄版 156→20dp，左移 136dp）——同一个库里前后翻两个条目就是两种版式。版式稳定本身就是「这里应该有张图」的信息。

> **改法**：抽一个 `ArtworkPlaceholder(kind, shape, size)`：按 ItemKind 给图标（MOVIE→Movie、SERIES/SEASON→Tv、EPISODE→Slideshow、人物→Person 或姓名首字），四个调用点全部换成它。HeroPoster 去掉 `?: return`，无图时画同尺寸占位（gap 也保住），让详情页头部版式与有图时一致。顺手把 `.clip(RoundedCornerShape(50))` 改成给 Surface 传 `shape = CircleShape`。

### 悬停时海报中央那个圆形播放键点下去不是播放，是进详情页；触摸端则永远看不到它

**严重** · 两端 · `Components.kt:153、Components.kt:167-187、Components.kt:223-241、Components.kt:232、Components.kt:243-256、LibraryScreen.kt:90、SearchScreen.kt:74、HomeScreen.kt:91-112、DetailScreen.kt:619、DetailScreen.kt:384、ItemContextMenu.kt:109-121`

Components.kt:223-241 在 hovered 时铺一层 scrim 渐变，并在正中画 `Surface(shape = RoundedCornerShape(50), color = colorScheme.primary) { Icon(Icons.Filled.PlayArrow, tint = onPrimary, 26.dp) }`——形状、填色、图标三要素齐备，就是一个 M3 filled icon button 的样子，:243-256 的进度条还在强化这个播放承诺。但整张卡只有 Components.kt:153 一个 onClick，作用在 :167-187 的 Column 上，播放键所在的 Box 内没有任何独立 clickable；六处调用点 LibraryScreen.kt:90、SearchScreen.kt:74、HomeScreen.kt:91/99/102/112、DetailScreen.kt:619 传的一律是 `state.navigate(Screen.Detail(...))`。真正的播放入口在别处（ItemContextMenu.kt:109-121 的右键菜单、DetailScreen.kt:384 的详情页按钮）。这是典型的 false affordance；而触摸端因为 hover 只认鼠标，这个键永远不出现，海报除了「进详情页」没有第二种可见操作。

> **改法**：把播放键做成真正的子按钮：外层 Column 的 onClick 保持导航，在 Components.kt:232 的 Surface 上加 IconButton/clickable 并回调一个新的 `onPlay` 参数（item.isPlayable 时才画）。若暂不想接播放链路，就把这个圆形播放键换成一个不承诺动作的记号（只加深 scrim 或显示片名），别用 PlayArrow。

### 已观看的对勾在四处用了两套互斥编码：卡片/详情页按状态画（primary 实心勾=已看），右键菜单按动作画（primary 实心勾=未看）；卡片角标还是无底衬裸图标

**严重** · 两端 · `Components.kt:214-221、DetailScreen.kt:443-450、DetailScreen.kt:774、ItemContextMenu.kt:144-148、Theme.kt:23、AppState.kt:97`

Components.kt:214-221 的卡片角标是 `Icons.Filled.CheckCircle`、tint=primary，只在 played 时出现；DetailScreen.kt:443-450 与 :774 是 `if (played) Icons.Filled.Check else Icons.Filled.CheckCircleOutline`，PLAYED→primary；而 ItemContextMenu.kt:144-148 完全镜像：`if (played) CheckCircleOutline else Check`，tint 也反过来（played→onSurfaceVariant，未 played→primary）。于是同一屏之内，紫色实心勾在卡片上表示「已看」、在覆盖其上的菜单里表示「还没看」——分集行 DetailScreen.kt:774 的按钮与其右键菜单 ItemContextMenu.kt:144 甚至同屏同行同时出现两种编码。M3 的菜单项 leading icon 是「这条命令是什么」的标记，不承担 on/off 状态。另外深色主题（默认）下卡片角标是 #C9BCFF 的裸图标、无底衬无描边无阴影，压在浅色海报上对纯白只有 1.73:1、对 #808080 是 2.28:1，低于 WCAG 1.4.11 的 3:1——大量日系/动画海报是高亮度底。

> **改法**：定一条规则：对勾只表示「已观看」这一个状态，全 App 用同一个字形（建议保留 CheckCircle），未看不画图标；菜单项的 leading icon 换成中性的 `Icons.Filled.Done` 或干脆去掉，让文案承担动作语义。角标改成 `Surface(shape = CircleShape, color = primary)` 包一层、图标用 onPrimary，尺寸不变。

### 设置里的开关行自己拼 Row+Switch：标签点不动（360dp 行里只有右端 52dp 可点）、桌面无 hover 反馈、读屏节点没有可访问名称

**打磨** · 两端 · `SettingsScreen.kt:290、SettingsScreen.kt:293-296、SettingsScreen.kt:376-396、PlatformPlayerSettings.desktop.kt:207-223、PlatformPlayerSettings.desktop.kt:242-258`

SettingsScreen.kt:293-296 是 `Row(verticalAlignment = CenterVertically) { Text("深色主题", Modifier.weight(1f)); Switch(checked = state.darkTheme, onCheckedChange = { state.setTheme(it) }) }`，Row 上没有任何 clickable/toggleable；SettingsScreen.kt:376-396 的「启用同步」同构。SwitchTokens.TrackWidth=52dp、TrackHeight=32dp，父 Column 是 :290 的 `padding(horizontal = 20.dp)`，400dp 屏减 40dp 后可点区只有 52dp，死区 308dp = 85.56%——点「深色主题」这四个字没有任何反应，桌面端鼠标划过整行也没有 hover 反馈。无障碍侧同一根因：M3 Switch 内部的 toggleable 自带 mergeDescendants=true，是一个独立的焦点停靠点，兄弟节点的标签 Text 进不来，TalkBack 读到的是「开启，开关，双击切换」——不说这是什么的开关。全树 grep semantics/toggleable 零命中，没有任何补救。

> **改法**：两处都换成 `ListItem(checked = ..., onCheckedChange = ..., headlineContent = { Text("深色主题") }, trailingContent = { Switch(checked = ..., onCheckedChange = null) })`。不想换组件的话，最小改法是给 Row 加 `Modifier.fillMaxWidth().toggleable(value = ..., role = Role.Switch, onValueChange = ...).padding(vertical = 8.dp)` 并把 Switch 的 onCheckedChange 置 null。这个「一行标签 + 一个开关」的组合出现四次，抽成一个 SettingRow 一次改完。

### 六组 ToggleButton 一套外观承担三种语义：五组互斥单选、一组独立多选，选中态还是 primary 实心、与紧邻 10dp 的主操作「导出」同一色阶

**打磨** · 两端 · `LibraryScreen.kt:61、DetailScreen.kt:112-115、SettingsScreen.kt:499-504、SettingsScreen.kt:524、SettingsScreen.kt:526、SettingsScreen.kt:547、SettingsScreen.kt:673、PlatformPlayerSettings.desktop.kt:133-150、PlatformPlayerSettings.desktop.kt:232-236、Theme.kt:128`

六处全是 `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` 包裸 ToggleButton，渲染路径完全一致，互斥语义只存在于 onCheckedChange 的实现里：单选组 LibraryScreen.kt:61（排序五选一）、DetailScreen.kt:112-115（季）、SettingsScreen.kt:673（库类型四选一）、PlatformPlayerSettings.desktop.kt:133-150 与 :232-236；多选组 SettingsScreen.kt:499-504（「包含刮削数据」「包含凭据」彼此独立）。用户只能靠试点击才知道点第二个会不会把第一个弹起来。全仓库没有任何 SegmentedButton/FilterChip。视觉上还有第二重问题：ToggleButtonDefaults 的 UnselectedContainerColor=SurfaceContainer、SelectedContainerColor=Primary，所以选中的「包含刮削数据」就是一个 primary 实心按钮，而 SettingsScreen.kt:524 只隔 10dp 的 Spacer 之下就是 :526 的 Button「导出」和 :547 的 FilledTonalButton「导入…」——四个东西同一排列节奏、同一色阶，上面两个是状态、下面两个是动作，第一眼分不出哪个点下去会真的发生事情。另外形状也没帮上忙：默认 shape=CornerFull（40dp 高按钮即 20dp）、checkedShape=CornerMedium，本项目 Theme.kt medium=18dp，选中与未选中的圆角只差 2dp。

> **改法**：五个单选组换成 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`（连体外观自带互斥暗示）；备份的两个选项换成带标签的 Checkbox 行或 ListItem(checked, onCheckedChange, trailingContent = { Checkbox(onCheckedChange = null) })，与同页「深色主题」Switch 行的表达统一，也就顺手和下方的主按钮拉开了色阶。

### 仓库自带统一的 Chip 组件，桌面播放器却另用 AssistChip(enabled=false) 伪装只读状态：两套长相，读屏还念成「按钮，已停用」

**打磨** · 两端 · `Components.kt:397-409、DetailScreen.kt:322-331、HomeScreen.kt:193-198、InternalPlayer.desktop.kt:396-417、InternalPlayer.desktop.kt:405-416、InternalPlayer.desktop.kt:332、Theme.kt:23、Theme.kt:57`

自绘版 Components.kt:397-409 是 `Surface(shape = RoundedCornerShape(50), color = surfaceContainerHighest)` + labelMedium（行高 16sp）+ 上下各 6dp = 28dp 高、无描边，用在 DetailScreen.kt:322-331 的详情页信息标签行与 HomeScreen.kt:193-198 的首页大图标签行。M3 版 InternalPlayer.desktop.kt:396-417 的 EnhancementChip 用 `AssistChip(onClick = {}, enabled = false)`，AssistChipTokens.ContainerHeight=32dp、ContainerShape=CornerSmall（本主题 12dp），colors 只覆盖 disabledLabelColor、没传 border，于是沿用 FlatDisabledOutlineColor=OnSurface × 0.12 的禁用描边加透明容器——渲染出来是一段悬空的彩色小字外面套一圈几乎看不见的轮廓（混色 #2E2C35 对底 1.34:1）。同一个「小标签」概念两套长相：一次会话里从详情页的实心灰药丸切到播放器的悬空紫字。语义也错：M3 的 disabled 表示「当前不可用但本质可交互的控件」，纯状态展示应该用 Badge 或不可交互的 Label，而 Chip 走的是 Role.Button + disabled 语义，TalkBack/Narrator 会念成「已停用」——鼠标移上去没有 hover、光标不变、点了没有涟漪也不发生任何事。

> **改法**：EnhancementChip 内部换成本仓库已有的 `Chip("$label · $suffix")`，或换成 `Row { Box(8dp 圆点，颜色按状态) + Text(labelSmall) }` / Badge——两条路都能去掉「已停用按钮」的语义，也让播放条和详情页的状态标签长得一样。无论选哪种，全 App 只留一套 chip 实现。

### Card / Surface 的点击挂在 Modifier.clickable 上而不是用 onClick 重载：水波纹与 hover 高亮画在 clip 之前，会溢出圆角

**打磨** · 两端 · `DetailScreen.kt:691-704、SettingsScreen.kt:649-653、IdentifyDialog.kt:256-259、MergeDialog.kt:155-158、Theme.kt:128-129、Components.kt:178-187`

SurfaceKt 的私有扩展 surface 字节码末尾依次是 border → `background(shape)` → `clip(shape)`，而 Surface 是 `modifier.then(surface(...))`——调用方传进来的 clickable 排在 clip 之前，其 IndicationModifierNode 按自己节点的完整矩形绘制，不受后面 clip 的 graphicsLayer 裁剪。落点：DetailScreen.kt:691-704（Card + combinedClickable，shape=shapes.medium=18dp）、SettingsScreen.kt:649-653（Surface + clickable，shapes.small=12dp）、IdentifyDialog.kt:256-259、MergeDialog.kt:155-158。于是鼠标划过分集卡片时四个圆角外面各多出一小块方角亮斑，按下时是一个方角水波纹盖在圆角矩形上；18dp 圆角每角溢出面积 0.2146r² ≈ 69.5dp²。PosterCard 因为传了 indication=null 反而免疫。Compose Material3 专门提供了 `Card(onClick = …)`、`Surface(onClick = …)`、`ListItem(onClick = …)`，它们在内部把 clickable 放在 clip 之后。

> **改法**：SettingsScreen.kt:649、IdentifyDialog.kt:256、MergeDialog.kt:155 改用 `Surface(onClick = ...)` 重载（目录行更适合直接用 `ListItem(onClick, leadingContent = { Icon(Folder) }, headlineContent = { Text(name) })`）；DetailScreen.kt:691 因为要保留长按，把 Modifier 顺序改成 `.clip(MaterialTheme.shapes.medium).secondaryClick(...).combinedClickable(...)` 即可。

### 分节标题有三套实现：SectionHeader 组件、六份无 vertical padding 的手写副本、以及首页那个被套两层 20dp 缩进到 40dp 的；标题到正文的间距在 0/8/12/13dp 之间随机，节尾 20/24dp 混用

**打磨** · 两端 · `Components.kt:118-131、SettingsScreen.kt:82、SettingsScreen.kt:115、SettingsScreen.kt:207-208、SettingsScreen.kt:239-240、SettingsScreen.kt:291-292、SettingsScreen.kt:364-365、SettingsScreen.kt:488-489、SettingsScreen.kt:226、SettingsScreen.kt:468、HomeScreen.kt:118-119、PlatformPlayerSettings.desktop.kt:76`

Components.kt:118-131 的 SectionHeader = fillMaxWidth + `padding(horizontal = 20.dp, vertical = 8.dp)` + titleLarge + SemiBold。手写副本恰好六处、样式相同但没有 vertical padding：SettingsScreen.kt:207/239/291/364/488 与 PlatformPlayerSettings.desktop.kt:76。标题到第一件内容的间距逐个复算：媒体库 = :82 SectionHeader（自带 8dp）+ :115 LibraryCard 的 vertical=5dp = 13dp；WebDAV 存储 :207→:208 `Spacer(12.dp)`；刮削源 :239→:240 直接是说明 Text，0dp（titleLarge 紧接 bodySmall，只靠 lineHeight 撑开，是最扎眼的一个）；跨端同步 / 备份与迁移 / 客户端 各 8dp。节尾也有两个值：:226/:284/:315 = 24dp，:468/:589 = 20dp，而 PlatformPlayerSettings.desktop.kt 的 Column 到 273 行结束根本没有 Spacer，SettingsScreen.kt:104-105 两个相邻 item 间距 0dp。容器形态同样不统一：只有第一节是 SectionHeader + Card，其余五节是裸 Text + 裸 Column，整页 7 个分节里只有 2 处有分隔线（:96、PlatformPlayerSettings.desktop.kt:75）。首页还多一层：HomeScreen.kt:118-119 用 `Column(Modifier.padding(horizontal = 20.dp)) { SectionHeader("正在扫描") }`，SectionHeader 自带 20dp ⇒ 标题起点 40dp，比同层所有表头右缩 20dp，而它自己的正文与进度条仍在 20dp。

> **改法**：六处手写标题全部换成 SectionHeader 并删掉各自的首个 Spacer；HomeScreen.kt:118 去掉外层 Column 的 horizontal padding（进度文本改用自己的 20dp padding）；节尾间距统一交给 LazyColumn 的 `verticalArrangement = Arrangement.spacedBy(24.dp)`，让每节自己不再管前后间距。页面标题（LibraryScreen.kt:46 也复用了 SectionHeader）另起一个组件或用 TopAppBar。

### 首页顶部大图没有右键菜单，同一条目在它正下方的卡片上却有

**打磨** · 桌面 · `Components.kt:173、DetailScreen.kt:694、ItemContextMenu.kt:50、ItemContextMenu.kt:102-107、HomeScreen.kt:53、HomeScreen.kt:149-215`

secondaryClick 的调用点只有 Components.kt:173（PosterCard）与 DetailScreen.kt:694（EpisodeRow），定义在 ItemContextMenu.kt:50。真正缺失且成立的只有 HomeScreen.kt:149-215 的 HeroBanner——它是一整块 Box、只有内部 :201/:206 两个 Button 可点、持有的正是 MediaItemDto，右键什么都不出现，而它正下方同一部片的小卡右键就有完整菜单。用户学会「卡片可以右键」之后，会在整屏最显眼的那个元素上失败一次。缓解在于 HomeScreen.kt:53 决定 hero 必为 resume/nextUp/latest 的首条，同一条目必然同时出现在 :88/:95/:102 某一行的 PosterCard 上，那张卡带完整菜单。（库快捷卡、侧栏库项、设置页库卡持有的是 LibraryDto，而 ItemContextMenu.kt:102-107 的 ItemMenuItems 只接受 MediaItemDto，给它们右键属于新功能，不在本条内。）

> **改法**：HeroBanner 的最外层 Box 套上 `secondaryClick` + `DropdownMenu(ItemMenuItems)`，与 PosterCard 用同一段代码。

### 库类型图标里「其他」与「电影」取同一个 ImageVector 完全不可分辨，「番剧」借用播放列表图标，同一个电影图标还兼任全 App 的缺图占位

**打磨** · 两端 · `App.kt:207-212、App.kt:247-248、HomeScreen.kt:240-247、Components.kt:205-210、SettingsScreen.kt:593-598`

App.kt:207-212 的 libraryIcon：`MOVIE -> Icons.Filled.Movie`、`SERIES -> Icons.Filled.Tv`、`ANIME -> Icons.AutoMirrored.Filled.PlaylistPlay`、`OTHER -> Icons.Filled.Movie`——OTHER 与 MOVIE 取同一个字形，侧栏里两个库并排时只能读文字区分，而 App.kt:248 的 label 是 maxLines=1 且没有 Ellipsis，长库名截断后就彻底分不出。HomeScreen.kt:240-245 是同内容的第二份 when（:247 tint=primary），两份 when 各自维护。ANIME 拿到的「列表 + 播放三角」在这个 App 里没有第二处用法，用户无从建立联想。更绕的是 Components.kt:205-210 把同一个电影胶片图标用作所有缺图条目的占位，于是一部没刮到海报的电视剧，卡片中央显示的是电影图标。文字标签那一侧倒是分得清四种（SettingsScreen.kt:593-598）。

> **改法**：OTHER 换成 `Icons.Filled.FolderOpen`（或 Inventory2），ANIME 换成能与 Tv 区分又不误导的字形（如 Animation / MovieFilter）；把两份 when 合并成一个公共 `libraryIcon(kind)` 放进 ui 包供两处调用；缺图占位按 ItemKind 取图标，不要复用电影图标。

### 按钮文案两套规则并存：会弹系统文件框的四个命令只有两个带省略号；一对同级动作叫「立即上传」和「从云端合并」，不对仗

**打磨** · 两端 · `SettingsScreen.kt:88-91、SettingsScreen.kt:108-110、SettingsScreen.kt:225、SettingsScreen.kt:283、SettingsScreen.kt:425、SettingsScreen.kt:437、SettingsScreen.kt:545、SettingsScreen.kt:574、Platform.desktop.kt:152-169、Platform.android.kt:77-79、PlatformPlayerSettings.desktop.kt:104-109`

四个调用点全部走通：SettingsScreen.kt:545「导出」→ saveTextFile()，桌面 Platform.desktop.kt:164-169 `FileDialog(..., SAVE)`、Android Platform.android.kt:77-79 AndroidFilePicker.save，两端都先弹选择器；SettingsScreen.kt:574「导入…」→ pickTextFile() → Platform.desktop.kt:152-156；SettingsScreen.kt:88-91「从 WebDAV 添加媒体库」→ :108-110 打开 WebDavPickerDialog；PlatformPlayerSettings.desktop.kt:104-109「指定 libmpv…」→ FileDialog。带省略号 2/4——Windows / GNOME / Apple 三家 HIG 一致：命令若在执行前还需要用户再提供信息就以「…」结尾，所以用户会以为「导出」立即生效，点下去反而先被要求选保存位置。命名风格另有一处：SettingsScreen.kt:425「立即上传」与 :437「从云端合并」是一对反向操作却不共词干（一个强调时机、一个强调方向和行为）；同页 :225「保存存储设置」、:283「保存刮削设置」带宾语而 :545 不带。

> **改法**：「导出」改「导出…」，「从 WebDAV 添加媒体库」改「添加媒体库…」（路径来源写在按钮下方说明里）；同步区改成「上传到云端」/「从云端合并」或「立即上传」/「立即合并」，让两个词干对齐；保存类按钮统一成不带宾语的「保存」（分节标题已经说明作用域）或统一带宾语，别混。

## 无障碍与键盘可达

### 海报卡与 LinkText 显式关掉 indication：Tab / D-pad 焦点落上去零像素变化，网格自己在滚却看不出选中了谁，按空格就盲开一个详情页

**严重** · 两端 · `Components.kt:178-187、Components.kt:156-157、Components.kt:190、Components.kt:193、Components.kt:223-241、Components.kt:386、Components.kt:390、DetailScreen.kt:698-701、Theme.kt:142`

Components.kt:178-187 的 PosterCard 是 `combinedClickable(interactionSource = interaction, indication = null, ...)`，Components.kt:386 的 LinkText 是 `clickable(interactionSource = interaction, indication = null, onClick = onClick)`。AbstractClickableNode 内部有一个 `FocusableNode` delegate（构造时以 `Focusability.SystemDefined` 传入）并在 applySemantics 中调用它，所以 clickable 默认可聚焦、Tab 可达、Enter/Space 可触发，LazyVerticalGrid / LazyRow 还会带 bringIntoView 自己滚动——画面在动，但 indication=null 意味着连 IndicationNode 都不实例化，焦点态没有任何绘制。PosterCard 的四处视觉反馈（Components.kt:157 scale、:190、:193 tonalElevation、:223-241 遮罩与播放键）全部只读 hovered，LinkText 的下划线（:390）也只读 hovered。同仓库自相矛盾：DetailScreen.kt:698-701 的 EpisodeRow 用的是不传 indication 的重载，拿到 M3 ripple，Tab 过去有 10% 的 focus state layer。WCAG 2.1 SC 2.4.7 Focus Visible（AA）直接不达标；Android TV / 遥控器场景更是硬要求。

> **改法**：两处各加一行 `val focused by interaction.collectIsFocusedAsState()`，把 Components.kt:157/:193/:223 的 `hovered` 换成 `hovered || focused`、把 :390 的下划线条件同样改成 `hovered || focused`——scale 与遮罩都是现成的，等于零新增视觉设计。想更贴 M3 就再给 Surface 加 `if (focused) Modifier.border(3.dp, colorScheme.primary, shapes.large)`。

### LinkText 可点击时写死 primary 并丢弃传入的 color：同一行剧名在紫与暖橙间随数据跳色，与紧邻正文亮度比 1.00:1，触摸目标只有 20dp

**严重** · 两端 · `Components.kt:357-394、Components.kt:361、Components.kt:381-393、Components.kt:388、Components.kt:390、HomeScreen.kt:178-183、DetailScreen.kt:303-308、SettingsScreen.kt:121-126、PlayerScreen.kt:87`

Components.kt:361 声明了 `color` 参数但只有 :366-377 的 `onClick == null` 分支用它，:388 的可点击分支写死 `MaterialTheme.colorScheme.primary`，:390 的下划线只在 hover 时出现（Android 无 hover）。四个调用点里 HomeScreen.kt:178-183 与 DetailScreen.kt:303-308 传的是 secondary，而 onSeries/openSeries 由 `seriesId?.let` 决定是否为 null——同一位置的同一条剧名，能解析出 seriesId 时是紫色 #C9BCFF、解析不出时是暖橙 #FFC98A，颜色随数据完整度变化；SettingsScreen.kt:124 传的 onSurface 是纯死代码（库名恒可点）。对比度实算：深色 primary #C9BCFF(L=0.5560) 对紧邻的 onSurfaceVariant #C9C2D6(L=0.5586) 是 1.00:1、对标题 onBackground 是 1.36:1；浅色 #6C4BF6 对 #48434F 是 1.81:1——WCAG 1.4.1/G183 要求仅靠颜色区分的内联链接与周围文字达到 3:1。触摸目标同样不够：Components.kt:381-393 的 modifier 链是 `hoverable → pointerHoverIcon → clickable`，没有任何 padding，Modifier.clickable 是 foundation 原语、不带 minimumInteractiveComponentSize，命中区严格等于文字排版框——labelLarge 20dp（M3 48dp 的 42%，也低于 WCAG 2.2 SC 2.5.8 的 24px）、titleMedium 24dp。这三处正是「从分集回到剧集页」「进入某个库」的唯一入口。

> **改法**：可点击分支尊重 `color` 参数，把「可点」交给下划线表达而不是颜色，并在非鼠标平台常驻下划线（Components.kt:164 已经在维护 lastPointer）；在 clickable 之前插入 `Modifier.heightIn(min = 48.dp)` 或 `padding(vertical = 14.dp)`（padding 必须写在 clickable 之前才算进命中区）；补 `Modifier.semantics { role = Role.Button }`，现在读屏把它当纯文本。

### TalkBack 播报的「长按」是个死动作：右键/长按菜单被 lastPointer 判断挡住，无障碍派发的 OnLongClick 永远不满足条件

**严重** · Android · `Components.kt:164、Components.kt:184-186、ItemContextMenu.kt:50-58、ItemContextMenu.kt:56、DetailScreen.kt:698-701`

Components.kt:164 `var lastPointer by remember { mutableStateOf(PointerType.Unknown) }`，:184-186 `onLongClick = if (menu == null) null else { { if (lastPointer == PointerType.Touch) menuAt = Offset.Zero } }`；唯一的写入点是 ItemContextMenu.kt:50-58 的 secondaryClick，其 :56 `if (event.type != PointerEventType.Press) return@awaitEachGesture` 会挡掉一切非 Press 事件（TalkBack 触摸浏览阶段送的 hover Enter/Move/Exit 全被挡），:58 才调 onPointerType。而 CombinedClickableNode.applyAdditionalSemantics 只要 `onLongClick != null` 就注册 SemanticsActions.OnLongClick，其 lambda 恒返回 true——所以 TalkBack 会念出「双击并长按可执行长按操作」、操作菜单里也列出「长按」，用户照做却什么都不会发生，而框架 TouchExplorer.onDoubleTapAndHold 那条「注入 MotionEvent 模拟长按」的退路也因为 lambda 恒 true 而不触发。右键菜单里的「播放 / 用 XX 播放 / 标记为已观看 / 收藏 / 查看详情」这一整套快捷操作对读屏用户完全不存在。DetailScreen.kt:698-701 的分集行同款。

> **改法**：把条件从「上次按下的是触摸」改成「上次按下的不是鼠标」：`{ if (lastPointer != PointerType.Mouse) menuAt = Offset.Zero }`——初值 Unknown 就能通过，鼠标长按仍然不会开菜单。同时给 combinedClickable 补上 `onLongClickLabel = "打开菜单"`。Components.kt 与 DetailScreen.kt 两处各改一行。

### 海报卡的「看了一半」对读屏是彻底空白，还额外多出一个只念百分比的无名焦点停靠点，片名被念两遍

**严重** · Android · `Components.kt:167、Components.kt:199、Components.kt:214-221、Components.kt:243-256、Components.kt:274-280、DetailScreen.kt:720-730`

三段机制叠加：①Components.kt:243-256 的 LinearProgressIndicator 内部是 `semantics(mergeDescendants = true) { setProgressBarRangeInfo(...) }`，是一个独立的 merging 节点且自带进度语义值；②AbstractClickableNode.getShouldMergeDescendantSemantics 恒返回 true，卡片本身也是 merging 节点，而 SemanticsNode.mergeConfig 对 merging 子节点直接跳过、getChildren 走 findOneLayerOfMergingSemanticsNodes；③ui-android 的 isScreenReaderFocusable 对 merging 节点直接返回 true，getInfoStateDescriptionOrNull 会把 ProgressBarRangeInfo 生成一个百分比 stateDescription。结果是：进度条不并进卡片的可访问名称（「看了一半」这个状态对读屏完全不存在），却自己变成一个紧随其后的、没有名字、只念一个百分比的焦点停靠点。同时 Components.kt:199 的 AsyncImage 传了 `contentDescription = item.name`，与 :274-280 的标题文本重复，片名被念两遍。整行听下来是「名字念两遍 + 一个孤零零的数字」交替；而 Components.kt:214-221 的已观看 Icon 是三态里唯一可读的一态。

> **改法**：把 Components.kt:199 改成 `contentDescription = null`（图片是装饰，名字下面已经有了）；给 Components.kt:167 的 Column 加 `Modifier.semantics { stateDescription = when { item.userData.played -> "已观看"; progress > 0.01f -> "已看 ${(progress*100).toInt()}%"; else -> "未观看" } }`；给进度条加 `Modifier.clearAndSetSemantics {}` 去掉那个多余的焦点点。DetailScreen.kt:720-730 的分集进度条同样处理。

### 收藏与「手动指定刮削条目」两个 IconButton 的状态没进 contentDescription；后者连视觉上也只靠颜色区分

**严重** · 两端 · `DetailScreen.kt:429-436、DetailScreen.kt:432、DetailScreen.kt:445、DetailScreen.kt:467-476、DetailScreen.kt:775、ItemContextMenu.kt:167、Theme.kt:23、Theme.kt:41`

DetailScreen.kt:429-436 是 `IconButton(onClick = { state.toggleFavorite(item) }) { Icon(if (favorite) Icons.Filled.Favorite else FavoriteBorder, contentDescription = "收藏", tint = if (favorite) error else onSurfaceVariant) }`——描述是字面量，已收藏和未收藏念出来都是「收藏，按钮」，双击之后还是「收藏，按钮」，用户既不知道当前状态也无法确认自己刚才做了什么。DetailScreen.kt:467-476 的 Edit 按钮更重：两态用的是同一个 `Icons.Filled.Edit`、同一句描述，唯一差别是 tint primary vs onSurfaceVariant——纯颜色编码，构成 WCAG 1.4.1 失败，而 Theme.kt:23 的 primary #C9BCFF 与 Theme.kt:41 的 onSurfaceVariant #C9C2D6 在深色主题下还是同族色。同一个 FlowRow 里 DetailScreen.kt:445 的已观看按钮与列表里的 :775 都写了动态 contentDescription，ItemContextMenu.kt:167 的菜单文案也随状态变——同屏两种做法，属于遗漏而非风格。

> **改法**：最小改法把 DetailScreen.kt:432 换成 `contentDescription = if (item.userData.favorite) "取消收藏" else "收藏"`（与 :445、ItemContextMenu.kt:167 保持一致）；更正确的做法是改用 `IconToggleButton(checked = ..., onCheckedChange = ...)` 并加 `Modifier.semantics { stateDescription = ... }`。Edit 按钮除了动态描述（加上「（已锁定 tmdb）」这类后缀）还要给两态不同的字形，别只换 tint。

### 三个对话框都不把 Enter 绑到默认按钮、打开时也不落焦点：Esc 能关但 Enter 不能确认，键盘路径是单向的

**打磨** · 两端 · `IdentifyDialog.kt:130、IdentifyDialog.kt:176-183、IdentifyDialog.kt:198-211、IdentifyDialog.kt:212-217、IdentifyDialog.kt:244-249、SettingsScreen.kt:664-669、MergeDialog.kt:132-139`

IdentifyDialog.kt:176-183（条目 id）、:198-204（片名）、:205-211（年份）三个 OutlinedTextField 全是 `singleLine = true` 且没有 keyboardOptions/keyboardActions；搜索的唯一触发点是 :212-217 的 `FilledTonalIconButton(onClick = { search() })`；:130 的 AlertDialog 没有 FocusRequester，:244-249 的 confirmButton 没有和 Enter 绑定。同形状还有 SettingsScreen.kt:664-669 与 MergeDialog.kt:132-139。而 Esc 是有效的——ui-desktop 的 `Dialog_skikoKt` 读 `DialogProperties.dismissOnBackPress` 去开关 OnBackClickEventHandler，`BackNavigationEventInput.onKeyEvent` 判的正是 KeyDown + Key.Escape，所以 Esc 关得掉、Enter 确认不了，键盘路径是单向的：在「片名」里打完按回车什么都不发生，必须移鼠标去点那个放大镜图标；对话框刚打开时焦点不在任何字段上，第一步永远得先点一下。（搜索按钮本身是 clickable→focusable 且 Enter/Space 可激活，所以 WCAG 2.1.1 并未违反。）

> **改法**：给「片名」加 `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)` + `keyboardActions = KeyboardActions(onSearch = { search() })`；给「条目 id」加 `onDone = { if (providerId.isNotBlank()) apply(providerId) }`；对话框打开时用 FocusRequester 把焦点放到「条目 id」或「片名」上。WebDavPickerDialog 与 MergeDialog 同样处理。

### 全应用 15 个输入框没有一个声明 imeAction：安卓上填任何一段表单都是「打一个字段 → 收一次键盘 → 点下一个字段」

**打磨** · Android · `SettingsScreen.kt:209-218、SettingsScreen.kt:246-264、SettingsScreen.kt:399、SettingsScreen.kt:664、IdentifyDialog.kt:176、IdentifyDialog.kt:198、IdentifyDialog.kt:205、MergeDialog.kt:132、SearchScreen.kt:47、PlatformPlayerSettings.desktop.kt:155`

`grep -rn "keyboardOptions|keyboardActions|ImeAction"`（排除 build）零命中；`grep "OutlinedTextField("` 恰好 15 处：SettingsScreen.kt:209/211/213/246/252/258/264/399/664、IdentifyDialog.kt:176/198/205、MergeDialog.kt:132、SearchScreen.kt:47、PlatformPlayerSettings.desktop.kt:155。全部是 `singleLine = true` 且不传 keyboardOptions ⇒ EditorInfo 落到 IME_ACTION_DONE，软键盘右下角是勾，按下去键盘收起。配置 WebDAV 时填完「地址」要再点「账号」把键盘唤起来，填完再收、再点「密码」；刮削源那一段四个字段同理。而 Compose 的 KeyboardActionRunner 对 Next 的默认动作正是 `focusManager.moveFocus(FocusDirection.Next)`，等于白送。

> **改法**：给每段表单里除最后一个字段外的都加 `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)`，最后一个用 `ImeAction.Done` + `keyboardActions = KeyboardActions(onDone = { 保存() })`；地址字段顺带 `keyboardType = KeyboardType.Uri`，密码字段 `KeyboardType.Password`。

### 搜索页进去不自动聚焦输入框，桌面也没有任何进入搜索的快捷键

**打磨** · 两端 · `SearchScreen.kt:40-44、SearchScreen.kt:47-55`

SearchScreen.kt:47-55 的 OutlinedTextField 只有 value/onValueChange/leadingIcon/placeholder/singleLine/shape/modifier，没有 FocusRequester，文件里也没有请求焦点的 LaunchedEffect；全仓库 grep `FocusRequester|requestFocus|onPreviewKeyEvent` 零命中。于是点底栏「搜索」落在一个唯一用途就是打字的页面上，还得再点一次输入框、再等 IME 弹出才能开始输入；桌面端同样要先用鼠标点进框，且没有 Ctrl+F 或 `/` 之类的快捷键能直接进来。M3 的 SearchBar 组件本身是展开即聚焦并弹 IME 的，这里手搓 OutlinedTextField 把这项丢了。（IME 键那一半在这个页面上几乎无害：SearchScreen.kt:40-44 是 250ms 去抖的增量搜索、没有「提交」这一步，而 KeyboardActionRunner 对 Search/Go/Send 的默认动作本来就是空操作。）

> **改法**：加 `val focusRequester = remember { FocusRequester() }` 挂到 modifier 上，配 `LaunchedEffect(Unit) { focusRequester.requestFocus() }`（进入 Screen.Search 时触发一次）；桌面端顺手在 Main.kt 的 Window 上加 onPreviewKeyEvent 处理 Ctrl+F → `state.navigate(Screen.Search)`。

### 搜索框只有 placeholder 没有 label，输入之后读屏念不出这是什么框

**打磨** · Android · `SearchScreen.kt:47-55、SearchScreen.kt:50、SearchScreen.kt:52`

SearchScreen.kt:47-55 的完整参数只有 value、onValueChange、leadingIcon、placeholder、singleLine、shape、modifier——没有 label，也没有在 modifier 上加 semantics（全树 grep semantics 零命中）；:50 的 leadingIcon 传 `contentDescription = null` 本身是对的做法，但也堵死了第二个名称来源。M3 OutlinedTextField 的装饰盒只在 value 为空时组合 placeholder，所以一旦打了字，语义节点上就只剩 EditableText，既无 contentDescription 也无 label——TalkBack 念的是「钢铁侠，编辑框，双击以编辑」，用户把焦点移开再移回来时完全不知道这个输入框是干什么的。W3C 明确把「只用 placeholder 当 label」列为失败案例 F82，WCAG 3.3.2 与 4.1.2 都要求持久的可访问名称。

> **改法**：给 SearchScreen.kt:47 的 OutlinedTextField 加 `label = { Text("搜索") }`（M3 会把它上浮成持久标签）；若不想要浮动标签，就在 modifier 上加 `.semantics { contentDescription = "搜索媒体库" }`。

---

## 设计上做得对的地方

- LoadingPane 的 150ms 延迟阈值是对的：Components.kt:101-115 先 `delay(150)` 再画 ContainedLoadingIndicator，短查询根本不会闪出指示器——这正是加载指示器该有的做法，可惜它只被 App.kt:89/92 与 LibraryScreen.kt:71 用上，详情页与搜索页没接。
- PosterCard 的桌面悬停做得比多数同类都足：Components.kt:157 的 scale 1.04 走 spring（stiffness 1500，约 69ms 沉降），Components.kt:223-241 在 hovered 时铺 `Brush.verticalGradient(Transparent → scrim@0.55)` 并在正中画一个 primary 底色的圆形 PlayArrow——可感知性远超一个 6dp 阴影，桌面端「这张卡可以操作」这件事传达得很清楚。
- secondaryClick 对鼠标与触摸的区分是一个考虑过的输入模型，不是随手写的：ItemContextMenu.kt:50-71 的手势只在 Press 时上报 PointerType，Components.kt:184-186 据此让 `onLongClick` 只对触摸生效，并在注释里写明「A held mouse button is not a request for the menu — it already has the right button」。方向是对的，只是判断条件写成了 `== Touch` 而不是 `!= Mouse`，才把 TalkBack 挡在门外。
- 备份导入与云同步的 busy 状态机是完整的：SettingsScreen.kt:557 置位、:570 finally 复位、:527 的 `enabled = !busy` 防重复提交、:577-580 的 LinearWavyProgressIndicator，同步区 :440-443 是同一套写法——长耗时操作该有的四件事一件不少，只是并排的导出分支忘了接上同一条链路。
- 状态描述并非全线缺失，而是有明确的正确样板：DetailScreen.kt:445 的已观看按钮与 :775 的分集按钮都写了随状态变化的 contentDescription，ItemContextMenu.kt:167 的菜单文案同样随状态变；同理 DetailScreen.kt:698-701 的 EpisodeRow、SettingsScreen.kt:653、IdentifyDialog.kt:259 用的都是保留默认 ripple 的 combinedClickable。作者知道正确写法长什么样，缺的是把它推广到全部同类调用点。

---

## 方法与边界

**17 个维度各派一个审查者读代码。**
配色与对比度、排版、形状与海拔、间距、动效、导航结构、响应式、桌面窗口、桌面指针、键盘与焦点、Android 系统 UI 集成、触摸交互、反馈与状态、Material 3 组件用法、播放器界面、无障碍、跨屏一致性。

**每条都被对抗验证。**
验证者要复算数值（对比度按 WCAG 相对亮度实算、可用宽度按实际 dp 推、触摸目标按组件默认尺寸查）、核对引用的规范是否真实存在、判断是不是纯审美偏好、以及是否与 UX-REVIEW.md 重复。129 条提出、109 条存活。多个维度反编译了 material3 / foundation / ui-desktop / androidx.activity 的实际 artifact 来核对默认值，而不是凭记忆引用规范。

**桌面窗口尺寸这一条做过额外核实。**
两个审查者对默认窗口给出了互相矛盾的数值，我反编译 `ui-desktop` 的 `Windows_desktopKt.setSizeImpl` 确认：`DpSize` 的数值被**直接当 AWT 物理像素**用（`roundToInt` 后 `coerceAtLeast`，全程不乘 density，也不与屏幕工作区取交集）。所以窗口恒为 1360×900 物理像素，窗口内的 `maxWidth` = 1360 ÷ 系统缩放，系统缩放 ≥200% 时会掉到 720dp 断点以下。报告里采用的是这个结论。

**没有跑起来看。**
全部结论来自读代码与反编译依赖，没有实际构建运行、没有截图、没有真机。涉及具体像素观感的判断标的是代码上的成因与算出来的数值，实际效果需要你在真机和不同缩放率的显示器上确认。
