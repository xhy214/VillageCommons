try {
    . "$PSScriptRoot\common.ps1"
    [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
    Write-Host '麦风村 · AI 共享麦田 Demo' -ForegroundColor Yellow
    Write-Host 'Minecraft Java 1.21.11 | 本机服务器 | 手动回合 | 云端 API'
    $server = Join-Path $script:VillageRoot 'server'
    $pluginDirectory = Join-Path $server 'plugins\VillageCommons'
    $pluginJar = Join-Path $script:VillageRoot 'dist\VillageCommons.jar'
    if (-not (Test-Path -LiteralPath $pluginJar)) { throw '缺少 dist\VillageCommons.jar，请先运行 build.bat。' }
    $probe = New-Object Net.Sockets.TcpClient
    try {
        $probe.Connect('127.0.0.1',25565)
        throw '本机 25565 端口已在使用。请关闭已有 Minecraft 服务器，避免重复启动。'
    } catch [Net.Sockets.SocketException] { }
    finally { $probe.Dispose() }

    New-Item -ItemType Directory -Path $pluginDirectory -Force | Out-Null
    $localFile = Join-Path $pluginDirectory 'api.local.json'
    $old = $null
    if (Test-Path -LiteralPath $localFile) {
        try { $old = Get-Content -LiteralPath $localFile -Raw -Encoding UTF8 | ConvertFrom-Json } catch { Write-Host '旧 API 配置无法读取，请重新填写。' }
    }
    Write-Host ''
    Write-Host '填写完整的 Chat Completions 接口地址；留空可查看村庄，回合将暂停等待API配置。'
    $previousEndpoint = if ($old) { [string]$old.endpoint } else { '' }
    $endpoint = Read-Value 'Endpoint' $previousEndpoint
    if ($endpoint) {
        $uri = New-Object Uri($endpoint)
        if (-not $uri.IsAbsoluteUri -or $uri.UserInfo -or $uri.Fragment -or ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.IsLoopback))) { throw 'Endpoint 必须是完整 HTTPS URL；本机测试地址可使用 HTTP。' }
        $sameEndpoint = $old -and $old.endpoint -eq $endpoint
        $previousKey = if ($sameEndpoint) { [string]$old.apiKey } else { '' }
        $previousModel = if ($sameEndpoint) { [string]$old.model } else { '' }
        $key = Read-ApiKey $previousKey
        $model = Read-Value 'Model（填写服务商提供的准确模型 ID）' $previousModel
        if (-not $key -or -not $model -or $key -match '[\r\n]') { throw 'API Key 与 Model 不能为空。' }
        $extra = if ($sameEndpoint -and $old.model -eq $model -and $old.extra) { $old.extra } else { @{} }
        $configuration = [ordered]@{ endpoint=$endpoint; apiKey=$key; model=$model; timeoutSeconds=45; maxTokens=1200; maxRequests=30; extra=$extra }
        if ($sameEndpoint -and $old.model -eq $model) {
            foreach ($name in @('timeoutSeconds','maxTokens','maxRequests')) { if ($old.PSObject.Properties.Name -contains $name) { $configuration[$name] = $old.$name } }
        }
        Write-Utf8 "$localFile.tmp" ($configuration | ConvertTo-Json -Depth 20)
        Move-Item -LiteralPath "$localFile.tmp" -Destination $localFile -Force
        Write-Host '配置已保存；进入游戏后点击“下一回合”才会产生模型请求。' -ForegroundColor Green
    } else { Write-Host '未填写 API；可以查看村庄，推进回合时会暂停。之后重启 start.bat 即可配置。' -ForegroundColor Cyan }

    $eula = Join-Path $server 'eula.txt'
    $accepted = (Test-Path -LiteralPath $eula) -and ((Get-Content -LiteralPath $eula -Raw) -match '(?m)^\s*eula\s*=\s*true\s*$')
    if (-not $accepted) {
        Write-Host '运行 Minecraft 服务器需要你同意 Minecraft EULA：'
        Write-Host 'https://www.minecraft.net/eula'
        if ((Read-Host '阅读并同意后输入 YES；其他输入取消启动') -ine 'YES') { Write-Host '已取消启动。'; exit 0 }
        Write-Utf8 $eula "eula=true`r`n"
    }
    $java = Get-VillageJava
    $paperInfo = Get-Content -LiteralPath "$PSScriptRoot\paper-download.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Host "检查 Paper $($paperInfo.minecraftVersion) build $($paperInfo.build)…"
    Download-Verified $paperInfo.url (Join-Path $server 'paper.jar') $paperInfo.sha256
    Copy-Item -LiteralPath $pluginJar -Destination (Join-Path $server 'plugins\VillageCommons.jar') -Force
    $properties = Join-Path $server 'server.properties'
    if (-not (Test-Path -LiteralPath $properties)) { Copy-Item -LiteralPath "$PSScriptRoot\server.properties" -Destination $properties }
    $bukkitFile = Join-Path $server 'bukkit.yml'
    if (-not (Test-Path -LiteralPath $bukkitFile)) { Write-Utf8 $bukkitFile "settings:`n  allow-end: false`n" }
    Disable-VillageTelemetry $server
    Write-Host ''
    Write-Host '等待命令行出现 Done 后，用 Minecraft Java 1.21.11 连接 127.0.0.1:25565。' -ForegroundColor Green
    Write-Host '游戏内输入 /village。关闭服务请在此窗口输入 stop 并回车。'
    Write-Host 'Key 保存在 server\plugins\VillageCommons\api.local.json；分享时请勿包含 server 目录。'
    Push-Location $server
    try {
        & $java '-Xms512M' '-Xmx2G' '-XX:ActiveProcessorCount=4' '-jar' 'paper.jar' '--nogui'
        if ($LASTEXITCODE -ne 0) { throw "服务器退出码为 $LASTEXITCODE，请查看 server\logs\latest.log。" }
    } finally { Pop-Location }
} catch {
    Write-Host "启动未完成：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
