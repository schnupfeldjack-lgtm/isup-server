@echo off
cd /d %~dp0
rem  ISUP 设备接入端口放行脚本（必须右键 -> 以管理员身份运行）

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo   权限不足：请右键本文件，选择「以管理员身份运行」
  echo.
  pause
  exit /b 1
)

echo ==========================================
echo  正在放行 ISUP 设备接入端口（入站）
echo ==========================================

netsh advfirewall firewall add rule name="ISUP-CMS-7660" dir=in action=allow protocol=TCP localport=7660 >nul
echo  [1/3] 7660  CMS 注册        已放行
netsh advfirewall firewall add rule name="ISUP-Preview-8003" dir=in action=allow protocol=TCP localport=8003 >nul
echo  [2/3] 8003  实时预览        已放行
netsh advfirewall firewall add rule name="ISUP-Playback-8004" dir=in action=allow protocol=TCP localport=8004 >nul
echo  [3/3] 8004  回放 / 录像下载 已放行

echo.
echo  自校验（应显示 LocalPort: 7660）：
netsh advfirewall firewall show rule name="ISUP-CMS-7660" | findstr /i "LocalPort"
echo.
echo  下一步：双击 检查连接.bat 看设备有没有连进来
pause
