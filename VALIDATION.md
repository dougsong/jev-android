# Validation record

Date: 2026-09-21. This record covers version 0.3.5, with earlier device and regression results retained under their version labels.

## Version 0.3.5 Bilibili triple-action policy and cancellation diagnostics

After the successful version 0.3.4 search and Save-text checks below, the user reported that the triple action had not succeeded. A subsequent version 0.3.4 trace recorded one `LONG_PRESS` with `accepted=true`, another `LONG_PRESS` decision, and then a generic task-stopped message. The user confirmed that they had not pressed Stop. UI inspection after the run showed Like, Coin, and Favorite all reporting `checked=false`; successful triple action was not established. A chosen decision is not an execution result, and cancellation without a second result does not prove that a second gesture was never submitted.

The diagnostic procedure included `uiautomator dump` while the accessibility task could still be active. Android documents that [UiAutomation suppresses accessibility services by default](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES). The debug inspection may therefore have interrupted this run; its exact cancellation source was not retained. The earlier Samsung interception finding from the local hold fixture remains relevant, but it does not establish the cause of this Bilibili failure. The Samsung long-press setting remained `1` during this investigation. Further live validation must avoid a default UI-automation connection while the task is active.

Version 0.3.5 adds optional Android view resource IDs to observed `Element` descriptions in both providers. Resource IDs identify controls but are never dispatch targets: operations still resolve through the current snapshot's element ID and pass the existing host gate and fresh-screen checks. Adding the optional constructor parameter is source-compatible for Kotlin calls that omit it when rebuilt, but changes the binary constructor signature; core, SDK, and host must be rebuilt together.

The sample's Bilibili triple-action preset now enforces a local policy using the exact Like, Coin, and Favorite resource IDs on a video detail page. It permits one timed hold on Like only when all three controls are unchecked, and rejects individual like/coin/favorite interactions. The hold allowance is consumed by an execution result, so a provably unsubmitted stale decision may be observed and selected again. After the hold, the local provider checks up to four observations, with waits between them; it cannot request another interaction. Its verifier requires all three controls to report `checked=true` on the same observed video title. This check does not establish first-result ordering, a newly spent coin, or backend account state. Missing, ambiguous, or unsupported controls cannot produce verified success.

The service also records a fixed cancellation diagnostic for a Stop request, Android accessibility interruption, or service disconnection. These messages include no task or screen content. They make future cancellations distinguishable; they cannot retrospectively identify the version 0.3.4 cancellation or undo a submitted gesture.

### Version 0.3.5 verification status

All **146 offline tests passed**: 29 core, 94 SDK, and 23 sample tests, with no failures, errors, or skips. The release AAR, demo APK, instrumented test APK, and both local Maven publications built successfully at version 0.3.5. The full build completed in 18 seconds. Lint reported 0 errors with 6 SDK warnings and 34 sample warnings; the build is not warning-free.

The installed 0.3.5 demo passed **3 Samsung device smoke tests, 0 failures**, in 8.914 seconds: saving text through the accessibility fixture, refreshing a stale UI before exactly one Save click, and capturing a resource ID while distinguishing a service interruption. The new test observed `android:id/button1` through the runtime, invoked the service's `onInterrupt()` callback, and confirmed that the waiting deterministic provider was cancelled with the fixed interruption reason before any UI action. This checks callback handling, not the cause of the earlier live cancellation.

The test APK was removed afterward, and installed versionCode 8 / versionName 0.3.5 was verified. Instrumentation initially left the Jev accessibility binding unavailable; opening the demo and rebinding only its service restored the binding. The exact original accessibility-service list including Bixby was preserved, and the Samsung long-press setting remained `1`.

The version 0.3.5 source, local Maven, and APK packages passed configuration/cache exclusion, expected artifact and source content, executable wrapper permission, SHA-256, and built-versus-packaged APK checks. A credential-pattern scan found no matching secrets in tracked or publishable source files.

Version 0.3.5 live API validation is pending a user-entered key. A successful real-account Bilibili triple action has not been verified. The four post-hold observations currently span roughly two seconds; slower UI updates can conservatively stop the task without another interaction. Inspect the final app state before starting a new run. The version 0.3.4 live search and Save-text results below remain evidence for those flows only.

## Version 0.3.4 bounded DeepSeek action selection

After version 0.3.3 was installed, the user reported `INVALID_TARGET` with `attempts=2` during the Bilibili preset. This establishes that the final response selected a target outside the valid candidates for its chosen operation after one correction request. The diagnostic does not retain the first rejection category or the actual invalid target value. Selecting a text child rather than its clickable parent is a possible explanation, but it has not been established from these logs.

Version 0.3.4 couples each valid operation and target into a locally generated `action_id`. DeepSeek receives the IDs as an enum in a strict `select_action` function schema and must return exactly one call to that function with `action_id`, `text_key`, and `confidence` arguments. It no longer supplies free-form operation names or raw UI target IDs. The text key is a separate string enum of the host's nonblank keys plus an empty string for no input; local validation also checks its compatibility with the chosen operation. The empty-string marker maps to `null` in `Decision`.

Requests use DeepSeek's official `https://api.deepseek.com/beta/chat/completions` endpoint with thinking disabled and a forced function choice, following its [strict tool-call guide](https://api-docs.deepseek.com/guides/tool_calls/). The key still goes only to the same DeepSeek host. A function call is parsed as a local model decision; no remote tool is executed. Local validation, the host gate, and fresh-screen checks remain required. Invalid decisions can receive at most one correction request; HTTP/network errors, refusals, and content filtering remain terminal. A live call through this endpoint succeeded on one DeepSeek account and Samsung device on 2026-09-21, as detailed below; other account or model combinations remain unverified.

The DeepSeek candidate catalog also adds bounded visible descendant text to actionable containers, so a clickable video card can expose its title in the candidate description. It does not synthesize click support for a non-actionable child. This improves candidate descriptions without claiming that it reproduces the user's exact invalid selection.

### Version 0.3.4 verification status

All **129 offline tests passed**: 29 core, 92 SDK, and 8 sample tests, with no failures, errors, or skips. The SDK total includes 35 strict DeepSeek protocol tests, 12 candidate-catalog tests, and 7 decision-correction tests. They cover the strict function schema, snapshot-bound action-ID mapping, invalid or multiple function calls, input-key compatibility, immutable candidate bindings, bounded descendant descriptions, terminal failures, and at most one correction request. These tests use fixture responses, not a real DeepSeek API key.

The release AAR, demo APK, instrumented test APK, and both local Maven publications built successfully at version 0.3.4. Lint reported 0 errors with 6 SDK warnings and 35 sample warnings; the build is not warning-free.

The installed 0.3.4 demo passed **2 Samsung device smoke tests, 0 failures**, in 6.364 seconds: observing and saving text through the real accessibility fixture, and refreshing a changed UI before exactly one Save click. These use deterministic providers. The test APK was removed afterward, demo versionCode 7 / versionName 0.3.4 was verified, and the original accessibility-service list including Bixby was restored with the Jev service bound. The Samsung long-press setting remained at its original value of `1`.

The source archive, local Maven archive, and APK packages passed checks for excluded local configuration and caches, expected artifact contents, executable `gradlew` permissions in the source archive, SHA-256 hashes, and packaged APK equality with the built APK. The version 0.3.2 seven-test run below remains the prior broader device baseline.

### Version 0.3.4 live DeepSeek checks

On 2026-09-21, the user entered a DeepSeek API key directly in the Samsung demo. The key was not read or recorded by the validation operator. The live Bilibili `search testv` preset completed its model decision loop with **5 accepted actions**, **3 fresh-screen refreshes before dispatch**, and no response rejection. Its final `DONE` decision produced `UNVERIFIED`, as expected for a scenario without an independent outcome verifier.

ADB UI inspection showed a TESTV official-channel video detail page. The exact first-result ordering was not independently recorded, and no Bilibili triple action was tested. This run establishes that the strict beta endpoint and the search/video-opening flow worked for this account, device, and run; it does not establish a general success rate or verify the triple-action preset.

A second live DeepSeek run used **Built-in: save text**. `SET_TEXT` and `CLICK` both returned `accepted=true`; the final `DONE` decision produced `VERIFIED: Outcome verified`. The fixture was independently observed displaying `Saved: Hello Jev`. Both live runs completed without a protocol rejection. Unlike the Bilibili preset, the built-in scenario supplies an `OutcomeVerifier` that checks the saved value.

## Version 0.3.3 DeepSeek response handling

The user reported `Failed: IllegalArgumentException DeepSeek response rejected` while running the Bilibili triple-action preset on version 0.3.2. The retained event logs showed accepted app launch, search-field selection, text entry, and search submission before a response-parsing failure in two runs. The raw provider responses were discarded, so those logs do not establish which response field or validation condition caused the failure. The earlier launch-transition and stop-overlay regressions do not explain this parser error.

Version 0.3.3 replaces the generic rejection message with a typed, sanitized reason code and attempt count. Optional `error`, `function_call`, and `tool_calls` fields may be null, and an empty `tool_calls` array is tolerated. Actual tool calls, refusals, content filtering, and invalid actions or targets remain rejected. The output budget increases from 256 to 1,024 tokens. DeepSeek's [JSON mode documentation](https://api-docs.deepseek.com/guides/json_mode/) notes that an empty response can occur and that insufficient `max_tokens` can truncate JSON; these are handled cases, not confirmed causes of the user's two failures.

An empty, truncated, or invalid decision permits one correction request before a UI action is submitted. It reuses the original snapshot, task context, and a fixed reason code, without forwarding the malformed response. A corrected decision still passes the original action/target validation, host gate, and fresh-screen checks. HTTP/network failures, refusals, content filtering, and tool-call responses are terminal; cancellation stops pending work. Final rejection diagnostics contain no raw response or nested parser exception. This is separate from the stale-screen refresh mechanism and never resubmits an already dispatched UI action.

### Version 0.3.3 verification status

All **120 offline tests passed**: 29 core, 83 SDK, and 8 sample tests. The SDK total includes 38 DeepSeek protocol tests and 7 decision-correction tests. The new cases exercise nullable metadata, typed rejection reasons, at most two model requests per decision, preserved decision context, no forwarded malformed response, terminal HTTP/refusal/filter/tool failures, cancellation, diagnostic redaction, and exactly one UI action after a corrected decision passes validation. All model responses in these tests are fixtures; no real DeepSeek API key or response is used.

The release AAR, demo APK, instrumented test APK, and both local Maven publications built successfully at version 0.3.3. Lint reported 0 errors with 6 SDK warnings and 35 sample warnings; the build is not warning-free.

The installed 0.3.3 demo passed **3 Samsung device smoke tests, 0 failures**, in 7.480 seconds: scenario selection, delayed text entry and Save, and a changed target requiring a new decision before exactly one Save click. These use deterministic providers; they do not exercise a live DeepSeek response or Bilibili triple action. The Samsung long-press setting remained `1` throughout this run. Afterward the test APK was removed, demo versionCode 6 / versionName 0.3.3 was verified, and the original accessibility-service list (including Bixby) was restored with the Jev service bound.

The version 0.3.2 seven-test run below remains the prior broader device baseline. The user's original parser failure was not reproduced with its actual raw response; live DeepSeek/Bilibili completion had not been verified at the time of the 0.3.3 checks.

## Version 0.3.2 launch-transition regression

The user reported the same `accepted=false` / `BLOCKED` message with the Bilibili triple-action preset after Bilibili opened but before the `testv` search. The captured event sequence showed an `OPEN_APP` decision on the launcher accepted by Android, a new observation roughly 250 ms later still reporting the launcher, and a second `OPEN_APP` decision rejected as stale after the app switch completed. This is a separate timing case from the stop-overlay regression fixed in 0.3.1.

Version 0.3.2 waits up to 10 seconds for the requested app to become the foreground app before accepting `OPEN_APP`. A stale snapshot rejected before any action is submitted permits a fresh observation and new provider decision, with at most three consecutive refreshes. Each refresh consumes a decision cycle from `maxSteps`, but is not an executed step or action-history entry. Refreshes remain within the task's time budget; a successful action resets the consecutive-refresh counter. The host gate applies again to the new decision. Other rejections and uncertain outcomes remain terminal, so submitted actions are not blindly repeated.

Two focused Samsung regression tests passed: a target changes during the provider wait, causing one fresh decision and exactly one Save click; and app launch returns only when the requested package is observed. A separate run with `launchPackage=tv.danmaku.bili` passed on the real installed Bilibili app, with exactly one `OPEN_APP` execution and Bilibili present in the next provider observation. No search, video interaction, triple action, or live model API call was performed by this launch test.

## Version 0.3.2 Samsung device checks

The final instrumented run passed **7 tests, 0 failures**, in 20.401 seconds on the Samsung SM-F9460, Android 16 / API 36, through wireless ADB. It includes the previous five scenarios (selection, delayed text entry and Save, measured two-second hold, stale/allowlist rejection, native long-click) and the two new refresh/launch regression tests. The final run supplied `launchPackage=tv.danmaku.bili`, so its launch check exercised Bilibili rather than the demo app.

As previously authorized for the local two-second hold test, the Samsung long-press interceptor setting was temporarily changed from `1` to `0` and restored to `1` in a `finally` block. The separate Bilibili launch test also passed with the original setting of `1`. After the final run, the test APK was removed, demo 0.3.2 was opened, and its accessibility service was rebound. Read-back checks confirmed versionCode 5, the original accessibility-service list including Bixby, accessibility enabled at `1`, Samsung long-press setting `1`, and the interceptor package's default state (`enabled=0`).

One earlier full-suite attempt timed out in AndroidX `ActivityScenario.close()` while launching its empty cleanup activity, after the refresh test's action assertions had completed. The test helper now explicitly finishes its activity, waits at most five seconds for destruction, and closes the destroyed scenario. The final seven-test run above uses that helper; the earlier timeout report is retained separately.

## Version 0.3.2 build and offline checks

| Check | Result |
|---|---|
| `:core:test` | 29 passed, 0 failed |
| `:sdk:testDebugUnitTest` | 64 passed, 0 failed |
| `:sample:testDebugUnitTest` | 8 passed, 0 failed |
| `:sdk:assembleRelease` | Passed; release AAR generated |
| `:sample:assembleDebug` | Passed; installable debug APK generated |
| `:sample:assembleDebugAndroidTest` | Passed; instrumented test APK generated |
| `:sdk:lintDebug` | 0 errors, 6 warnings |
| `:sample:lintDebug` | 0 errors, 35 warnings |
| Core and SDK local Maven publication | Passed; includes POM files, Gradle module metadata, and AAR/JAR artifacts |

All 101 offline tests passed. The SDK suite includes 13 Jev protocol tests, 26 DeepSeek protocol tests, 12 HTTP/provider tests, 5 geometry tests, and 8 snapshot fingerprint tests. Long-click and long-press checks cover compatible target selection, allowlists, unsupported targets, hold-duration bounds, clipping to observed screen/window bounds, and occluded controls. Fingerprint tests check delayed overlay layout, real UI changes, gesture geometry, and missing gesture fingerprints. Provider checks also cover strict JSON, truncation, invalid candidates, request payloads, status handling, redirects/retries, response limits, cancellation, and sanitized errors. No external model API is called by these tests.

The 8 new core tests cover fresh target selection after a stale decision, omitted stale action history, bounded refreshes and decision budgets, reset after acceptance, terminal uncertain input/hold outcomes, repeated host-gate checks, cancellation, and timeouts. Existing Boolean runtimes remain terminal on `false`. Bytecode inspection confirmed that `DeviceRuntime` still contains only its original `observe` and `execute` methods; typed results use the optional `DetailedDeviceRuntime` interface and bridging extension.

The 8 sample tests check provider-key isolation and the scenario catalog: fixture routing follows the actual host package, Bilibili scenarios replace the demo allowlist and input, custom fields start blank, and presets construct valid tasks.

Lint warnings concern dependency updates, hardcoded UI strings, and sample manifest/resource configuration. Lint errors were not suppressed, and the build is not warning-free. Both local Maven publications use version 0.3.2.

## Delayed-provider regression

The user reported `accepted=false` followed by `BLOCKED: Action rejected or stale UI; inspect before restarting` in the built-in save-text scenario when using DeepSeek. Adding a 400 ms delay before each deterministic provider decision reproduced the exact failure on the Samsung phone with version 0.3.0: 1 test ran and failed with expected `VERIFIED`, actual `BLOCKED`.

The stop overlay was added immediately before the first observation but had not completed layout. During the provider wait, its geometry became available and changed the shared snapshot fingerprint, incorrectly invalidating a native text-entry action. Version 0.3.1 waits for the stop control to have bounds and checks gesture geometry separately from native UI identity. Real UI changes still invalidate all actions; timed holds also require matching non-null gesture fingerprints and fresh targets.

After installing 0.3.1, the same delayed save-text test passed on the same phone with the Samsung long-press setting left at its original value of `1`. It saved `Hello Jev` and independently returned `VERIFIED` after two mutating actions. This reproduces and verifies the SDK timing bug without using a model API key; it does not establish live DeepSeek API availability.

## Version 0.3.1 Samsung device checks

The final instrumented run passed **5 tests, 0 failures**, on a Samsung SM-F9460 running Android 16 / API 36, connected through wireless ADB. The tests use deterministic providers or direct SDK runtime calls, with no model API key or request:

1. Scenario selection fills the Bilibili goal, package, and `testv` input without starting a task; switching to Custom clears those fields.
2. Real accessibility text entry and clicking save `Hello Jev`, with a 400 ms provider delay before each decision; an independent verifier returns `VERIFIED`.
3. A two-second `LONG_PRESS` reaches the fixture button. The fixture measures touch-down to touch-up, requires at least 1,800 ms in the test, confirms exactly one completed hold, and rejects a second action using the stale snapshot.
4. External UI changes invalidate old snapshots; controls outside the allowlist are not exposed.
5. Native `LONG_CLICK` invokes the long-click listener without producing a timed touch or a normal click.

**Device condition:** Samsung's `com.samsung.android.onetouch` initially intercepted the held touch after about 1.3 seconds and delivered `ACTION_CANCEL` to the correctly targeted button. The successful five-test run temporarily changed the device's secure setting `otch_long_press_enabled_setting` from `1` to `0`, with the owner's explicit permission. It was restored to `1` immediately afterward and read back to verify restoration. The component's original package state (`enabled=0`, meaning Android's default state) was also verified after an earlier temporary-disable attempt. The SDK does not modify either setting.

With the Samsung trigger enabled, a timed hold may still be interrupted. The runtime now checks the foreground after a hold and stops if a system component or another app took over. Callback completion alone does not establish that the app accepted a hold or that the business action succeeded. Bilibili's triple action was not executed during validation.

The test setup rebinds only the demo accessibility service when instrumentation restarts the process, preserves other services, and restores the original accessibility settings. Button matching tolerates Android's uppercase display transformation. After validation, the temporary test APK was removed; demo 0.3.1 remained installed and its accessibility service was rebound. The original service list (including Bixby), accessibility-enabled value, Samsung long-press setting, and package state were read back and verified.

## Previous device-test baseline

The initial version passed 2 instrumented tests with 0 failures on `Pixel_3a_API_33_x86_64`, Android 13 / API 33. Those older emulator reports precede the language, provider, and long-press updates. The version-labeled Samsung sections above distinguish current results from earlier baselines.

Device tests temporarily enabled the accessibility service and restored the original settings afterward. The `enabled_accessibility_services` setting was checked and had returned to its original empty value.

Device test 1 locates the text field through the real accessibility tree, enters `Hello Jev`, clicks the save control, and independently checks the saved-value label. The baseline returned `VERIFIED` after two mutating actions. In the updated English fixture, the control is labeled `Save` and the expected result is `Saved: Hello Jev`.

Device test 2 modifies the text field after observation and confirms that an action based on the old snapshot is rejected. It also checks that a snapshot from a non-allowlisted app has an empty controls list.

Device tests use a **deterministic `DecisionProvider`** and execute real Android actions without calling Jev or DeepSeek. Provider protocol unit tests use local JSON fixtures.

## Build environment

- Gradle 8.13, Android Gradle Plugin 8.12.3, and Kotlin 2.0.21.
- Android SDK Platform 36 and Android Studio JBR 21.0.6; Java bytecode target 17.
- Compilation worked in a Windows project directory containing Chinese characters, but Gradle test workers failed with `ClassNotFoundException`. Switching JDKs did not resolve the failure. The checks passed from a new temporary ASCII-only directory.
- `scripts/build-windows.ps1` was run successfully to copy sources, build, and copy artifacts and reports back to the project.

## Not yet verified

- Offline unit tests and instrumented smoke tests use fixture responses or deterministic providers. The separate 0.3.4 live DeepSeek checks above used one user-configured account; live Jev responses, other account/model combinations, general Chinese-language accuracy, model latency, and billing remain unverified.
- A live Bilibili search run reached a TESTV video detail page, but exact first-result ordering was not independently recorded. The triple-action preset remains untested. Both presets have no independent outcome verifier; provider-reported completion is `UNVERIFIED`.
- macOS setup instructions are included, but the project has not been built or run on a Mac in this validation session.
- Physical Xiaomi testing has not been performed. HyperOS permissions, background behavior, and third-party app task success rates have not been verified.
- Android version coverage is incomplete. API 26 is the declared minimum; completed device tests cover API 36 for versions 0.3.1 through 0.3.4, and API 33 for the earlier baseline.
- SDK artifacts have not been published to Maven Central or an app store. The source is MIT-licensed; the artifact and group coordinates currently exist only in the local Maven distribution.

## Local reports

- `core/build/reports/tests/test/index.html`
- `sdk/build/reports/tests/testDebugUnitTest/index.html`
- `sample/build/reports/tests/testDebugUnitTest/index.html`
- `sample/build/reports/deepseek-live-0.3.4.txt` (sanitized live DeepSeek check record)
- `sample/build/reports/samsung-smoke-0.3.4.txt` (current two-test device smoke result)
- `sample/build/reports/samsung-smoke-0.3.3.txt` (previous three-test device smoke result)
- `sample/build/reports/deepseek-delay-regression-before-0.3.1.txt` (delayed save-text failure on 0.3.0)
- `sample/build/reports/deepseek-delay-regression-after-0.3.1.txt` (same delayed save-text test passing on 0.3.1)
- `sample/build/reports/launch-refresh-regression-0.3.2.txt` (two focused regression tests)
- `sample/build/reports/bilibili-launch-regression-0.3.2.txt` (real Bilibili launch with the original Samsung setting)
- `sample/build/reports/samsung-instrumentation-0.3.2.txt` (previous seven-test device result)
- `sample/build/reports/samsung-instrumentation-0.3.2-cleanup-timeout.txt` (earlier test-framework cleanup timeout; superseded by the final run)
- `sample/build/reports/samsung-instrumentation-0.3.1.txt` (previous five-test device result)
- `sdk/build/reports/lint-results-debug.html`
- `sample/build/reports/lint-results-debug.html`
- `sample/build/reports/androidTests/connected/debug/index.html` (previous device-test baseline)

Some links inside copied HTML reports may refer to the temporary build directory. Machine-readable unit-test XML results are in each module's `build/test-results` directory. The Samsung run used an explicitly selected wireless ADB device and recorded AndroidJUnitRunner output in the text report above. Retained `androidTests/connected` HTML/XML reports are from the earlier emulator baseline.
