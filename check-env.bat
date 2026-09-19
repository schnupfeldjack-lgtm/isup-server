@echo off
chcp 65001 >nul
cd /d %~dp0
setlocal enabledelayedexpansion

rem ============================================================
rem  isup-server 环境自检（只检查，不启动服务）
rem  与 start.bat 互补：start.bat 只管启动，这个脚本负责把环境毛病一次挑出来
rem ============================================================

set "JAR=target\isup-server-1.0.0.jar"
set "CFG=config\application.yml"
set "ERR=0"

echo ============================================================
echo  isup-server 环境自检
echo ============================================================

rem ---------- 1. 服务包 ----------
echo [1/6] 服务包
if not exist "%JAR%" (
    echo      [失败] 找不到 %JAR% —— 先跑 mvn clean package -DskipTests
    set "ERR=1"
) else (
    echo      [正常] %JAR%
)

rem ---------- 2. Java ----------
echo [2/6] Java
java -version >nul 2>&1
if errorlevel 1 (
    echo      [失败] java 不可用，需要 JDK 21
    set "ERR=1"
) else (
    for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /r "version"') do echo      [正常] %%~v
)

rem ---------- 3. SDK ----------
echo [3/6] 海康 SDK（sdk\）
set "SDK_MISS="
if not exist "sdk\HCISUPCMS.dll"    set "SDK_MISS=!SDK_MISS! HCISUPCMS.dll"
if not exist "sdk\HCISUPStream.dll" set "SDK_MISS=!SDK_MISS! HCISUPStream.dll"
if not exist "sdk\libeay32.dll"     set "SDK_MISS=!SDK_MISS! libeay32.dll"
if not exist "sdk\ssleay32.dll"     set "SDK_MISS=!SDK_MISS! ssleay32.dll"
if not exist "sdk\HCAapSDKCom"      set "SDK_MISS=!SDK_MISS! HCAapSDKCom/"
if defined SDK_MISS (
    echo      [失败] 缺少：!SDK_MISS!
    echo             从 ISUP SDK_Win64 包的 lib64\ 目录整包拷进来
    set "ERR=1"
) else (
    echo      [正常] HCISUPCMS.dll HCISUPStream.dll libeay32.dll ssleay32.dll HCAapSDKCom\
)

rem ---------- 4. 配置 ----------
echo [4/6] 配置（config\application.yml）
if not exist "%CFG%" (
    echo      [失败] 没有 %CFG%
    set "ERR=1"
) else (
    for /f "tokens=1,* delims=:" %%a in ('findstr /r "ffmpeg-path:" "%CFG%"') do set "FFMPEG=%%b"
    for /f "tokens=1,* delims=:" %%a in ('findstr /r "ehome-key:" "%CFG%"') do set "EHOMEKEY=%%b"
    for /f "tokens=1,* delims=:" %%a in ('findstr /r "public-ip:" "%CFG%"') do set "PUBIP=%%b"
    for /f "tokens=1,* delims=:" %%a in ('findstr /r "api-key:" "%CFG%"') do set "APIKEY=%%b"

    if defined FFMPEG   if "!FFMPEG:~0,1!"==" "   set "FFMPEG=!FFMPEG:~1!"
    if defined EHOMEKEY if "!EHOMEKEY:~0,1!"==" " set "EHOMEKEY=!EHOMEKEY:~1!"
    if defined PUBIP    if "!PUBIP:~0,1!"==" "    set "PUBIP=!PUBIP:~1!"
    if defined APIKEY   if "!APIKEY:~0,1!"==" "   set "APIKEY=!APIKEY:~1!"

    if exist "!FFMPEG!" (echo      [正常] ffmpeg：!FFMPEG!) else (echo      [警告] ffmpeg 不可用：!FFMPEG!)
    if "!EHOMEKEY!"=="CHANGE_ME_EHOME_KEY" (echo      [警告] ehome-key 仍是占位值，设备注册会被拒绝) else (echo      [正常] ehome-key 已配置)
    if "!PUBIP!"=="" (echo      [失败] public-ip 未配置 & set "ERR=1") else (echo      [正常] public-ip：!PUBIP!)
    if "!APIKEY!"=="" (echo      [失败] api-key 未配置 & set "ERR=1") else (echo      [正常] api-key 已配置)
)

rem ---------- 5. 端口 ----------
echo [5/6] 端口占用
set "PORT_BUSY="
for %%p in (7660 8003 8004 8080) do (
    netstat -ano | findstr /r /c:":%%p .*LISTENING" >nul
    if not errorlevel 1 set "PORT_BUSY=!PORT_BUSY! %%p"
)
if defined PORT_BUSY (echo      [警告] 已被占用：!PORT_BUSY! —— 服务会起不来) else (echo      [正常] 7660 8003 8004 8080 均空闲)

rem ---------- 6. 防火墙 ----------
echo [6/6] 防火墙（设备回连需要）
netsh advfirewall firewall show rule name="ISUP-CMS-7660" >nul 2>&1
if errorlevel 1 (
    echo      [警告] 没找到 7660 入站放行规则，设备可能连不进来
    echo             管理员终端执行（8003/8004 同理）：
    echo             netsh advfirewall firewall add rule name="ISUP-CMS-7660" dir=in action=allow protocol=TCP localport=7660
) else (
    echo      [正常] 已存在 ISUP-CMS-7660 放行规则
)

echo ============================================================
if "%ERR%"=="1" (echo  结论：有 [失败] 项，修完再启动) else (echo  结论：可以启动（[警告] 项不影响启动，但会影响功能）)
echo ============================================================
endlocal
pause
