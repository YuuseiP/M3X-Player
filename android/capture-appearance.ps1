param([string]$Serial, [switch]$SkipBuild, [ValidateSet("all", "endfield")][string]$Scope = "all")
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    function Invoke-Adb {
        param([string[]]$Arguments)
        $result = & adb -s $Serial @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
        return $result
    }
    if (-not $Serial) {
        $devices = @(& adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        if ($devices.Count -ne 1) { throw '请连接一台真机，或使用 -Serial 指定设备。' }
        $Serial = $devices[0]
    }
    $emulator = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.kernel.qemu')) -join ''
    if ($emulator.Trim() -eq '1') { throw '外观验收必须连接物理设备。' }
    $packageInfo = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', 'cn.yuusei.m3x')
    $installed = [regex]::Match(($packageInfo -join "`n"), 'versionName=([^\s]+)').Groups[1].Value
    $sourceVersion = [regex]::Match((Get-Content -Raw -Encoding UTF8 '.\app\build.gradle'), "versionName '([^']+)'").Groups[1].Value
    if (-not $installed -or $installed -ne $sourceVersion) { throw "设备版本 $installed 与测试源码版本 $sourceVersion 不符；请先使用原发布签名覆盖安装对应版本。" }
    if (-not $SkipBuild) {
        & npm.cmd run assets
        if ($LASTEXITCODE -ne 0) { throw '界面资源构建失败。' }
        & '.\.tools\gradle-8.9\bin\gradle.bat' -I '.tools\release-test.init.gradle' :app:assembleReleaseAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw '发布签名截图工具构建失败。' }
    }
    $run = 'appearance-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    $output = Join-Path $PSScriptRoot "captures\$run"
    New-Item -ItemType Directory -Path $output | Out-Null
    $model = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model')) -join ''
    $metadata = [ordered]@{
        serial = $Serial; model = $model.Trim(); installedVersion = $installed
        sourceCommit = (& git rev-parse HEAD); sourceChanges = @(& git status --short)
        capturedWith = 'installed release APK; matching release instrumentation; physical display'
        startedAt = (Get-Date -Format o); scope = $Scope
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'host.json') -Encoding UTF8
    Invoke-Adb -Arguments @('install', '-r', '.\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk') | Write-Output
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', '82') | Out-Null
    $remote = "/sdcard/Android/data/cn.yuusei.m3x/files/appearance/$run"
    try {
        $result = Invoke-Adb -Arguments @('shell', 'am', 'instrument', '-w', '-e', 'class', 'cn.yuusei.m3x.UiAppearanceCaptureTest', '-e', 'captureRun', $run, '-e', 'captureScope', $Scope, 'cn.yuusei.m3x.test/android.test.InstrumentationTestRunner')
        $result | Set-Content -LiteralPath (Join-Path $output 'instrumentation.txt') -Encoding UTF8
        $result | Write-Output
    } finally {
        # Pull partial evidence even when a page fails. Never pipe binary screenshots through PowerShell.
        Invoke-Adb -Arguments @('pull', "$remote/.", $output) | Write-Output
        if (Test-Path -LiteralPath (Join-Path $output 'manifest.json')) {
            & python '.\appearance-index.py' $output
            if ($LASTEXITCODE -ne 0) { throw '截图索引校验失败。' }
        }
    }
    if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw "截图未全部完成，已有证据保存在 $output" }
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', 'cn.yuusei.m3x/.MainActivity') | Out-Null
    Write-Output "真机截图索引：$output\INDEX.md"
} finally { Pop-Location }
