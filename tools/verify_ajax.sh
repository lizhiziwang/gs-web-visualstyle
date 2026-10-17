#!/usr/bin/env bash
# =============================================================================
# 端到端功能验证（核心数据流 + 服务端计算 + 渲染）
#
# 依次验证：
#   A. 登录并打开插件页面（同一会话）
#   B. 表单状态回传 → 服务端用 GeoTools 对象模型生成 SLD 1.1.0 并推回
#   C. 「枚举唯一值并配色」服务端动作 → 生成唯一值分类规则（含 ogc:Filter）
#   D. 把生成的 SLD 交给 GeoServer WMS（SLD_BODY）实际渲染，确认样式可用
#
# 依赖：curl + python；凭据取自 $HOME/.gs-test.netrc。
# 用法：bash tools/verify_ajax.sh [图层名] [分类字段]
# =============================================================================
set -u

BASE="${GS_BASE:-http://localhost:8000/geoserver}"
NETRC="${GS_NETRC:-$HOME/.gs-test.netrc}"
LAYER="${1:-topp:states}"
FIELD="${2:-STATE_ABBR}"

JAR=/tmp/vs-e2e-cookies.txt
PAGE=/tmp/vs-e2e-page.html
RESP=/tmp/vs-e2e-resp.txt
OUTDIR="$(dirname "$0")/tmp"
mkdir -p "$OUTDIR"

PY="/c/Users/zk/.workbuddy/binaries/python/versions/3.13.12/python.exe"
command -v python >/dev/null 2>&1 || PY="py"

USER_NAME="$(awk '/^machine/{for(i=1;i<=NF;i++) if($i=="login") print $(i+1)}' "$NETRC" | head -1)"
PASS_WORD="$(awk '/^machine/{for(i=1;i<=NF;i++) if($i=="password") print $(i+1)}' "$NETRC" | head -1)"
[ -z "${USER_NAME:-}" ] && { echo "缺少凭据：$NETRC" >&2; exit 2; }

rm -f "$JAR"
PASS=0; FAIL=0
ok()   { echo "  OK   $1"; PASS=$((PASS + 1)); }
bad()  { echo "  MISS $1"; FAIL=$((FAIL + 1)); }
check(){ if grep -q -- "$1" "$2"; then ok "$1"; else bad "$1"; fi; }

echo "== A) 登录并打开页面 =="
curl -sS -c "$JAR" -b "$JAR" -o /dev/null -X POST "$BASE/j_spring_security_check" \
  --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASS_WORD" \
  -H 'Sec-Fetch-Site: same-origin' -H "Origin: http://localhost:8000"
curl -sS -L -c "$JAR" -b "$JAR" -o "$PAGE" -w '  页面 %{http_code} %{size_download}\n' \
  "$BASE/web/wicket/bookmarkable/org.geoserver.web.style.VisualStyleEditorPage?filter=false" \
  -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: navigate' -H 'Sec-Fetch-Dest: document' \
  -H "Origin: http://localhost:8000" -H "Referer: $BASE/web/"

PUSH_URL="$(grep -oE '"u":"[^"]*visualForm-vsPush[^"]*"' "$PAGE" | head -1 | sed 's/"u":"//; s/"$//; s#^\./#'"$BASE"'/web/#')"
UNIQ_URL="$(grep -oE '"u":"[^"]*enumerateUnique[^"]*"' "$PAGE" | head -1 | sed 's/"u":"//; s/"$//; s#^\./#'"$BASE"'/web/#')"
[ -z "$PUSH_URL" ] && { echo "找不到 vsPush 回调，页面未正确渲染" >&2; exit 1; }
echo "  回传通道: $PUSH_URL"

ajax() {  # ajax <url> <respfile> [额外 -d 参数...]
  local url="$1"; shift
  local resp="$1"; shift
  curl -sS -b "$JAR" -c "$JAR" -o "$resp" -w '%{http_code} %{size_download}\n' \
    -X POST "$url" \
    -H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
    -H 'Wicket-Ajax: true' -H 'Wicket-Ajax-BaseURL: ./visualstyle' \
    -H 'X-Requested-With: XMLHttpRequest' \
    -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' \
    -H "Origin: http://localhost:8000" -H "Referer: $BASE/web/visualstyle" \
    --data-urlencode 'vsPush=1' "$@"
}

BASE_FIELDS=(
  --data-urlencode 'vs.styleName=vstest'
  --data-urlencode "vs.previewLayer=$LAYER"
  --data-urlencode 'vs.symbolizerKind=polygon'
  --data-urlencode 'vs.fillEnabled=true'
  --data-urlencode 'vs.fillColor=#ff5500'
  --data-urlencode 'vs.fillOpacity=0.7'
  --data-urlencode 'vs.strokeColor=#003366'
  --data-urlencode 'vs.strokeWidth=3'
  --data-urlencode 'vs.strokeDashArray=6 3'
  --data-urlencode 'vs.labelEnabled=true'
  # ⚠ 标注字段必须真实存在于 $LAYER 上，否则 GeoServer 会用
  # 「The requested Style can not be used with this layer」直接拒绝该 SLD
  # （topp:states 上是 STATE_NAME/STATE_ABBR，没有 NAME）。
  --data-urlencode "vs.labelField=$FIELD"
  --data-urlencode 'vs.fontSize=14'
)

echo "== B) 单一符号：表单 → SLD 1.1.0 =="
CODE=$(ajax "$PUSH_URL" "$RESP" "${BASE_FIELDS[@]}" \
  --data-urlencode 'vs.classificationMethod=single')
echo "  $CODE"
# 注意：AJAX 响应里 SLD 是以 JSON/JS 字符串形式返回的，属性引号被转义，
# 因此这里分别断言命名空间、版本号与符号化器元素。
for m in 'VisualStyle.onState' 'StyledLayerDescriptor' '1.1.0' 'http://www.opengis.net/se' 'se:PolygonSymbolizer' \
         'ff5500' 'fill-opacity' '003366' 'stroke-dasharray' 'se:TextSymbolizer' 'conflictResolution'; do
  check "$m" "$RESP"
done
"$PY" "$(dirname "$0")/extract_sld.py" "$RESP" "$OUTDIR/generated-single.sld" >/dev/null 2>&1 \
  && echo "  已导出: $OUTDIR/generated-single.sld"

echo "== C) 唯一值分类：服务端枚举 + 规则生成 =="
if [ -n "$UNIQ_URL" ]; then
  CODE=$(ajax "$UNIQ_URL" "$RESP" "${BASE_FIELDS[@]}" \
    --data-urlencode 'vs.classificationMethod=unique' \
    --data-urlencode "vs.classificationField=$FIELD")
  echo "  $CODE"
  for m in 'VisualStyle.onState' 'uniqueValues' 'PropertyIsEqualTo' 'se:Rule' 'ogc:Filter'; do
    check "$m" "$RESP"
  done
  "$PY" "$(dirname "$0")/extract_sld.py" "$RESP" "$OUTDIR/generated-unique.sld" >/dev/null 2>&1 \
    && echo "  已导出: $OUTDIR/generated-unique.sld"
else
  echo "  (页面中未找到 enumerateUnique 回调，跳过)"
fi

echo "== D) 用生成的 SLD 渲染 WMS 地图 =="
SLD_FILE="$OUTDIR/generated-single.sld"
if [ -f "$SLD_FILE" ]; then
  # ⚠ 关键：把 SLD 里第一个 NamedLayer 的名字改成 $LAYER。
  # GeoServer 用 SLD_BODY 时是按「LAYERS 与 NamedLayer 同名」来匹配样式的；匹配不上会
  # 静默回退到图层默认样式（不报错），那样这项检查就变成了「假通过」。
  SLD_RETARGETED="$OUTDIR/generated-single-retargeted.sld"
  "$PY" - "$SLD_FILE" "$SLD_RETARGETED" "$LAYER" <<'PYEOF'
import re, sys
src, dst, layer = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(src, encoding='utf-8').read()
pat = re.compile(r'(<(?:(?:[A-Za-z_][\w.-]*):)?Name\b[^>]*>)([^<]*)(</(?:(?:[A-Za-z_][\w.-]*):)?Name>)')
m = pat.search(s)
if m:
    s = s[:m.start()] + m.group(1) + layer + m.group(3) + s[m.end():]
open(dst, 'w', encoding='utf-8').write(s)
print('  已把 NamedLayer 重定向到: ' + layer)
PYEOF
  # ⚠⚠ 坐标轴序陷阱（曾经让这项检查长期「空对空」假通过/假失败）：
  #   WMS 1.3.0 下 CRS=EPSG:4326 必须按 **lat,lon（Y,X）** 给 BBOX。
  #   写成大家习惯的 lon,lat（-130,20,-65,50）GeoServer 不报错，
  #   只是把纬度当成 -130 去查 → 结果是一张**全透明空白图**（实测 4357 字节、
  #   0 个非透明像素），于是「插件 SLD」和「默认样式」两张图逐字节相同，
  #   检查就变成了毫无意义的比对。
  #   正确写法：BBOX=20,-130,50,-65（覆盖美国本土）。
  #   插件里的 OpenLayers 预览之所以没事，是因为 OL3 给 EPSG:4326 定义了
  #   axisOrientation:'neu'，发请求时会自己按 lat,lon 组包。
  WMS_BBOX="20,-130,50,-65"
  # ⚠ 必须用 POST：SLD_BODY 放进 GET 查询串会因为 URL 超长被 Tomcat 直接 400
  # （默认 maxHttpHeaderSize=8KB，而 URL 编码后的 SLD 通常有 10~20KB）。
  # 插件真实的预览链路也是 POST，这里保持一致。
  wms_get() {  # $1 = SLD_BODY 文件（空表示用图层默认样式，此时不带 SLD_BODY 参数）, $2 = 输出文件
    if [ -n "$1" ]; then
      curl -sS -o "$2" -w '%{http_code} %{content_type} %{size_download}' \
        --max-time 90 -X POST "$BASE/wms" \
        --data-urlencode "SERVICE=WMS" --data-urlencode "VERSION=1.3.0" \
        --data-urlencode "REQUEST=GetMap" --data-urlencode "LAYERS=$LAYER" \
        --data-urlencode "STYLES=" --data-urlencode "FORMAT=image/png" \
        --data-urlencode "TRANSPARENT=true" --data-urlencode "WIDTH=500" --data-urlencode "HEIGHT=320" \
        --data-urlencode "CRS=EPSG:4326" --data-urlencode "BBOX=$WMS_BBOX" \
        --data-urlencode "SLD_VERSION=1.1.0" \
        --data-urlencode "SLD_BODY@$1"
    else
      # 空的 SLD_BODY= 会被 GeoServer 当成「解析空文档」直接报错，必须整个省略该参数
      curl -sS -o "$2" -w '%{http_code} %{content_type} %{size_download}' \
        --max-time 90 -X POST "$BASE/wms" \
        --data-urlencode "SERVICE=WMS" --data-urlencode "VERSION=1.3.0" \
        --data-urlencode "REQUEST=GetMap" --data-urlencode "LAYERS=$LAYER" \
        --data-urlencode "STYLES=" --data-urlencode "FORMAT=image/png" \
        --data-urlencode "TRANSPARENT=true" --data-urlencode "WIDTH=500" --data-urlencode "HEIGHT=320" \
        --data-urlencode "CRS=EPSG:4326" --data-urlencode "BBOX=$WMS_BBOX" \
        --data-urlencode "SLD_VERSION=1.1.0"
    fi
  }
  info=$(wms_get "$SLD_RETARGETED" "$OUTDIR/render.png")
  echo "  WMS(插件 SLD) $info"
  if [ -s "$OUTDIR/render.png" ] && head -c 4 "$OUTDIR/render.png" | grep -q 'PNG'; then
    ok "WMS 用插件生成的 SLD 成功渲染 PNG"
  else
    bad "WMS 渲染返回非 PNG"
  fi
  # 强校验 1：真的画出了要素，而且画面里出现了我们提交的填充色 #ff5500。
  # 这一条才是「样式被应用」的直接证据；只比「两张图不同」是抓不到全空白假象的。
  if [ -f "$OUTDIR/render.png" ] && head -c 4 "$OUTDIR/render.png" | grep -q 'PNG'; then
    # 注意：不能写成 `cmd | tee`，管道的退出码取自最后一个命令，会吞掉校验失败。
    if "$PY" "$(dirname "$0")/check_render.py" "$OUTDIR/render.png" '#ff5500'; then
      ok "插件 SLD 渲染出要素且命中提交的填充色 #ff5500"
    else
      bad "插件 SLD 渲染图为空白或未出现提交的填充色"
    fi
  fi
  # 强校验 2：与默认样式渲染结果必须不同（防止 SLD_BODY 被静默忽略）
  info2=$(wms_get "" "$OUTDIR/render-default.png")
  echo "  WMS(默认样式) $info2"
  if [ -s "$OUTDIR/render-default.png" ] && head -c 4 "$OUTDIR/render-default.png" | grep -q 'PNG'; then
    if cmp -s "$OUTDIR/render.png" "$OUTDIR/render-default.png"; then
      bad "SLD_BODY 未生效（渲染结果与图层默认样式完全相同）"
    else
      ok "SLD_BODY 确实生效（渲染结果不同于默认样式）"
    fi
  fi
else
  echo "  (无生成的 SLD，跳过渲染)"
fi

echo "-----------------------------------------------"
echo "通过 $PASS / 失败 $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
