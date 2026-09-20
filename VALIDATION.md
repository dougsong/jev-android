# Validation record

Date: 2026-09-20. This record covers version 0.1.0, including the English-language update.

## English-language update checks

| Check | Result |
|---|---|
| `:core:test` | 12 passed, 0 failed |
| `:sdk:testDebugUnitTest` | 6 passed, 0 failed |
| `:sdk:assembleRelease` | Passed; release AAR generated |
| `:sample:assembleDebug` | Passed; installable debug APK generated |
| `:sample:assembleDebugAndroidTest` | Passed; instrumented test APK generated |
| `:sdk:lintDebug` | 0 errors, 4 warnings |
| `:sample:lintDebug` | 0 errors, 16 warnings |
| Core and SDK local Maven publication | Passed; includes POM files, Gradle module metadata, and AAR/JAR artifacts |

The English update was rebuilt and the 18 offline tests passed. The instrumented test APK compiled, but device tests were not rerun after the language changes because the previously used emulator was no longer available in the connected-device inventory.

Lint warnings primarily concern available dependency updates and hardcoded UI strings in the sample and stop button. Lint errors were not suppressed, and the build is not warning-free.

## Previous device-test baseline

The initial version passed 2 instrumented tests with 0 failures on `Pixel_3a_API_33_x86_64`, Android 13 / API 33. These results precede the English-language update and do not establish device-test success for the updated English fixture.

Device tests temporarily enabled the accessibility service and restored the original settings afterward. The `enabled_accessibility_services` setting was checked and had returned to its original empty value.

Device test 1 locates the text field through the real accessibility tree, enters `Hello Jev`, clicks the save control, and independently checks the saved-value label. The baseline returned `VERIFIED` after two mutating actions. In the updated English fixture, the control is labeled `Save` and the expected result is `Saved: Hello Jev`.

Device test 2 modifies the text field after observation and confirms that an action based on the old snapshot is rejected. It also checks that a snapshot from a non-allowlisted app has an empty controls list.

Device tests use a **deterministic `DecisionProvider`** and execute real Android actions without calling Jev. Jev protocol unit tests use local JSON fixtures.

## Build environment

- Gradle 8.13, Android Gradle Plugin 8.12.3, and Kotlin 2.0.21.
- Android SDK Platform 36 and Android Studio JBR 21.0.6; Java bytecode target 17.
- Compilation worked in a Windows project directory containing Chinese characters, but Gradle test workers failed with `ClassNotFoundException`. Switching JDKs did not resolve the failure. The checks passed from a new temporary ASCII-only directory.
- `scripts/build-windows.ps1` was run successfully to copy sources, build, and copy artifacts and reports back to the project.

## Not yet verified

- The updated English fixture has not been run through instrumented tests on a device or emulator.
- No TypeSafe key was available. Live Jev API responses, account availability, Chinese-language accuracy, model latency, and billing have not been verified.
- Physical Xiaomi testing has not been performed. HyperOS permissions, background behavior, and third-party app task success rates have not been verified.
- Android version coverage is incomplete. API 26 is the declared minimum; previous device tests covered API 33 only.
- SDK artifacts have not been published to Maven Central or an app store. The source is MIT-licensed; the artifact and group coordinates currently exist only in the local Maven distribution.

## Local reports

- `core/build/reports/tests/test/index.html`
- `sdk/build/reports/tests/testDebugUnitTest/index.html`
- `sdk/build/reports/lint-results-debug.html`
- `sample/build/reports/lint-results-debug.html`
- `sample/build/reports/androidTests/connected/debug/index.html` (previous device-test baseline)

Some links inside copied HTML reports may refer to the temporary build directory. Machine-readable XML results are in each module's `build/test-results` directory and the sample's `build/outputs/androidTest-results` directory. Any retained device-test reports are from the previous baseline, not a rerun of the English-language update.
