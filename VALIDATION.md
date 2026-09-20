# Validation record

Date: 2026-09-20. This record covers version 0.3.1, including the stop-overlay fingerprint regression fix, native long-click, timed long-press, and selectable task scenarios.

## Build and offline checks

| Check | Result |
|---|---|
| `:core:test` | 21 passed, 0 failed |
| `:sdk:testDebugUnitTest` | 64 passed, 0 failed |
| `:sample:testDebugUnitTest` | 8 passed, 0 failed |
| `:sdk:assembleRelease` | Passed; release AAR generated |
| `:sample:assembleDebug` | Passed; installable debug APK generated |
| `:sample:assembleDebugAndroidTest` | Passed; instrumented test APK generated |
| `:sdk:lintDebug` | 0 errors, 6 warnings |
| `:sample:lintDebug` | 0 errors, 35 warnings |
| Core and SDK local Maven publication | Passed; includes POM files, Gradle module metadata, and AAR/JAR artifacts |

All 93 offline tests passed. The SDK suite includes 13 Jev protocol tests, 26 DeepSeek protocol tests, 12 HTTP/provider tests, 5 geometry tests, and 8 snapshot fingerprint tests. Long-click and long-press checks cover compatible target selection, allowlists, unsupported targets, hold-duration bounds, clipping to observed screen/window bounds, and occluded controls. Fingerprint tests check delayed overlay layout, real UI changes, gesture geometry, and missing gesture fingerprints. Provider checks also cover strict JSON, truncation, invalid candidates, request payloads, status handling, redirects/retries, response limits, cancellation, and sanitized errors. No external model API is called by these tests.

The 8 sample tests check provider-key isolation and the scenario catalog: fixture routing follows the actual host package, Bilibili scenarios replace the demo allowlist and input, custom fields start blank, and presets construct valid tasks.

Lint warnings concern dependency updates, hardcoded UI strings, and sample manifest/resource configuration. Lint errors were not suppressed, and the build is not warning-free. Both local Maven publications use version 0.3.1.

## Delayed-provider regression

The user reported `accepted=false` followed by `BLOCKED: Action rejected or stale UI; inspect before restarting` in the built-in save-text scenario when using DeepSeek. Adding a 400 ms delay before each deterministic provider decision reproduced the exact failure on the Samsung phone with version 0.3.0: 1 test ran and failed with expected `VERIFIED`, actual `BLOCKED`.

The stop overlay was added immediately before the first observation but had not completed layout. During the provider wait, its geometry became available and changed the shared snapshot fingerprint, incorrectly invalidating a native text-entry action. Version 0.3.1 waits for the stop control to have bounds and checks gesture geometry separately from native UI identity. Real UI changes still invalidate all actions; timed holds also require matching non-null gesture fingerprints and fresh targets.

After installing 0.3.1, the same delayed save-text test passed on the same phone with the Samsung long-press setting left at its original value of `1`. It saved `Hello Jev` and independently returned `VERIFIED` after two mutating actions. This reproduces and verifies the SDK timing bug without using a model API key; it does not establish live DeepSeek API availability.

## Samsung device checks

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

The initial version passed 2 instrumented tests with 0 failures on `Pixel_3a_API_33_x86_64`, Android 13 / API 33. Those older emulator reports precede the language, provider, and long-press updates. The Samsung results above describe the current version.

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

- No model API credentials were used in automated validation. Live Jev and DeepSeek responses, account/model availability, Chinese-language accuracy, model latency, and billing have not been independently verified.
- Bilibili search and triple-action presets have not been validated end to end. They have no independent outcome verifier; provider-reported completion is `UNVERIFIED`.
- macOS setup instructions are included, but the project has not been built or run on a Mac in this validation session.
- Physical Xiaomi testing has not been performed. HyperOS permissions, background behavior, and third-party app task success rates have not been verified.
- Android version coverage is incomplete. API 26 is the declared minimum; device tests cover API 36 for this version and API 33 for the earlier baseline.
- SDK artifacts have not been published to Maven Central or an app store. The source is MIT-licensed; the artifact and group coordinates currently exist only in the local Maven distribution.

## Local reports

- `core/build/reports/tests/test/index.html`
- `sdk/build/reports/tests/testDebugUnitTest/index.html`
- `sample/build/reports/tests/testDebugUnitTest/index.html`
- `sample/build/reports/deepseek-delay-regression-before-0.3.1.txt` (delayed save-text failure on 0.3.0)
- `sample/build/reports/deepseek-delay-regression-after-0.3.1.txt` (same delayed save-text test passing on 0.3.1)
- `sample/build/reports/samsung-instrumentation-0.3.1.txt` (current five-test device result)
- `sdk/build/reports/lint-results-debug.html`
- `sample/build/reports/lint-results-debug.html`
- `sample/build/reports/androidTests/connected/debug/index.html` (previous device-test baseline)

Some links inside copied HTML reports may refer to the temporary build directory. Machine-readable unit-test XML results are in each module's `build/test-results` directory. The Samsung run used an explicitly selected wireless ADB device and recorded AndroidJUnitRunner output in the text report above. Retained `androidTests/connected` HTML/XML reports are from the earlier emulator baseline.
