# 安卓开发环境配置指南

适用于 TwinLeafReader（大肥鱼阅读）3.0.3。以 Windows 10/11、PowerShell 和 Android Studio 为例，配置基准日期：2026-11-13。

本文帮助首次接触安卓开发的读者完成：获取源码 → 配置环境 → 编译 APK → 安装到设备。版本以仓库配置为准；本文不代表已在全新电脑上完成安装验证。

## 1. 先认识这些工具

| 工具 | 用途 | 是否需要单独安装 |
| --- | --- | --- |
| Android Studio | 编写代码、管理 SDK、调试和运行应用 | 推荐安装 |
| JDK | 运行 Gradle 等 Java 构建工具 | 配置一个 JDK 17 |
| Android SDK | 提供安卓平台 API 和打包工具 | 用 SDK Manager 安装 |
| Gradle | 下载依赖、编译代码、打包 APK | 使用项目 Wrapper，无需全局安装 |
| AGP | 让 Gradle 能构建安卓项目的插件 | 项目自动下载 |
| Git | 下载源码和管理修改 | 推荐安装，也可下载 ZIP |
| ADB | 连接设备、安装应用、查看调试信息 | 包含在 SDK Platform-Tools 中 |
| Android Emulator | 在电脑上运行安卓虚拟设备 | 可选，有真机时可暂不安装 |

本项目使用 Kotlin 和 Jetpack Compose，不需要另外安装 Kotlin 命令行编译器，也不需要 Node.js、Flutter 或 Unity。

## 2. 本项目的版本要求

以下数值直接取自当前项目文件，不要照搬到所有安卓项目。

| 配置项 | 当前值 | 定义位置 |
| --- | --- | --- |
| Gradle 构建 JDK | 建议统一使用 17 | AGP 兼容要求；项目 Java 编译目标为 17 |
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.0 | 根目录 `build.gradle.kts` |
| Kotlin Compose 插件 | 2.3.21 | 根目录 `build.gradle.kts` |
| compileSdk / targetSdk | 37 / 37 | `app/build.gradle.kts` |
| minSdk | 26（Android 8.0） | `app/build.gradle.kts` |
| Compose BOM | 2026.08.00 | `app/build.gradle.kts` |
| SDK Build-Tools | 安装 36.0.0 | AGP 9.4 默认版本，项目未单独指定 |

编译时需要 API 37 平台包，但安装应用的设备不必是 API 37；最低支持版本由 minSdk 决定。AGP 9.4 的 Gradle、JDK 和 Build-Tools 要求见 [官方兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes)。

## 3. 安装 Android Studio 和 Git

从 [Android Studio 官网](https://developer.android.com/studio) 下载支持本项目 AGP 9.4 的版本。安装并完成首次启动向导；若旧 IDE 提示不支持 AGP，更新 IDE，不要随意降低项目版本。安装流程参考 [官方安装说明](https://developer.android.com/studio/install)。

Git 可从 [Git 官方网站](https://git-scm.com/downloads) 获取。安装后重新打开 PowerShell，检查：

```powershell
git --version
```

本指南示例使用 `D:\Projects\TwinLeafReader` 存放源码、`D:\Android\Sdk` 存放 SDK。可替换成自己的路径，建议留出足够空间存放依赖缓存和模拟器镜像。

## 4. 获取源码

```powershell
New-Item -ItemType Directory -Force D:\Projects
Set-Location D:\Projects
git clone https://github.com/alakkkaaaaa/TwinLeafReader.git
Set-Location TwinLeafReader
```

也可以在仓库页面选择 Code → Download ZIP，解压后打开。Android Studio 应打开包含 `settings.gradle.kts` 的项目根目录，而不是只打开 `app` 文件夹。

## 5. 安装 SDK 组件

在 Android Studio 中打开 Tools → SDK Manager；欢迎页也可通过 More Actions 找到 SDK Manager。记录顶部显示的 Android SDK Location。

安装以下组件：

- SDK Platforms：Android API 37 对应的平台包。
- SDK Tools：Android SDK Build-Tools 36.0.0，可勾选 Show Package Details 查找。
- SDK Tools：Android SDK Platform-Tools。
- SDK Tools：Android SDK Command-line Tools，便于使用 sdkmanager。
- 需要模拟器时，再安装 Android Emulator 和相应系统镜像。

当前项目没有声明 NDK/CMake 源码构建任务，首次配置无需额外安装它们。根据安装界面提示阅读并接受 SDK 许可。

在项目根目录创建 `local.properties`，填写实际 SDK 路径。Windows 路径可以用正斜杠避免转义：

```properties
sdk.dir=D:/Android/Sdk
```

这个文件只适用于自己的电脑，已被 `.gitignore` 排除，不应上传到仓库。

## 6. 配置 JDK 17

在 Android Studio 的 Settings → Build, Execution, Deployment → Build Tools → Gradle 中，将 Gradle JDK 设置为 JDK 17。可通过 Download JDK 获取，也可选择已安装的 JDK 17。

IDE 自身继续使用其自带运行时即可；这里设置的是运行 Gradle 的 JDK。IDE 与命令行可能使用不同 JDK，二者应对齐，具体区别见 [官方 JDK 配置说明](https://developer.android.com/build/jdks)。

下面的路径仅作示例，必须替换为自己的安装位置。以下环境变量只对当前 PowerShell 窗口生效：

```powershell
$env:JAVA_HOME = 'D:\Java\jdk-17'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
java -version
.\gradlew.bat --version
```

检查输出中的 Java/Launcher JVM 路径和 Gradle 版本。若需要长期生效，在 Windows“编辑账户的环境变量”中设置 JAVA_HOME、ANDROID_HOME，并在 Path 中追加对应 bin/platform-tools 路径，然后重新打开终端。

仓库不会包含开发者电脑上的 `.toolchain`。虽然当前 Windows 启动脚本会尝试该目录下的本机 JDK，但新电脑应自行安装并设置 JAVA_HOME。

## 7. 首次同步与网络配置

用 Android Studio 打开项目，等待 Gradle Sync 完成。首次运行需要下载 Gradle、AGP、Compose 等依赖，比后续构建更慢。

项目使用 Google Maven、Maven Central、Gradle Plugin Portal，以及 Gradle 官方分发地址。下载失败时，先检查错误中的具体域名、网络连接和代理设置，不要直接更改所有依赖版本。

若网络需要代理，可在 IDE 的 HTTP Proxy 页面配置。命令行 Gradle 可在用户目录的 `.gradle/gradle.properties` 设置自己的代理；不要把含账号密码的配置提交到项目。首次下载尚未完成时不要开启 Gradle Offline Mode。

## 8. 编译并取得 APK

在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

看到 `BUILD SUCCESSFUL` 后，调试安装包通常位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

不需要每次构建前运行 clean；保留增量缓存通常更快。只有需要排查构建缓存问题时再考虑清理。

可选的开发检查命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

这些命令不能代替真机上的手写笔、触控板和阅读体验验收。

## 9. 连接真机或模拟器

### 真机

在设备设置中开启开发者选项和 USB 调试，使用支持数据传输的 USB 线连接电脑，并在设备上确认调试授权。Windows 如未识别设备，需要检查厂商 USB 驱动。操作依据见 [官方真机调试说明](https://developer.android.com/studio/run/device)。

```powershell
adb devices
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

列表显示 `device` 才表示就绪；`unauthorized` 表示尚未在设备上允许调试。存在多个设备时，用 `adb -s 设备序列号 install -r ...` 指定目标。

本机编译的 debug APK 与已有安装包可能使用不同签名，导致覆盖安装失败。不要为解决签名冲突直接卸载有重要数据的应用；先导出书籍批注、笔记及其他需要保留的数据，再决定如何处理。

### 模拟器

在 Tools → Device Manager 中创建平板虚拟设备，安装匹配主机架构的系统镜像后启动。运行配置选择 `app` 和目标设备，点击 Run。

可用模拟器检查横竖屏、单双页和常规界面；OPPO Pencil 2 的压力、悬停、防误触，以及官方触控键盘行为仍需对应真机验证。

## 10. 正式发行与云同步配置

调试包方便开发使用。正式发布可通过 Build → Generate Signed App Bundle or APK 创建签名安装包；当前仓库没有配置正式签名密钥。密钥和密码应自行保管，持续更新同一应用需要保持签名一致，不要把密钥提交到公开仓库。

本地阅读功能不要求配置 OneDrive。若要体验云同步，按项目 `ONEDRIVE_SETUP.md` 注册并配置 Microsoft Entra 公共客户端应用，再在阅读器中填写 Client ID。不要把客户端密钥或用户令牌写入源码。

## 11. 常见问题速查

| 现象 | 优先检查 |
| --- | --- |
| JAVA_HOME 无效或找不到 java | JAVA_HOME 应指向 JDK 根目录，不是 bin；确认目录确实存在 |
| IDE 能构建，终端不能 | 对比 IDE Gradle JDK 和终端 JAVA_HOME |
| SDK location not found | 检查 local.properties 的 sdk.dir |
| 找不到 android-37 | 在 SDK Manager 安装 API 37 平台包 |
| Unsupported AGP version | 更新到支持 AGP 9.4 的 Android Studio |
| 下载超时或依赖解析失败 | 检查具体下载地址、网络、代理和 Offline Mode |
| Gradle 内存不足 | 关闭额外模拟器及大型应用，结合内存容量调整 org.gradle.jvmargs；项目当前为 -Xmx4g |
| adb 不是可识别命令 | 安装 Platform-Tools 并把其目录加入 Path |
| 设备显示 unauthorized | 解锁设备并确认 USB 调试授权 |
| 安装提示签名不一致 | 核对已有应用签名，先保留数据，再选择兼容的安装方式 |

配置完成的判断标准：Gradle 能输出版本、SDK 路径有效、assembleDebug 成功、APK 能在目标设备启动。本文仅新增文档，没有重新执行完整构建或真机测试。
