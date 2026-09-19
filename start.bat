@echo off
chcp 65001 >nul
cd /d %~dp0

rem ==========================================
rem  ISUP Server 启动脚本（Windows）
rem  配置在 config/application.yml 中修改
rem ==========================================

if not exist "target\isup-server-1.0.0.jar" (
    echo 未找到 target\isup-server-1.0.0.jar
    echo 请先执行: mvn clean package -DskipTests
    pause
    exit /b 1
)

if not exist "sdk" (
    echo [警告] 未找到 sdk 目录，海康 SDK 未放置，设备将无法注册
    echo 请将 libHCISUPCMS.so / HCISUPCMS.dll 等文件放入 sdk\
    echo.
)

echo 启动 ISUP Server...
echo 配置文件: config\application.yml
echo 健康检查: http://127.0.0.1:8080/api/health
java -jar target\isup-server-1.0.0.jar

pause
