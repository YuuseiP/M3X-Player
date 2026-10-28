param([switch]$Test)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not (Test-Path '.\.tools\gradle-8.9\bin\gradle.bat')) {
        python '.\setup-tools.py'
        if ($LASTEXITCODE -ne 0) { throw 'Gradle 工具准备失败' }
    }
    if (-not (Test-Path '.\local-signing.properties')) {
        if (Test-Path '.\.signing\m3x-local.jks') { throw '签名配置丢失；请先恢复 local-signing.properties，不要重新生成密钥。' }
        New-Item -ItemType Directory -Force '.\.signing' | Out-Null
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes); $rng.Dispose()
        $password = [Convert]::ToBase64String($bytes)
        $env:M3X_SIGNING_PASSWORD = $password
        try {
            & keytool -genkeypair -keystore '.\.signing\m3x-local.jks' -alias m3x -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Cheng Local, OU=Personal, O=M3X Player, C=CN' -storepass:env M3X_SIGNING_PASSWORD -keypass:env M3X_SIGNING_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw '生成本地安装签名失败' }
        } finally { Remove-Item Env:\M3X_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
        $properties = "storeFile=.signing/m3x-local.jks`nstorePassword=$password`nkeyAlias=m3x`nkeyPassword=$password`n"
        [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local-signing.properties'), $properties, [Text.Encoding]::ASCII)
    }
    if (-not (Test-Path '.\node_modules')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw '安装界面构建依赖失败' }
    }
    & npm.cmd run assets
    if ($LASTEXITCODE -ne 0) { throw '构建 Android 7.1 界面失败' }
    & '.\prepare-flac.ps1'
    $tasks = @(':app:assembleRelease')
    if ($Test) { $tasks += ':app:testDebugUnitTest' }
    & '.\.tools\gradle-8.9\bin\gradle.bat' @tasks '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败' }
    $dist = Join-Path (Split-Path $PSScriptRoot -Parent) 'dist'
    New-Item -ItemType Directory -Force $dist | Out-Null
    $version = [regex]::Match((Get-Content -Raw -Encoding UTF8 '.\app\build.gradle'), "versionName '([^']+)'").Groups[1].Value
    if (-not $version) { throw '无法读取 APK 版本号' }
    $name = "Cheng-M3X-$version.apk"
    $apk = Join-Path $dist $name
    Copy-Item -LiteralPath '.\app\build\outputs\apk\release\app-release.apk' -Destination $apk -Force
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$apk.sha256", "$hash  $name`n", [Text.Encoding]::ASCII)
    Write-Output "APK: $apk"
    Write-Output "SHA256: $hash"
} finally { Pop-Location }
