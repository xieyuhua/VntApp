#!/usr/bin/env bash
# 在 mingc/android-build-box 容器中构建 APK。
#
# 用法：bash scripts/docker-build.sh [gradle 任务...]
#   默认任务：assembleDebug
#
# 说明：
#   - 容器内 SDK 固定为 /opt/android-sdk（镜像自带 android-36 与 build-tools 36）。
#   - 不使用 ANDROID_SDK_ROOT：该镜像里的取值会被 AGP 判定为无效目录并打印告警。
#   - local.properties 由容器临时写入，构建结束后删除，不会污染宿主机
#     （宿主机若已有该文件会先备份再恢复）。
set -euo pipefail

if [ "$#" -eq 0 ]; then
  set -- assembleDebug
fi

# Git Bash / MSYS 会把以 / 开头的参数改写成 Windows 路径，
# 导致容器内的 -v 目标路径被破坏。挂载源使用 Windows 盘符形式，
# 容器内路径保持原样。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# /e/workspace/... 形式的 MSYS 路径 Docker Desktop 无法识别，转成 E:/workspace/...
PROJECT_DIR="$(cd "$SCRIPT_DIR" && pwd -W 2>/dev/null || echo "$SCRIPT_DIR")"
GRADLE_CACHE="${GRADLE_CACHE:-$HOME/.gradle-cache}"
IMAGE="${IMAGE:-mingc/android-build-box}"

mkdir -p "$GRADLE_CACHE"

# 校验任务名，只允许 Gradle 任务标识符，防止拼接进容器命令时被注入。
for task in "$@"; do
  if [[ ! "$task" =~ ^[A-Za-z0-9:_-]+$ ]]; then
    echo "非法的 Gradle 任务名：$task" >&2
    exit 2
  fi
done

docker run --rm \
  -v "$PROJECT_DIR":/project \
  -v "$GRADLE_CACHE":/root/.gradle \
  -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  "$IMAGE" \
  bash -lc '
    set -euo pipefail
    cd /project
    if [ -f local.properties ]; then
      cp local.properties /tmp/local.properties.bak
    fi
    cleanup() {
      rm -f local.properties
      if [ -f /tmp/local.properties.bak ]; then
        mv /tmp/local.properties.bak local.properties
      fi
    }
    trap cleanup EXIT
    printf "sdk.dir=/opt/android-sdk\n" > local.properties
    ./gradlew "$@" --no-daemon
  ' docker-build "$@"

echo
echo "构建产物：$PROJECT_DIR/app/build/outputs/apk/debug/"
ls -lh "$PROJECT_DIR"/app/build/outputs/apk/debug/*.apk 2>/dev/null || true
