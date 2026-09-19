#!/bin/bash
# ==========================================
#  ISUP Server 启动脚本（Linux）
#  配置在 config/application.yml 中修改
# ==========================================
cd "$(dirname "$0")" || exit 1

JAR="target/isup-server-1.0.0.jar"

if [ ! -f "$JAR" ]; then
    echo "未找到 $JAR，请先执行: mvn clean package -DskipTests"
    exit 1
fi

if [ ! -d "sdk" ]; then
    echo "[警告] 未找到 sdk 目录，海康 SDK 未放置，设备将无法注册"
    echo "请将 libHCISUPCMS.so、libHCISUPStream.so、libcrypto.so、libssl.so、HCAapSDKCom/ 放入 sdk/"
    echo
fi

echo "启动 ISUP Server..."
echo "配置文件: config/application.yml"
echo "健康检查: http://127.0.0.1:8080/api/health"
exec java -jar "$JAR"
