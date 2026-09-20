param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string[]]$Tasks = @(':core:test', ':sdk:testDebugUnitTest', ':sdk:assembleRelease', ':sample:assembleDebug', ':sdk:lintDebug', ':sample:lintDebug', ':core:publishCorePublicationToLocalBuildRepository', ':sdk:publishReleasePublicationToLocalBuildRepository')
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
# An ASCII build path avoids Gradle test-worker classpath failures under some Windows locales.
$hash = [System.Security.Cryptography.SHA256]::Create()
try { $id = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectRoot))).Replace('-', '').Substring(0, 12) }
finally { $hash.Dispose() }
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$stage = Join-Path ([IO.Path]::GetTempPath()) "jev-android-$id-$suffix"
if ($stage -match '[^\x00-\x7F]') { throw 'TEMP must be an ASCII path. Set TEMP to a writable ASCII directory for this build.' }
Write-Host "Build stage: $stage"
& robocopy $projectRoot $stage /E /XD build .gradle .kotlin .git /NFL /NDL /NJH /NJS
if ($LASTEXITCODE -ge 8) { throw "Source copy failed: $LASTEXITCODE" }
& (Join-Path $stage 'gradlew.bat') -p $stage @Tasks
if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
foreach ($relative in @('core\build', 'sdk\build', 'sample\build', 'build\repository')) {
    $source = Join-Path $stage $relative
    if (Test-Path -LiteralPath $source) {
        & robocopy $source (Join-Path $projectRoot $relative) /E /NFL /NDL /NJH /NJS
        if ($LASTEXITCODE -ge 8) { throw "Artifact copy failed: $LASTEXITCODE" }
    }
}
Write-Host 'Build and validation complete. Artifacts and reports copied to project build directories.'
