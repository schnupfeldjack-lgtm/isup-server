#!/bin/bash
# ==========================================
#  ISUP Server 启动脚本（一个脚本，按参数切环境）
#  打包用 mvn -P prod
#
#  用法：
#    ./isup-server.sh              # 默认 prod
#    ./isup-server.sh prod         # 生产（正式 RDS）
#    ./isup-server.sh dev-test     # dev-test（连生产库，扫 _test 表）
#    ISUP_PUBLIC_IP=x.x.x.x ./isup-server.sh dev-test   # 覆盖设备回连 IP
# ==========================================
cd "$(dirname "$0")" || exit 1

# jar 位置：部署机 /app/isup/app/，开发机 Maven 打在 target/ 下
JAR="isup-server-1.0.0.jar"
if [ -f "app/$JAR" ]; then
    JAR="app/$JAR"
elif [ -f "target/$JAR" ]; then
    JAR="target/$JAR"
fi

if [ ! -f "$JAR" ]; then
    echo "未找到 $JAR，请先执行: mvn -P prod clean package -DskipTests"
    exit 1
fi

# 第一个参数选 profile，默认 prod
PROFILE="${1:-prod}"
case "$PROFILE" in
  prod|dev-test) ;;
  *) echo "未知 profile: $PROFILE（支持 prod / dev-test）"; exit 1 ;;
esac

echo "启动 ISUP Server（$PROFILE）..."
echo "配置文件: application.yml + application-$PROFILE.yml"
echo "健康检查: http://127.0.0.1:8080/api/health"

# 固定堆大小避免动态扩缩；给 G1 一个暂停目标，实时码流下避免长暂停造成批量丢帧。
JAVA_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# 海康 SDK 由 jar 自动释放到 sdk 目录，Linux 动态链接器需要这个路径
# 才能找到 libHCISUPCMS.so 依赖的 libHCISUPStream.so / libcrypto.so / libssl.so
export LD_LIBRARY_PATH="/app/isup/sdk:${LD_LIBRARY_PATH:-}"

# 设备回连 IP：默认走 yml 里的值；设了 ISUP_PUBLIC_IP 才覆盖（测试机用它换自己的 IP）
PUBLIC_IP_ARG=""
if [ -n "${ISUP_PUBLIC_IP:-}" ]; then
    PUBLIC_IP_ARG="--isup.public-ip=${ISUP_PUBLIC_IP}"
fi

# ffmpeg 路径：设了 ISUP_FFMPEG_PATH 直接用；否则自动 which ffmpeg 找真实路径；找不到再回退 yml 默认值
FFMPEG_ARG=""
if [ -n "${ISUP_FFMPEG_PATH:-}" ]; then
    FFMPEG_ARG="--isup.ffmpeg-path=${ISUP_FFMPEG_PATH}"
elif command -v ffmpeg >/dev/null 2>&1; then
    FFMPEG_ARG="--isup.ffmpeg-path=$(command -v ffmpeg)"
fi

exec java $JAVA_OPTS -jar "$JAR" --spring.profiles.active="$PROFILE" $PUBLIC_IP_ARG $FFMPEG_ARG