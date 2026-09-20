# jev-android

[English](README.md) | [简体中文](README.zh-CN.md)

可接入 Android 项目的 Kotlin UI Agent SDK。**TypeSafe Jev 或 DeepSeek** 从当前屏幕的真实控件中选择操作，再由 Android 无障碍服务执行。两个后端都能独立完成任务：使用 DeepSeek API Key 无需 Jev 账号，使用 Jev 则填写 TypeSafe Key。项目包含示例 App 和无需目标应用账号的本地测试页面。

API 尚未稳定。项目未发布到 Maven Central；下文的依赖坐标仅适用于本项目生成的本地 Maven 仓库。示例 App 界面和代码保持英文。

## 功能与限制

- 根据可读取的控件动态构建动作表。Jev 通过一次请求中的多个问题选择操作与目标；DeepSeek 通过严格的 `select_action` 函数结构，从枚举动作中选择。两者共用执行循环和检查机制。
- 为 DeepSeek 的可操作容器描述补充有数量和长度限制的子节点文字，让可点击卡片包含可见标题，同时不会把不可操作的文字子节点变成点击目标。
- 支持点击、长按可访问控件、替换输入框内容、前后滚动、返回、打开允许的应用、等待，以及报告完成或受阻。
- `Operation.LONG_CLICK` 调用控件声明支持的 Android `ACTION_LONG_CLICK` 动作；`Operation.LONG_PRESS` 在当前读取到的可见、可操作控件中心按住触屏。按住时长由宿主通过 `Task.longPressDurationMillis` 指定（默认 2,000 毫秒，允许范围为 500–5,000 毫秒），模型不能提供坐标或时长。
- 本 SDK 的两个后端均不生成任意输入文字。调用方通过 `Task.textValues` 提供命名的准确候选值，由选中的模型选择；只需要当前后端的 API Key。
- 执行前重新读取屏幕并比较指纹。如果页面在动作提交前已变化，重新观察并让后端做出新决策，最多连续刷新三次。每次刷新都会占用一个决策步骤，仍受任务步数和时间上限限制。不会在变化后的页面上重复执行旧决策。
- 首次观察前等待停止按钮完成布局。手势几何信息单独校验，避免模型请求期间的浮层布局变化导致原生节点操作被误判为过期。
- 启动应用后，最多等待 10 秒确认目标应用进入前台，再报告启动已接受，避免 Android 切换应用期间再次做出启动决策。
- 输入后读取控件并验证完整值。操作被拒绝或结果不确定时停止，不自动重复执行；只有明确在提交前因页面过期而被拒绝的动作才会触发重新观察。
- 提供包名白名单、步骤和时间限制、最低置信度、无进展检测及宿主自定义操作策略。
- 取消协程会取消正在进行的 HTTP 请求；无障碍悬浮按钮可停止后续操作。已经提交给 Android 的操作无法撤销；已经提交的按住手势可能持续到设定时长结束后才释放触屏。
- `DONE` 仅表示模型声称完成。只有提供 `OutcomeVerifier` 且验证通过，任务才返回 `VERIFIED`，否则返回 `UNVERIFIED`。

当前不支持截图视觉、猜测坐标、除上述限时按住操作以外的任意手势、通用 WebView 或 Canvas 识别、密码输入、文本生成、锁屏操作及长期后台无人值守。尚未在小米真机上验证 HyperOS 兼容性。宿主应用负责决定允许哪些设置变更、付款、消息发送等操作。默认 gate 放行白名单应用内的有效动作，不会自动识别所有敏感控件。

## 模块

| 模块 | 作用 |
|---|---|
| `core` | 与平台无关的任务模型、决策接口、执行循环、验证和事件 |
| `sdk` | Jev 与 DeepSeek HTTP/JSON 接口、无障碍运行时、服务及停止按钮 |
| `sample` | 后端、模型和场景选择，可编辑的任务预设，API 配置、授权设置入口、结果日志和本地测试页面 |

执行流程：读取控件 → 构建有效选项 → 所选后端选择动作 → 检查宿主策略 → 确认页面未过期 → 执行动作 → 观察结果。

## 选择后端

| 后端 | SDK 提供者 | 所需密钥 | 默认模型 |
|---|---|---|---|
| Jev (TypeSafe) | `JevProvider` | TypeSafe API Key | `jev-latest` |
| DeepSeek | `DeepSeekProvider` | DeepSeek API Key | `deepseek-flash` |

DeepSeek 调用 `https://api.deepseek.com/beta/chat/completions`，关闭思考并强制使用严格的 `select_action` 函数。每次请求枚举当前允许的 `action_id`，各 ID 在本地对应一个确定的操作与目标。模型只提供 `action_id`、`text_key` 和 `confidence`，不再生成操作名或原始 UI 目标 ID。SDK 只接受恰好一次对该函数的调用，在考虑执行 UI 动作前会在本地校验参数。函数调用只表达决策，不会执行远程工具。参见官方 [DeepSeek 严格工具调用指南](https://api-docs.deepseek.com/guides/tool_calls/)。2026-09-21 已使用一个 DeepSeek 账号，在三星手机上的 0.3.4 版本中成功调用此 beta 接口；这不代表所有账号或模型都可用。详见[验证记录](VALIDATION.md)。

`text_key` 是字符串枚举，包含宿主提供的非空输入键，并用空字符串表示不输入文字。SDK 会交叉检查输入键与所选动作是否兼容；输入动作不能使用任意生成的文字值。空字符串标记会在最终的 `Decision` 中转换为 `null`。

DeepSeek 的响应上限为 1,024 tokens。在提交任何 UI 操作前，如果决策为空、被截断或无效，最多发起一次纠正请求。纠正请求复用原始页面快照和任务上下文，只附加固定的拒绝原因，不附加格式错误的原始回复。纠正后的决策仍须经过相同的严格校验、宿主策略和页面时效检查。HTTP 或网络错误、模型拒绝及内容过滤不会触发重试。两个提供者均接受自定义 `model`，请选用账号可访问、且兼容对应后端请求格式的模型。

共用的最低置信度阈值在两个后端中含义不同。Jev 返回给定选项的概率分布；DeepSeek 必须在函数参数中给出自报的置信度。DeepSeek 的数值未经校准，不能与 Jev 的概率直接比较，两者也都不能证明动作正确。需要更强保证的决策应依靠宿主策略和结果验证。

## 构建

使用 JDK 17 或 21，以及 Android SDK Platform 36。项目自带 Gradle 8.13 Wrapper。可以用 Android Studio 打开项目根目录，或通过 `ANDROID_HOME`、不纳入版本管理的 `local.properties` 配置 SDK 路径：

```properties
sdk.dir=/your/path/to/Android/Sdk
```

### macOS（Apple 芯片和 Intel）

Mac 用于开发、构建 SDK，并将示例安装到 Android 手机或模拟器。Agent 在 Android 上运行，不控制 macOS 或 iOS。

**1. 安装开发环境。** 安装适合 Mac 芯片架构的 [Android Studio](https://developer.android.com/studio)，完成首次启动向导。在 **SDK Manager** 中安装 Android SDK Platform 36、Android SDK Platform-Tools 和 Android SDK Build-Tools 35.0.0（勾选 **Show Package Details** 后选择该版本）。如果使用模拟器，还需安装 Android Emulator，并选择匹配 Mac 架构的系统镜像：Apple 芯片使用 ARM64，Intel 使用 x86_64；镜像 API 版本应为 26 或以上。

**2. 克隆项目。** 在终端执行：

```bash
git clone https://github.com/dougsong/jev-android.git
cd jev-android
chmod +x gradlew
```

如果运行 `git` 时 macOS 提示安装 Command Line Tools，请先完成安装。无需单独安装 Gradle，使用项目自带的 Wrapper 即可。

**3. 配置 Java 和 Android SDK。** 以下假设 Android Studio 安装在 `/Applications`，SDK 使用默认位置：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
java -version
./gradlew --version
```

本项目使用 JDK 17 或 21。请检查上面输出的实际版本，不要直接假设 Android Studio 内置 JDK 的版本。如果内置版本不同，安装适合 Mac 架构的 JDK 17，并将 `JAVA_HOME` 指向该安装目录；已向 macOS 注册的 JDK 可以通过 `export JAVA_HOME="$(/usr/libexec/java_home -v 17)"` 选择。切换 JDK 后，重新执行上面的 `PATH` 配置和版本检查命令。从 Android Studio 构建时，在 **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK** 选择相同的 JDK。

如果修改过 SDK 安装位置，请按照 SDK Manager 显示的位置调整 `ANDROID_HOME`。这些环境变量仅对当前终端生效，可以将适当的配置加入 `~/.zshrc`。如果项目从 Windows 复制而来，请删除或修改未跟踪的 `local.properties`，避免旧的 Windows `sdk.dir` 覆盖 Mac SDK 路径。

**4. 构建并运行离线检查。** 在项目根目录执行：

```bash
./gradlew :core:test :sdk:testDebugUnitTest :sample:testDebugUnitTest :sdk:assembleRelease :sample:assembleDebug
./gradlew :sdk:lintDebug :sample:lintDebug
```

首次构建会下载 Gradle 和依赖。也可以用 Android Studio 打开项目，等待 Gradle 同步完成，然后选择 Android 设备运行 `sample` 配置。产物路径见下文。

**5. 安装并试用示例。** 使用真机时，开启开发者选项和 USB 调试，用支持数据传输的 USB 线连接 Mac，解锁手机并接受调试授权提示。macOS 的 ADB 无需额外安装厂商 USB 驱动。也可以在 Android Studio 的 **Device Manager** 中启动模拟器。仅连接一个目标设备时执行：

```bash
adb devices
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
adb shell am start -n io.github.jevandroid.sample/.MainActivity
```

目标状态应为 `device`，不能是 `unauthorized` 或 `offline`。连接多个设备时，在安装和启动命令的 `adb` 后加上 `-s YOUR_DEVICE_SERIAL`。在 App 中选择 **Jev (TypeSafe)** 或 **DeepSeek**，填写对应的 API Key，并阅读所选后端的数据说明。保留默认模型或填写兼容的自定义模型，手动开启无障碍服务，选择 **Built-in: save text**，再点击 **Run selected task**，并保持设备解锁。密钥不要写入终端命令或提交到仓库。安装完成后，示例通过手机自己的网络连接模型，无需持续连接 Mac。

可选：在专用模拟器或测试设备上运行设备测试：

```bash
./gradlew :sample:connectedDebugAndroidTest
```

该任务会安装测试 APK、临时开启无障碍服务，并在结束后恢复之前的设置。它使用确定性决策提供者，不调用 Jev 或 DeepSeek。请仅连接预期的测试设备，因为 Gradle 可能在多个已连接设备上运行测试。

**6. 接入其他 Android 项目。** 按下文的源码模块方式接入，或在 Mac 上生成本地 Maven 仓库：

```bash
./gradlew :core:publishCorePublicationToLocalBuildRepository :sdk:publishReleasePublicationToLocalBuildRepository
```

将 `build/repository` 复制到宿主项目的 `vendor/jev-maven`，再按下文配置 Maven 依赖并注册服务。macOS 不需要运行 Windows 临时目录构建脚本。本节已按工程配置和平台文档核对，但尚未在实际 Mac 上执行构建。

平台参考：[AGP 环境要求](https://developer.android.com/build/releases/agp-8-12-0-release-notes)、[Gradle 与 Java 兼容性](https://docs.gradle.org/current/userguide/compatibility.html)、[Android 环境变量](https://developer.android.com/tools/variables)、[真机设置](https://developer.android.com/studio/run/device)、[模拟器架构](https://developer.android.com/studio/run/emulator-acceleration)。

### Windows

```powershell
.\gradlew.bat :core:test :sdk:testDebugUnitTest :sample:testDebugUnitTest :sdk:assembleRelease :sample:assembleDebug
.\gradlew.bat :sdk:lintDebug :sample:lintDebug
```

Linux 使用 `./gradlew`。项目设置了 `android.overridePathCheck=true`，允许 Windows 非 ASCII 路径。如果第三方工具出现路径相关问题，请改用纯 ASCII 路径。

本机验证时，中文 Windows 路径导致 Gradle 测试进程出现 `ClassNotFoundException`。随附脚本会在新的临时英文目录中构建，再把产物和报告复制回项目：

```powershell
.\scripts\build-windows.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr'
```

请按实际安装位置修改 JDK 路径。脚本保留原项目文件；Android SDK 路径仍需通过 `ANDROID_HOME` 或 `local.properties` 提供。

### 构建产物

- `sample/build/outputs/apk/debug/sample-debug.apk`
- `sdk/build/outputs/aar/sdk-release.aar`
- `core/build/libs/core-0.3.4.jar`

**AAR 不包含全部依赖。** SDK 还依赖 core 模块、协程、OkHttp 和 Gson，建议按下面的源码模块或 Maven 方式接入。

## 接入现有 Android 项目

### 方式 A：源码模块

将 `core` 和 `sdk` 模块复制到宿主项目，在其 `settings.gradle.kts` 中引入这两个模块，并使用兼容的 Kotlin 和 Android Gradle Plugin 版本。宿主 app 添加：

```kotlin
dependencies {
    implementation(project(":sdk"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### 方式 B：本地 Maven 仓库

```powershell
.\gradlew.bat :core:publishCorePublicationToLocalBuildRepository :sdk:publishReleasePublicationToLocalBuildRepository
```

将生成的 `build/repository` 整体复制到宿主项目的 `vendor/jev-maven`，然后在宿主 settings 的 `dependencyResolutionManagement.repositories` 中添加：

```kotlin
maven { url = uri("vendor/jev-maven") }
```

宿主 app 添加依赖：

```kotlin
implementation("io.github.jevandroid:jev-android:0.3.4")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

### 注册服务

宿主需要 `minSdk >= 26`、`compileSdk >= 36`。SDK manifest 会合并网络权限和启动器查询声明；服务需要宿主明确注册，并由用户手动开启。

在 app manifest 的 `<application>` 内添加：

```xml
<service
    android:name="io.github.jevandroid.JevAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true"
    android:label="Jev UI automation">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/jev_accessibility" />
</service>
```

将示例中的 `res/xml/jev_accessibility.xml` 和 `strings.xml` 中的说明字符串复制到宿主。执行定时按住的 `LONG_PRESS` 时，XML 必须包含 `android:canPerformGestures="true"` 和 `android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"`。原生 `LONG_CLICK` 使用控件的无障碍动作。Android 的屏幕放大和触摸探索功能可能影响手势传递。先向用户说明可见页面文字、任务和输入候选会发送到选中的后端（TypeSafe 或 DeepSeek），再由用户通过 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 开启服务。服务应与宿主 app 运行在同一进程。

### 启动任务

```kotlin
import io.github.jevandroid.JevAccessibilityService
import io.github.jevandroid.JevProvider
import io.github.jevandroid.core.*

// Call on the main thread, for example from a button callback.
// Obtain the key from the host configuration; do not embed it in source code.
val service = JevAccessibilityService.connected.value
    ?: error("Enable the accessibility service first")

val job = service.start(
    task = Task(
        goal = "Open Bilibili, search for Xiaomi phone reviews, and stop on the search results screen",
        allowedPackages = setOf("tv.danmaku.bili"),
        textValues = mapOf("search_query" to "Xiaomi phone reviews"),
        maxSteps = 20,
    ),
    provider = JevProvider(apiKey = yourTypeSafeKey),
    onEvent = { event -> /* Update progress without logging input contents. */ },
    onError = { error -> /* Show the error and allow manual takeover. */ },
)

// Invoke from a separate stop button. Alternatively, call service.stop().
job.cancel()
```

在主线程调用，例如按钮回调。密钥从宿主配置读取，不要写入源码。上例演示 API 用法，不代表已验证 B 站流程成功率；实际应用中应将 `job.cancel()` 放在单独的停止按钮回调里。

使用 DeepSeek 执行同一个任务时，引入 `DeepSeekProvider` 并将其作为 provider 传入，无需 TypeSafe Key，也不会调用 Jev：

```kotlin
import io.github.jevandroid.DeepSeekProvider
import io.github.jevandroid.JevAccessibilityService
import io.github.jevandroid.core.Task

val service = JevAccessibilityService.connected.value
    ?: error("Enable the accessibility service first")

val job = service.start(
    task = Task(
        goal = "Open Bilibili, search for Xiaomi phone reviews, and stop on the search results screen",
        allowedPackages = setOf("tv.danmaku.bili"),
        textValues = mapOf("search_query" to "Xiaomi phone reviews"),
        maxSteps = 20,
    ),
    provider = DeepSeekProvider(
        apiKey = yourDeepSeekKey,
        model = "deepseek-flash", // Optional; this is the default.
    ),
    onEvent = { event -> /* Update progress without logging input contents. */ },
    onError = { error -> /* Show the error and allow manual takeover. */ },
)
```

两个后端的主线程调用、取消和结果验证要求相同。此代码演示 API 用法。2026-09-21 使用真实 DeepSeek 运行 **Built-in: save text**，返回 `VERIFIED`，页面显示 `Saved: Hello Jev`。另一次测试运行了 B 站 `testv` 搜索预设，进入了 TESTV 视频详情页；由于预设没有独立结果验证器，完成状态为 `UNVERIFIED`。详见[验证记录](VALIDATION.md)。

`OutcomeVerifier` 接收新读取的 `UiSnapshot`，用于检查具体业务结果。示例会核对测试页面显示的保存值或长按结果；验证器返回 false 时，结果为 `UNVERIFIED`。

`ActionGate` 可以接入宿主确认界面或业务规则，返回 false 会停止任务。如果等待确认时页面发生变化，运行时会在提交前拒绝旧决策；允许刷新时会获取新决策，并再次经过 gate 检查。不要仅凭后端返回的置信度授权敏感操作。

实现 `DecisionProvider` 可以替换模型，实现 `DeviceRuntime` 可以用模拟设备测试。直接使用 `JevAgent` 时，由宿主负责协程生命周期、错误处理、停止控制和同一设备的互斥访问。通常使用 `JevAccessibilityService.start` 更方便，它限制每个服务只能运行一个任务。

## 在 Android 手机上试用

1. 安装示例 debug APK，选择 **Jev (TypeSafe)** 或 **DeepSeek**，填入对应的 API Key。模型栏默认值为 `jev-latest` 或 `deepseek-flash`，也可以填写兼容的自定义模型。请阅读所选后端的数据说明；DeepSeek 模式无需 TypeSafe 账号。
2. 在系统设置中开启无障碍服务。不同 Android 版本与厂商（包括小米 HyperOS、三星 One UI）的菜单和限制可能不同，请以实际设备为准。
3. 选择 **Built-in: save text**，再点击 **Run selected task**，保持屏幕解锁。任务应输入 `Hello Jev`，点击 `Save`，并验证显示 `Saved: Hello Jev`。也可以选择 **Built-in: long press**，尝试在测试控件上执行定时按住操作。
4. 点击右上角的 `Stop Jev` 可停止执行。返回示例主界面查看结果；只有 `VERIFIED` 表示通过了本地结果检查。
5. 选择其他场景或 **Custom task**，检查任务、允许的包名和输入候选，再点击 **Run selected task**。悬浮按钮可能覆盖屏幕顶部的控件，当前版本需要避开其覆盖区域。

自动化使用当前前台界面，不能同时手动操作其他应用。系统授权、登录和无法读取的控件需要人工处理。

在运行 Android 16 的三星 SM-F9460 上，本地测试发现系统组件 `com.samsung.android.onetouch` 会拦截两秒按住手势。临时关闭其长按选项后，测试通过，随后已恢复该设置，详情见 [VALIDATION.md](VALIDATION.md)。如果按住操作唤出了系统功能，请检查该功能的设置，关闭其长按触发，或在支持排除应用时排除目标 App。SDK 不会修改这些系统设置；如果按住操作使前台切换到原应用之外，运行时会停止任务。

### 可选示例场景

选择场景会自动填写任务、包名白名单和输入候选，填写后仍可编辑。仅选择场景不会执行操作；点击 **Run selected task** 后，内置场景会打开本地测试页面，其他场景则开始执行目标应用任务。后端选择和 API Key 与场景独立。

| 场景 | 目标 | 行为 |
|---|---|---|
| **Built-in: save text** | 示例 App | 输入 `Hello Jev`、保存，并检查显示的结果。 |
| **Built-in: long press** | 示例 App | 按住测试控件，并检查可见结果。 |
| **Bilibili: search testv** | `tv.danmaku.bili` | 搜索 `testv`，跳过广告和直播，打开第一个普通视频。 |
| **Bilibili: search + triple action** | `tv.danmaku.bili` | 搜索 `testv`，打开第一个普通视频，再按住点赞按钮两秒一次，尝试完成一键三连。 |
| **Custom task** | 用户配置 | 从空白字段开始，填写任务、允许的包名和准确的输入候选。 |

B 站预设面向国内版 Android App，是任务说明示例，尚未验证为可稳定完成的应用集成。三连预设会影响当前登录账号，并可能消耗 B 站硬币。其说明要求执行一次 `LONG_PRESS`，并在需要登录、出现验证码、硬币不足、没有可用目标或没有可见成功结果时停止，不要求重复按住，也不退回到分别点赞、投币和收藏。定时按住使用读取到的控件中心，因此目标必须可读取且识别正确，SDK 不能保证完成 B 站三连。外部应用预设没有内置结果验证器，模型声称完成时返回 `UNVERIFIED`。

## 数据处理与错误行为

- 只读取白名单应用的可见控件。其他页面的快照仅包含前台包名和任务允许打开的应用列表。
- 跳过密码节点及其子树。这不是完整的个人信息脱敏机制：允许应用内的其他可见文字可能含私人信息。页面信息、任务目标、输入候选和任务历史会发送到选中的后端。
- 不上传截图。各后端只将自己的 API Key 发送到对应的固定地址：Jev 使用 `https://api.typesafe.ai/v1/systemone`，DeepSeek 使用 `https://api.deepseek.com/beta/chat/completions`。禁用重定向和自动连接重试；选择一个后端不会调用另一个后端。
- 单个快照最多包含 220 个元素，节点遍历也有上限。较长页面需要滚动；截断后未包含的控件不会提供给任何模型。
- 示例分别在内存中保存 Jev 与 DeepSeek 的 Key，切换后端不会复用另一个后端的密钥。密钥不持久化或备份；配置页面禁止截图。SDK 不替宿主管理凭据。向其他用户分发时，建议使用用户自备 Key 或受控后端。
- HTTP、协议和运行时异常交给 `onError`。DeepSeek 响应被拒绝时，提供脱敏的原因代码（如 `EMPTY_CONTENT`、`TRUNCATED` 或 `INVALID_TARGET`）及尝试次数，不包含原始响应内容或嵌套解析错误。允许的一次决策纠正不同于 HTTP 重试，也不会重复执行 UI 操作。取消遵循协程取消语义，不作为成功返回。
- 操作失败、低置信度、gate 拒绝、无进展或用尽连续三次页面刷新机会时返回 `BLOCKED`。刷新要求确认动作尚未提交，重新读取当前页面，并让后端做出新决策。成功执行动作后会重置连续刷新计数。已提交或结果不确定的操作不会自动重复提交。
- 模型声称完成，以及 Android 返回 `performAction=true`，都不是业务结果成功的证据。

## 测试与后续工作

离线测试覆盖取消、超时、步骤限制、白名单、无效目标、输入值限制、结果验证、低置信度、动作失败后停止且不重复提交，以及 Jev 响应的概率分布与分支校验。DeepSeek 测试覆盖严格函数结构、动作 ID 映射、无效函数调用或参数、格式错误或被截断的响应、有限次数的决策纠正、脱敏拒绝原因、HTTP 状态处理、响应大小限制及网络请求取消。长按测试检查动作是否可用及后端决策；示例测试覆盖场景配置和切换后端时的密钥隔离。后端响应和 HTTP 行为使用离线样例与模拟请求验证，不调用真实 API。Android 构建和 lint 通过不等于真机任务成功，当前验证状态见 [VALIDATION.md](VALIDATION.md)。

`:sample:connectedDebugAndroidTest` 在连接的模拟器或测试设备上使用确定性决策提供者，检查真实无障碍读取、输入、点击、长按、结果验证、应用启动完成、过期页面刷新和白名单限制。测试会临时开启服务，并在结束后恢复原有无障碍设置。请仅在专用测试设备上运行；这些自动化结果不调用 Jev 或 DeepSeek，不能证明实时 API 可用性或真实模型成功率。

自定义运行时可以实现 `DetailedDeviceRuntime`，区分 `StaleBeforeDispatch` 与操作被拒绝或结果不确定。已有 `DeviceRuntime` 实现仍保留 Boolean 接口；返回 `false` 仍会停止任务，因为 agent 无法确认动作是否已经提交。

首阶段优先验证真实模型调用、中文目标界面和小米真机。后续可加入截图辅助、可选文字生成、更多输入控件、可拖动停止按钮和后端性能评测。通过 `JevProvider(apiKey = key, model = "...")` 或 `DeepSeekProvider(apiKey = key, model = "...")` 可以指定模型；可用性和行为由对应服务决定。默认 `jev-latest` 会跟随服务端更新。

## 参考与许可

动态动作表和单次请求多问题的设计参考了 [browser-use/jev-ultrafast](https://github.com/browser-use/jev-ultrafast)。Android 实现独立编写，本项目不是 Browser Use、TypeSafe 或 DeepSeek 官方 SDK。

- [Jev 官方文档](https://docs.typesafe.ai/introduction)
- [DeepSeek API 文档](https://api-docs.deepseek.com/api/create-chat-completion/)
- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [上游 MIT 许可证](https://github.com/browser-use/jev-ultrafast/blob/main/LICENSE)

本项目采用 MIT 许可证。模型服务、Android 平台和依赖包受各自条款约束。个人安装与应用商店分发的要求不同，上架前需要核对相应平台的无障碍自动化政策。
