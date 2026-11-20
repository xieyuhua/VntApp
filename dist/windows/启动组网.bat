@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM  VNT Windows 客户端启动脚本
REM  与 Android 端使用相同的 Token 和服务器地址即可互通。
REM  首次运行会请求管理员权限：创建虚拟网卡需要提权。
REM ============================================================

REM ---- 按你的实际配置修改这三项 ----
set TOKEN=xieyuhua
set SERVER=www.jiaoyuhua.cn:8004
set DEVICE_NAME=%COMPUTERNAME%
REM -----------------------------------

REM 组网加密密码：与手机端保持一致；不需要加密则留空
set PASSWORD=
REM STUN 服务器：留空时 P2P 无法探测 NAT，只能走中继
set STUN=-e stun.l.google.com:19302 -e stun.qq.com:3478 -e stun.miwifi.com:3478

REM 需要访问对端局域网时填写，格式：目标网段,出口节点虚拟IP
set IN_IPS=
REM 允许他人访问本机局域网时填写
set OUT_IPS=

net session >nul 2>&1
if %errorLevel% neq 0 (
    echo 正在请求管理员权限...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"

set ARGS=-k %TOKEN% -s %SERVER% -n "%DEVICE_NAME%" --cmd
if not "%PASSWORD%"=="" set ARGS=%ARGS% -w %PASSWORD%
if not "%STUN%"=="" set ARGS=%ARGS% %STUN%
if not "%IN_IPS%"=="" set ARGS=%ARGS% -i %IN_IPS%
if not "%OUT_IPS%"=="" set ARGS=%ARGS% -o %OUT_IPS%

echo ============================================
echo  Token : %TOKEN%
echo  服务器: %SERVER%
echo  设备名: %DEVICE_NAME%
echo.
echo  交互命令：list 查看设备 / info 查看本机
echo            route 查看转发路径 / quit 退出
echo ============================================
echo.

vnt-cli.exe %ARGS%

echo.
echo 程序已退出，按任意键关闭窗口。
pause >nul
