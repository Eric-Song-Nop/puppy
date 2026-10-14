# Puppy

独立 Android 终端：内置 Termux 派生运行环境，采用 Ghostty 终端核心，本地与远程 SSH 会话拥有相同地位。

- [完整产品与技术设计](docs/design.md)
- [运行环境调研](docs/research/runtime.md)
- [终端核心与主题调研](docs/research/terminal.md)
- [交互与 SSH 调研](docs/research/product-ssh.md)
- [本地运行环境构建](runtime/README.md)

已确定：Android 单平台、无需外部 Termux、首版独立 APK、按 GPLv3（GPL-3.0-only）开源；applicationId 为 `org.pocketlab.puppy`。

沿用原平板视觉，所有屏幕尺寸使用同一布局：会话标签放在可收起的侧栏，顶部只保留当前会话标题和必要操作，主体为单终端，无底部状态栏。保存的 SSH 主机通过新建连接或主机管理访问。首版不做 split。使用系统键盘，以 Unexpected Keyboard 为主要兼容对象；不开发键盘或辅助键栏。

## 开发环境

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Zig | 0.16.0（`mise.toml` 固定；Ghostty 要求 ≥ 0.16.0） |
| Android SDK | compileSdk 37，build-tools 37 |
| Android NDK | 29.0.14206865（Gradle `ndkVersion` 固定） |
| Ghostty | `9f13eb7526cc070a2b829f2aae41a44d5cce12bf`（`native/build.zig.zon`） |

```sh
mise install                          # Zig 0.16.0
./gradlew :app:assembleDebug          # 同时用 Zig 构建 libpuppy.so
./gradlew :app:testDebugUnitTest      # JVM 单元测试（含 725 个主题全量解析）
./gradlew :app:connectedDebugAndroidTest   # 设备上的 native 核心与 PTY 测试
(cd native && zig build test)         # 主机上对真实 Ghostty 核心的测试
```

开发与回归使用 arm64 模拟器；`system-images;android-36;google_apis_ps16k;arm64-v8a` 用于 16 KB 页验证。

## 结构

| 路径 | 内容 |
| --- | --- |
| `native/` | Zig：Ghostty VT 封装、帧导出、PTY、JNI（`libpuppy.so`） |
| `app/.../terminal/` | 帧解码、Canvas 绘制、`TerminalView`（IME、按键、选择、手势） |
| `app/.../session/` | 会话、后端契约、本地 PTY、SSH（SSHJ）、前台服务 |
| `app/.../runtime/` | 运行环境安装与本地 Shell 环境 |
| `app/.../theme/` | Ghostty 主题解析、仓库、选择器 |
| `app/.../data/` | Room（主机、已知主机、密钥）、Keystore 凭据库、设置 |
| `app/.../ui/` | Compose 工作区、对话框、设置、主机管理 |
| `runtime/` | 以本应用 PREFIX 重建 bootstrap 的脚本 |
| `tools/` | 主题快照导入脚本 |

## 当前状态

已在 Android 16（16 KB 页，arm64 模拟器）验证：本地 Shell、Ghostty 解析与绘制、IME 输入、主题切换与预览、SSH（密钥认证、主机指纹确认、PTY、窗口变化）、运行环境安装流程（使用最小测试 bootstrap）。

尚未完成：

- 尚未构建真正的 bootstrap（需要 Docker，数小时）；未打包时本地终端使用 `/system/bin/sh`。
- 自有签名软件源与域名未确定（设计 §15 第 1 项），`pkg install` 暂无可用仓库。
- 真机门槛（Unexpected Keyboard、中文 IME、外接键盘、TalkBack、厂商后台行为）未验证。
- 主题快照的许可证需在发布前与上游再次核对。
