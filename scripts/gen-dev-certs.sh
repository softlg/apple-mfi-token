#!/usr/bin/env bash
# 生成本地开发/自测用的自签证书材料，绝不用于生产，也绝不入库。
#
# 产物（默认 .dev-certs/，已在 .gitignore 中）：
#   ca.crt/ca.key                  自签根 CA，mTLS 桩与服务端互信靠它
#   server.crt/server.pk8.pem      桩服务器证书（CN=localhost + SAN）
#   tenant-a.pk8.pem               租户 A 客户端证书 + PKCS#8 私钥（BEGIN PRIVATE KEY）
#   tenant-b.pk1.pem               租户 B 客户端证书 + PKCS#1 私钥（BEGIN RSA PRIVATE KEY）
#   tenant-expired.crt/.key        已过期客户端证书，用于验证入库前的有效期校验
#
# 用法：bash scripts/gen-dev-certs.sh [输出目录]
set -euo pipefail

OPENSSL="${OPENSSL_BIN:-openssl}"
OUT="${1:-.dev-certs}"
DAYS_CA=3650
DAYS_LEAF=365

# Git Bash 会把 /C=CN/O=... 这类 -subj 当成绝对路径转换成 D:/tools/Git/C=CN/...，
# openssl 是 Windows 原生程序，必须关掉 MSYS 的路径自动转换。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

command -v "$OPENSSL" >/dev/null 2>&1 || OPENSSL="$OPENSSL"
"$OPENSSL" version >/dev/null

case "$OUT" in
  .*|/*) ;;
  *) ;;
esac

if [ -e "$OUT" ] && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if [ -n "$(git ls-files -- "$OUT")" ]; then
    echo "拒绝执行：$OUT 里已有被 git 跟踪的文件，证书不能入库" >&2
    exit 1
  fi
fi

mkdir -p "$OUT"
cd "$OUT"

issue() { # issue <cn> <san> <csr> <crt>
  local cn="$1" san="$2" csr="$3" crt="$4"
  if [ -n "$san" ]; then
    printf 'subjectAltName=%s\n' "$san" > "$(basename "$crt").ext"
    "$OPENSSL" x509 -req -in "$csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
      -days "$DAYS_LEAF" -sha256 -extfile "$(basename "$crt").ext" -out "$crt" 2>/dev/null
    rm -f "$(basename "$crt").ext"
  else
    "$OPENSSL" x509 -req -in "$csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
      -days "$DAYS_LEAF" -sha256 -out "$crt" 2>/dev/null
  fi
  # 苹果对链上证书无要求，但厂商常把 leaf+CA 拼成一个文件，这里保持同样习惯
  cat "$crt" ca.crt > "$(basename "$crt" .crt)-chain.crt"
}

echo "==> 根 CA"
"$OPENSSL" req -x509 -newkey rsa:2048 -sha256 -nodes \
  -keyout ca.key -out ca.crt -days "$DAYS_CA" \
  -subj "/C=CN/O=Apple MFI Token Dev/CN=MFI Dev Root CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

echo "==> 桩服务器证书（CN=localhost）"
"$OPENSSL" req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/C=CN/O=Apple MFI Token Dev/CN=localhost" 2>/dev/null
issue "localhost" "DNS:localhost,IP:127.0.0.1" server.csr server.crt
# 桩服务端需要 PKCS#8，Java 侧直接读 server.pk8.pem
"$OPENSSL" pkcs8 -topk8 -nocrypt -in server.key -out server.pk8.pem

echo "==> 租户 A：PKCS#8 私钥（BEGIN PRIVATE KEY）"
"$OPENSSL" req -newkey rsa:2048 -nodes -keyout tenant-a.pk8.key -out tenant-a.csr \
  -subj "/C=CN/O=Tenant A/CN=MFI Tenant A Client" 2>/dev/null
issue "MFI Tenant A Client" "" tenant-a.csr tenant-a.crt
cat tenant-a.crt > tenant-a.pk8.pem
cat tenant-a.pk8.key >> tenant-a.pk8.pem

echo "==> 租户 B：PKCS#1 私钥（BEGIN RSA PRIVATE KEY，厂商常见交付格式）"
"$OPENSSL" req -newkey rsa:2048 -nodes -keyout tenant-b.key -out tenant-b.csr \
  -subj "/C=CN/O=Tenant B/CN=MFI Tenant B Client" 2>/dev/null
issue "MFI Tenant B Client" "" tenant-b.csr tenant-b.crt
"$OPENSSL" rsa -traditional -in tenant-b.key -out tenant-b.pk1.key
cat tenant-b.crt > tenant-b.pk1.pem
cat tenant-b.pk1.key >> tenant-b.pk1.pem

echo "==> 已过期客户端证书（自签即可，服务端只校验有效期与配对）"
"$OPENSSL" req -x509 -newkey rsa:2048 -sha256 -nodes \
  -keyout tenant-expired.key -out tenant-expired.crt \
  -not_before 20250101000000Z -not_after 20250102000000Z \
  -subj "/C=CN/O=Tenant Expired/CN=MFI Expired Client" 2>/dev/null
printf 'MISMATCH\n' > README.txt
echo "tenant-a.crt 与 tenant-b.pk1.key 故意互不配对，用于验证入库前的配对校验" >> README.txt

rm -f tenant-a.csr tenant-b.csr server.csr ca.srl tenant-b.key

echo
echo "完成。材料在 $(pwd)："
ls -1 *.pem *.crt *.key 2>/dev/null | sed 's/^/  /'
echo
echo "提醒：这些是本地自签材料，任何真实苹果证书都不要放进这个目录。"
