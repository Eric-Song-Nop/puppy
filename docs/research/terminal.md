# Android 终端核心、输入、绘制与 Ghostty 主题

调研日期：2026-10-14。本文是设计与验证计划，尚未实现 Android 原型或完成性能测试。产品前提是独立 Android App，应用内运行 Termux 派生环境；本地 PTY 与 SSH 通道向同一个 Ghostty 终端核心提供字节流。

## 1. 复用范围与版本依据

采用 Kotlin + Jetpack Compose 编写应用界面；终端区域是由 Compose 承载的专用 Android View。该 View 经 Zig 实现的 JNI 适配层调用 `libghostty-vt` 公开 Zig module API。JNI 不限定实现语言，无需新增 C/C++ 中转层；C ABI 也不等于 C 源码。业务层无需再引入 Rust 或另一套跨平台 UI。[Zig 导出说明](https://ziglang.org/documentation/0.15.2/#Exporting-a-C-Library)、[Ghostling 关于 Zig API 的说明](https://github.com/ghostty-org/ghostling#why-not-zig)

本次核对的 Ghostty 候选提交是 `9f13eb7526cc070a2b829f2aae41a44d5cce12bf`，通过远端 HEAD 与固定提交头文件核验。它是调研基线，不是已通过 Android 验收的生产依赖。公开 API 仍明确标记不稳定；不能把 Ghostty 桌面应用版本视为这个库的稳定 ABI 版本。[固定提交的 C API](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/include/ghostty/vt.h)

上游已有 Android NDK 路径接入和 Android 链接配置，包括 16 KiB 页面大小设置。因此移植不是从零开始；但这些源码支持不代表我们已经验证了所有设备、ABI 或完整 Android 前端。[NDK 接入代码](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/pkg/android-ndk/build.zig)、[库构建代码](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/src/build/GhosttyLibVt.zig)

| 部分 | 复用或实现责任 |
| --- | --- |
| VT 转义序列、屏幕网格、滚动回看、换行与 resize 重排 | 复用 libghostty-vt |
| 键盘、鼠标、焦点与粘贴协议编码 | 复用上游编码器，Android 负责提供正确事件 |
| 可见区域和发生变化的行 | 复用 Render State API |
| 字体选择、字形排布、实际绘制、触摸交互、无障碍 | Android 前端负责 |
| PTY、SSH、子进程、会话生命周期 | 应用会话层负责 |
| 标签页、连接管理、主题选择、设置 | Compose UI 负责；首版不含 split |

官方 Ghostling 示例明确说明 `libghostty-vt` 没有窗口或实际绘制代码，消费者需要自己的 renderer；同样不会附送标签页、会话管理和配置界面。不能由“使用 Ghostty 核心”推导出“全部桌面 Ghostty 功能已经支持”。[官方嵌入示例](https://github.com/ghostty-org/ghostling#what-is-libghostty)

## 2. 构建与 JNI 边界

以下是项目设计决策：

- 生产构建只使用完整 commit SHA；同时固定 Zig、NDK、构建开关、ABI、补丁和产物校验值。候选提交通过原型后才能提升为生产基线。构建日志和 About 页面记录核心 revision。
- 首先交付 `arm64-v8a`，使用 `x86_64` 模拟器做开发与回归；其他 ABI 按明确需求加入。必须验证最终 APK/AAB 内所有原生库的 16 KiB 兼容性，不能只检查 Ghostty 一项设置。
- 优先将固定版本 Ghostty 公开 Zig module 编入本应用的 Zig JNI 动态库；需要时也可调用上游已有 C API。二者都需固定上游提交与 Zig 工具链，均不意味着稳定 API 保证；使用 C API 时头文件与库必须来自同一提交。
- JNI 只暴露项目自己的窄接口：创建/销毁、写入字节批次、resize、输入事件、主题、滚动/选区、获取与归还绘制帧。Kotlin 不直接引用上游 C 枚举数值或结构布局。
- 桥接接口有独立 `bridgeVersion` 和会话 generation；原生侧返回受校验的 handle。销毁后的回调或旧帧必须被拒绝，避免对象重建时访问已释放的内存。
- 禁止每个 cell 调一次 JNI。输入按字节块传递；输出按一个 frame 或 dirty-row 批次传递。可使用复用的 DirectByteBuffer，加显式借用/归还协议；帧中的字符串池、属性数组和行索引都有边界检查。

Android 官方建议减小跨 JNI 的数据转换和调用频率，这支持上述批量边界；这些具体接口和缓冲格式是本项目设计，并非上游已经提供的 Kotlin SDK。[JNI 官方指南](https://developer.android.com/ndk/guides/jni-tips)

## 3. 核心所有权、并发和帧生命周期

每个会话有一个 `TerminalCore`，仅有一个串行 owner 可以修改它。PTY/SSH 读循环把字节批次排入该 owner；键盘、resize、主题、选区和滚动操作也经过同一队列。应用会话服务拥有核心，Activity/View 的重建只重新附着显示。第一版采用有公平调度的专用终端工作队列，限制每次处理时间；不把读取 socket、字体绘制或文件操作放进该队列。

官方头文件中可核对的核心入口包括 `ghostty_terminal_new/free`、`ghostty_terminal_vt_write`、`ghostty_terminal_resize`。带副作用的终端事件需安装 effects 回调；回调在处理 VT 时同步发生，不允许重入同一终端的 VT write，且不应阻塞。项目将回调转换为有序事件或待写字节队列，由外层执行剪贴板、标题和传输动作。[Terminal C API](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/include/ghostty/vt/terminal.h)

Render State 有全局和逐行 dirty 状态；更新需独占终端访问，返回的数据有借用生命周期。可使用 `ghostty_render_state_update`，或按需要用 begin/end 两阶段降低独占时间；绘制成功后才清除相应 dirty 状态。应用还需处理 synchronized output 的暂停/恢复，不能每次无条件抓取正在更新的半帧。[Render State C API](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/include/ghostty/vt/render.h)

拟定帧协议如下：

1. owner 处理输入后标记会话可刷新。前台可见 View 按显示节奏请求帧，后台会话继续解析但停止绘制。
2. owner 在安全点捕获 Render State，将需要的内容复制到项目自有的不可变帧缓冲。帧包含 `sessionGeneration/frameId/baseFrameId/gridSize/themeVersion/fontVersion`、行内容、颜色与样式、字素映射、光标和选区信息。原生借用指针不跨线程交给 Kotlin。
3. 第一版每个可见会话最多有一个待确认的增量帧。在该帧完成前，原生 terminal 可继续接收输出，但其 render-state snapshot 保持不变。UI 成功呈现后确认对应 frameId，owner 再清理该快照并抓取后续变化。
4. View 脱离、超时、丢帧、基帧不匹配或尺寸变化时，丢弃增量链并请求完整帧；不能清掉比确认帧更新的变化。缓冲只有在消费者归还后才能复用。
5. 对 synchronized output 设置暂停超时；恶意或出错程序不能永久冻结画面。显示切换、主题变化、字体变化和 resize 都触发完整重绘。

帧可以跳过中间视觉状态，PTY/SSH 字节流不能随意丢弃。读队列设置内存上限与背压；持续输出、回看内存和图片缓存各自有预算。帧快照只代表屏幕状态，不能用来恢复已经被 Android 终止的本地进程或 SSH 连接。

## 4. Android 绘制方案

第一阶段采用 Kotlin 专用 View + Android Canvas/Skia 路径，Compose 仅管理外层布局与状态；不为每行或字符创建 Composable。按 dirty row 缓存布局/绘制结果，按帧批量提交。缓存可采用行级 RenderNode 或其他经原型确认的方案，具体选型以实测为准；不能把软件 Bitmap 缓存自动描述成完整 GPU 加速。

该路径已有社区源代码先例：expo-libghostty 的 Android 层通过 JNI 访问 Ghostty 状态，并由 Kotlin Canvas 绘制；Chuchu 则展示 Kotlin/Compose、Ghostty VT 与 SSH 的应用组合。它们证明可实现，不构成本项目性能或兼容性验收结果。[expo-libghostty 原仓库](https://github.com/arcboxlabs/expo-libghostty)、[Chuchu 原仓库](https://github.com/jossephus/chuchu)

| 绘制内容 | 第一版处理 |
| --- | --- |
| 纯 ASCII | 同样式连续 run 批量绘制，固定 cell metrics |
| CJK、组合字符、emoji | 以核心的字素与 cell 宽度为布局约束，Android 文本 shaping 和字体 fallback 负责生成字形 |
| 背景、反色、下划线、删除线、光标 | 独立几何绘制层，保持样式优先级一致 |
| 选区、搜索高亮、链接 | 以 cell/字素映射计算，不能用 UTF-16 下标当终端列号 |
| 滚动回看 | 复用核心 viewport；平滑移动与缓存由 View 管理 |
| 内联图片 | 在完成解码、位置、层级、生命周期和资源上限验证后开启 |

Canvas 提供带上下文的文本 run 绘制；本项目应利用它处理 shaping，而不是逐 Unicode code point 画一个字符。字形自然宽度不能反过来改变终端网格。[Canvas API](https://developer.android.com/reference/android/graphics/Canvas#drawTextRun(char[],%20int,%20int,%20int,%20int,%20float,%20float,%20boolean,%20android.graphics.Paint))

字体默认选择可分发的等宽字体，额外提供许可明确的 Nerd Font 符号字体。fallback 顺序需要区分普通字符、CJK、emoji 和私用区图标，避免用符号字体覆盖普通文字。用户导入字体后要重新计算行高、基线与列宽。字体连字作为可关闭选项，只有光标、选区、命中测试和编辑器定位测试通过才启用；不能以“看起来能显示”代替正确性。

OpenGL ES 是保留的优化路线，不是首版前置条件。若 Canvas 在约定的中档真机、TUI 快速刷新和持续输出负载中不达标，再引入 native glyph atlas + GLES renderer；保留相同核心和帧契约。桌面 Ghostty 的 Metal/桌面 OpenGL 绘制器不会直接在 Android 上工作，也不承诺采用相同核心就能达到其桌面吞吐量。

## 5. 输入、触摸与剪贴板

IME 和物理键盘走不同入口，再合并到有序会话输入队列：

- IME 通过 `InputConnection` 接收 composing、commit、删除与 selection 更新。拼音候选字符串仅显示在输入预编辑层；确认后才编码发送，不把每次 composing 更新写入 shell。支持批量编辑、emoji、代理对和跨 code point 删除。
- 物理键盘保留按键身份、Unicode 文本、修饰键、重复和按下/抬起信息；经过 Ghostty key encoder 生成终端输入。每次按当前终端模式同步编码选项，覆盖应用光标键、keypad 与 Kitty keyboard 协商。软键盘无法提供的物理事件不伪造。
- 防止 `KeyEvent` 与 `commitText` 对同一输入重复发送。中文输入、死键、Ctrl/Alt 组合、外接键盘切换必须各有回归样例。
- 按用户决定不开发软键盘或辅助键栏；以 [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) 为主要兼容对象，验证它发出的 Esc、Tab、Ctrl、Alt、方向键与功能键。输入法负责自身布局和修饰键 UI，App 只负责标准输入接收与终端编码。
- 触摸滚动默认用于回看；支持鼠标报告的 TUI 可进入显式交互模式。长按选择、拖动手柄、复制和粘贴优先采用 Android 习惯；不把每次手势同时发给 TUI 和回看层。

InputConnection 是 Android 输入法交互的正式接口；Ghostty 暴露 `ghostty_key_encoder_setopt_from_terminal` 和 `ghostty_key_encoder_encode`，分别负责模式同步与协议编码。[Android InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection)、[Ghostty 键盘编码器](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/include/ghostty/vt/key/encoder.h)

粘贴使用核心 paste API 处理 bracketed paste 等模式，不手工固定拼接一种转义格式。多行或含控制字符的内容提供预览；用户明确粘贴后写入传输队列。OSC 52 写剪贴板默认要求当前会话在前台且用户允许；终端程序主动读取系统剪贴板默认拒绝，可按会话提供临时授权。限制解码后的字节数，不自动打开 OSC 链接、执行命令或把终端内容写入连接配置。[Ghostty Paste API](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/include/ghostty/vt/paste.h)、[终端 effects 定义](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/include/ghostty/vt/terminal.h)

## 6. 能力声明与 terminfo

能力声明以“核心 + 编码器 + 绘制器 + 平台行为已通过测试”的交集为准。例如解析 Kitty Graphics 不等于能正确绘制图片；支持 OSC 52 解析不代表系统剪贴板读写已经开放。构建开关、设备属性响应、协议查询响应和产品文案必须来自同一个能力表。

第一版默认 `TERM=xterm-256color`，仅在 truecolor 验证通过时设置 `COLORTERM=truecolor`。应用内附带其使用的 terminfo。以后如果使用项目自己的 terminfo 名称，需要从实际完成的能力生成和测试。不能只因为核心来自 Ghostty 就默认宣称完整 `xterm-ghostty`；如果提供该兼容选项，要给出该版本的已验证范围。

Ghostty 官方说明 `xterm-ghostty` 依赖匹配的 terminfo，并提供远端安装与回退指导。因此 SSH 主机配置应允许用户选择兼容终端类型；安装远端 terminfo 是用户触发的明确操作，不能在连接时悄悄改写远端文件。[Ghostty terminfo 文档](https://ghostty.org/docs/help/terminfo)、[SSH 文档](https://ghostty.org/docs/features/ssh)

拟定首版能力门槛：

| 类别 | 交付要求 |
| --- | --- |
| 基础 VT、256 色、truecolor、scrollback、alternate screen、resize | 必须通过 shell、vim/neovim、tmux、less、htop、中文与 emoji 用例 |
| bracketed paste、应用光标键、焦点、鼠标报告 | 按协议与常见 TUI 行为验证 |
| Kitty keyboard | 有完整可提供事件时启用，验证协商与修饰键映射 |
| synchronized output | 验证暂停期间无半帧、超时恢复与 resize 行为 |
| OSC 标题、PWD、链接 | 按非可信数据处理；长度限制，链接由用户点击 |
| OSC 52 | 能力与剪贴板权限策略一致 |
| Kitty Graphics | 可作为后续能力，未完成绘制和内存上限前不宣传支持 |
| tmux control mode、桌面通知、窗口控制等 | 单独评估，核心暴露入口不代表首版承诺 |

## 7. 全部 Ghostty 官方配色主题

产品承诺定义为：支持与指定 Ghostty 主题快照一致的全部官方配色，支持同格式自定义配色导入、预览、收藏和浅色/深色配对。终端颜色与 App chrome 分开：标题栏、SSH 管理和表单可跟随终端背景与强调色，但保持界面文字对比度及触控识别度；选择某个主题不应让设置页变得不可用。

官方主题来自 iTerm2-Color-Schemes；主题文件使用 Ghostty 配置语法，可能包含配色之外的设置。因此“全部官方配色”不等价于执行任意桌面配置、着色器或平台专属窗口效果。[Ghostty 主题文档](https://ghostty.org/docs/features/theme)

本次候选核心在 `build.zig.zon` 中依赖 `ghostty-themes-release-20261005-151109-31756e7.tgz`，同时记录了上游 Zig 内容哈希。拟从这个依赖建立第一份主题清单，记录原始名称、文件路径、文件 SHA-256、来源版本和署名，而不是自行维护主题名称列表。[固定提交的主题依赖](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/build.zig.zon)

本次直接获取该主题归档返回 HTTP 403，因此没有完成该快照全量枚举，不能报告“所有主题已验证”或给出臆测的精确数量。归档获取与完整字段审计是实施阶段的阻断验收项。

解析与应用设计：

1. 自己实现有限配置行解析，不直接把主题当作完整 Ghostty 启动配置执行。支持 UTF-8、空白、整行注释、`key = value`、重复 `palette`，避免把颜色值中的 `#` 误当注释。
2. 数值颜色交给上游 `ghostty_color_parse`，palette 项交给 `ghostty_color_parse_palette_entry`，尽量复用 Ghostty 自己的十六进制、X11 名称和索引语义。[颜色 C API](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/include/ghostty/vt/color.h)
3. 基础字段覆盖 `background`、`foreground`、`palette`、`cursor-color`、`cursor-text`、`selection-background`、`selection-foreground`。颜色可含指定字段允许的 `cell-foreground`/`cell-background` 引用；省略值、重设、覆盖顺序按固定版本语义处理。[配置字段定义](https://github.com/ghostty-org/ghostty/blob/9f13eb7526cc070a2b829f2aae41a44d5cce12bf/src/config/Config.zig)
4. 用官方快照扫描得到实际字段集合，补齐快照需要的安全视觉项。若出现 `palette-generate`、cursor style 等字段，应实现并加入一致性用例；不能静默丢弃后仍宣称全主题兼容。新字段审计失败则阻止主题包升级，现有版本继续可用。
5. 用户导入时给出成功应用、无效和不支持字段的报告。命令执行、环境变量、配置包含、任意文件读取、网络地址资源以及桌面专属效果不在主题导入执行范围。原文件可保留用于导出，生效模型只包含验证后的字段。
6. 主题先加载，用户覆盖随后应用；每个会话保存“继承全局 / 固定主题”的明确选择。前景、背景和 palette 默认值交给核心；终端程序设置的动态颜色遵循核心默认值/OSC override 层次，选区和 App chrome 由 Android 绘制。

主题验收必须覆盖整个版本清单：每个文件可解析、无未知生效字段、颜色模型与同版本 Ghostty 对照一致。生成包含 16/256 色、真彩色、光标、选区、粗体、反色、下划线和 CJK 的固定测试画面。验证配色值与行为，不要求不同平台的抗锯齿和系统字体输出逐像素一致。加入重名、Unicode 文件名、超大文件、越界 palette、重复赋值、坏颜色、未支持字段和恶意配置导入用例。

## 8. 验收、升级与许可

终端核心适配层需要有意义的 native/JNI 回归：随机字节分块与 UTF-8 边界、resize/reflow、交替屏幕、回看截断、输入协议、帧确认丢失、会话销毁重建、持续大输出与资源上限。真机覆盖中文 IME、外接键盘、折叠/旋转、Android 系统分屏、切后台再返回。性能门槛在原型机型上确定，以帧耗时、输入到显示延迟、吞吐、内存及耗电报告决定是否进入 GLES 优化，不先虚构桌面级结果。

升级 Ghostty 时只替换独立适配模块和固定依赖；上游变更先跑协议回归、主题全量覆盖与真机交互用例。避免业务层引用上游内部结构，减少 API 变动影响。

Ghostty 主仓库采用 MIT 许可；分发时仍需保留相应版权及许可，并分别记录实际打包的依赖、字体和主题。iTerm2-Color-Schemes 的集合许可为 MIT，但其 LICENSE 另行注明单个主题的版权与许可属于各作者，因此应保存主题出处并检查选定快照的个别声明。这是组件清单与发布核对要求，本文不作“一切依赖都可按同一许可分发”的法律结论。[Ghostty LICENSE](https://github.com/ghostty-org/ghostty/blob/main/LICENSE)、[主题集合 LICENSE](https://github.com/mbadolato/iTerm2-Color-Schemes/blob/master/LICENSE)
