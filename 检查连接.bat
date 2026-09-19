@echo off
cd /d %~dp0
echo ==========================================
echo  ISUP 连接检查
echo ==========================================
echo.
echo [1] 7660 是否在监听
netstat -ano | findstr ":7660" | findstr "LISTENING"
if errorlevel 1 echo     （无）服务没启动或启动失败
echo.
echo [2] 设备有没有连进来（出现 ESTABLISHED 才算网络通）
netstat -ano | findstr ":7660" | findstr "ESTABLISHED"
if errorlevel 1 echo     （无）设备还没连上来
echo.
echo [3] 健康检查
curl -s http://127.0.0.1:8080/api/health
echo.
echo [4] 防火墙规则（应显示 LocalPort: 7660）
netsh advfirewall firewall show rule name="ISUP-CMS-7660" | findstr /i "LocalPort"
if errorlevel 1 echo     （无）请先运行 放行端口.bat
echo.
pause
