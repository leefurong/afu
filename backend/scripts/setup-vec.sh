#!/usr/bin/env bash
# 下载并安装 sqlite-vec 扩展到 backend/extensions/
# 运行一次即可，后续测试/启动会自动使用

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
EXT_DIR="$BACKEND_DIR/extensions"
VERSION="0.1.6"

case "$(uname -m)" in
  arm64|aarch64) ARCH="aarch64" ;;
  x86_64|amd64)  ARCH="x86_64" ;;
  *) echo "Unsupported arch: $(uname -m)"; exit 1 ;;
esac

case "$(uname -s)" in
  Darwin)  OS="macos" ;;
  Linux)   OS="linux" ;;
  *) echo "Unsupported OS: $(uname -s)"; exit 1 ;;
esac

mkdir -p "$EXT_DIR"
URL="https://github.com/asg017/sqlite-vec/releases/download/v${VERSION}/sqlite-vec-${VERSION}-loadable-${OS}-${ARCH}.tar.gz"

if [[ -f "$EXT_DIR/vec0.dylib" || -f "$EXT_DIR/vec0.so" || -f "$EXT_DIR/vec0.dll" ]]; then
  echo "sqlite-vec 已存在，跳过下载"
else
  echo "下载 sqlite-vec v${VERSION} (${OS}-${ARCH})..."
  curl -sL "$URL" | tar xz -C "$EXT_DIR"
  echo "已安装到 $EXT_DIR"
fi

echo ""
echo "VEC_EXTENSION_PATH=$EXT_DIR/vec0"
echo "将上述路径加入 .env 或运行测试时："
echo "  VEC_EXTENSION_PATH=$EXT_DIR/vec0 clj -M:test"
echo "  (从 backend/components/memory-store 目录)"
