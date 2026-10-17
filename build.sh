#!/usr/bin/env bash
# =============================================================================
# gs-web-visualstyle 一键构建 / 部署脚本
#
# 用法：
#   bash build.sh                 # 构建 jar
#   bash build.sh --deploy        # 构建并部署到名为 geoserver 的 Docker 容器
#   bash build.sh --deploy --restart
#   bash build.sh --clean         # 清理后重建
#
# 环境变量：
#   JAVA_HOME   JDK 17+（GeoServer 3.0.1 的运行基线）。未设置时脚本会尝试
#               从常见位置自动探测（本机可用的有 IntelliJ 的 JBR 21/25）。
#   MVN_OFFLINE 设为 1 时使用 -o 离线模式（依赖已由 tools/gen_local_repo.py 就位）。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

ARTIFACT="gs-web-visualstyle"
VERSION="1.0.0"
JAR="${ARTIFACT}/target/${ARTIFACT}-${VERSION}.jar"
CONTAINER="${GS_CONTAINER:-geoserver}"
DEPLOY_DIR="/usr/local/tomcat/webapps/geoserver/WEB-INF/lib"

DEPLOY=0
RESTART=0
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --deploy)  DEPLOY=1 ;;
    --restart) RESTART=1; DEPLOY=1 ;;
    --clean)   CLEAN=1 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- JAVA_HOME
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for cand in \
      "/d/zsh/app/pycharm/PyCharm 2025.2.1.1/jbr" \
      "/d/zsh/app/idea2026/IntelliJ IDEA 2026.2.0.1/jbr" \
      "/c/Program Files/Eclipse Adoptium"/* \
      "/c/Program Files/Java"/jdk-1[7-9]* \
      "/usr/lib/jvm"/java-17-openjdk* ; do
    if [ -x "$cand/bin/javac" ]; then
      export JAVA_HOME="$cand"
      break
    fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
  echo "未找到可用的 JDK 17+。请设置 JAVA_HOME 后重试。" >&2
  exit 3
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME = $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# ---------------------------------------------------- 离线依赖仓库（首次）
if [ ! -d "$ROOT/.gs-jars" ] || [ -z "$(ls -A "$ROOT/.gs-jars" 2>/dev/null)" ]; then
  echo
  echo "[!] 未发现 .gs-jars（GeoServer 运行时 jar 快照）。"
  echo "    GeoServer / GeoTools 构件不在 Maven Central，"
  echo "    OSGeo 官方仓库也缺 gs-web-* 模块，因此本工程用"
  echo "    「目标 GeoServer 容器里的真实 jar」生成本地 Maven 仓库。"
  echo "    请先执行："
  echo "      docker cp ${CONTAINER}:${DEPLOY_DIR}/. .gs-jars"
  echo "      python tools/gen_local_repo.py"
  echo "    再重新运行本脚本。"
  exit 4
fi

# ------------------------------------------------------------------- build
MVN_ARGS=(-B)
if [ "${MVN_OFFLINE:-0}" = "1" ]; then MVN_ARGS+=(-o); fi
if [ "$CLEAN" = "1" ]; then MVN_ARGS+=(clean); fi

echo
echo "== mvn package =="
mvn "${MVN_ARGS[@]}" package

echo
ls -la "$JAR"

# ------------------------------------------------------------------ deploy
if [ "$DEPLOY" = "1" ]; then
  echo
  echo "== 部署到容器 $CONTAINER =="
  MSYS_NO_PATHCONV=1 docker cp "$JAR" "${CONTAINER}:${DEPLOY_DIR}/"
  MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" sh -c \
    "ls -la ${DEPLOY_DIR}/${ARTIFACT}-${VERSION}.jar"

  if [ "$RESTART" = "1" ]; then
    echo "== 重启容器 =="
    docker restart "$CONTAINER" >/dev/null
    echo -n "等待 GeoServer 就绪"
    for _ in $(seq 1 60); do
      sleep 2
      printf '.'
      if curl -s -o /dev/null --max-time 3 "http://localhost:8000/geoserver/web/" ; then break; fi
    done
    echo
    MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" sh -c \
      "grep -a 'gs-web-visualstyle' /usr/local/tomcat/logs/*.log 2>/dev/null | tail -3" || true
  else
    echo
    echo "提示：新增 jar 需要重启容器才能加载 —— docker restart $CONTAINER"
  fi
fi

echo
echo "完成。页面入口："
echo "  http://localhost:8000/geoserver/web/visualstyle"
echo "  http://localhost:8000/geoserver/web/wicket/bookmarkable/org.geoserver.web.style.VisualStyleEditorPage"
