@echo off
REM 构建 VNT Go 客户端。
REM 注意：必须用包模式（go build .）编译，
REM 因为 manifest 与图标通过 rsrc.syso 嵌入，显式列 .go 文件会忽略它。

chcp 65001 >/dev/null
cd /d "%~dp0"

echo 生成资源文件...
rsrc -manifest vnt.manifest -ico vnt.ico -o rsrc.syso
if errorlevel 1 (
    echo 找不到 rsrc 命令，请先执行： go install github.com/akavel/rsrc@latest
    pause
    exit /b 1
)

echo 编译...
go build -ldflags="-H windowsgui -s -w" -o VntGo.exe .
if errorlevel 1 (
    echo 编译失败
    pause
    exit /b 1
)

echo 完成：VntGo.exe
pause
