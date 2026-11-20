#!/usr/bin/env bash
# 交叉编译 vnt 1.x 的 JNI 库（aarch64 / armv7 / x86_64）。
#
# 在 Linux 容器中执行：Windows 宿主缺少可用的链接器（MSVC 未安装，
# MinGW 7.3 过旧无法链接 Rust 1.100 产物），因此统一放到容器里编译。
#
# 用法：
#   bash native/build-android.sh           # 编译并安装到 app/src/main/jniLibs
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK="${NDK_HOME:-/opt/android-sdk/ndk/latest}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/target}"

build_abi() {
  local target="$1" abi="$2" clang="$3" cargo_env="$4" cc_env="$5" ar_env="$6"

  echo "==> 构建 $abi ($target)"
  export "CARGO_TARGET_${cargo_env}_LINKER=$TOOLCHAIN/$clang"
  # cc-rs 用下划线形式的环境变量名，必须同时设置 TARGET_CC 回退。
  export "CC_$cc_env=$TOOLCHAIN/$clang"
  export "AR_$ar_env=$TOOLCHAIN/llvm-ar"
  export "TARGET_CC=$TOOLCHAIN/$clang"
  export "TARGET_AR=$TOOLCHAIN/llvm-ar"

  cargo build -p vnt-jni --release --target "$target"

  local library="$CARGO_TARGET_DIR/$target/release/libvnt_jni.so"
  test -s "$library" || { echo "未生成 $library" >&2; exit 1; }
  mkdir -p "$ROOT/../app/src/main/jniLibs/$abi"
  cp -f "$library" "$ROOT/../app/src/main/jniLibs/$abi/libvnt_jni.so"
  echo "==> 已安装 app/src/main/jniLibs/$abi/libvnt_jni.so"
  ls -lh "$ROOT/../app/src/main/jniLibs/$abi/libvnt_jni.so"
}

cd "$ROOT"
build_abi aarch64-linux-android arm64-v8a aarch64-linux-android24-clang   AARCH64_LINUX_ANDROID aarch64_linux_android aarch64_linux_android
build_abi armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi24-clang   ARMV7_LINUX_ANDROIDEABI armv7_linux_androideabi armv7_linux_androideabi
build_abi x86_64-linux-android x86_64 x86_64-linux-android24-clang   X86_64_LINUX_ANDROID x86_64_linux_android x86_64_linux_android

echo "全部 ABI 构建完成"
