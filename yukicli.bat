@echo off
rem YukiCli Windows 启动脚本
rem 修复 cmd 默认代码页 936 (GBK) 导致的 Unicode 字符（雪花等）乱码
rem
rem 原理：
rem   1. chcp 65001 把当前 cmd 会话代码页切到 UTF-8（在脚本内执行，影响当前会话）
rem   2. -Dfile.encoding=UTF-8 让 JVM 用 UTF-8 编码 stdout/stderr
rem   3. -Dsun.stdout.encoding=UTF-8 / -Dsun.stderr.encoding=UTF-8 显式指定 stdout/stderr 编码
rem   4. 两者匹配后，cmd 用 UTF-8 解码 Java 输出的 UTF-8 字节，全 Unicode 正常显示
rem
rem 直接用 java -jar yukicli.jar 启动时，chcp 在 Java 子进程里执行无效，
rem 且 Java 默认 GBK 输出与 cmd GBK 解码匹配但 GBK 不支持雪花等 Unicode 字符。
rem
rem 用法：
rem   yukicli.bat              # 启动交互式 CLI
rem   yukicli.bat 参数         # 透传参数

chcp 65001 >nul

rem 定位 jar：优先 target/ 下的开发构建产物，其次同目录的 jar
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR_DEV=%SCRIPT_DIR%target\yukicli-1.0-SNAPSHOT.jar"
set "JAR_DIST=%SCRIPT_DIR%yukicli-1.0-SNAPSHOT.jar"

if exist "%JAR_DEV%" (
    set "JAR_PATH=%JAR_DEV%"
) else if exist "%JAR_DIST%" (
    set "JAR_PATH=%JAR_DIST%"
) else (
    echo 错误: 未找到 yukicli-1.0-SNAPSHOT.jar
    echo 请先执行 mvn clean package 构建产物
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%JAR_PATH%" %*
endlocal
