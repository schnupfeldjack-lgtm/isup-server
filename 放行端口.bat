@echo off
chcp 65001 >nul
cd /d %~dp0

rem ============================================================
rem  放行 ISUP 设备接入端口（必须"以管理员身份运行"）
rem  7660 注册 / 8003 预览 / 8004 回放下载
rem  8080 是 HTTP API，只给你自己和 ERP，这里不放行（避免暴露到全网）
rem ============================================================

net session >nul 2>&1
if errorlevel 1 (
    echo ============================================================
    echo  权限不足：请右键本文件，选择「以管理员身份运行」
    echo ============================================================
    pause
    exit /b 1
)

echo ============================================================
echo  正在放行入站端口...
echo ============================================================

netsh advfirewall firewall add rule name="ISUP-CMS-7660" dir=in action=allow protocol=TCP localport=7660 >nul
echo  [1/3] 7660  CMS 注册        —— 已放行

netsh advfirewall firewall add rule name="ISUP-Preview-8003" dir=in action=allow protocol=TCP localport=8003 >nul
echo  [2/3] 8003  实时预览        —— 已放行

netsh advfirewall firewall add rule name="ISUP-Playback-8004" dir=in action=allow protocol=TCP localport=8004 >nul
echo  [3/3] 8004  回放 / 录像下载 —— 已放行

echo.
echo ============================================================
echo  完成。验证方式：
echo    1. 确认服务在跑（IDEA 里重启后 7660 处于 LISTENING）
echo    2. 到录像机上保存一次「平台接入」配置，触发重新注册
echo    3. 回到本机执行： netstat -ano ^| findstr ":7660"
echo       若出现 ESTABLISHED 且远端是录像机 IP，说明网络已通
echo ============================================================
pause
