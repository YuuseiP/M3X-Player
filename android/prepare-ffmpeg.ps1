$ErrorActionPreference = 'Stop'
$tools = Join-Path $PSScriptRoot '.tools'
$aar = Join-Path $tools 'media3-decoder-ffmpeg.aar'
$expected = 'ECE5448B9E1C6263E5D7FB903350EDC7358A53BD82407A372EFDFABBCF12E0CA'
if (-not (Test-Path -LiteralPath $aar)) {
    New-Item -ItemType Directory -Force $tools | Out-Null
    $commit = '340a6eead602c25d7dfdddc3d72a48a24fc9cb47'
    Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/jonzey231/AerioTV-Android/$commit/app/libs/media3-decoder-ffmpeg.aar" -OutFile $aar
}
if ((Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash -ne $expected) {
    throw 'FFmpeg decoder archive checksum mismatch'
}
