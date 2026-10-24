# Freezer（冰箱）

Wear OS 上的应用冻结工具。把不常用的应用「冻」起来（`pm disable-user --user 0`），需要时再解冻
（`pm enable`），省得它们在后台占内存、也省得在启动器里碍眼。比卸载温和：数据保留，随时可还原。

特权执行走 [Shizuku](https://shizuku.rikka.app/)：命令直接交给 Shizuku 服务端（跑在 shell
或 root 权限下的进程）执行——`Shizuku.getBinder()` → `IShizukuService.newProcess(["sh","-c",…])`，
读回退出码与输出。应用本身不需要 root，也不再自己起子进程。已 root 的设备可在设置页改用 Root（`su -c`）。

## 界面

- **主界面**：跟踪列表，整行可点直接冻结/解冻；长按某行把它置顶（再长按取消），
  置顶区内按应用名排序，与冻结状态无关，置顶行另有底色。拨完开关那一行不会当场跳走，
  顺序是快照，回到主界面才按「未冻结在前」重排；外部工具改的冻结状态也在这一刻校正。
- **添加页**：全部已安装应用，第三方在前、同类按名字排序，已勾选的应用排在最前面。
  底部两枚图标：搜索与筛选。搜索框是**原生 `EditText`**（`AndroidView` 嵌进来）：
  Compose 自带那套 `InputConnection` 与 Gboard 不同步，打好的字不实时回显、退格也跟不上。
  勾选/取消**不会**让那一行或整张列表跳位——顺序是进入本页时定的快照，切走再回来或改筛选条件才重排
  （与主界面同一套做法，否则拨完开关视线就被拽到列表另一端，想接着往下滑还得从头滑）。
- **筛选页**：三组条件——应用类型（全部 / 第三方 / 系统）、启用状态（全部 / 已启用 / 已禁用）、
  启动器可见性（全部 / 可显示 / 不显示；筛的是有没有 MAIN/LAUNCHER 入口，按结构判断，
  被我们冻掉的包仍算「可显示」，这一组真正筛出来的是库里包和只挂服务/provider 的包）。
  条件存在共享状态里，右滑回添加页即生效，不需要确认按钮；生效时筛选按钮走主色，
  列表首行说明当前是按什么筛的，另有「重置」。
- **设置页**：权限来源（Shizuku / Root）及它的实时可用性——缺授权时给「授权」按钮，
  已授权但没实测过时给「验证」按钮（按下才让服务端跑一条 `id`），
  界面语言（自动 / 中文 / English，切换即重建界面），屏幕形状（自动 / 圆表鱼眼 / 方表平铺，
  并显示系统报的分辨率与圆方），伪装模式，关于，运行日志导出。
- **关于页**（独立一页）：GitHub 头像、项目简介、仓库地址，下面两个动作——
  「二维码」本地用 zxing 生成给手机扫（不联网、不需要 INTERNET 权限），
  「在手机上打开」走 `RemoteActivityHelper` 把链接交给配对的手机执行。
- **伪装模式**：启动器入口换成一个 `activity-alias`，图标与名称跟着变（「耳机控制器」），
  打开后先是一块居中的假门扉页——图标、名称、一句「请连接到手机后继续」，
  **长按两次**才进主界面。开关存 SharedPreferences 同步读，否则第一帧会把真界面闪出来。
- 表冠滚动带逐格触感，由 Wear Compose 的 rotary snap 行为发出，只挂在表冠事件上；
  手指拖动与甩动都不震。

## 前置条件

1. Android 8.1 及以上（`minSdk 28` / `targetSdk 35`）。**不要求 Wear OS**：清单里
   `android.hardware.type.watch` 是 `required="false"`，所以只跑普通安卓、不声明该 feature 的
   安卓手表（以及手机/模拟器）也装得上——之前 `required="true"` 会被系统直接拒装。
   界面仍按 Wear 的样子画（Wear Compose 的鱼眼列表 + 顶部时间），没有换成手机版 Material。
   实机验证机型 Samsung Galaxy Watch 4 / SM-R940。清单里另有 standalone 元数据，不需要搭配手机端。
2. Shizuku 服务端已启动并授权给本应用；或者设备已 root 并在设置页把权限来源改成 Root。
   **服务端必须起在手表上**：手机端 Shizuku 授权的是手机里的进程，不会让手表上的应用拿到权限。
   用 Shizuku 里「通过 ADB / 无线调试启动」给的那条命令，改成 `adb -s <手表> shell` 执行。

圆表方表自动识别：圆屏走鱼眼缩放，方屏关掉缩放（`edgeScale = 1f`、纵向偏移归零），
设置页可以强制指定。判定只看 `Configuration.isScreenRound`，存 SharedPreferences 同步读——
缩放参数在第一次组合就要定下来，等 DataStore 会先闪一版错的。

枚举其他开发者的应用需要 `QUERY_ALL_PACKAGES`，除此之外不申请权限——逐格触感走
`View.performHapticFeedback`，振动由 system_server 触发，不需要 `VIBRATE`。

## 构建

JDK 17、Gradle 8.13（用仓库自带的 `gradlew`）、AGP 8.7.3、Kotlin 2.1.0、
Wear Compose 1.4.0、Shizuku 13.1.5、zxing 3.5.4（二维码）、
wear-remote-interactions 1.2.0（在手机上打开），`compileSdk 35` 配 build-tools 35.0.1。

需要本地签名文件 `keystore.properties`（与 `settings.gradle.kts` 同级，已被 `.gitignore` 排除）：

```properties
storeFile=D:/keystores/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

缺这个文件时 `assembleRelease` 会在 `validateSigningRelease` 明确报错，不会静默产出未签名的包。

**上表实测请用 `assembleRelease`，别用 `installDebug`**：debug 包没经过 R8、资源也不收缩，
在这块表上掉帧是 release 的三到五倍，滚动与表冠触感的观感都不能信（详见下面「性能测试请用
release 变体」）。debug 变体只适合确认「装得上、界面起得来」。

想要一个既收缩又能就地覆盖 debug 包的产物：把本机 `~/.android/debug.keystore`
（别名与口令都是 `androiddebugkey` / `android`）临时写进 `keystore.properties` 再
`assembleRelease`，签名与 `installDebug` 同一张证书，`adb install -r` 不会丢应用数据。
用完把这个文件删掉，否则下次 `assembleRelease` 会静默产出「调试证书签的 release 包」。

```
./gradlew assembleRelease      # 混淆 + 资源收缩后的包
```

**项目目录名含中文时别动 `gradle.properties` 里这两行**：
`android.overridePathCheck=true`（缺了它 AGP 直接拒绝非 ASCII 路径）和
`kotlin.compiler.execution.strategy=in-process`（缺了它 Kotlin 守护进程会把中文路径
传成 `\uXXXX`，表现为 "source file or directory not found"）。本项目纯 Kotlin + Compose、
无 NDK，关掉路径检查是安全的。

## 代码结构

- `core/`——`FreezeManager`（把命令交给 Shizuku 服务端或 `su -c`，解析退出码与输出）、
  `RunLog`（进程内环形缓冲 + 导出）、`LocaleManager`（语言：同步读写 SharedPreferences +
  按语言包装 Context）、`DisplayShapeStore`（圆方屏判定与设置）、
  `Masquerade`（伪装模式：开关 + 启动器 alias 切换）。
- `data/`——`AppsRepository`（枚举应用并落盘缓存）、`SettingsRepository`（DataStore：
  跟踪列表、置顶集合、权限来源、「上次实测通过」的结论）。
- `ui/`——三个界面 + 筛选页 + 伪装门扉页 + 共用的行/项组件，状态集中在 `FreezerViewModel`。

## 已知问题与排查

**命令都在 Shizuku 服务端进程里执行**：拨开关、点「验证」、回前台那一次预热，都是
`IShizukuService.newProcess` 的一次调用，实测几十到几百毫秒，不再有「第一次要等半分钟」这件事。
早先的版本走 `bindUserService`（自己起一个 `:freeze` 子进程），代价是每次冷启动要等 3.5~28 秒绑定、
超时得设到 60 秒、还要处理绑定途中宿主进程被系统杀掉导致那次操作无声丢失。这三件事现在都不存在，
所以 `service/` 那一层（手写 `Binder` 与协议常量）整个删掉了。

**状态判定的红线**：启动、回到前台、binder 回调都只做轻量判定（服务端在不在、授权有没有、
上次实测过没有），而且一律放 IO 线程——`pingBinder` / `checkSelfPermission` 是打到管理器进程的
binder 事务，这块表上「我们」和「Shizuku」都会被 Freecess 冻结（真机日志：
`FZ : com.watchfridge.freezer(10230), reason: LEV` 连着 `UFZ … reason: Cfb` 隔了 3.8 秒），
对方冻住时事务被推迟，放主线程就是干等卡帧。三个回调（binder 起来 / binder 掉了 / 授权结果）
由 Activity 在 `onStart`/`onStop` 挂摘：少了 binder-dead 那个，本地缓存会让状态一直停在
「已就绪」而每条命令都失败——这正是「经常拿不到授权」的形状。

**第一次列出应用较慢**：逐个应用取显示名（`loadLabel`）要读对方包里的资源，
是整条链路上唯一贵的一步，300+ 个应用实测 5–19 秒；没有缓存时列表每 40 条发一次
中间结果，边扫边填而不是一片空白。结果会落盘缓存，之后只有安装路径变了
（换版，即 `/data/app` 下那个随机段）或固件标识（`Build.DISPLAY`）变了才重新取名；
冻结状态不进缓存，每次都现取。缓存格式换版（`apps_v2` → `apps_v3`，加了「能否在启动器显示」
一列）之后第一次启动会整表重取名，真机实测 `apps scanned=332 cached=0 relabeled=332 in 10995ms`，
之后就回到几百毫秒。

**「Shizuku 在运行，但本应用没连上」是另一种故障**：本应用进程长期不死（真机见过 19 小时），
中间 Shizuku 管理器重启过一次，客户端手里那个 binder 就成了死的，而 Shizuku 客户端不会自己
重新握手——`pingBinder()` 从此恒为 false。日志特征：这次启动没有 `binder received`，状态停在
`SHIZUKU_NOT_RUNNING`，可 `ps` 里 `shizuku_server` 明明在。所以判定不只看 `pingBinder`：
拿不到 binder 时再去解析一次管理器的中转 provider，解析得到就报「没连上」并给一个「重启」按钮
（新进程会重新握手），解析不到才报「服务未启动」。注意 provider 解析得到只证明**管理器应用装着、
进程在**，不证明服务端已启动，所以那句提示给的是两步：先重开冰箱，仍不行再去 Shizuku 启动服务。
不自动重启本应用——应用自己消失再回来，比一句提示更让人不信任。

**命令失败时的三种形状**（导出日志头部有一行 `running= granted= serverVersion= relay=`，
一眼能分清是哪一种）：

- `Shizuku 未授权给本应用`（`SecurityException`）：授权没落到本包上。在 Shizuku 的已授权列表里
  确认包名 `com.watchfridge.freezer`，或在设置页点「授权」。
- `Shizuku 服务端不在线`（`pingBinder=false`）：服务端没起在手表上，或者管理器应用被省电策略
  清了（日志里会有 `FZ : moe.shizuku.privileged.api … reason: LEV`）。打开一次 Shizuku 应用即可恢复。
- `pm` 自己报错（退出码非 0）：输出原样带回界面，通常是那个包当前状态不允许这个操作。

`relay=` 是旧方案留下的字段：走 `bindUserService` 时它解析不到就是干等 60 秒绑定超时，
日志形如 `E ShizukuServiceStarter: provider is null moe.shizuku.privileged.api.shizuku` 加
`System.exit called, status: 1`。现在它只剩「管理器应用在不在」这一点诊断价值。

**表冠触感别自己按滚动位置补一发**：`RotaryScrollableDefaults.snapBehavior` 的
`hapticFeedbackEnabled` 默认就是开的，它只在表冠事件上发触感，并按机型查触感常量表
（三星 `SM-R*` 用 101/102/50107，API 34 及以上走 Wear Input SDK）。自己再监听
`listState.centerItemIndex` 补一发有三个后果：手指拖动也照样改 index 所以手滑也震、
表冠每一格变成两下叠在一起发闷、快转时被自己的节流间隔吃掉节奏。

**界面语言存在 SharedPreferences，不在 DataStore**：语言必须在第一个 Activity 的
`attachBaseContext` 之前就定下来，而 DataStore 的读是异步的（真机首读实测 0.1~2.9 秒）。
早先版本用 `runBlocking { withTimeoutOrNull(1000) }` 去等它，一旦超时那整场启动就按
AUTO 显示——选了中文或 English 也不生效，得再启动一次。

**「隐藏图标」这个功能已经移除**：官方 Wear 启动器按「包」而不是按 activity 列条目，
第三方应用禁用启动组件只会让那个条目变成打不开，不会消失——只有系统应用做得到真隐藏。

**性能测试请用 release 变体**：同一份代码在这块表上，debug 包滚动掉帧 32–55%、
中位帧 36–69ms；release 包 4–12%、22–30ms，与系统设置应用同档。用 debug 包测出来的
数字不能当结论。

## 诊断

设置页 → 导出日志，会把本进程的运行日志（授权探测结论、每条 `pm` 命令的退出码与耗时、
Shizuku 侧状态快照、跟踪与置顶列表）连同设备信息写到应用外部目录，路径显示在界面上。
文件名固定，`adb pull` 那行命令不用每次改：

```
adb pull /storage/emulated/0/Android/data/com.watchfridge.freezer/files/freezer-last.log
```

同一份日志镜像到 logcat（tag = `Freezer`），能连上 adb 时不必导出：

```
adb logcat -d -s Freezer:*
```

环形缓冲只活在本进程内：要在出问题的那一次之后、进程被杀之前导出。
命令都在 Shizuku 服务端进程里跑，那边不归本进程记日志——但退出码和输出摘要已经随
`pm … -> exit=… in …ms …` 这一行落在我们的缓冲里，够定位是哪一步了。

## 许可证

Apache License 2.0，详见 [LICENSE](LICENSE)。
