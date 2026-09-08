<#
.SYNOPSIS
    project-initializer 启动脚本（Windows / PowerShell）
    与 start.sh 等价：官网下载工具链 -> 构建前后端一体化 jar -> 非 demo 模式启动。

.DESCRIPTION
    1. 优先使用系统已装且达标的 JDK(>=17) / Maven / Node(>=20)；
       缺失或版本不足时从官网下载到项目内 .tools\（Adoptium / Apache / Node.js 官方 dist），不改动系统环境。
    2. 配置 Maven 阿里云镜像（$HOME\.m2\settings.xml，已存在则不覆盖）
    3. 构建一体化 jar（npm install + vite build + 拷贝 dist 进 static）
    4. 以【非 demo 模式】启动：不传 --demo，清单为空，数据等待管理端发布

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File start.ps1
    powershell -ExecutionPolicy Bypass -File start.ps1 -Rebuild
    powershell -ExecutionPolicy Bypass -File start.ps1 -SkipBuild
    powershell -ExecutionPolicy Bypass -File start.ps1 -Action stop
    powershell -ExecutionPolicy Bypass -File start.ps1 -Action status
#>
param(
    [ValidateSet('start','stop','restart','status','logs','help')]
    [string]$Action = 'start',
    [switch]$Rebuild,
    [switch]$SkipBuild,
    [switch]$Demo
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolsDir    = Join-Path $ProjectRoot '.tools'
$JarPath     = Join-Path $ProjectRoot 'initializer-app\target\initializer-app-0.1.0-SNAPSHOT.jar'
$LogDir      = Join-Path $ProjectRoot 'logs'
$LogOut      = Join-Path $LogDir 'initializer.log'
$LogErr      = Join-Path $LogDir 'initializer.err.log'
$PidFile     = Join-Path $LogDir 'initializer.pid'

$Port        = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8080' }
$AdminToken  = if ($env:INITIALIZER_ADMIN_TOKEN) { $env:INITIALIZER_ADMIN_TOKEN } else { 'admin-dev-token' }
$JavaOpts    = if ($env:JAVA_OPTS) { $env:JAVA_OPTS } else { '-Xmx512m' }
$MirrorUrl   = if ($env:MAVEN_MIRROR_URL) { $env:MAVEN_MIRROR_URL } else { 'https://maven.aliyun.com/repository/public' }
$NpmRegistry = if ($env:NPM_REGISTRY) { $env:NPM_REGISTRY } else { 'https://registry.npmmirror.com' }

$JdkVersion   = if ($env:JDK_VERSION)   { $env:JDK_VERSION }   else { '17' }
$MavenVersion = if ($env:MAVEN_VERSION) { $env:MAVEN_VERSION } else { '3.9.16' }
$NodeVersion  = if ($env:NODE_VERSION)  { $env:NODE_VERSION }  else { '22.14.0' }
$ForceTooling = if ($env:FORCE_TOOLING) { $env:FORCE_TOOLING } else { '0' }

$MinJavaMajor = 17
$MinNodeMajor = 20
$HealthPath   = '/api/metadata/java-versions'
$ReadyTimeout = 120

function Log($msg) { Write-Host "[start.ps1] $msg" }
function Die($msg) { Write-Host "[start.ps1][错误] $msg" -ForegroundColor Red; exit 1 }

function Get-Arch {
    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($arch -eq 'ARM64') { return 'aarch64' }
    return 'x64'
}
$Arch = Get-Arch

function Download-File($url, $out) {
    Log "下载: $url"
    $dir = Split-Path -Parent $out
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    try {
        # 优先 curl.exe（Windows 10+ 自带，速度快且支持进度）
        & curl.exe -fsSL --retry 3 --retry-delay 2 -o "$out" "$url"
        if ($LASTEXITCODE -ne 0) { throw "curl 退出码 $LASTEXITCODE" }
    } catch {
        Log "curl 下载失败，改用 Invoke-WebRequest: $($_.Exception.Message)"
        try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
        Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
    }
}

# 解压 zip 并去掉顶层目录
function Expand-ZipStrip($zip, $dest) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    $tmp = "$dest.__tmp"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
    $inner = Get-ChildItem -Path $tmp | Select-Object -First 1
    if (-not $inner) { Die "压缩包为空: $zip" }
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Get-ChildItem -Path $inner.FullName -Force | ForEach-Object {
        Move-Item -Path $_.FullName -Destination $dest -Force
    }
    Remove-Item -Recurse -Force $tmp
}

function Get-JavaMajor($javaBin) {
    try {
        $out = & $javaBin -version 2>&1 | Select-Object -First 1
        if ($out -match 'version "([^"]+)"') {
            $v = $Matches[1].Split('.')
            if ($v[0] -eq '1') { return [int]$v[1] } else { return [int]$v[0] }
        }
    } catch { }
    return 0
}

function Ensure-Java {
    $toolsJava = Join-Path $ToolsDir 'jdk\bin\java.exe'
    if (Test-Path $toolsJava) {
        $script:JavaBin = $toolsJava
        $env:JAVA_HOME = Join-Path $ToolsDir 'jdk'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        Log "JDK 就绪 (.tools): $(Get-JavaMajor $toolsJava)"
        return
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($ForceTooling -ne '1' -and $cmd -and (Get-JavaMajor $cmd.Source) -ge $MinJavaMajor) {
        $script:JavaBin = $cmd.Source
        Log "JDK 就绪 (系统): $(Get-JavaMajor $cmd.Source)"
        return
    }
    Log "安装 JDK $JdkVersion（Eclipse Temurin 官方下载）-> $ToolsDir\jdk"
    $url = "https://api.adoptium.net/v3/binary/latest/$JdkVersion/ga/windows/$Arch/jdk/hotspot/normal/eclipse"
    $zip = Join-Path $ToolsDir 'jdk.zip'
    Download-File $url $zip
    Expand-ZipStrip $zip (Join-Path $ToolsDir 'jdk')
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    if (-not (Test-Path $toolsJava)) { Die "JDK 安装失败: $toolsJava 不存在" }
    $script:JavaBin = $toolsJava
    $env:JAVA_HOME = Join-Path $ToolsDir 'jdk'
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    Log "JDK 安装完成"
}

function Ensure-Maven {
    $toolsMvn = Join-Path $ToolsDir 'maven\bin\mvn.cmd'
    if (Test-Path $toolsMvn) {
        $script:MvnBin = $toolsMvn
        $env:PATH = "$ToolsDir\maven\bin;$env:PATH"
        Log "Maven 就绪 (.tools): $MavenVersion"
        return
    }
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($ForceTooling -ne '1' -and $cmd) {
        $script:MvnBin = 'mvn'
        Log "Maven 就绪 (系统)"
        return
    }
    Log "安装 Maven $MavenVersion（Apache 官方下载）-> $ToolsDir\maven"
    $base = "https://dlcdn.apache.org/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
    $fallback = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
    $zip = Join-Path $ToolsDir 'maven.zip'
    try { Download-File $base $zip } catch { Download-File $fallback $zip }
    Expand-ZipStrip $zip (Join-Path $ToolsDir 'maven')
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    if (-not (Test-Path $toolsMvn)) { Die "Maven 安装失败: $toolsMvn 不存在" }
    $script:MvnBin = $toolsMvn
    $env:PATH = "$ToolsDir\maven\bin;$env:PATH"
    Log "Maven 安装完成"
}

function Ensure-Node {
    $toolsNode = Join-Path $ToolsDir 'node\node.exe'
    if (Test-Path $toolsNode) {
        $env:PATH = "$ToolsDir\node;$env:PATH"
        Log "Node 就绪 (.tools): $(& $toolsNode -v)"
        return
    }
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if ($ForceTooling -ne '1' -and $cmd) {
        $major = [int]((& node -v).TrimStart('v').Split('.')[0])
        if ($major -ge $MinNodeMajor) { Log "Node 就绪 (系统): $(& node -v)"; return }
    }
    Log "安装 Node.js $NodeVersion（nodejs.org 官方下载）-> $ToolsDir\node"
    $url = "https://nodejs.org/dist/v$NodeVersion/node-v$NodeVersion-win-x64.zip"
    $zip = Join-Path $ToolsDir 'node.zip'
    Download-File $url $zip
    Expand-ZipStrip $zip (Join-Path $ToolsDir 'node')
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    $env:PATH = "$ToolsDir\node;$env:PATH"
    if (-not (Test-Path $toolsNode)) { Die "Node.js 安装失败" }
    Log "Node 安装完成: $(& $toolsNode -v)"
}

function Ensure-MavenMirror {
    $settings = Join-Path $HOME '.m2\settings.xml'
    if ((Test-Path $settings) -and (Select-String -Path $settings -SimpleMatch $MirrorUrl -Quiet)) {
        Log "Maven 镜像已配置: $MirrorUrl"; return
    }
    if (Test-Path $settings) { Log "已存在 $settings 且未包含阿里云镜像，保持原样"; return }
    Log "配置 Maven 镜像: $MirrorUrl -> $settings"
    New-Item -ItemType Directory -Path (Split-Path $settings) -Force | Out-Null
    $content = @"
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <mirrors>
    <mirror>
      <id>aliyunmaven</id>
      <name>Aliyun Maven Public Mirror</name>
      <url>$MirrorUrl</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
"@
    Set-Content -Path $settings -Value $content -Encoding UTF8
}

function Build-Jar {
    Log "构建前后端一体化包（Maven + 前端 npm install/vite build）..."
    Push-Location $ProjectRoot
    try {
        $env:npm_config_registry = $NpmRegistry
        & $script:MvnBin -B clean package -DskipTests -ntp
        if ($LASTEXITCODE -ne 0) { Die "Maven 构建失败（退出码 $LASTEXITCODE）" }
    } finally { Pop-Location }
    if (-not (Test-Path $JarPath)) { Die "构建未产出 $JarPath" }
    Log "构建完成: $JarPath"
}

function Ensure-Jar {
    if ($Rebuild) { Build-Jar; return }
    if ($SkipBuild) {
        if (-not (Test-Path $JarPath)) { Die "-SkipBuild 但产物不存在: $JarPath" }
        return
    }
    if (Test-Path $JarPath) { Log "复用已有产物（需重建加 -Rebuild）: $JarPath" } else { Build-Jar }
}

function Test-Health {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port$HealthPath" -UseBasicParsing -TimeoutSec 5
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

function Get-RunningPid {
    if (Test-Path $PidFile) {
        $pidTxt = (Get-Content $PidFile -Raw).Trim()
        if ($pidTxt -match '^\d+$') {
            $p = Get-Process -Id ([int]$pidTxt) -ErrorAction SilentlyContinue
            if ($p) { return [int]$pidTxt }
        }
    }
    # 兜底：按命令行匹配（PowerShell 7 可用；5.1 下忽略）
    try {
        $proc = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
            Where-Object { $_.CommandLine -like '*initializer-app*' } | Select-Object -First 1
        if ($proc) { return $proc.ProcessId }
    } catch { }
    return $null
}

function Invoke-Start {
    if (Test-Health) { Log "服务已在运行，端口 $Port（先执行 -Action stop）"; return }
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    $args = @()
    if ($JavaOpts) { $args += $JavaOpts.Split(' ') }
    $args += @('-jar', $JarPath, "--server.port=$Port")
    if ($Demo) { $args += '--demo' }
    Log "启动服务（非 demo 模式，清单为空，等待管理端发布配置）..."
    Log "  java: $script:JavaBin"
    Log "  端口: $Port   日志: $LogOut / $LogErr"
    $env:INITIALIZER_ADMIN_TOKEN = $AdminToken
    $proc = Start-Process -FilePath $script:JavaBin -ArgumentList $args `
        -WorkingDirectory $ProjectRoot -RedirectStandardOutput $LogOut -RedirectStandardError $LogErr `
        -PassThru -WindowStyle Hidden
    Set-Content -Path $PidFile -Value $proc.Id -Encoding ascii -NoNewline

    Log "等待服务就绪 ..."
    $waited = 0
    while ($waited -lt $ReadyTimeout) {
        if (Test-Health) {
            Log "服务已就绪: http://localhost:$Port"
            if ($Demo) { Log "模式: demo（已加载 classpath 内置清单）" } else { Log "模式: 非 demo（清单为空，数据由管理端发布提供）" }
            Show-Status; return
        }
        Start-Sleep -Seconds 2
        $waited += 2
    }
    Die "服务启动超时（${ReadyTimeout}s），请查看日志: $LogOut / $LogErr"
}

function Invoke-Stop {
    $pidTxt = Get-RunningPid
    if ($pidTxt) {
        Log "停止服务 (PID $pidTxt) ..."
        Stop-Process -Id $pidTxt -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
    if (Test-Path $PidFile) { Remove-Item -Force $PidFile -ErrorAction SilentlyContinue }
    try {
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
            Where-Object { $_.CommandLine -like '*initializer-app*' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    } catch { }
    Log "服务已停止"
}

function Show-Status {
    $pidTxt = Get-RunningPid
    if ($pidTxt) {
        Log "状态: 运行中  PID=$pidTxt  端口=$Port"
        Log "日志: $LogOut"
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$Port$HealthPath" -UseBasicParsing -TimeoutSec 5
            Log "健康检查: $($r.Content) (HTTP $($r.StatusCode))"
        } catch { Log "健康检查: 不可达" }
    } else {
        Log "状态: 未运行"
        Log "日志: $LogOut"
    }
}

switch ($Action) {
    'stop'    { Invoke-Stop }
    'status'  { Show-Status }
    'restart' { Invoke-Stop; $Rebuild = $false; $SkipBuild = $true; Ensure-Java; Invoke-Start }
    'logs'    { if (Test-Path $LogOut) { Get-Content -Path $LogOut -Tail 200 -Wait } else { Die "日志文件不存在: $LogOut" } }
    'help'    { Get-Help $MyInvocation.MyCommand.Path -Detailed }
    'start'   {
        Ensure-Java
        Ensure-Maven
        Ensure-Node
        Ensure-MavenMirror
        Ensure-Jar
        Invoke-Start
    }
}
