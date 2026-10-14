# 产品、会话与 SSH 设计

核查日期：2026-10-14。前提为独立 Android App、内置 Termux 派生运行环境、Ghostty 终端核心。以下“建议”为待验证设计；库和平台事实附官方来源。

## 界面与输入

建议使用 Kotlin、Jetpack Compose 和 Material 3 Adaptive 管理导航、主机配置与主题；自定义终端 View/Surface 接入 native 核心。避免每个字符格都成为独立 Compose 节点。借鉴 Ghostty/Kitty 的紧凑标签、细分隔线、低干扰工具栏；不用拟真的 macOS 窗口按钮。布局按窗口宽度调整，而非硬编码“手机/平板”。[Android 自适应布局](https://developer.android.com/develop/ui/compose/layouts/adaptive/get-started-with-adaptive-apps)

打开应用直接进入终端工作区；Local 与 SSH 是同级标签类型，新建按钮同时提供两者。根据用户最新决定，沿用原平板视觉，会话标签统一移至可收起侧栏，顶部只保留侧栏开关、当前会话标题、新建、主题和更多。侧栏只显示已打开会话，保存的主机通过新建 SSH 或主机管理访问，避免重复列表。所有屏幕尺寸和姿态使用同一布局，无底部状态栏或辅助键栏；侧栏在窄窗口按需覆盖显示，宽窗口可并排显示，使用同一开关和结构。会话标签纵向排列，窗口宽高改变终端行列数；折叠、展开与横屏不切换界面结构。首版不设计或实现 split。标签显示标题、Local/SSH 标识、必要连接状态；不能仅靠颜色区分。

用户明确不做键盘产品，使用系统 IME，以 [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) 为主要兼容对象；不嵌入或修改它，不开发键盘、辅助键行或键盘布局设置。终端仍需要标准输入适配层：中文 IME 的 composing 文本在本地预编辑，commit 后才发送；物理键盘区分键位事件与已输入字符；Ctrl/Alt/Esc/Tab、组合键、重复键和粘贴分别编码。默认应用快捷键用 Ctrl+Shift，减少抢占 shell 的 Ctrl+C、Ctrl+R、Tab。焦点切换不得遗留卡住的修饰键或把预编辑文本误发给另一会话；打开对话框后明确转移焦点，关闭时恢复终端。触屏长按选择、滑动回看；应用鼠标上报模式下提供明确的“选择文本”入口，避免滚动手势冲突。远端 OSC 52 写剪贴板默认询问，禁止远端读取剪贴板。

自绘终端不会自动获得完整 TalkBack 能力。需暴露可读文本、当前输入和选择/复制操作，限速播报输出，保持工具栏语义与键盘焦点可见；动态字号、中文宽字符、emoji、输入法弹出后的 PTY 尺寸均属于首版验收。[Android 自定义 View 无障碍](https://developer.android.com/guide/topics/ui/accessibility/views/custom-views)

## 配置与会话分离

`Profile` 是可重复使用的连接配置；`Session` 是一次运行实例。同一主机可同时开多个会话。Room 保存主机、端口、用户名、标签、主题引用、凭据 ID 和已确认主机密钥；偏好由 DataStore 保存。SSH 私钥不放入 Room 明文字段。

会话由独立于 Activity 的 SessionManager 持有，界面只订阅状态：

```text
Created → Starting → Running → Closing → Exited
SSH Starting = Resolving → Connecting → VerifyingHost → Authenticating → OpeningPTY
任意阶段 → Failed(reason)
SSH Running → Disconnected → Reconnecting → Starting
```

拒绝主机密钥、认证失败、PTY 被拒、超时、网络断开分别显示原因；不把“连接建立”当成“shell 就绪”。旋转、窗口缩放和标签切换不能重建进程或连接。关闭标签才触发关闭；按返回键优先收起键盘/面板，不直接杀会话。运行状态不能从数据库假装恢复：进程死亡后可恢复标签与配置，但必须显示已中断；本地进程是否存活要实际核验。输出默认仅在内存保留，日志录制需显式开启。

后台活动由符合目标 Android 版本要求的前台服务承载，并提供用户可见的停止入口；服务类型与发布渠道要求是实现前置验证项。Doze 会限制网络，前台服务不能承诺永久在线。[Android Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)

## SSH 库与传输边界

建议先以 SSHJ 做候选原型，经 Android 真机门槛后冻结版本。它有 Android 兼容修复历史，但 Java/JVM 可运行不等于所有 Android 版本和加密提供者都可用。[SSHJ](https://github.com/hierynomus/sshj) ConnectBot 的新 Kotlin `cbssh` 支持 PTY、认证和转发，可作为替代评估；旧 Trilead 已弃用，不作为新项目默认依赖。[ConnectBot](https://github.com/connectbot)、[cbssh](https://github.com/connectbot/cbssh) Apache MINA 明确未充分测试 Android；libssh2 则增加 JNI、密码库与 ABI 维护，因此两者暂不优先。[MINA Android 说明](https://github.com/apache/mina-sshd/blob/master/docs/android.md)

统一 `SessionTransport` 提供读写字节、resize、close 与退出事件；Local 实现绑定 PTY，SSH 实现绑定远端 PTY channel，Ghostty 不负责认证。图形界面的 SSH 直连使用库；内置 shell 的 `ssh` 命令仍属于运行环境，其配置/密钥不自动与图形界面共享。默认请求 `xterm-256color`，验证后可选 Ghostty terminfo；不能因本机使用 Ghostty 就假设服务器已经安装对应 terminfo。

重连只重建连接，不重放未确认输入或历史命令。可选“连接后进入指定 tmux 会话”，从而恢复远端工作；远端必须装有 tmux，且关闭 App 不等于结束 tmux 任务。[tmux](https://github.com/tmux/tmux/wiki/Getting-Started)

## 主机信任与凭据

首次连接显示主机、端口、算法与 SHA-256 指纹，用户确认后保存；预导入已核验密钥也可。已保存密钥不符时硬失败，不自动覆盖，不采用接受一切主机的 verifier。用户名相同不代表主机可信；每个跳板和目标都要独立验证。

导入的 OpenSSH 私钥保留原有口令保护，并以 Android Keystore 的 AES-GCM 密钥再次封装，存应用私有目录；每次加密使用唯一 nonce。密钥库默认上锁，连接时解锁，锁定后清理缓存并允许现有 SSH 连接继续运行；重连需要密钥时重新认证。服务器密码默认不保存；密钥口令默认不持久化，凭据只绑定用户明确选定的 profile。

不要承诺“导入私钥永不离开硬件”：软件私钥使用时会进入进程内存。Keystore 支持哪些算法、是否硬件隔离需按设备检测；不能把任意 Ed25519 密钥默认视为 StrongBox 密钥。密钥库排除系统自动备份和设备迁移；首版导出配置默认不含凭据，私钥仅经显式操作导出为加密格式。换机不能仅复制被旧设备 Keystore 加密的文件。[Android Keystore](https://developer.android.com/privacy-and-security/keystore)

特别边界：若内置 runtime 与应用同 UID，本地任意程序与凭据库不构成强隔离。将 vault 移出 `$HOME`、不把密钥放入环境变量只能减少意外泄露；不能抵御同身份恶意程序。首版把用户安装的本地代码视作信任边界内；如需运行不可信代码同时保护凭据，必须另立 UID/沙箱方案。[Android 应用隔离](https://developer.android.com/privacy-and-security/security-tips)

## 首版范围与验证门槛

首版包含 Local/SSH 多标签、主机分组搜索、密码/公钥/keyboard-interactive 认证、严格指纹校验、私钥导入、主题与字体设置。跳板、代理、端口转发、SFTP、agent forwarding、密钥生成及云同步后置；首版默认关闭转发，后续按主机单独授权。

发布前必须通过：最低支持版本及当前版本真机连接 OpenSSH；Ed25519/RSA-SHA2、加密私钥、交互认证、主机密钥变化、密钥库锁定与失效；拒绝 PTY、resize、输出背压与取消连接；断网/切网/Doze/进程死亡；Unexpected Keyboard、中文 IME、物理键盘、TalkBack、折叠/展开与窗口变化。任一 SSH 库未通过这些门槛，都不能仅以“纯 Java/Kotlin”宣布选型完成。
