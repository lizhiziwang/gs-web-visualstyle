#!/usr/bin/env bash
# =============================================================================
# 端到端验证：登录 GeoServer 后台 → 打开可视化样式编辑页面 → 检查关键标记。
#
# 凭据从 $HOME/.gs-test.netrc 读取（形如：machine localhost login admin password xxx），
# 避免出现在命令行 / 进程列表 / shell 历史中。
#
# 用法：
#   bash tools/verify_page.sh [输出文件]
#
# 注意（GeoServer 3.0.1 实测）：
#   1) 页面 URL 为 /geoserver/web/wicket/bookmarkable/<FQCN>（Wicket 10 已移除
#      ?wicket:bookmarkablePage= 查询参数映射器）；本插件另挂载了 /geoserver/web/visualstyle。
#   2) GeoServer 3 启用了 Fetch Metadata 跨站隔离，非浏览器客户端必须补齐
#      Sec-Fetch-* / Origin / Referer 请求头，否则会被当成跨站请求而重定向到登录页。
# =============================================================================
set -u

BASE="${GS_BASE:-http://localhost:8000/geoserver}"
NETRC="${GS_NETRC:-$HOME/.gs-test.netrc}"
OUT="${1:-/tmp/vs-page.html}"

USER_NAME="$(awk '/^machine/{for(i=1;i<=NF;i++) if($i=="login") print $(i+1)}' "$NETRC" | head -1)"
PASS_WORD="$(awk '/^machine/{for(i=1;i<=NF;i++) if($i=="password") print $(i+1)}' "$NETRC" | head -1)"

if [ -z "$USER_NAME" ] || [ -z "$PASS_WORD" ]; then
  echo "无法从 $NETRC 读取凭据。请先创建：" >&2
  echo "  printf 'machine localhost login admin password geoserver\n' > ~/.gs-test.netrc && chmod 600 ~/.gs-test.netrc" >&2
  exit 2
fi

JAR="/tmp/gs-vs-cookies.txt"
rm -f "$JAR"

fetch() {  # fetch <outfile> <url> [extra curl args...]
  local out="$1"; shift
  local url="$1"; shift
  curl -sS -L -b "$JAR" -c "$JAR" -o "$out" \
    -w '%{http_code} %{size_download}\n' \
    -H 'Sec-Fetch-Site: same-origin' \
    -H 'Sec-Fetch-Mode: navigate' \
    -H 'Sec-Fetch-Dest: document' \
    -H 'Sec-Fetch-User: ?1' \
    -H "Origin: http://localhost:8000" \
    -H "Referer: $BASE/web/" \
    "$url" "$@"
}

echo "== 1. 登录 =="
fetch /dev/null "$BASE/j_spring_security_check" \
  -X POST --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASS_WORD"

echo "== 2. 打开插件页面 =="
CODE=$(fetch "$OUT" "$BASE/web/wicket/bookmarkable/org.geoserver.web.style.VisualStyleEditorPage?filter=false")
echo "  $CODE"

TITLE=$(grep -oE '<title>[^<]*</title>' "$OUT" | head -1 | sed 's/<[^>]*>//g')
echo "  标题: $TITLE"

echo "== 3. 关键标记 =="
MISS=0
# 说明：面板不会在 HTML 里出现类名，稳的是各面板根节点的 class 与表单字段名。
# 只有当前激活的标签页会被渲染（AjaxTabbedPanel 懒加载），因此这里断言
# 默认激活的「矢量符号化」面板 + 右侧预览面板。
# 另外 Wicket 会给资源 URL 追加版本后缀（如 visualstyle-ver-xxxx.js），
# 因此静态资源按路径片段断言，不写完整文件名。
for marker in 'vs-visual-form' 'vs-col-left' 'vs-col-center' 'vs-col-right' 'vs-source-strip' \
              'vs-panel-vector' 'vs-panel-preview' 'vs.symbolizerKind' 'vs.rampPreset' \
              'vs.classificationMethod' 'vs.fillColor' 'vs.externalGraphic' 'vs.pointSize' \
              'js/visualstyle-' 'js/colormap-' 'css/visualstyle-' 'vs-legend' 'vsPush' 'vs-map' \
              'openlayers3/ol.js' 'VisualStyle.init' 'SLD_BODY' 'wmsUrl'; do
  if grep -q -- "$marker" "$OUT"; then
    echo "  OK   $marker"
  else
    echo "  MISS $marker"
    MISS=$((MISS + 1))
  fi
done
echo "  缺失: $MISS"

echo "== 4. 异常检查 =="
if grep -q "Oops, something went wrong" "$OUT"; then
  echo "  !! 页面抛出异常"
  grep -oE 'Caused by[^<]{0,160}' "$OUT" | head -5
else
  echo "  OK 无异常"
fi

exit 0
