$ErrorActionPreference = 'Stop'
$tools = Join-Path $PSScriptRoot '.tools'
$source = Join-Path $tools 'flac-1.4.3'
if (-not (Test-Path -LiteralPath (Join-Path $source 'CMakeLists.txt'))) {
    New-Item -ItemType Directory -Force $tools | Out-Null
    $zip = Join-Path $tools 'flac-1.4.3.zip'
    Invoke-WebRequest -Uri 'https://github.com/xiph/flac/archive/refs/tags/1.4.3.zip' -OutFile $zip
    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
    if ($hash -ne 'D40333A10F1691105C4CE863675E303412CCAE8F23C3969952528310510262F8') {
        throw 'libFLAC archive checksum mismatch'
    }
    Expand-Archive -LiteralPath $zip -DestinationPath $tools -Force
}
