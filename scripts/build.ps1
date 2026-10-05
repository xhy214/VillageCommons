try {
    . "$PSScriptRoot\common.ps1"
    $java = Get-VillageJava -RequireJdk
    $jdk = Split-Path (Split-Path $java -Parent) -Parent
    $mavenVersion = '3.9.11'
    $runtime = Join-Path $script:VillageRoot 'runtime'
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    $maven = Join-Path $runtime "apache-maven-$mavenVersion\bin\mvn.cmd"
    if (-not (Test-Path -LiteralPath $maven)) {
        $zip = Join-Path $runtime 'maven.zip'
        Download-Verified "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip" $zip '03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076' 'SHA512'
        Expand-Archive -LiteralPath $zip -DestinationPath $runtime -Force
        Remove-Item -LiteralPath $zip -Force
    }
    $env:JAVA_HOME = $jdk
    Push-Location $script:VillageRoot
    try {
        & $maven '-B' '-Dmaven.repo.local=runtime/maven-repository' 'clean' 'verify'
        if ($LASTEXITCODE -ne 0) { throw '编译或测试失败，未更新 dist 插件。' }
        New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
        Copy-Item -LiteralPath 'target\VillageCommons.jar' -Destination 'dist\VillageCommons.jar' -Force
        Write-Host '构建完成：dist\VillageCommons.jar。服务器停止后重新运行 start.bat 使用新插件。' -ForegroundColor Green
    } finally { Pop-Location }
} catch {
    Write-Host "构建未完成：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
