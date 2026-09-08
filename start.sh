#!/usr/bin/env bash
#
# project-initializer 启动脚本（Linux / macOS / Git Bash）
# Windows 原生 CMD / PowerShell 请使用同目录 start.ps1
#
# 职责：环境准备（JDK / Maven / Node，官网下载到项目内 .tools/，不改动系统）→
#       构建前后端一体化 jar → 非 demo 模式后台启动。
#
# 默认行为：
#   1. 优先使用系统已装且达标的 JDK(>=17) / Maven / Node(>=20)；
#      缺失或版本不足时从官网下载到 .tools/（Adoptium / Apache / Node.js 官方 dist）
#   2. 配置 Maven 阿里云镜像（~/.m2/settings.xml，已存在则不覆盖）
#   3. 构建一体化 jar（npm install + vite build + 拷贝 dist 进 static）
#   4. 以【非 demo 模式】启动：不传 --demo，清单为空，数据等待管理端发布
#
# 用法：
#   ./start.sh                 # 默认：准备环境 → 按需构建 → 非 demo 启动
#   ./start.sh --rebuild       # 强制重新构建前端 + 后端后启动
#   ./start.sh --skip-build    # 不构建，直接用已有 jar 启动
#   ./start.sh --demo          # demo 模式启动（加载 classpath 内置清单，仅本地试用）
#   ./start.sh stop|restart|status|logs|help
#
# 环境变量：
#   SERVER_PORT=8080                服务端口
#   INITIALIZER_ADMIN_TOKEN=xxx     管理端 token（生产必须覆盖，默认 admin-dev-token）
#   INITIALIZER_CATALOG_DIR=xxx     外部清单目录（默认 <项目根>/catalog，见下方「清单目录」）
#   JAVA_OPTS="-Xmx512m"            附加 JVM 参数
#   MAVEN_MIRROR_URL=...            Maven 镜像（默认阿里云）
#   NPM_REGISTRY=...                npm registry（默认 npmmirror）
#   JDK_VERSION=17                  下载用 JDK 主版本
#   MAVEN_VERSION=3.9.9             下载用 Maven 版本
#   NODE_VERSION=22.14.0            下载用 Node 版本
#   FORCE_TOOLING=1                 强制使用/下载 .tools 内工具链（忽略系统已装）
#
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$PROJECT_ROOT/.tools"
JAR_DIR="$PROJECT_ROOT/initializer-app/target"
JAR_NAME="initializer-app-0.1.0-SNAPSHOT.jar"
JAR_PATH="$JAR_DIR/$JAR_NAME"

LOG_DIR="$PROJECT_ROOT/logs"
LOG_FILE="$LOG_DIR/initializer.log"
PID_FILE="$LOG_DIR/initializer.pid"

PORT="${SERVER_PORT:-8080}"
ADMIN_TOKEN="${INITIALIZER_ADMIN_TOKEN:-admin-dev-token}"
# 清单目录（jar 外配置）：metadata.yml / starters/*.yml / conflict-rules.yml / templates/** / validation.yml
CATALOG_DIR="${INITIALIZER_CATALOG_DIR:-$PROJECT_ROOT/catalog}"
JAVA_OPTS="${JAVA_OPTS:--Xmx512m}"
MAVEN_MIRROR_URL="${MAVEN_MIRROR_URL:-https://maven.aliyun.com/repository/public}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"

JDK_VERSION="${JDK_VERSION:-17}"
MAVEN_VERSION="${MAVEN_VERSION:-3.9.16}"
NODE_VERSION="${NODE_VERSION:-22.14.0}"
FORCE_TOOLING="${FORCE_TOOLING:-0}"

MIN_JAVA_MAJOR=17
MIN_NODE_MAJOR=20
HEALTH_PATH="/api/metadata/java-versions"
READY_TIMEOUT=120

DO_BUILD="auto"
DEMO_MODE="false"
ACTION="start"
for arg in "$@"; do
  case "$arg" in
    --rebuild)    DO_BUILD="force" ;;
    --skip-build) DO_BUILD="skip" ;;
    --demo)       DEMO_MODE="true" ;;
    start|stop|restart|status|logs|help) ACTION="$arg" ;;
    -h|--help)    ACTION="help" ;;
    *) echo "未知参数: $arg（用 ./start.sh help 查看用法）" >&2; exit 2 ;;
  esac
done

log() { echo "[start.sh] $*"; }
die() { echo "[start.sh][错误] $*" >&2; exit 1; }

# ---------- 平台识别 ----------
detect_platform() {
  local raw_os raw_arch
  raw_os="$(uname -s)"
  raw_arch="$(uname -m)"
  case "$raw_os" in
    Linux*)                            PLATFORM="linux" ;;
    Darwin*)                           PLATFORM="mac" ;;
    MINGW*|MSYS*|CYGWIN*|Windows_NT*)  PLATFORM="windows" ;;
    *) die "不支持的操作系统: $raw_os（Windows 原生请用 start.ps1）" ;;
  esac
  case "$raw_arch" in
    x86_64|amd64|AMD64) PLATFORM_ARCH="x64" ;;
    aarch64|arm64|ARM64) PLATFORM_ARCH="aarch64" ;;
    *) die "不支持的 CPU 架构: $raw_arch" ;;
  esac
}
detect_platform

# ---------- 下载 / 解压 ----------
download() {
  local url="$1" out="$2"
  log "下载: $url"
  mkdir -p "$(dirname "$out")"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 2 -o "$out" "$url" || die "下载失败: $url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$out" "$url" || die "下载失败: $url"
  else
    die "缺少 curl / wget，无法下载 $url"
  fi
}

# 解压并去掉顶层目录，内容直接落到 dest
extract_strip() {
  local file="$1" dest="$2"
  mkdir -p "$dest"
  case "$file" in
    *.zip)
      command -v unzip >/dev/null 2>&1 || die "缺少 unzip，无法解压 $file（Windows 可用 start.ps1）"
      local tmp="$dest.__tmp"
      rm -rf "$tmp"; mkdir -p "$tmp"
      unzip -q -o "$file" -d "$tmp"
      local inner; inner="$(ls -1 "$tmp" | head -1)"
      [ -n "$inner" ] || die "压缩包为空: $file"
      ( shopt -s dotglob 2>/dev/null || true; mv "$tmp/$inner"/* "$dest"/ 2>/dev/null || true )
      rm -rf "$tmp"
      ;;
    *.tar.gz|*.tgz) tar -xzf "$file" -C "$dest" --strip-components=1 ;;
    *) die "不支持的压缩包格式: $file" ;;
  esac
}

# ---------- 工具链安装（官网下载到 .tools/） ----------
tooling_urls() {
  JDK_URL="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/${PLATFORM}/${PLATFORM_ARCH}/jdk/hotspot/normal/eclipse"
  case "$PLATFORM" in
    windows)
      JDK_FILE="jdk.zip"
      MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.zip"
      MAVEN_URL_FALLBACK="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.zip"
      MAVEN_FILE="maven.zip"
      NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-win-x64.zip"
      ;;
    mac)
      JDK_FILE="jdk.tar.gz"
      MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      MAVEN_URL_FALLBACK="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      MAVEN_FILE="maven.tar.gz"
      if [ "$PLATFORM_ARCH" = "aarch64" ]; then
        NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-darwin-arm64.tar.gz"
      else
        NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-darwin-x64.tar.gz"
      fi
      ;;
    linux)
      JDK_FILE="jdk.tar.gz"
      MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      MAVEN_URL_FALLBACK="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      MAVEN_FILE="maven.tar.gz"
      if [ "$PLATFORM_ARCH" = "aarch64" ]; then
        NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.gz"
      else
        NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz"
      fi
      ;;
  esac
  NODE_FILE="node.tar.gz"
  [ "$PLATFORM" = "windows" ] && NODE_FILE="node.zip"
  true
}

java_major_of() {
  "$1" -version 2>&1 | head -1 \
    | sed -E 's/.*version "([^"]+)".*/\1/' \
    | awk -F. '{print ($1=="1" ? $2 : $1)}'
}

ensure_java() {
  # 1) .tools 自带优先
  if [ -x "$TOOLS_DIR/jdk/bin/java" ]; then
    export JAVA_HOME="$TOOLS_DIR/jdk"; export PATH="$JAVA_HOME/bin:$PATH"; JAVA_BIN="$JAVA_HOME/bin/java"
  elif [ -d "$TOOLS_DIR/jdk/Contents/Home/bin" ]; then
    export JAVA_HOME="$TOOLS_DIR/jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"; JAVA_BIN="$JAVA_HOME/bin/java"
  else
    JAVA_BIN="java"
  fi

  if [ "$FORCE_TOOLING" != "1" ] && command -v "$JAVA_BIN" >/dev/null 2>&1; then
    local v; v="$(java_major_of "$JAVA_BIN")"
    if [ -n "$v" ] && [ "$v" -ge "$MIN_JAVA_MAJOR" ] 2>/dev/null; then
      log "JDK 就绪 ($([ "$JAVA_BIN" = "java" ] && echo 系统 || echo .tools)): $("$JAVA_BIN" -version 2>&1 | head -1)"
      export PATH="$(dirname "$JAVA_BIN"):$PATH"
      return
    fi
  fi

  log "安装 JDK $JDK_VERSION（Eclipse Temurin 官方下载）-> $TOOLS_DIR/jdk"
  tooling_urls
  rm -rf "$TOOLS_DIR/jdk"
  download "$JDK_URL" "$TOOLS_DIR/$JDK_FILE"
  extract_strip "$TOOLS_DIR/$JDK_FILE" "$TOOLS_DIR/jdk"
  rm -f "$TOOLS_DIR/$JDK_FILE"
  if [ -d "$TOOLS_DIR/jdk/Contents/Home/bin" ]; then JAVA_HOME="$TOOLS_DIR/jdk/Contents/Home"; else JAVA_HOME="$TOOLS_DIR/jdk"; fi
  export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"; JAVA_BIN="$JAVA_HOME/bin/java"
  [ -x "$JAVA_BIN" ] || die "JDK 安装失败: $JAVA_BIN 不可执行"
  log "JDK 安装完成: $("$JAVA_BIN" -version 2>&1 | head -1)"
}

ensure_maven() {
  if [ -x "$TOOLS_DIR/maven/bin/mvn" ]; then
    export PATH="$TOOLS_DIR/maven/bin:$PATH"; MVN_BIN="$TOOLS_DIR/maven/bin/mvn"
    log "Maven 就绪 (.tools): $("$MVN_BIN" -v 2>/dev/null | head -1)"
    return
  fi
  if [ "$FORCE_TOOLING" != "1" ] && command -v mvn >/dev/null 2>&1; then
    MVN_BIN="mvn"
    log "Maven 就绪 (系统): $(mvn -v 2>/dev/null | head -1)"
    return
  fi
  log "安装 Maven $MAVEN_VERSION（Apache 官方下载）-> $TOOLS_DIR/maven"
  tooling_urls
  rm -rf "$TOOLS_DIR/maven"
  download "$MAVEN_URL" "$TOOLS_DIR/$MAVEN_FILE" 2>/dev/null \
    || download "$MAVEN_URL_FALLBACK" "$TOOLS_DIR/$MAVEN_FILE"
  extract_strip "$TOOLS_DIR/$MAVEN_FILE" "$TOOLS_DIR/maven"
  rm -f "$TOOLS_DIR/$MAVEN_FILE"
  export PATH="$TOOLS_DIR/maven/bin:$PATH"; MVN_BIN="$TOOLS_DIR/maven/bin/mvn"
  [ -x "$MVN_BIN" ] || die "Maven 安装失败: $MVN_BIN 不可执行"
  log "Maven 安装完成: $("$MVN_BIN" -v 2>/dev/null | head -1)"
}

ensure_node() {
  local ok="false"
  if [ -x "$TOOLS_DIR/node/bin/node" ]; then
    export PATH="$TOOLS_DIR/node/bin:$PATH"
    log "Node 就绪 (.tools): $(node -v)"
    return
  fi
  if [ "$FORCE_TOOLING" != "1" ] && command -v node >/dev/null 2>&1; then
    local v; v="$(node -v | sed 's/^v//' | cut -d. -f1)"
    if [ "$v" -ge "$MIN_NODE_MAJOR" ] 2>/dev/null; then
      log "Node 就绪 (系统): $(node -v) / npm $(npm -v 2>/dev/null || echo '-')"
      return
    fi
  fi
  log "安装 Node.js $NODE_VERSION（nodejs.org 官方下载）-> $TOOLS_DIR/node"
  tooling_urls
  rm -rf "$TOOLS_DIR/node"
  download "$NODE_URL" "$TOOLS_DIR/$NODE_FILE"
  extract_strip "$TOOLS_DIR/$NODE_FILE" "$TOOLS_DIR/node"
  rm -f "$TOOLS_DIR/$NODE_FILE"
  export PATH="$TOOLS_DIR/node/bin:$PATH"
  command -v node >/dev/null 2>&1 || die "Node.js 安装失败"
  log "Node 安装完成: $(node -v)"
}

# ---------- Maven 镜像 ----------
ensure_maven_mirror() {
  local settings="$HOME/.m2/settings.xml"
  if [ -f "$settings" ] && grep -q "$MAVEN_MIRROR_URL" "$settings"; then
    log "Maven 镜像已配置: $MAVEN_MIRROR_URL"
    return
  fi
  if [ -f "$settings" ]; then
    log "已存在 $settings 且未包含阿里云镜像，保持原样"
    return
  fi
  log "配置 Maven 镜像: $MAVEN_MIRROR_URL -> $settings"
  mkdir -p "$HOME/.m2"
  cat > "$settings" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <mirrors>
    <mirror>
      <id>aliyunmaven</id>
      <name>Aliyun Maven Public Mirror</name>
      <url>$MAVEN_MIRROR_URL</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF
}

# ---------- 构建 ----------
build_jar() {
  log "构建前后端一体化包（Maven + 前端 npm install/vite build）..."
  ( cd "$PROJECT_ROOT" && npm_config_registry="$NPM_REGISTRY" "$MVN_BIN" -B clean package -DskipTests -ntp )
  [ -f "$JAR_PATH" ] || die "构建未产出 $JAR_PATH"
  log "构建完成: $JAR_PATH"
}

ensure_jar() {
  case "$DO_BUILD" in
    force) build_jar ;;
    skip)  [ -f "$JAR_PATH" ] || die "--skip-build 但产物不存在: $JAR_PATH（先执行一次 ./start.sh）" ;;
    auto)  if [ -f "$JAR_PATH" ]; then log "复用已有产物（需重建加 --rebuild）: $JAR_PATH"; else build_jar; fi ;;
  esac
}

# ---------- 服务控制 ----------
is_running() {
  if [ -f "$PID_FILE" ]; then
    local pid; pid="$(cat "$PID_FILE")"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then return 0; fi
  fi
  pgrep -f "initializer-app-.*\.jar" >/dev/null 2>&1
}

do_stop() {
  if [ -f "$PID_FILE" ]; then
    local pid; pid="$(cat "$PID_FILE")"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      log "停止服务 (PID $pid) ..."
      kill "$pid"
      for _ in $(seq 1 15); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "$pid" 2>/dev/null; then kill -9 "$pid" || true; fi
    fi
    rm -f "$PID_FILE"
  fi
  pkill -f "initializer-app-.*\.jar" 2>/dev/null || true
  log "服务已停止"
}

do_start() {
  if is_running; then
    log "服务已在运行，端口 $PORT（先执行 ./start.sh stop 再启动）"
    do_status
    exit 0
  fi
  mkdir -p "$LOG_DIR" "$CATALOG_DIR"
  local demo_arg=""
  if [ "$DEMO_MODE" = "true" ]; then demo_arg="--demo"; fi
  log "启动服务（清单目录: $CATALOG_DIR）..."
  log "  java: ${JAVA_BIN:-java}"
  log "  jar : $JAR_PATH"
  log "  端口: $PORT   日志: $LOG_FILE"
  cd "$PROJECT_ROOT"
  INITIALIZER_ADMIN_TOKEN="$ADMIN_TOKEN" \
    nohup "${JAVA_BIN:-java}" $JAVA_OPTS -jar "$JAR_PATH" --server.port="$PORT" \
      --initializer.catalog.dir="$CATALOG_DIR" $demo_arg > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"

  log "等待服务就绪 ..."
  local waited=0
  while [ "$waited" -lt "$READY_TIMEOUT" ]; do
    if curl -sf -o /dev/null "http://localhost:$PORT$HEALTH_PATH"; then
      log "服务已就绪: http://localhost:$PORT"
      if [ "$DEMO_MODE" = "true" ]; then
        log "模式: demo（已加载 classpath 内置清单）"
      else
        log "模式: 非 demo（清单为空，数据由管理端发布提供）"
      fi
      do_status
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  die "服务启动超时（${READY_TIMEOUT}s），请查看日志: $LOG_FILE"
}

do_status() {
  if is_running; then
    local pid="-"
    if [ -f "$PID_FILE" ]; then pid="$(cat "$PID_FILE")"; fi
    echo "[start.sh] 状态: 运行中  PID=$pid  端口=$PORT"
    echo "[start.sh] 日志: $LOG_FILE"
    echo "[start.sh] 健康检查: $(curl -s -w ' (HTTP %{http_code})' "http://localhost:$PORT$HEALTH_PATH" || echo '不可达')"
  else
    echo "[start.sh] 状态: 未运行"
    echo "[start.sh] 日志: $LOG_FILE"
  fi
}

do_logs() {
  [ -f "$LOG_FILE" ] || die "日志文件不存在: $LOG_FILE"
  tail -n 200 -f "$LOG_FILE"
}

case "$ACTION" in
  stop)    do_stop ;;
  status)  do_status ;;
  logs)    do_logs ;;
  restart) do_stop; DO_BUILD="skip"; do_start ;;
  help)    sed -n '2,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  start)
    ensure_java
    ensure_maven
    ensure_node
    ensure_maven_mirror
    ensure_jar
    do_start
    ;;
esac
