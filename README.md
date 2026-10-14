# Puppy

独立 Android 终端：内置 Termux 派生运行环境，采用 Ghostty 终端核心，本地与远程 SSH 会话拥有相同地位。

当前处于设计阶段，仓库尚无可运行的 Android 应用。

- [完整产品与技术设计](docs/design.md)
- [运行环境调研](docs/research/runtime.md)
- [终端核心与主题调研](docs/research/terminal.md)
- [交互与 SSH 调研](docs/research/product-ssh.md)

已确定：Android 单平台、无需外部 Termux、首版独立 APK、按 GPLv3 开源。具体复用模块和分发资源的许可声明将在选定依赖后加入。

沿用原平板视觉，所有屏幕尺寸使用同一布局：会话标签放在可收起的侧栏，顶部只保留当前会话标题和必要操作，主体为单终端，无底部状态栏。保存的 SSH 主机通过新建连接或主机管理访问。首版不做 split。使用系统键盘，以 Unexpected Keyboard 为主要兼容对象；不开发键盘或辅助键栏。
