# Android / Termux 派生运行环境研究草案

核验日期：2026-10-14（Asia/Shanghai）。范围：独立 Android App，内置 Termux 派生环境及复用代码；不依赖安装外部 Termux，不使用 SSH 连接本地环境。用户已确认 **APK 先行、之后考虑 Play，接受 GPLv3**。本文区分 **事实**、**建议** 和 **待验证**；这是源码/文档研究，尚未完成 Android 真机验证。

## 1. 首先定下的边界

**建议：**本地运行环境与终端画面放在同一 Android 应用、同一 UID 的沙盒内。重点复用 Termux 的包构建配方、Android 兼容补丁、环境组装与 bootstrap 机制；PTY/进程层优先由本产品用 Zig 封装系统接口，Termux 源码作为行为参考或可选局部复用；终端状态机采用 Ghostty。不要同时让 Termux emulator 和 Ghostty 解析同一会话作为双重真相来源。新应用不需要 `RUN_COMMAND` 权限、外部 Termux:API 插件、本机 sshd、跨 App PTY 桥或共享 UID。

**事实：**Termux 主仓库分别包含 app、terminal-emulator、terminal-view、termux-shared；其中 JNI 创建 PTY、fork 子进程、设置会话、连接标准输入输出，另有 resize 和 waitpid 接口。`TerminalSession` 本身也绑定 Termux emulator，因而不将整个类作为 Ghostty 接入依赖；PTY 是系统机制，本产品可独立封装。若局部复用该代码，需保留来源声明。[S1][S2]

**建议接口：**`LocalPtyBackend.open(argv, env, cwd, cols, rows)` 返回会话句柄；向上仅提供原始字节读写、`resize`、退出状态和关闭。UI/终端核心不应知道安装路径或包管理器细节。SSH 后端可实现相同会话接口，但本地不经过 SSH。

## 2. SDK 与二进制执行：APK-first 是取舍，不是消除平台约束

| 设置 | 意义 | 本项目暂定建议 |
| --- | --- | --- |
| `compileSdk` | 构建时可引用的 Android API；不等于开启该版本全部行为变化 | 36 起步；依赖要求更高时再提高并验证工具链 |
| `minSdk` | 允许安装/运行的最低 Android API；同时受所选库最低版本约束 | 26（Android 8）作为讨论基线，优先验证 Android 12–17；不是用户已经确认的决定 |
| `targetSdk` | 应用承诺适配的行为版本，影响执行/权限/后台兼容行为与商店准入 | APK-first 首版可评估 28；它是有生命周期的兼容路线，不能承诺长期可用 |

**事实：**本次读取 Termux 主线 `gradle.properties` 的配置为 `compileSdkVersion=36`、`targetSdkVersion=28`、`minSdkVersion=21`、NDK `29.0.14206865`。这直接证明“新 compile SDK”和“旧 target SDK”可以分开选择；它不代表本项目应该支持 Android 5。[S3]

**事实：**Android 10 对 target API 29+ 的非受信任应用限制直接 `execve()` 可写应用目录内的文件。将 bootstrap 解压到 `files/usr/bin`、设置可执行位，不能自动解决这个限制。APK 中的 JNI 动态库可正常按平台方式加载，与下载的任意 shell 工具直接执行不是同一问题。[S4]

**事实：**Android 15 阻止安装 target<24 的应用；target28 高于该门槛。但这条文档不能推出所有未来 Android、OEM ROM、企业策略和安装渠道都接受 target28。主线 Termux 的实际配置仅是当前可参考的实现，不能替代测试。[S5]

**事实：**核验时 Google Play 要求自 2026-08-31 起新应用和更新 target API36+，页面说明特定延期到 2026-11-01。因此 target28 的首版不满足常规 Play 新应用要求；直接分发 APK 与 Play 路线应分开设计。[S6]

**建议：**固定一个公开、长期使用的 release 包名和签名，先发布签名 APK。不得把较低 target 当作免除 Android 所有后台/内存/安装限制的保证。系统 API 仍按设备版本显式分支；评估依赖引入的最低 SDK、清单和 target 要求。旧 target 下通知权限弹窗等行为也需单独测试。未来提高 target 是一次运行环境迁移项目，而不是只改 Gradle 数字。

**选型门槛：**按已确认 APK-first 范围，`compile36 / min26 / target28` 是第一轮主线派生原型的候选基线，先在目标设备通过普通用户正常安装、启动、执行、更新和后台测试，才冻结为首版配置。不得把 ADB 绕过低 target 安装限制、root 或修改 SELinux 作为普通用户路径。若兼容门槛不通过，转向现代运行环境验证；参考 Play 分支时以 min30 起步并重新验证其 exec 模型。现代 target/Play 是另一个决策门槛，不并行承诺维护两套包仓，也不承诺将来只改 target 即可上架。

## 3. 主线与 Google Play 分支到底怎么做

| 路线 | 已核实实现 | 对本项目的意义 |
| --- | --- | --- |
| 主线 Termux | 当前源码保留 target28；软件包仍在私有 PREFIX 内 | APK-first 可以从这套已存在的运行模型派生；需要独立包构建/仓库和设备验证 |
| Play 分支 | 独立 `termux-play-store/termux-apps`、软件包与 termux-exec 分支；当前 `gradle.properties` 为 min30、compile37、target37、NDK30 | 已经存在现代 target 的技术实现，但兼容代价不能隐藏，也不能直接推定本项目获准上架 |
| 全部工具随 APK 更新 | 平台文档推荐执行 APK 内提供的代码 | 可以承载有限工具集，但不能直接宣称它等价于完整的 `pkg install` 与本地编译开发环境 |

**事实：**Play 分支的 `termux-exec` 文档解释了 `LD_PRELOAD` exec 拦截：把执行 ELF 改为 `/system/bin/linker64 /absolute/path/to/elf`，同时处理 shebang。其已列出的边界包括：依赖保留 LD_PRELOAD；直接调用 exec 系统调用的程序需额外适配；静态链接二进制不适用；通过 `/proc` 判断可执行程序的工具可能要修改。此处不是“所有包都变成 APK”的方案。[S7][S8]

**事实：**Play 组织更新记录在 2026-02-07 仍提到 Go 及相关软件包针对 linker execution 的修复。主线 README 也将 Play 称为实验分支，并指出功能/缺陷差异；网上旧资料中的“Termux 完全不能在 Play 发布”已不准确。[S1][S9]

**待验证：**本文只核实上述分支的当前配置和说明，没有审计它们所有补丁。主线 termux-exec 的后续演进也不应等同于 Play 分支。实施时固定上游 commit、逐项确认需要的 exec 修补和依赖，避免把移动的 `master/main` 当成复现版本。本地 C/Go/Rust/Zig 构建产物、Python/Node 子进程、proot、语言工具链及静态 ELF 是提高 target 前的兼容验收范围。本文不对应用商店政策作法律或审批结果判断。

## 4. 包名、PREFIX 与仓库是发行版工程

**事实：**Termux 官方说明更改包名需要以新 PREFIX 重建 bootstrap 和其他软件包。软件中的 FHS 路径在构建时替换，默认原版路径为 `/data/data/com.termux/files/usr`。新的包名不是简单改 UI 名称；把原版 bootstrap 解压到另一沙盒、仅设置环境变量并不能保证二进制路径正确。[S1][S10]

**建议目录合同（`<applicationId>` 为占位符，不是已决定包名）：**

```text
/data/data/<applicationId>/files/home       用户目录
/data/data/<applicationId>/files/usr        固定 PREFIX，软件包和 dpkg 数据库
files/runtime-state/                       安装/迁移状态、版本和锁
files/runtime-staging/                     首次安装的临时内容
files/session-state/                       会话元数据及可选有限滚动记录
```

**建议：**尽早固定一个较短包名及 PREFIX。为 arm64-v8a 构建应用、bootstrap 和所有随附运行库；x86_64 用于模拟器/测试，需要独立同前缀包产物。不要一开始承担所有 ABI。构建器配置包名、路径、仓库 URL、签名信任根；保留上游配方并维护少量可追踪补丁。`sources.list` 默认只指向本项目签名、按 ABI 发布的仓库，不指向为 `com.termux` 编译的上游二进制源。上游源是配方/源码来源，不是可无条件复用的二进制仓库。[S11]

**建议包集：**首版维护 shell、coreutils、findutils、grep/sed、tar/压缩工具、CA 证书、curl、apt/dpkg、git、openssh 客户端、terminfo 与 termux-exec 所需部分；再按用户范围加入 Python、Node、tmux、编辑器和编译器。完整 Termux 仓库兼容是长期维护目标，不作为第一版承诺。为每个包保存源码版本、补丁、构建日志、依赖、校验和及许可证。仓库元数据签名，固定信任根，保留上一个可恢复的已发布仓库快照。

**待验证：**次用户、工作资料、应用克隆、可采用存储改变实际路径/挂载可见性，可能与编译时前缀冲突。首版明示支持经过测试的常规主用户内部存储安装；其他场景先检测并给出原因，不凭路径字符串强行运行。应用 ID suffix 的 debug 包同样需要匹配其前缀的 runtime，不可偷用 release runtime。

## 5. Bootstrap、迁移与更新的真实可靠性边界

**事实：**Termux 安装器使用 staging prefix，解压完成后 rename 到正式 prefix；这可作为首次安装的参考。dpkg 明确存在 half-installed、unpacked、half-configured 等中间状态，并有修复/审计命令，因此不能将 apt/dpkg 的多包升级描述为“整批原子事务与自动回滚”。[S12][S13]

**建议首次安装状态机：**`Absent → Extracting → Verified → Activating → Ready`，任何失败进入 `RecoverableFailure`。验证签名/哈希、ABI、前缀清单、可用空间、文件数量/大小、目录穿越与符号链接目标。在同一文件系统 staging 解压；完成权限与静态验证后，以 rename 激活正式目录；写入可恢复状态及安装版本。激活后做 shell 动态执行自检，成功才允许创建会话。不得在临时前缀执行依赖正式绝对路径的完整包配置脚本，随后再期待迁移路径自动正确。

**建议锁与迁移：**runtime 安装/重大迁移持有应用级 OS 文件锁，而非仅协程 mutex；apt/dpkg 尊重它们自己的锁，UI 包管理操作与手动命令并存时不能通过删除锁文件“修复”。保留迁移日志，每一步可重入；安装/迁移时暂停新本地会话。普通更新不要重建或覆盖 home。schema/设置数据库迁移与 Linux runtime 升级分别版本化。重大 PREFIX 替换仅在所有本地会话停止、备份校验成功后进行；不能在活跃进程持有旧库/文件时承诺无缝切换。

**建议日常包升级：**显示下载/校验/解包/配置进度与完整结果；失败后保留包数据库、日志和缓存，提供诊断与受控修复流程。升级前可预下载、验证磁盘空间并导出包清单；需要回退保障时做用户主动备份或明确成本的整环境快照。APK 回滚不等于 runtime 回滚。只有首次安装目录切换是有限的原子步骤，不能将其扩大描述为所有包操作、用户文件和进程状态都事务化。

## 6. PTY、会话生命周期与 Android 后台

**建议：**Kotlin `SessionService` 持有会话生命周期，先采用同进程 service，Activity/Compose 仅 attach/detach 视图。原生 PTY 层保持小而清晰：打开 master/slave、fork/exec、setsid、控制终端、dup2、初始窗口大小、连续读取、`TIOCSWINSZ` 和 waitpid 回收。建议用 Zig 实现这层系统调用封装，以 Termux 源码作为行为核对清单；argv/env/cwd 采用明确参数。直接保留少量现有 C 实现也是可选路径，不要求引入整个 Termux App 或 TerminalSession。无论使用何种语言，都需验证交互式 Shell、作业控制、信号、resize 和退出竞态。[S2]

**建议：**每个会话一个 shell/进程组和一个终端核心实例；后台线程/调度器读写 PTY，不能在 UI 线程阻塞。采用有限队列和背压，避免大量输出无限占内存；渲染节流与终端解析分开。resize 向后端更新列/行，Ctrl-C 正常按终端输入语义传递；“关闭会话”独立执行挂断/终止与必要的超时清理，不依赖向屏幕发送 `exit` 字符串。PTY EOF、EIO、子进程退出和用户主动关闭分别处理，退出码不等于 UI 是否仍存在。

**建议恢复语义：**旋转/后台/视图销毁时，只要 service 仍活着，会话保持；进程死亡后只恢复标签页元数据并标记“会话已结束”，不得伪装恢复原进程。tmux 可作为可选工具，但同设备上的 tmux 不能抵抗 Android 杀掉它所在的全部进程。持久化滚动记录也不等于复活会话；默认不要记录输入或秘密。

**事实与建议：**后台策略必须区分设备版本和 target，不能机械给所有版本写同一种 FGS 类型：

| 情况 | 策略 |
| --- | --- |
| 当前 APK-first target28 | 仿照主线，声明基本 `FOREGROUND_SERVICE`，从可见界面由用户启动会话并及时提升 FGS、显示持续通知；新系统上仍验证系统/OEM停止行为 |
| 将来 target34+ | 必须声明适合的 FGS 类型及权限；`specialUse` 是合理候选，需说明持续的用户发起终端任务，不能承诺 Play 会批准 |
| `dataSync` | 仅可用于实际下载/同步，不把无限期终端会话伪装成数据同步；target35+ 背景限时存在 |
| `shortService` | 约三分钟用途，不适合任意长度 shell/SSH 会话 |
| `connectedDevice` / `systemExempted` | 不因“SSH连接远程服务器”或“需要保活”而默认套用；必须符合真实用途与前提 |

**事实：**Play Termux 当前清单使用 `specialUse`、`FOREGROUND_SERVICE_SPECIAL_USE` 和用途说明，这是可参考的实际实现。[S14] Android 对 target31+ 后台新启 FGS 有限制；target34+ 有类型要求；target35+ 的 dataSync/mediaProcessing 存在后台限时。[S15][S16][S17]

**建议：**只在有活跃会话/明确后台任务时保留 FGS；通知显示会话数、返回和停止操作。提供用户主动开启的有期限 wake lock；不用永久 wake lock 或 WorkManager 来假装长连接保活。Android 13+ 的用户停止入口可以结束整个应用，FGS 不是永生保障。低内存、厂商省电、强行停止和重启均作为正常可解释终止处理。后台 shell 子进程数量/CPU限制也须真机测试，不依赖开发者选项作为普通使用前提。[S18][S1]

## 7. 16 KB 页、ABI 与构建验收

**事实：**Android 15 起支持 16 KB 页设备；官方建议升级到支持的打包工具链，NDK r28+ 默认生成 16 KB 对齐。已有 native 库仍需重编，运行时代码也不能固定假设 PAGE_SIZE=4096。检验对象包括 ELF 段对齐与 APK 内库的 zip 对齐，二者不同。[S19]

**建议：**Ghostty/Zig 产物、JNI PTY、SSH 原生依赖、字体/图形库、bootstrap 和仓库中的原生工具都纳入 arm64 16 KB 验证，不能仅检查 APK 的一个 `.so`。Zig 构建独立确认 ELF program headers；不能因 NDK 升级就假定所有 Zig/预编译产物自动合规。使用 `getpagesize`/`sysconf` 获取真实页大小，审查 mmap/对齐运算。测试实际 16 KB 模拟器/设备上的启动、fork/exec、shell、包安装、dlopen 和绘制，另外保留 4 KB 设备回归。首版不依赖系统兼容模式兜底。

**待验证：**工具链版本应按 Ghostty 固定版本、Android Gradle Plugin、NDK/Zig 的兼容交集锁定；本文所述上游当前版本不是要求立即选择其最新版。Play 页尺寸政策也可能变化，准备上架时重新核验，不把商店日期当作侧载设备兼容性的截止日期。

## 8. 许可证与最小交付门槛

**事实：**Termux 主仓库声明 GPLv3-only，并列出部分 Apache 2.0 来源；termux-shared 总体 MIT 声明下有 `shared/termux/*` 的 GPLv3-only 等例外，不能把整个依赖视作 MIT。单独发行的软件包还具有各自许可证。[S20][S21]

**建议：**按用户接受的 GPLv3 开源方向管理复用，并尊重 Termux 相关代码的 GPLv3-only 标识：保留版权/修改记录，固定来源与版本，提供对应源码、补丁、构建说明以及依赖清单；应用、runtime 包、字体、主题分别登记许可。不要沿用 Termux 官方签名、名称或服务身份。最终分发和组合边界应逐组件核对，本文不作“任何链接形式必然产生某种法律后果”的绝对推断。

**建议首个技术验收门槛：**独立 release 包名下，从空数据目录完成自身 bootstrap 安装、启动本地 shell、读取/写入/resize PTY、运行 git/ssh/tmux 或选定工具、从自有签名源安装一个带依赖的软件包；覆盖安装中断、空间不足、两次安装并发、配置失败恢复、屏幕旋转、切后台、停止通知、Android 强停、4/16 KB 页。此门槛通过后再承诺大范围包兼容与后台体验。发现无法满足主目标的平台限制时回到 target/runtime 设计，不用跨 App 或本机 SSH 偷换已确认产品边界。

## 核验来源

以下均为主源，访问日期统一为 **2026-10-14**。移动分支只代表本次读取状态，实施时必须固定 commit。

- [S1 Termux 主仓库 README / 维护与 fork 说明](https://github.com/termux/termux-app)
- [S2 Termux PTY JNI 源码](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/jni/termux.c)
- [S3 Termux 主线 SDK 配置](https://github.com/termux/termux-app/blob/master/gradle.properties)
- [S4 Android 10 可写私有目录执行限制](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
- [S5 Android 15 最低 target 安装限制](https://developer.android.com/about/versions/15/behavior-changes-all#minimum-target-api-level)
- [S6 Google Play target API 要求](https://support.google.com/googleplay/android-developer/answer/11926878)
- [S7 Play 分支 SDK 配置](https://github.com/termux-play-store/termux-apps/blob/main/gradle.properties)
- [S8 Play termux-exec 实现说明](https://github.com/termux-play-store/termux-exec)
- [S9 Termux Play 项目及更新记录](https://github.com/termux-play-store)
- [S10 Termux 文件系统布局](https://github.com/termux/termux-packages/wiki/Termux-file-system-layout)
- [S11 Termux 软件包构建路径/仓库配置](https://github.com/termux/termux-packages/blob/master/scripts/properties.sh)
- [S12 TermuxInstaller 首次安装代码](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java)
- [S13 dpkg 状态与修复命令](https://manpages.debian.org/testing/dpkg/dpkg.1.en.html)
- [S14 Play Termux FGS 清单](https://github.com/termux-play-store/termux-apps/blob/main/termux-app/src/main/AndroidManifest.xml)
- [S15 Android 后台启动 FGS 限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [S16 Android 14 target34+ FGS 类型要求](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [S17 FGS 类型说明](https://developer.android.com/develop/background-work/services/fgs/service-types)；[FGS 限时](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [S18 用户停止运行中的 FGS 应用](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
- [S19 Android 16 KB 页支持指南](https://developer.android.com/guide/practices/page-sizes)
- [S20 Termux 主仓库许可证](https://github.com/termux/termux-app/blob/master/LICENSE.md)
- [S21 termux-shared 许可证及例外](https://github.com/termux/termux-app/blob/master/termux-shared/LICENSE.md)

研究上下文备注：本轮尝试 Nowledge 定向检索，服务 `127.0.0.1:14242` 拒绝连接；因此本文不声称恢复了未出现在本轮任务中的历史决定。
