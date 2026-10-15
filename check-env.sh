#!/bin/bash
# ==========================================
#  isup-server 环境自检（Linux，只检查不启动）
#  与 start.sh 互补：start.sh 只管启动，这个脚本把环境毛病一次挑出来
#  用法：./check-env.sh
# ==========================================
cd "$(dirname "$0")" || exit 1

JAR="isup-server-1.0.0.jar"
[ -f "$JAR" ] || [ -f "target/$JAR" ] || JAR="target/$JAR"
ERR=0

ok()   { echo "      [正常] $*"; }
warn() { echo "      [警告] $*"; }
fail() { echo "      [失败] $*"; ERR=1; }

# 配置优先从源码读，部署机上没有源码就从 jar 里取
read_cfg() {
    local key=$1
    if [ -f "src/main/resources/application-prod.yml" ]; then
        grep -E "^[[:space:]]*$key:" "src/main/resources/application-prod.yml" | head -1 | sed "s/.*$key:[[:space:]]*//"
    elif [ -f "$JAR" ] && command -v unzip >/dev/null 2>&1; then
        unzip -p "$JAR" BOOT-INF/classes/application-prod.yml 2>/dev/null \
            | grep -E "^[[:space:]]*$key:" | head -1 | sed "s/.*$key:[[:space:]]*//"
    fi
}

echo "============================================================"
echo " isup-server 环境自检（Linux）"
echo "============================================================"

# ---------- 1. 服务包 ----------
echo "[1/6] 服务包"
if [ -f "$JAR" ]; then
    ok "$JAR"
else
    fail "找不到 isup-server-1.0.0.jar —— 先跑 mvn -P prod clean package -DskipTests"
fi

# ---------- 2. Java ----------
echo "[2/6] Java（需要 21）"
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    case "$JAVA_VER" in
        *21*|*22*|*23*|*24*) ok "$JAVA_VER" ;;
        *) warn "$JAVA_VER —— 项目按 JDK 21 编译，低于 21 跑不起来" ;;
    esac
else
    fail "java 不可用"
fi

# ---------- 3. ffmpeg ----------
echo "[3/6] ffmpeg（HLS 转码依赖 libx264）"
FFMPEG=$(read_cfg ffmpeg-path)
[ -z "$FFMPEG" ] && FFMPEG=/usr/local/bin/ffmpeg
if [ -x "$FFMPEG" ]; then
    ok "$FFMPEG（$("$FFMPEG" -version 2>/dev/null | head -1 | cut -d' ' -f1-3)）"
    if "$FFMPEG" -hide_banner -encoders 2>/dev/null | grep -q libx264; then
        ok "libx264 在 —— 设备是 H.265，预览靠它转 H.264"
    else
        fail "没有 libx264！HLS 预览一定黑屏。换自带 x264 的静态包：
              wget https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz
              tar xf ffmpeg-release-amd64-static.tar.xz
              cp ffmpeg-*-static/ffmpeg $FFMPEG && chmod +x $FFMPEG"
    fi
    if "$FFMPEG" -hide_banner -encoders 2>/dev/null | grep -qE "^ A.* aac "; then
        ok "aac 编码器在（MP4 下载用）"
    else
        warn "没有 aac 编码器 —— 设备音频是 G.711 A-law，MP4 下载会失败"
    fi
else
    fail "ffmpeg 不可用或不可执行：$FFMPEG"
fi

# ---------- 4. 目录权限 ----------
echo "[4/6] 目录（SDK 释放 / 媒体输出 / 日志）"
for d in "$(read_cfg sdk-dir)" "$(read_cfg media-dir)"; do
    [ -z "$d" ] && continue
    if [ -d "$d" ]; then
        [ -w "$d" ] && ok "$d 可写" || fail "$d 不可写"
    elif mkdir -p "$d" 2>/dev/null; then
        ok "$d 已自动创建"
    else
        fail "$d 无法创建"
    fi
done

# ---------- 5. 端口 ----------
echo "[5/6] 端口占用（8660 CMS / 8003 预览 / 8661 回放 / 8080 API）"
BUSY=""
for p in 8660 8003 8661 8080; do
    if ss -lnt 2>/dev/null | grep -q ":$p "; then
        BUSY="$BUSY $p"
    elif netstat -lnt 2>/dev/null | grep -q ":$p "; then
        BUSY="$BUSY $p"
    fi
done
if [ -z "$BUSY" ]; then
    ok "均空闲"
else
    warn "已被占用：$BUSY —— 先停掉旧进程再启动"
fi

# ---------- 6. 数据库 ----------
echo "[6/6] 数据库连通"
DB_HOST_PORT=$(read_cfg url | sed 's/.*jdbc:mysql:\/\///; s/\/.*//')
if [ -n "$DB_HOST_PORT" ]; then
    DB_HOST=${DB_HOST_PORT%:*}
    DB_PORT=${DB_HOST_PORT#*:}
    if timeout 3 bash -c "cat < /dev/null > /dev/tcp/$DB_HOST/$DB_PORT" 2>/dev/null; then
        ok "$DB_HOST:$DB_PORT 可连通"
    else
        warn "$DB_HOST:$DB_PORT 连不通 —— 确认数据库已启动且白名单放行本机 IP
              （当前业务代码不查库，不影响启动）"
    fi
else
    warn "未能解析数据库地址"
fi

echo "============================================================"
if [ "$ERR" = "1" ]; then
    echo " 结论：有 [失败] 项，修完再启动"
else
    echo " 结论：可以启动（[警告] 项不影响启动，但会影响功能）"
fi
echo "============================================================"
exit $ERR
