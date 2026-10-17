@echo off
REM ============================================================================
REM  gs-web-visualstyle 一键构建 / 部署（Windows cmd）
REM
REM  用法：
REM    build.bat               构建 jar
REM    build.bat deploy        构建并部署到名为 geoserver 的 Docker 容器
REM    build.bat deploy-restart
REM
REM  需先设置 JAVA_HOME 指向 JDK 17+
REM ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set ARTIFACT=gs-web-visualstyle
set VERSION=1.0.0
set JAR=%ARTIFACT%\target\%ARTIFACT%-%VERSION%.jar
set CONTAINER=geoserver
set DEPLOY_DIR=/usr/local/tomcat/webapps/geoserver/WEB-INF/lib

if "%JAVA_HOME%"=="" (
  echo [!] 未设置 JAVA_HOME，请先指向 JDK 17+，例如：
  echo     set JAVA_HOME=D:\zsh\app\pycharm\PyCharm 2025.2.1.1\jbr
  exit /b 3
)

echo JAVA_HOME = %JAVA_HOME%
"%JAVA_HOME%\bin\java" -version 2>&1 | findstr /i version

if not exist ".gs-jars" (
  echo [!] 未发现 .gs-jars。请先执行：
  echo     docker cp %CONTAINER%:%DEPLOY_DIR%/. .gs-jars
  echo     python tools\gen_local_repo.py
  exit /b 4
)

echo.
echo == mvn package ==
call mvn -B package
if errorlevel 1 exit /b 1

echo.
dir /b %JAR%

if /i "%~1"=="deploy" goto deploy
if /i "%~1"=="deploy-restart" goto deployrestart
goto end

:deploy
echo.
echo == 部署到容器 %CONTAINER% ==
docker cp "%JAR%" "%CONTAINER%:%DEPLOY_DIR%/"
echo 提示：请重启容器以加载新 jar：docker restart %CONTAINER%
goto end

:deployrestart
echo.
echo == 部署并重启容器 %CONTAINER% ==
docker cp "%JAR%" "%CONTAINER%:%DEPLOY_DIR%/"
docker restart %CONTAINER%

:end
echo.
echo 完成。页面入口：
echo   http://localhost:8000/geoserver/web/visualstyle
endlocal
