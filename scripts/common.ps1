$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$script:VillageRoot = Split-Path $PSScriptRoot -Parent
$script:VillageAgent = 'VillageCommons/0.1.0 (https://github.com/xhy214)'

function Write-Utf8([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding($false)))
}

function Download-Verified([string]$Url, [string]$Destination, [string]$Hash, [string]$Algorithm = 'SHA256') {
    if (Test-Path -LiteralPath $Destination) {
        if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ieq $Hash) { return }
    }
    $part = "$Destination.download"
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -UserAgent $script:VillageAgent -TimeoutSec 300 -OutFile $part
        if ((Get-FileHash -LiteralPath $part -Algorithm $Algorithm).Hash -ine $Hash) { throw '下载文件校验失败，请重试。' }
        Move-Item -LiteralPath $part -Destination $Destination -Force
    } finally {
        if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force }
    }
}

function Java-Major([string]$Executable) {
    try {
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = $Executable
        $info.Arguments = '-version'
        $info.UseShellExecute = $false
        $info.RedirectStandardError = $true
        $info.RedirectStandardOutput = $true
        $p = [Diagnostics.Process]::Start($info)
        $text = $p.StandardError.ReadToEnd() + $p.StandardOutput.ReadToEnd()
        $p.WaitForExit()
        $p.Dispose()
        if ($text -match 'version\s+"(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

function Get-VillageJava([switch]$RequireJdk) {
    $candidates = New-Object 'Collections.Generic.List[string]'
    if ($env:VILLAGE_JAVA) { $candidates.Add($env:VILLAGE_JAVA) }
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates.Add($cmd.Source) }
    $runtime = Join-Path $script:VillageRoot 'runtime'
    if (Test-Path -LiteralPath $runtime) {
        Get-ChildItem -LiteralPath $runtime -Directory -Filter 'jdk*' | ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\java.exe')) }
    }
    foreach ($candidate in $candidates) {
        $compiler = Join-Path (Split-Path $candidate -Parent) 'javac.exe'
        if ((Test-Path -LiteralPath $candidate) -and (Java-Major $candidate) -ge 21 -and ((-not $RequireJdk) -or (Test-Path -LiteralPath $compiler))) { return $candidate }
    }
    Write-Host '没有找到 Java 21+，正在下载便携 Temurin JDK 21（只放在项目 runtime 目录）…'
    if (-not [Environment]::Is64BitOperatingSystem) { throw '本项目启动脚本需要 64 位 Windows。' }
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    $assets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows' -UserAgent $script:VillageAgent -TimeoutSec 60
    $package = $assets[0].binary.package
    if (-not $package.link -or -not $package.checksum) { throw 'Java 下载信息无法读取，请手动安装 JDK 21 后重试。' }
    $archive = Join-Path $runtime 'temurin21.zip'
    Download-Verified $package.link $archive $package.checksum
    Expand-Archive -LiteralPath $archive -DestinationPath $runtime -Force
    $java = Get-ChildItem -LiteralPath $runtime -Directory -Filter 'jdk*' | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } | Where-Object { (Test-Path -LiteralPath $_) -and (Java-Major $_) -ge 21 } | Select-Object -First 1
    if (-not $java) { throw 'Java 解压后仍无法运行，请手动安装 JDK 21。' }
    Remove-Item -LiteralPath $archive -Force
    return $java
}

function Read-Value([string]$Prompt, [string]$Previous = '') {
    if ($Previous) { $answer = Read-Host "$Prompt（回车沿用 $Previous）" } else { $answer = Read-Host $Prompt }
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Previous }
    return $answer.Trim()
}

function Read-ApiKey([string]$Previous = '') {
    $label = if ($Previous) { 'API Key（不回显，回车沿用已有 Key）' } else { 'API Key（不回显）' }
    $secure = Read-Host $label -AsSecureString
    if ($secure.Length -eq 0) { return $Previous }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Disable-VillageTelemetry([string]$ServerDirectory) {
    $stats = Join-Path $ServerDirectory 'plugins\bStats'
    New-Item -ItemType Directory -Path $stats -Force | Out-Null
    Write-Utf8 (Join-Path $stats 'config.yml') "enabled: false`nlogFailedRequests: false`nlogSentData: false`nlogResponseStatusText: false`n"
    $config = Join-Path $ServerDirectory 'config'
    New-Item -ItemType Directory -Path $config -Force | Out-Null
    $paper = Join-Path $config 'paper-global.yml'
    if (-not (Test-Path -LiteralPath $paper)) {
        Write-Utf8 $paper "_version: 31`nspark:`n  enable-immediately: false`n  enabled: false`nupdate-checker:`n  enabled: false`n"
    } else {
        $section = ''
        $found = @{}
        $lines = @(foreach ($line in (Get-Content -LiteralPath $paper -Encoding UTF8)) {
            if ($line -match '^([a-z-]+):\s*$') { $section = $Matches[1]; $found[$section] = $true }
            elseif ($line -match '^\S') { $section = '' }
            if ($section -in @('spark','update-checker') -and $line -match '^  enabled:') { '  enabled: false' }
            elseif ($section -eq 'spark' -and $line -match '^  enable-immediately:') { '  enable-immediately: false' }
            else { $line }
        })
        if (-not $found.ContainsKey('spark')) { $lines += @('spark:', '  enable-immediately: false', '  enabled: false') }
        if (-not $found.ContainsKey('update-checker')) { $lines += @('update-checker:', '  enabled: false') }
        Write-Utf8 $paper (($lines -join "`n") + "`n")
    }
}
