# jev-android

[English](README.md) | [简体中文](README.zh-CN.md)

可接入 Android 项目的 Kotlin UI Agent SDK。**TypeSafe Jev** 从当前屏幕的真实控件中选择操作，再由 Android 无障碍服务执行。项目包含示例 App 和无需目标应用账号的本地测试页面。

这是首个版本，API 尚未稳定。项目未发布到 Maven Central；下文的依赖坐标仅适用于本项目生成的本地 Maven 仓库。示例 App 界面和代码保持英文。

## 功能与限制

- 动态构建动作表。一次 Jev 请求同时选择操作及各类操作的目标，仅执行最终选中的分支。
- 支持点击、替换输入框内容、前后滚动、返回、打开允许的应用、等待，以及报告完成或受阻。
- Jev 不生成文字。调用方通过 `Task.textValues` 提供命名的准确候选值，由 Jev 选择；无需 DeepSeek Key。
- 执行前重新读取屏幕并比较指纹。页面过期时停止任务，避免把旧节点编号用于变化后的界面。
- 输入后读取控件并验证完整值。操作被拒绝时停止，不自动重复执行。
- 提供包名白名单、步骤和时间限制、最低置信度、无进展检测及宿主自定义操作策略。
- 取消协程会取消正在进行的 HTTP 请求；无障碍悬浮按钮可停止后续操作。已经提交给 Android 的操作无法撤销。
- `DONE` 仅表示模型声称完成。只有提供 `OutcomeVerifier` 且验证通过，任务才返回 `VERIFIED`，否则返回 `UNVERIFIED`。

当前不支持截图视觉、猜测坐标、任意手势、通用 WebView 或 Canvas 识别、密码输入、文本生成、锁屏操作及长期后台无人值守。尚未在小米真机上验证 HyperOS 兼容性。宿主应用负责决定允许哪些设置变更、付款、消息发送等操作。默认 gate 放行白名单应用内的有效动作，不会自动识别所有敏感控件。

## 模块

| 模块 | 作用 |
|---|---|
| `core` | 与平台无关的任务模型、决策接口、执行循环、验证和事件 |
| `sdk` | TypeSafe HTTP/JSON 接口、无障碍运行时、服务及停止按钮 |
| `sample` | API 配置、授权设置入口、任务执行、结果日志和本地测试页面 |

执行流程：读取控件 → 构建有效选项 → Jev 选择动作 → 检查宿主策略 → 确认页面未过期 → 执行动作 → 观察结果。

## 构建

使用 JDK 17 或兼容版本，以及 Android SDK Platform 36。项目自带 Gradle 8.13 Wrapper。可以用 Android Studio 打开项目根目录，或通过 `ANDROID_HOME`、不纳入版本管理的 `local.properties` 配置 SDK 路径：

```properties
sdk.dir=/your/path/to/Android/Sdk
```

Windows 命令：

```powershell
.\gradlew.bat :core:test :sdk:testDebugUnitTest :sdk:assembleRelease :sample:assembleDebug
.\gradlew.bat :sdk:lintDebug :sample:lintDebug
```

macOS 或 Linux 使用 `./gradlew`。项目设置了 `android.overridePathCheck=true`，允许 Windows 非 ASCII 路径。如果第三方工具出现路径相关问题，请改用纯 ASCII 路径。

本机验证时，中文 Windows 路径导致 Gradle 测试进程出现 `ClassNotFoundException`。随附脚本会在新的临时英文目录中构建，再把产物和报告复制回项目：

```powershell
.\scripts\build-windows.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr'
```

请按实际安装位置修改 JDK 路径。脚本保留原项目文件；Android SDK 路径仍需通过 `ANDROID_HOME` 或 `local.properties` 提供。

构建产物：

- `sample/build/outputs/apk/debug/sample-debug.apk`
- `sdk/build/outputs/aar/sdk-release.aar`
- `core/build/libs/core-0.1.0.jar`

**AAR 不包含全部依赖。** SDK 还依赖 core 模块、协程和 OkHttp，建议按下面的源码模块或 Maven 方式接入。

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
implementation("io.github.jevandroid:jev-android:0.1.0")
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

将示例中的 `res/xml/jev_accessibility.xml` 和 `strings.xml` 中的说明字符串复制到宿主。先向用户说明可见页面文字会发送到 TypeSafe，再由用户通过 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 开启服务。服务应与宿主 app 运行在同一进程。

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

`OutcomeVerifier` 接收新读取的 `UiSnapshot`，用于检查具体业务结果。示例会核对测试页面显示的保存值；验证器返回 false 时，结果为 `UNVERIFIED`。

`ActionGate` 可以接入宿主确认界面或业务规则，返回 false 会停止任务。如果等待确认时页面发生变化，运行时仍会拒绝基于旧页面的动作。不要仅凭 Jev 自报的置信度授权敏感操作。

实现 `DecisionProvider` 可以替换模型，实现 `DeviceRuntime` 可以用模拟设备测试。直接使用 `JevAgent` 时，由宿主负责协程生命周期、错误处理、停止控制和同一设备的互斥访问。通常使用 `JevAccessibilityService.start` 更方便，它限制每个服务只能运行一个任务。

## 在小米手机上试用

1. 安装示例 debug APK，填入 **TypeSafe** Key。DeepSeek Key 不能调用 Jev。
2. 在系统设置中开启无障碍服务。不同 HyperOS 版本的菜单与限制可能不同，请以实际设备为准。
3. 先点击 `2. Run on the built-in test page`，保持屏幕解锁。任务应输入 `Hello Jev`，点击 `Save`，并验证显示 `Saved: Hello Jev`。
4. 点击右上角的 `Stop Jev` 可停止执行。返回示例主界面查看结果；只有 `VERIFIED` 表示通过了本地结果检查。
5. 然后配置其他目标包名、任务和文字候选。悬浮按钮可能覆盖屏幕顶部的控件，首版需要避开其覆盖区域。

自动化使用当前前台界面，不能同时手动操作其他应用。系统授权、登录和无法读取的控件需要人工处理。

## 数据处理与错误行为

- 只读取白名单应用的可见控件。其他页面的快照仅包含前台包名和任务允许打开的应用列表。
- 跳过密码节点及其子树。这不是完整的个人信息脱敏机制：允许应用内的其他可见文字可能含私人信息，并会发送到 TypeSafe。
- 不上传截图，不把 API Key 发送给其他模型。HTTP 地址固定为 `https://api.typesafe.ai/v1/systemone`，禁用重定向和自动连接重试。
- 单个快照最多包含 220 个元素，节点遍历也有上限。较长页面需要滚动；截断后未包含的控件不会提供给 Jev。
- 示例 Key 仅保存在内存，不持久化或备份；配置页面禁止截图。SDK 不替宿主管理凭据。向其他用户分发时，建议使用用户自备 Key 或受控后端。
- HTTP、协议和运行时异常交给 `onError`；取消遵循协程取消语义，不作为成功返回。
- 操作失败、页面过期、低置信度、gate 拒绝或无进展时返回 `BLOCKED`，不会静默重新提交操作。
- 模型声称完成，以及 Android 返回 `performAction=true`，都不是业务结果成功的证据。

## 测试与后续工作

离线测试覆盖取消、超时、步骤限制、白名单、无效目标、输入值限制、结果验证、低置信度、动作失败后停止且不重试，以及 Jev 响应的概率分布与分支校验。Android 构建和 lint 通过不等于真机任务成功，当前验证状态见 [VALIDATION.md](VALIDATION.md)。

`:sample:connectedDebugAndroidTest` 在连接的模拟器或测试设备上使用确定性决策提供者，检查真实无障碍读取、输入、点击、结果验证、过期页面拒绝和白名单限制。测试会临时开启服务，并在结束后恢复原有无障碍设置。请仅在专用测试设备上运行；它不调用 Jev，也不能证明真实模型成功率。

首阶段优先验证中文目标界面和小米真机。后续可加入截图辅助、DeepSeek 文本生成、更多输入控件、可拖动停止按钮和性能评测。通过 `JevProvider(model = "...")` 可以固定模型版本；默认 `jev-latest` 会跟随服务端更新。

## 参考与许可

动态动作表和单次请求多问题的设计参考了 [browser-use/jev-ultrafast](https://github.com/browser-use/jev-ultrafast)。Android 实现独立编写，本项目不是 Browser Use 或 TypeSafe 官方 SDK。

- [Jev 官方文档](https://docs.typesafe.ai/introduction)
- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [上游 MIT 许可证](https://github.com/browser-use/jev-ultrafast/blob/main/LICENSE)

本项目采用 MIT 许可证。模型服务、Android 平台和依赖包受各自条款约束。个人安装与应用商店分发的要求不同，上架前需要核对相应平台的无障碍自动化政策。
