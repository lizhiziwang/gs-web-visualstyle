/* =============================================================================
 * GeoServer Visual Style Editor —— 前端脚本
 *
 * 设计边界（与后端的分工，务必遵守）：
 *   前端只负责：颜色选择器、色带拖拽、预览地图、源码高亮 / 错误定位、表单收集。
 *   业务模型（SLD 对象）完全由 Java 端 GeoTools 维护 —— 前端不解析、不生成 SLD，
 *   只把「表单字段」与「源码文本」原样传给后端，并把后端算好的状态渲染出来。
 *
 * 数据流：
 *   表单变更 ──VisualStyle.push()──► 隐藏字段 vsPush 的 change 事件
 *            ──Wicket AJAX（附加整个表单）──► Java: VisualStyleState → GeoTools SLD → SLD 1.1 XML
 *            ◄──AjaxRequestTarget.appendJavaScript── VisualStyle.onState(...)
 *   接收到状态后：刷新源码视图 / 图例 / OpenLayers 预览（SLD_BODY POST 方式）。
 * ========================================================================== */
(function (window, document) {
    'use strict';

    var VS = {};
    window.VisualStyle = VS;

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------
    var cfg = {};              // init 传入的配置
    var state = {};            // 后端推送的最新 VisualStyleState
    var ramps = [];            // 色带目录
    var rampById = {};
    var metadata = {fields: [], bands: 0, bounds: [-180, -90, 180, 90]};
    var lastSld = '';
    /**
     * 交给 WMS SLD_BODY 的版本：与 lastSld 内容一致，只是把 NamedLayer 名改写成了预览图层名。
     * 不改写的话 GeoServer 找不到同名图层，会静默回退渲染图层默认样式（预览看起来「永远不生效」）。
     */
    var lastPreviewSld = '';
    /** 最近一次真正交给 WMS 渲染的 SLD / 图层（用于跳过无意义的重复渲染）。 */
    var lastPreviewedSld = null;
    var lastPreviewedLayer = null;
    var lastErrors = [];
    var sourceFormat = 'sld';
    var previewSource = null;
    var previewLayer = null;
    var previewView = null;
    var baseMapLayer = null;
    var baseMapOn = false;
    var previewDirty = true;
    var errorLines = {};
    var pushPending = false;
    var pushQueued = false;     // 同步在途时的变更不丢弃，等返回后补发一次
    var pushWatchdog = null;    // pushPending 超时自愈
    var initialised = false;
    // 源码编辑器底层 textarea 的 id（与 Java 端 SOURCE_TEXTAREA_ID 一致）。
    // ⚠ 必须是合法 JS 标识符：GeoServer 的 CodeMirrorEditor 会用它拼出
    //   document.gsEditors.<id> = editor；含 '-' 会让整段 header 脚本语法错误。
    var SOURCE_TA_ID = 'vsSourceEditor';

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------
    function $(id) { return document.getElementById(id); }

    function qsa(sel, root) {
        return Array.prototype.slice.call((root || document).querySelectorAll(sel));
    }

    function esc(s) {
        if (s === null || s === undefined) { return ''; }
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function num(v, d) {
        var n = parseFloat(v);
        return isNaN(n) ? d : n;
    }

    function setStatus(text, cls) {
        var el = $('vs-status-text');
        if (!el) { return; }
        el.textContent = text || '';
        el.className = 'vs-status-text' + (cls ? ' ' + cls : '');
    }

    function normHex(v, dflt) {
        if (!v) { return dflt || '#000000'; }
        v = String(v).trim().toLowerCase();
        if (/^#[0-9a-f]{6}$/.test(v)) { return v; }
        if (/^#[0-9a-f]{3}$/.test(v)) {
            return '#' + v[1] + v[1] + v[2] + v[2] + v[3] + v[3];
        }
        if (/^#[0-9a-f]{8}$/.test(v)) { return v.substring(0, 7); }
        return dflt || '#000000';
    }

    function hexToRgb(hex) {
        var h = normHex(hex).substring(1);
        return [parseInt(h.substring(0, 2), 16), parseInt(h.substring(2, 4), 16), parseInt(h.substring(4, 6), 16)];
    }

    function rgbToHex(r, g, b) {
        function p(v) { v = Math.max(0, Math.min(255, Math.round(v))); var s = v.toString(16); return s.length === 1 ? '0' + s : s; }
        return '#' + p(r) + p(g) + p(b);
    }

    /** 在控制点列表上按 t（0..1）线性插值取色。 */
    function sampleGradient(stops, t) {
        if (!stops || !stops.length) { return '#808080'; }
        var s = stops.slice().sort(function (a, b) { return a.offset - b.offset; });
        if (t <= s[0].offset) { return normHex(s[0].color); }
        if (t >= s[s.length - 1].offset) { return normHex(s[s.length - 1].color); }
        for (var i = 0; i < s.length - 1; i++) {
            var a = s[i], b = s[i + 1];
            if (t >= a.offset && t <= b.offset) {
                var span = b.offset - a.offset;
                var k = span <= 0 ? 0 : (t - a.offset) / span;
                var ca = hexToRgb(a.color), cb = hexToRgb(b.color);
                return rgbToHex(ca[0] + (cb[0] - ca[0]) * k, ca[1] + (cb[1] - ca[1]) * k, ca[2] + (cb[2] - ca[2]) * k);
            }
        }
        return normHex(s[s.length - 1].color);
    }

    function parseStops(str) {
        var out = [];
        if (!str) { return out; }
        String(str).split(',').forEach(function (part) {
            var bits = part.split(':');
            if (bits.length < 2) { return; }
            out.push({offset: num(bits[0], 0), color: normHex(bits[1])});
        });
        return out;
    }

    function cssGradient(stops) {
        var s = (stops || []).slice().sort(function (a, b) { return a.offset - b.offset; });
        if (!s.length) { return 'linear-gradient(to right, #eee, #eee)'; }
        var parts = s.map(function (x) {
            return (Math.round(x.offset * 1000) / 10) + '% ' + normHex(x.color);
        });
        return 'linear-gradient(to right, ' + parts.join(',') + ')';
    }

    // ==================================================================
    // 1. 表单收集（前端 → 后端）
    // ==================================================================

    /**
     * 把可视化表单中的所有具名控件收集成扁平对象。
     * 约定：控件 name 形如 vs.xxx / vs.breaks.0.fill / vs.colorStops.2.color。
     * 复选框始终输出 true/false，避免「未勾选」在服务端被误判为「未提交」。
     */
    VS.collect = function (form, out) {
        out = out || {};
        if (!form) { return out; }
        qsa('[name]', form).forEach(function (el) {
            var name = el.getAttribute('name');
            if (!name || name.indexOf('vs.') !== 0) { return; }
            if (el.type === 'checkbox') {
                out[name] = el.checked ? 'true' : 'false';
            } else if (el.type === 'radio') {
                if (el.checked) { out[name] = el.value; }
            } else if (el.tagName === 'SELECT' && el.multiple) {
                out[name] = Array.prototype.slice.call(el.selectedOptions)
                    .map(function (o) { return o.value; }).join(',');
            } else {
                out[name] = el.value;
            }
        });
        // 色带控制点计数
        var rampRows = qsa('#vs-ramp .vs-ramp-row');
        if (rampRows.length) { out['vs.colorStops.count'] = String(rampRows.length); }
        // 分级条目计数
        var brkRows = qsa('#vs-breaks tbody tr');
        if (brkRows.length) { out['vs.breaks.count'] = String(brkRows.length); }
        return out;
    };

    /** 触发一次「表单 → 服务端」同步。 */
    VS.push = function () {
        if (cfg.readOnly) { return; }
        var el = $(cfg.pushId || 'vsPush');
        if (!el) { return; }
        if (pushPending) {
            // 上一轮同步还在途：不能丢弃本次变更（否则快速连续操作会丢状态，
            // 例如同步途中切换符号类型），记下来等 onState 返回后补发。
            pushQueued = true;
            return;
        }
        pushPending = true;
        setStatus('同步中…', 'vs-busy');
        el.value = String(Date.now());
        try {
            el.dispatchEvent(new Event('change', {bubbles: true}));
        } catch (e) {
            var ev = document.createEvent('HTMLEvents');
            ev.initEvent('change', true, false);
            el.dispatchEvent(ev);
        }
        // 看门狗：万一这次 change 没能变成一次 Ajax 往返（组件被替换、请求被丢弃、
        // 响应异常等），必须把 pushPending 复位。否则它一旦卡在 true，后续所有同步
        // 都会被静默丢弃，用户看到的症状就是「改什么都没反应」。
        armPushWatchdog();
    };

    function armPushWatchdog() {
        if (pushWatchdog) { clearTimeout(pushWatchdog); }
        pushWatchdog = setTimeout(function () {
            pushWatchdog = null;
            if (!pushPending) { return; }
            pushPending = false;
            // 说明：服务端在渲染海量预览图层时，同机请求可能被拖到十几秒才返回，
            // 因此这里的阈值放得比较宽（25s），避免把「慢」误报成「失败」。
            setStatus('同步较慢，已自动恢复（请重试刚才的操作）', 'vs-err');
            if (pushQueued) { pushQueued = false; pushSoon(); }
        }, 25000);
    }

    /**
     * 取原生 CodeMirror 实例。
     * 首选 GeoServer CodeMirrorEditor 自己维护的注册表 document.gsEditors.<markupId>
     * （markupId 必须是合法 JS 标识符，见 SOURCE_TEXTAREA_ID），再退回 DOM 探测。
     */
    function cmEditor() {
        try {
            var reg = document.gsEditors;
            if (reg && reg[SOURCE_TA_ID]) { return reg[SOURCE_TA_ID]; }
        } catch (e) { /* 忽略：注册表尚未建立 */ }
        var wraps = qsa('.CodeMirror');
        for (var i = 0; i < wraps.length; i++) {
            if (wraps[i].CodeMirror) { return wraps[i].CodeMirror; }
        }
        var ta = $(SOURCE_TA_ID);
        if (ta) {
            if (ta.nextSibling && ta.nextSibling.CodeMirror) { return ta.nextSibling.CodeMirror; }
            if (ta.previousSibling && ta.previousSibling.CodeMirror) { return ta.previousSibling.CodeMirror; }
        }
        return null;
    }

    /** 读取原生 CodeMirror 编辑器内容（带 textarea 兜底）。 */
    VS.editorValue = function () {
        var cm = cmEditor();
        if (cm && typeof cm.getValue === 'function') { return cm.getValue(); }
        var ta = $(SOURCE_TA_ID);
        return ta ? ta.value : null;
    };

    VS.setEditorValue = function (text) {
        var cm = cmEditor();
        if (cm && typeof cm.setValue === 'function') { cm.setValue(text || ''); return true; }
        var ta = $(SOURCE_TA_ID);
        if (ta) { ta.value = text || ''; return true; }
        return false;
    };

    /** 服务端最近一次推送的 SLD 原文。
     *  提交脚本用它判断「编辑器内容是否被用户手工改过」：两者一致就说明用户没碰过源码，
     *  此时提交不能携带 vs.rawStyle —— 否则在「表单已改、推送响应未回」的窗口期里，
     *  编辑器里还是旧 SLD，服务端会按「源码优先」拿旧内容覆盖表单参数，
     *  用户的修改被静默丢弃（现象：保存提示成功，图层与预览却毫无变化）。 */
    VS.serverSld = function () { return lastSld; };

    // ==================================================================
    // 2. 后端 → 前端
    // ==================================================================

    /**
     * 接收后端推送的完整状态。
     * @param payload {state, sld, previewSld, errors, overwriteSource, readOnly}
     */
    VS.onState = function (payload) {
        pushPending = false;
        if (pushWatchdog) { clearTimeout(pushWatchdog); pushWatchdog = null; }
        if (!payload) { return; }
        state = payload.state || {};
        lastSld = payload.sld || '';
        // 预览专用 SLD（NamedLayer 名已对齐到预览图层）；后端没给就退回原始 SLD
        lastPreviewSld = payload.previewSld || lastSld;
        lastErrors = payload.errors || [];
        sourceFormat = state.format || 'sld';

        if (payload.overwriteSource) {
            VS.setEditorValue(lastSld);
        }

        applyStateToForm(state);
        renderBreaks(state);
        renderRampEditor(state);
        renderRampPreview();
        renderLegend(state);
        renderSourceView();
        renderScaleNotes();

        if (!lastErrors.length) {
            setStatus('已同步 · ' + (state.symbolizerKind || '') + ' · ' + formatNumber((state.breaks || []).length) + ' 条规则', 'vs-ok');
        } else {
            setStatus(lastErrors.length + ' 个错误', 'vs-err');
        }
        previewDirty = true;
        VS.refreshPreview(false);
        // 同步在途期间的变更排队补发一次，保证服务端最终拿到用户最新操作
        if (pushQueued) {
            pushQueued = false;
            pushSoon();
        }
    };

    /** 图层元数据变化（切换绑定图层时后端推送）。 */
    VS.onMetadata = function (meta) {
        if (!meta) { return; }
        metadata = meta || metadata;
        refreshFieldSelects();
        fillFontList();
        fillBandSelects();
        var lbl = $('vs-preview-layer');
        if (lbl) { lbl.textContent = metadata.layer || '-'; }
        // ⚠ 切换绑定图层时必须同步 WMS 请求的 LAYERS 参数，否则预览图会一直停留在旧图层上
        // （只是左下角文字变了，图没变 —— 这是很容易漏掉的交互缺陷）。
        if (metadata.layer) {
            previewLayer = metadata.layer;
            if (previewSource) {
                var p = previewSource.getParams();
                p.LAYERS = previewLayer;
                if (previewSource.updateParams) { previewSource.updateParams(p); }
            }
        }
        if (metadata.bounds && metadata.bounds.length === 4) {
            cfg.bounds = metadata.bounds;
        }
        updatePreviewView();
    };

    /** 栅格统计（后端读取后推送）。 */
    VS.onRasterStats = function (stats) {
        if (!stats) { return; }
        var legend = $('vs-raster-hist-legend');
        if (legend) {
            if (stats.available) {
                legend.textContent = 'bands=' + stats.bandCount + '  ' + stats.width + '×' + stats.height
                    + '  min=' + fmt(stats.min) + '  max=' + fmt(stats.max)
                    + '  mean=' + fmt(stats.mean);
            } else {
                legend.textContent = stats.message || '';
            }
        }
        drawHistogram('vs-raster-hist', stats.histogram, stats.histogramMin, stats.histogramMax);
        var minEl = $('vs.stretchMin'), maxEl = $('vs.stretchMax');
        if (stats.available) {
            if (minEl) { minEl.value = String(stats.min); }
            if (maxEl) { maxEl.value = String(stats.max); }
        }
    };

    function fmt(v) {
        if (v === null || v === undefined || isNaN(v)) { return '-'; }
        var a = Math.abs(v);
        if (a !== 0 && (a < 0.001 || a > 100000)) { return Number(v).toExponential(3); }
        return String(Math.round(v * 1000) / 1000);
    }

    function formatNumber(v) { return v === undefined || v === null ? '0' : String(v); }

    // ==================================================================
    // 3. 状态 → 表单 回填
    // ==================================================================

    var TEXT_FIELDS = ['styleName', 'workspace', 'classificationField', 'classCount',
        'fillColor', 'fillOpacity', 'strokeColor', 'strokeOpacity', 'strokeWidth', 'strokeDashArray',
        'strokeLineCap', 'strokeLineJoin', 'strokeOffset', 'pointSize', 'pointRotation', 'markShape',
        'externalGraphic', 'externalGraphicFormat', 'pointOpacity', 'markFillColor', 'markFillOpacity',
        'markStrokeColor', 'markStrokeWidth', 'sizeByField', 'sizeMin', 'sizeMax',
        'labelField', 'fontFamily', 'fontSize', 'fontColor', 'fontOpacity', 'haloColor', 'haloRadius',
        'labelOffsetX', 'labelOffsetY', 'labelAnchor', 'labelSpaceAround', 'labelMinScale', 'labelMaxScale',
        'labelRotationField', 'labelGroupField',
        'grayBand', 'redBand', 'greenBand', 'blueBand', 'rasterOpacity',
        'stretchType', 'stretchNormalize', 'stretchMin', 'stretchMax', 'gamma',
        'rampPreset', 'rampIntervals', 'demPalette', 'minScale', 'maxScale'];

    var BOOL_FIELDS = ['fillEnabled', 'labelEnabled', 'fontBold', 'fontItalic', 'haloEnabled',
        'labelDisplacement', 'labelScaleEnabled', 'channelSelectionEnabled', 'stretchEnabled',
        'rampReversed', 'rampContinuous', 'scaleEnabled'];

    function applyStateToForm(s) {
        if (!s) { return; }

        qsa('input[name="vs.symbolizerKind"]').forEach(function (el) {
            el.checked = (el.value === s.symbolizerKind);
        });
        qsa('input[name="vs.rasterMode"]').forEach(function (el) {
            el.checked = (el.value === s.rasterMode);
        });
        var methodEl = $('vs.classificationMethod');
        if (methodEl) { methodEl.value = s.classificationMethod || 'single'; }

        TEXT_FIELDS.forEach(function (k) {
            var el = $('vs.' + k) || $('vs-' + k);
            if (!el) { return; }
            var v = s[k];
            if (v === undefined || v === null) { return; }
            if (el.tagName === 'SELECT') {
                var sv = String(v);
                var found = false;
                for (var i = 0; i < el.options.length; i++) {
                    if (el.options[i].value === sv) { found = true; break; }
                }
                if (!found && sv !== '') { el.value = sv; }
                el.value = sv;
            } else {
                el.value = String(v);
            }
        });

        BOOL_FIELDS.forEach(function (k) {
            var el = $('vs.' + k) || $('vs-' + k);
            if (el) { el.checked = !!s[k]; }
        });

        // 色带控件命名差异（栅格页的预设 / 反转）
        var presetRaster = $('vs-ramp-preset-raster');
        if (presetRaster && s.rampPreset) { presetRaster.value = s.rampPreset; }
        var reversedRaster = $('vs-ramp-reversed-raster');
        if (reversedRaster) { reversedRaster.checked = !!s.rampReversed; }

        syncColorPairs();
        syncRangeOutputs();
        applyKindVisibility();
        document.body.className = document.body.className.replace(/\s*vs-ro/g, '');
        var form = $(cfg.formId || 'vs-visual-form');
        if (form && s.readOnly) { form.classList.add('vs-readonly'); }
    }

    /**
     * 按符号类型显隐「矢量符号化」表单区块（仅影响展示，字段值仍全量提交）。
     *
     * <ul>
     *   <li>point：隐藏「单一符号」（点用 Graphic 的 mark 填充/描边），显示「点符号/图标」；</li>
     *   <li>line：线用描边色渲染，隐藏填充色/填充透明度/显示填充，显示描边偏移；</li>
     *   <li>polygon：显示填充与描边，隐藏描边偏移（仅 LineSymbolizer 支持）；</li>
     *   <li>raster：矢量区块全部隐藏（栅格配置在「栅格色带」标签页）。</li>
     * </ul>
     */
    function applyKindVisibility() {
        var checked = document.querySelector('input[name="vs.symbolizerKind"]:checked');
        var kind = (checked && checked.value) || (state && state.symbolizerKind) || 'polygon';
        var form = $(cfg.formId || 'vs-visual-form');
        if (!form) { return; }

        function show(sel, visible) {
            qsa(sel, form).forEach(function (el) {
                el.classList[visible ? 'remove' : 'add']('vs-hidden');
            });
        }

        var isPoint = kind === 'point';
        var isLine = kind === 'line';
        var isPolygon = kind === 'polygon';
        var isRaster = kind === 'raster';

        show('.vs-block-single', !isPoint && !isRaster);
        show('.vs-block-point', isPoint);
        show('.vs-block-classify', !isRaster);
        show('.vs-only-fill', isPolygon);
        show('.vs-only-line', isLine);
    }

    /** color 输入与 hex 文本框双向同步。 */
    function syncColorPairs() {
        qsa('.vs-color[data-target]').forEach(function (picker) {
            var text = $(picker.getAttribute('data-target'));
            if (!text) { return; }
            var hex = normHex(text.value, picker.value);
            picker.value = hex;
            text.value = hex;
        });
    }

    /** range 输入与 output 显示同步。 */
    function syncRangeOutputs() {
        qsa('.vs-range').forEach(function (r) {
            var out = document.querySelector('.vs-out[data-for="' + r.getAttribute('name') + '"]');
            if (out) { out.textContent = r.value; }
        });
    }

    // ==================================================================
    // 4. 分级 / 分类条目表格
    // ==================================================================

    function renderBreaks(s) {
        var host = $('vs-breaks');
        if (!host) { return; }
        var breaks = (s && s.breaks) ? s.breaks : [];
        var unique = s && s.classificationMethod === 'unique';
        var kind = (s && s.symbolizerKind) || 'polygon';

        if (!breaks.length) {
            host.innerHTML = '<div class="vs-empty">尚未生成分类 / 分级结果。'
                + '选择分类方式后点击「枚举唯一值并配色」或「计算分级断点」。</div>';
            return;
        }

        var html = '<table><thead><tr>';
        if (unique) {
            html += '<th>取值</th>';
        } else {
            html += '<th>下界</th><th>上界</th>';
        }
        html += '<th>' + (kind === 'line' ? '颜色' : '填充') + '</th><th>不透明度</th><th>描边宽</th>';
        if (kind === 'point') { html += '<th>大小</th>'; }
        html += '<th>图例标签</th><th></th></tr></thead><tbody>';

        breaks.forEach(function (b, i) {
            html += '<tr data-idx="' + i + '">';
            if (unique) {
                html += '<td><input type="text" name="vs.breaks.' + i + '.value" value="' + esc(b.value || '') + '"/></td>';
            } else {
                html += '<td><input type="number" step="any" name="vs.breaks.' + i + '.min" value="' + num(b.min, 0) + '"/></td>';
                html += '<td><input type="number" step="any" name="vs.breaks.' + i + '.max" value="' + num(b.max, 0) + '"/></td>';
            }
            html += '<td><input type="color" name="vs.breaks.' + i + '.fill" value="' + normHex(b.fill, '#3d7ebb') + '"/></td>';
            html += '<td><input type="number" step="0.05" min="0" max="1" name="vs.breaks.' + i + '.fillOpacity" value="' + num(b.fillOpacity, 1) + '"/></td>';
            html += '<td><input type="number" step="0.1" min="0" name="vs.breaks.' + i + '.strokeWidth" value="' + num(b.strokeWidth, 1) + '"/></td>';
            if (kind === 'point') {
                html += '<td><input type="number" step="1" min="1" name="vs.breaks.' + i + '.size" value="' + num(b.size, 8) + '"/></td>';
            }
            html += '<td><input type="text" name="vs.breaks.' + i + '.label" value="' + esc(b.label || '') + '"/></td>';
            html += '<td><button type="button" class="vs-x" data-del="' + i + '" title="删除该分类">✕</button></td>';
            html += '</tr>';
        });
        html += '</tbody></table>';
        host.innerHTML = html;
    }

    // ==================================================================
    // 5. 图例渲染
    // ==================================================================

    function renderLegend(s) {
        var html = buildLegendHtml(s);
        ['vs-legend', 'vs-legend-mini'].forEach(function (id) {
            var el = $(id);
            if (el) { el.innerHTML = html; }
        });
    }

    function buildLegendHtml(s) {
        if (!s) { return '<div class="vs-empty-note">无数据</div>'; }
        var breaks = s.breaks || [];
        var kind = s.symbolizerKind || 'polygon';
        var isRaster = kind === 'raster';
        var title = esc(s.styleName || '(未命名)');
        var sb = '<div class="vs-legend-title">' + title + '</div>';

        if (isRaster) {
            var stops = currentRampStops(s);
            sb += '<div class="vs-legend-ramp">';
            sb += '<span>' + fmt(s.stretchMin) + '</span>';
            sb += '<span class="vs-legend-ramp-bar" style="background:' + cssGradient(stops) + '"></span>';
            sb += '<span>' + fmt(s.stretchMax) + '</span>';
            sb += '</div>';
            sb += '<div class="vs-legend-ticks"><span>'
                + esc(s.rasterMode || 'gray') + '</span><span>'
                + esc(s.rampPreset || '') + '</span></div>';
            return sb;
        }

        // ⚠ 线符号的颜色语义与面/点不同：LineSymbolizer 没有填充，服务端
        // （StyleCompiler.buildLine）取的是 strokeColor（分级时取 brk.fill）。
        // 图例若仍按 fillColor 画线，改描边色后色块永远是旧色（用户看到「图例不更新」）。
        var singleColor = (kind === 'line') ? s.strokeColor : s.fillColor;

        if (!breaks.length) {
            sb += swatchLine(kind, singleColor, s.fillOpacity, s.strokeColor, s.strokeWidth,
                s.pointSize, s.markShape, s.strokeDashArray, '—');
            return sb;
        }
        breaks.forEach(function (b) {
            var label = b.label || b.value || (fmt(b.min) + ' ~ ' + fmt(b.max));
            var color = b.fill || singleColor;
            sb += swatchLine(kind, color, b.fillOpacity, b.stroke || s.strokeColor,
                b.strokeWidth, b.size || s.pointSize, s.markShape, s.strokeDashArray, label);
        });
        return sb;
    }

    /** 用 SVG 画一个小符号作为图例色块（点/线/面三种形态）。 */
    function swatchLine(kind, fill, fillOpacity, stroke, width, size, markShape, dash, label) {
        var f = normHex(fill, '#3d7ebb');
        var st = normHex(stroke, '#1f4e79');
        var op = num(fillOpacity, 1);
        var w = Math.max(0.6, num(width, 1));
        var svg;

        if (kind === 'point') {
            var r = Math.max(3, Math.min(9, num(size, 8) / 2 + 1));
            svg = '<svg width="22" height="14" viewBox="0 0 22 14">' + markPath(markShape, f, st, r) + '</svg>';
        } else if (kind === 'line') {
            svg = '<svg width="22" height="14" viewBox="0 0 22 14">'
                + '<line x1="1" y1="11" x2="21" y2="3" stroke="' + f + '" stroke-width="'
                + Math.min(6, w + 1.2) + '" stroke-linecap="round"'
                + (dash ? ' stroke-dasharray="' + esc(dash) + '"' : '') + '/></svg>';
        } else {
            svg = '<svg width="22" height="14" viewBox="0 0 22 14">'
                + '<rect x="1.5" y="1.5" width="19" height="11" rx="1.5" fill="' + f + '" fill-opacity="' + op
                + '" stroke="' + st + '" stroke-width="' + Math.min(3, w) + '"/></svg>';
        }
        return '<div class="vs-legend-item"><span class="vs-legend-swatch">' + svg
            + '</span><span>' + esc(label) + '</span></div>';
    }

    function markPath(shape, fill, stroke, r) {
        var cx = 11, cy = 7;
        var attrs = ' fill="' + fill + '" stroke="' + stroke + '" stroke-width="1"';
        switch (shape) {
            case 'square':
                return '<rect x="' + (cx - r) + '" y="' + (cy - r) + '" width="' + (2 * r)
                    + '" height="' + (2 * r) + '"' + attrs + '/>';
            case 'triangle':
                return '<polygon points="' + cx + ',' + (cy - r) + ' ' + (cx + r) + ',' + (cy + r) + ' '
                    + (cx - r) + ',' + (cy + r) + '"' + attrs + '/>';
            case 'star':
                return '<polygon points="' + starPoints(cx, cy, r, r * 0.45, 5) + '"' + attrs + '/>';
            case 'cross':
                return '<path d="M' + (cx - r) + ',' + cy + ' H' + (cx + r) + ' M' + cx + ',' + (cy - r)
                    + ' V' + (cy + r) + '" stroke="' + fill + '" stroke-width="2"/>';
            case 'x':
                return '<path d="M' + (cx - r) + ',' + (cy - r) + ' L' + (cx + r) + ',' + (cy + r)
                    + ' M' + (cx + r) + ',' + (cy - r) + ' L' + (cx - r) + ',' + (cy + r)
                    + '" stroke="' + fill + '" stroke-width="2"/>';
            default:
                return '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '"' + attrs + '/>';
        }
    }

    function starPoints(cx, cy, rOuter, rInner, n) {
        var pts = [];
        for (var i = 0; i < n * 2; i++) {
            var r = (i % 2 === 0) ? rOuter : rInner;
            var a = -Math.PI / 2 + i * Math.PI / n;
            pts.push((cx + r * Math.cos(a)).toFixed(2) + ',' + (cy + r * Math.sin(a)).toFixed(2));
        }
        return pts.join(' ');
    }

    function currentRampStops(s) {
        if (s.colorStops && s.colorStops.length) {
            return s.colorStops.map(function (x) { return {offset: num(x.offset, 0), color: x.color}; });
        }
        var presetId = (s.rasterMode === 'dem' ? s.demPalette : s.rampPreset) || 'viridis';
        var def = rampById[presetId] || rampById.viridis;
        var stops = def ? parseStops(def.stops) : [{offset: 0, color: '#000000'}, {offset: 1, color: '#ffffff'}];
        if (s.rampReversed) {
            stops = stops.map(function (x) { return {offset: 1 - x.offset, color: x.color}; });
            stops.sort(function (a, b) { return a.offset - b.offset; });
        }
        return stops;
    }

    // ==================================================================
    // 6. 底部源码视图（只读 + 语法高亮 + 错误行标记）
    // ==================================================================

    function renderSourceView() {
        var host = $('vs-code-lines');
        if (!host) { return; }
        var text = (sourceFormat === 'css') ? lastSld : lastSld;
        if (sourceFormat === 'css' && lastSld && lastSld.indexOf('<') === 0) {
            text = '/* 当前样式为 SLD 格式；GeoCSS 视图需要安装 GeoServer 的 CSS 扩展（gs-css-plugin）后可用。 */\n'
                + lastSld;
        }
        var lines = String(text || '').split('\n');
        errorLines = {};
        lastErrors.forEach(function (e) {
            if (e && e.line && e.line > 0) { errorLines[e.line] = e.message; }
        });

        var html = '';
        for (var i = 0; i < lines.length; i++) {
            var no = i + 1;
            var cls = errorLines[no] ? ' class="vs-line-err" title="' + esc(errorLines[no]) + '"' : '';
            html += '<li' + cls + '><span>' + highlight(lines[i], sourceFormat) + '</span></li>';
        }
        host.innerHTML = html || '<li><span class="vs-empty-note">（空）</span></li>';

        var errBox = $('vs-err-count');
        if (errBox) {
            if (lastErrors.length) {
                errBox.className = 'vs-err-count';
                errBox.textContent = '✕ ' + lastErrors.length + ' 个错误';
                errBox.title = lastErrors.map(function (e) { return e.message; }).join('\n');
            } else {
                errBox.className = 'vs-err-count vs-ok';
                errBox.textContent = '✓ 语法校验通过';
                errBox.title = '';
            }
        }
        var ph = $('vs-source-placeholder');
        if (ph) {
            ph.textContent = lastErrors.length
                ? ('定位：' + lastErrors.map(function (e) {
                    return '第 ' + (e.line > 0 ? e.line : '?') + ' 行 ' + e.message;
                }).join('；'))
                : (String(text || '').split('\n').length + ' 行 · ' + (text || '').length + ' 字符');
        }
    }

    /** 极简语法高亮：只做 XML / CSS 的词法着色，避免引入额外前端依赖。 */
    function highlight(line, format) {
        var s = esc(line);
        if (format === 'css') {
            s = s.replace(/(\/\*[\s\S]*?\*\/)/g, '<span class="vs-tag-x">$1</span>');
            s = s.replace(/^(\s*)([A-Za-z-]+)(\s*:)/, '$1<span class="vs-tag-k">$2</span>$3');
            s = s.replace(/(#[0-9a-fA-F]{3,8})/g, '<span class="vs-tag-v">$1</span>');
            s = s.replace(/(\b\d+(\.\d+)?(px|pt|em|%)?\b)/g, '<span class="vs-tag-v">$1</span>');
            return s;
        }
        // XML
        s = s.replace(/(&lt;!--[\s\S]*?--&gt;)/g, '<span class="vs-tag-x">$1</span>');
        s = s.replace(/(&lt;\/?)([A-Za-z_][\w.:-]*)/g, '$1<span class="vs-tag-c">$2</span>');
        s = s.replace(/([A-Za-z_][\w.:-]*)(=)(&quot;)/g, '<span class="vs-tag-a">$1</span>$2$3');
        s = s.replace(/(&quot;)([^&]*?)(&quot;)/g, function (m, a, b, c) {
            if (b.indexOf('<span') >= 0) { return m; }
            return a + '<span class="vs-tag-v">' + b + '</span>' + c;
        });
        return s;
    }

    // ==================================================================
    // 7. 色带编辑器
    // ==================================================================

    function renderRampPreview() {
        var el = $('vs-ramp-preview');
        if (!el || !state) { return; }
        el.style.background = cssGradient(currentRampStops(state));
    }

    function renderRampEditor(s) {
        var host = $('vs-ramp');
        if (!host) { return; }
        var stops = (s && s.colorStops && s.colorStops.length) ? s.colorStops : currentRampStops(s);

        var html = '<div class="vs-ramp-bar" id="vs-ramp-bar" style="background:'
            + cssGradient(stops) + '">';
        stops.forEach(function (x, i) {
            html += '<span class="vs-ramp-handle" data-idx="' + i + '" style="left:'
                + (num(x.offset, 0) * 100) + '%;background:' + normHex(x.color) + '"></span>';
        });
        html += '</div><div class="vs-ramp-rows" id="vs-ramp-rows">';
        stops.forEach(function (x, i) {
            html += '<div class="vs-ramp-row">'
                + '<input type="color" data-idx="' + i + '" data-role="color" value="' + normHex(x.color) + '"/>'
                + '<input type="number" class="vs-input" min="0" max="100" step="0.5" data-idx="' + i
                + '" data-role="offset" value="' + Math.round(num(x.offset, 0) * 1000) / 10 + '"/>'
                + '<span class="vs-note">%</span>'
                + '<button type="button" class="vs-mini-btn" data-del="' + i + '">删除</button>'
                + '</div>';
        });
        html += '</div>';
        host.innerHTML = html;
        bindRampEvents();
    }

    /** 把当前色带编辑器的内容写回隐藏字段（提交时由 collect 读取）。 */
    function collectRampInto(stateObj) {
        var rows = qsa('#vs-ramp-rows .vs-ramp-row');
        if (!rows.length) { return; }
        var stops = [];
        rows.forEach(function (row) {
            var color = row.querySelector('[data-role="color"]').value;
            var offset = num(row.querySelector('[data-role="offset"]').value, 0) / 100;
            stops.push({offset: Math.max(0, Math.min(1, offset)), color: normHex(color)});
        });
        stops.sort(function (a, b) { return a.offset - b.offset; });
        stateObj.colorStops = stops;
    }

    function bindRampEvents() {
        var bar = $('vs-ramp-bar');
        if (!bar) { return; }

        // 拖拽手柄调整位置
        qsa('.vs-ramp-handle', bar).forEach(function (h) {
            h.addEventListener('mousedown', function (ev) {
                ev.preventDefault();
                var handle = h;
                handle.classList.add('vs-drag');
                var rect = bar.getBoundingClientRect();
                function move(e) {
                    var k = (e.clientX - rect.left) / rect.width;
                    k = Math.max(0, Math.min(1, k));
                    handle.style.left = (k * 100) + '%';
                    var idx = handle.getAttribute('data-idx');
                    var row = qsa('#vs-ramp-rows .vs-ramp-row')[parseInt(idx, 10)];
                    if (row) { row.querySelector('[data-role="offset"]').value = String(Math.round(k * 1000) / 10); }
                    rebuildRampBar();
                }
                function up() {
                    document.removeEventListener('mousemove', move);
                    document.removeEventListener('mouseup', up);
                    handle.classList.remove('vs-drag');
                    pushSoon();
                }
                document.addEventListener('mousemove', move);
                document.addEventListener('mouseup', up);
            });
        });

        // 点击渐变条空白处添加控制点
        bar.addEventListener('dblclick', function (ev) {
            var rect = bar.getBoundingClientRect();
            var k = Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width));
            var rows = qsa('#vs-ramp-rows .vs-ramp-row');
            var rowsHost = $('vs-ramp-rows');
            if (!rowsHost) { return; }
            rowsHost.insertAdjacentHTML('beforeend', '<div class="vs-ramp-row">'
                + '<input type="color" data-role="color" value="' + sampleGradient(readRampStops(), k) + '"/>'
                + '<input type="number" class="vs-input" min="0" max="100" step="0.5" data-role="offset" value="'
                + Math.round(k * 1000) / 10 + '"/>'
                + '<span class="vs-note">%</span>'
                + '<button type="button" class="vs-mini-btn" data-del="-1">删除</button></div>');
            rebuildRampBar();
            pushSoon();
        });

        // 颜色 / 位置变化
        qsa('#vs-ramp-rows input').forEach(function (inp) {
            inp.addEventListener('input', function () { rebuildRampBar(); });
            inp.addEventListener('change', function () { pushSoon(); });
        });

        qsa('#vs-ramp-rows [data-del]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var row = btn.closest('.vs-ramp-row');
                if (row) { row.parentNode.removeChild(row); }
                rebuildRampBar();
                pushSoon();
            });
        });
    }

    function readRampStops() {
        var out = [];
        qsa('#vs-ramp-rows .vs-ramp-row').forEach(function (row) {
            out.push({
                offset: Math.max(0, Math.min(1, num(row.querySelector('[data-role="offset"]').value, 0) / 100)),
                color: normHex(row.querySelector('[data-role="color"]').value)
            });
        });
        out.sort(function (a, b) { return a.offset - b.offset; });
        return out;
    }

    function rebuildRampBar() {
        var bar = $('vs-ramp-bar');
        if (!bar) { return; }
        var stops = readRampStops();
        bar.style.background = cssGradient(stops);
        bar.innerHTML = '';
        stops.forEach(function (x, i) {
            var s = document.createElement('span');
            s.className = 'vs-ramp-handle';
            s.setAttribute('data-idx', String(i));
            s.style.left = (x.offset * 100) + '%';
            s.style.background = x.color;
            bar.appendChild(s);
        });
        bindHandlesOnly();
    }

    function bindHandlesOnly() {
        var bar = $('vs-ramp-bar');
        if (!bar) { return; }
        qsa('.vs-ramp-handle', bar).forEach(function (h) {
            h.addEventListener('mousedown', function (ev) {
                ev.preventDefault();
                var handle = h;
                handle.classList.add('vs-drag');
                var rect = bar.getBoundingClientRect();
                function move(e) {
                    var k = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                    handle.style.left = (k * 100) + '%';
                    var rows = qsa('#vs-ramp-rows .vs-ramp-row');
                    var row = rows[parseInt(handle.getAttribute('data-idx'), 10)];
                    if (row) { row.querySelector('[data-role="offset"]').value = String(Math.round(k * 1000) / 10); }
                    syncBarGradient();
                }
                function up() {
                    document.removeEventListener('mousemove', move);
                    document.removeEventListener('mouseup', up);
                    handle.classList.remove('vs-drag');
                    pushSoon();
                }
                document.addEventListener('mousemove', move);
                document.addEventListener('mouseup', up);
            });
        });
    }

    function syncBarGradient() {
        var bar = $('vs-ramp-bar');
        if (bar) { bar.style.background = cssGradient(readRampStops()); }
    }

    // ==================================================================
    // 8. 直方图
    // ==================================================================

    function drawHistogram(canvasId, hist, min, max) {
        var cv = $(canvasId);
        if (!cv || !cv.getContext) { return; }
        var ctx = cv.getContext('2d');
        var w = cv.width, h = cv.height;
        ctx.clearRect(0, 0, w, h);
        if (!hist || !hist.length) {
            ctx.fillStyle = 'rgba(128,128,128,.35)';
            ctx.font = '11px sans-serif';
            ctx.fillText('无直方图数据', 8, h / 2);
            return;
        }
        var style = window.getComputedStyle(document.documentElement);
        var accent = (style.getPropertyValue('--gs-primary') || '#2c6ea8').trim() || '#2c6ea8';
        var maxCount = 0;
        for (var i = 0; i < hist.length; i++) { maxCount = Math.max(maxCount, hist[i]); }
        if (maxCount <= 0) { maxCount = 1; }
        var bw = w / hist.length;
        for (var j = 0; j < hist.length; j++) {
            var bh = (hist[j] / maxCount) * (h - 14);
            ctx.fillStyle = accent;
            ctx.globalAlpha = 0.75;
            ctx.fillRect(j * bw, h - 12 - bh, Math.max(1, bw - 1), bh);
        }
        ctx.globalAlpha = 1;
        ctx.fillStyle = 'rgba(128,128,128,.9)';
        ctx.font = '10px monospace';
        ctx.fillText(fmt(min), 2, h - 2);
        var t2 = fmt(max);
        ctx.fillText(t2, w - ctx.measureText(t2).width - 2, h - 2);
    }

    function drawNavigationHistogram() {
        var raw = document.querySelector('.vs-hist .vs-hidden');
        if (!raw) { return; }
        var hist;
        try { hist = JSON.parse(raw.textContent || '[]'); } catch (e) { hist = []; }
        drawHistogram('vs-hist-canvas', hist, 0, 0);
    }

    // ==================================================================
    // 9. OpenLayers 预览
    // ==================================================================

    /**
     * 预览初始化入口（由 PreviewPanel 的 OnDomReady 调用）。
     *
     * <p>若 {@link #init} 还没跑（两个 OnDomReady 条目顺序不定），先把参数暂存起来，
     * 等 init 里 cfg/lastPreviewSld 就绪后再真正建图。
     */
    VS.initPreview = function (init) {
        pendingPreviewInit = init || {};
        if (initialised) {
            var p = pendingPreviewInit;
            pendingPreviewInit = null;
            buildPreview(p);
        }
    };

    /** 预览初始化参数暂存（见 VS.initPreview 注释）。 */
    var pendingPreviewInit = null;

    function buildPreview(init) {
        if (!window.ol) { return; }
        if (previewSource) { return; }   // 已经建过图就不再重复建（防止重复下发初始化脚本）
        var el = $(init.containerId || 'vs-map');
        if (!el) { return; }
        cfg.wmsUrl = init.wmsUrl || cfg.wmsUrl;
        cfg.bounds = init.bounds || cfg.bounds;
        previewLayer = init.layer || cfg.layer;

        var view = new ol.View({
            projection: 'EPSG:4326',
            center: [0, 0],
            zoom: 2
        });
        previewView = view;

        // ⚠ 首次 GetMap 必须带样式：如果先建 map 再调 updateParams(SLD_BODY)，
        // OpenLayers 会立刻用「图层默认样式」发一次 GetMap，随后被带 SLD_BODY 的请求覆盖。
        // 对 OSM 这类海量图层，那次多余的默认样式渲染可能长达数十秒并拖垮整个服务端
        // （同一时刻的表单同步响应会被它挤到几十秒），必须在一开始就把 SLD_BODY 放进 params。
        var initialParams = {
            SERVICE: 'WMS', VERSION: '1.3.0', REQUEST: 'GetMap',
            LAYERS: previewLayer, STYLES: '', FORMAT: 'image/png',
            TRANSPARENT: true, TILED: false, SLD_VERSION: '1.1.0'
        };
        if (lastPreviewSld && lastPreviewSld.length) {
            initialParams.SLD_BODY = lastPreviewSld;
            lastPreviewedSld = lastPreviewSld;
        }

        previewSource = new ol.source.ImageWMS({
            url: cfg.wmsUrl,
            params: initialParams,
            ratio: 1,
            serverType: 'geoserver',
            crossOrigin: 'anonymous'
        });

        // 关键 1：把 GetMap 的 KVP 查询串改写为 POST，彻底规避 SLD_BODY 的 URL 长度限制。
        // 关键 2：GeoServer 3 的 CSP 是 img-src 'self' data:，blob: 会被拦截，
        //        所以用 FileReader 把响应转成 data: URL 再喂给 <img>。
        //
        // ⚠ 关键 3（并发闸门）：同屏只允许一个 GetMap 在途。
        // 预览图层可能是 OSM 级别的海量数据，单次渲染动辄数十秒；如果每次表单变更都直接发一次，
        // 请求会不断堆积并占满服务端 CPU，把同一时刻的「表单同步」AJAX 挤到几十秒之外
        // （现象：状态栏一直「同步中…」、组件像死掉一样）。这里改为「只保留最新一次」：
        // 在途时新请求只记录，等在途结束后再补发最新的一次。
        previewSource.setImageLoadFunction(function (image, src) {
            if (previewInFlight) {
                previewPendingReq = {image: image, src: src};
                return;
            }
            startPreviewFetch(image, src);
        });

        function startPreviewFetch(image, src) {
            var q = src.indexOf('?');
            var base = q >= 0 ? src.substring(0, q) : src;
            var body = q >= 0 ? src.substring(q + 1) : '';
            var target = image.getImage();
            previewInFlight = true;
            previewStartedAt = Date.now();
            previewCtl = (typeof AbortController === 'function') ? new AbortController() : null;
            // 在途太久（服务端在渲染海量图层）时主动放弃等待，让最新的一次有机会发出去，
            // 否则用户看到的就是「画了半分钟还在转圈」。
            if (previewStaleTimer) { clearTimeout(previewStaleTimer); }
            previewStaleTimer = setTimeout(function () {
                if (previewPendingReq && previewCtl) {
                    try { previewCtl.abort(); } catch (e) { /* 忽略 */ }
                }
            }, PREVIEW_STALE_MS);

            fetch(base, {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: body,
                credentials: 'same-origin',
                signal: previewCtl ? previewCtl.signal : undefined
            }).then(function (resp) {
                if (!resp.ok) { throw new Error('HTTP ' + resp.status); }
                return resp.blob();
            }).then(function (blob) {
                var reader = new FileReader();
                reader.onload = function () {
                    target.onload = function () { showMask(false); };
                    target.onerror = function () {
                        showMask(true, (cfg.labels && cfg.labels.previewError) || '预览图像解码失败');
                    };
                    target.src = reader.result; // data:image/png;base64,...
                };
                reader.onerror = function () {
                    showMask(true, (cfg.labels && cfg.labels.previewError) || '预览读取失败');
                };
                reader.readAsDataURL(blob);
                finishPreviewFetch();
            }).catch(function (e) {
                // 主动 abort 不算错误（上层马上会补发最新的一次）
                if (!e || e.name !== 'AbortError') {
                    showMask(true, (cfg.labels && cfg.labels.previewError) || '预览加载失败');
                }
                finishPreviewFetch();
            });
        }

        function finishPreviewFetch() {
            if (previewStaleTimer) { clearTimeout(previewStaleTimer); previewStaleTimer = null; }
            previewInFlight = false;
            previewCtl = null;
            if (previewPendingReq) {
                var next = previewPendingReq;
                previewPendingReq = null;
                startPreviewFetch(next.image, next.src);
            }
        }

        // 注意：ol 图层对象必须存到独立变量，绝不能覆盖 previewLayer（字符串图层名），
        // 否则 applySldToSource 会把对象写进 WMS 的 LAYERS 参数导致渲染失败。
        var wmsLayer = new ol.layer.Image({source: previewSource, opacity: 1});

        var map = new ol.Map({
            target: el,
            layers: [wmsLayer],
            view: view,
            controls: ol.control.defaults.defaults ? ol.control.defaults.defaults({attribution: false})
                : ol.control.defaults({attribution: false})
        });
        cfg.map = map;

        map.on('moveend', function () {
            updateScaleBadge(map);
        });

        updatePreviewView();
        updateScaleBadge(map);
        showMask(false);
    };

    /** 在途的预览 GetMap 控制（并发闸门，见 setImageLoadFunction 注释）。 */
    var previewInFlight = false;
    var previewPendingReq = null;
    var previewCtl = null;
    var previewStartedAt = 0;
    var previewStaleTimer = null;
    /** 单次预览等待上限：超过就放弃等待（若已有更新的请求在排队）。 */
    var PREVIEW_STALE_MS = 12000;

    function updateScaleBadge(map) {
        var el = $('vs-map-scale');
        if (!el || !map) { return; }
        var view = map.getView();
        var res = view.getResolution();
        if (!res) { el.textContent = '-'; return; }
        // 屏幕像素 ≈ 0.00028 m；比例尺分母 = res(m/px) / 0.00028
        // res 单位为度，需按当前纬度换算为米
        var center = ol.proj.toLonLat(view.getCenter());
        var mPerDeg = 111319.49079327358 * Math.cos(center[1] * Math.PI / 180);
        var denom = Math.abs(res) * mPerDeg / 0.00028;
        var txt = '1:' + Math.round(denom).toLocaleString();
        el.textContent = txt;
        var minEl = $('vs.minScale'), maxEl = $('vs.maxScale');
        if (minEl && maxEl) {
            var min = num(minEl.value, 0), max = num(maxEl.value, 0);
            var visible = (min <= 0 || denom >= min) && (max <= 0 || denom <= max);
            el.textContent = txt + (visible ? '  ✓可见' : '  ✕超出比例尺范围');
        }
    }

    function updatePreviewView() {
        var map = cfg.map;
        if (!map) { return; }
        var b = cfg.bounds || [-180, -90, 180, 90];
        if (!b || b.length !== 4 || !isFinite(b[0])) { return; }
        try {
            var extent = ol.proj.transformExtent(b, 'EPSG:4326', 'EPSG:4326');
            var view = map.getView();
            // GeoServer 自带的是老版 OL3：fit 的第二参数是 size 而非选项对象；
            // 且没有 setMinZoom。全部做特性探测 + 回退，绝不能让这里抛异常
            // 中断 VS.init 的后续初始化（表单事件绑定 / 显隐 / 状态栏）。
            var opts = {padding: [12, 12, 12, 12], maxZoom: 14};
            var isOl3 = !ol.VERSION || String(ol.VERSION).indexOf('3.') === 0;
            if (isOl3) {
                view.fit(extent, map.getSize() || map.getTargetElement(), opts);
            } else {
                view.fit(extent, opts);
            }
            if (typeof view.setMinZoom === 'function') { view.setMinZoom(0); }
        } catch (e) {
            if (window.console && console.warn) { console.warn('预览视图适配失败（不影响其它功能）:', e); }
        }
    }

    function applySldToSource() {
        if (!previewSource) { return; }
        var params = previewSource.getParams();
        params.LAYERS = previewLayer || '';
        if (lastPreviewSld && lastPreviewSld.length) {
            params.SLD_BODY = lastPreviewSld;
            lastPreviewedSld = lastPreviewSld;
            lastPreviewedLayer = previewLayer || '';
            if (previewSource.updateParams) { previewSource.updateParams(params); }
            if (previewSource.refresh) { previewSource.refresh(); }
        }
    }

    /** 刷新预览。force=true 时强制重新 fit 视图并忽略「样式未变」的短路。 */
    VS.refreshPreview = function (force) {
        if (!previewSource) { return; }
        if (force) { updatePreviewView(); }
        // 样式与图层都没变就不重发 GetMap：海量图层的单次渲染代价极高，不能做无意义的重复渲染
        if (!force && lastPreviewedSld === lastPreviewSld
            && lastPreviewedLayer === (previewLayer || '')) {
            showMask(false);
            previewDirty = false;
            return;
        }
        showMask(true, '正在渲染…');
        applySldToSource();
        previewDirty = false;
    };

    VS.resetView = function () { updatePreviewView(); };

    VS.toggleBaseMap = function () {
        if (!cfg.map) { return; }
        if (baseMapOn) {
            cfg.map.removeLayer(baseMapLayer);
            baseMapLayer = null;
            baseMapOn = false;
            return;
        }
        // 底图优先使用 GeoServer 自带的 OpenStreetMap 切片（若未配置则用简单的全球占位）
        baseMapLayer = new ol.layer.Tile({
            source: new ol.source.OSM({crossOrigin: 'anonymous'}),
            opacity: 0.85
        });
        cfg.map.getLayers().insertAt(0, baseMapLayer);
        baseMapOn = true;
    };

    function showMask(show, text) {
        var m = $('vs-map-mask');
        if (!m) { return; }
        var t = $('vs-map-mask-text');
        if (text && t) { t.textContent = text; }
        if (show) { m.classList.remove('vs-hidden-mask'); } else { m.classList.add('vs-hidden-mask'); }
    }

    // ==================================================================
    // 10. 渲染辅助
    // ==================================================================

    /**
     * 统一补填「字段下拉 / 字段插入器」。
     *
     * ⚠ 这些控件分布在懒渲染的页签面板里（矢量符号化、标注）：onMetadata 下发时
     * 非激活页签的 DOM 还不存在，fillFieldSelect 拿不到元素直接 return，
     * 等用户切到该页签（面板由 Wicket 后渲染）时选项仍然是空的
     * （现象：标注页签「插入字段…」下拉一个选项都没有，用户选不到字段，
     *   也就配不出标注 —— 被感知为「配置标注不生效」）。
     * 因此除了 onMetadata 之外，面板首次出现时（enhanceDynamicPanels）必须再补一次。
     */
    function refreshFieldSelects() {
        var fields = (metadata && metadata.fields) ? metadata.fields : [];
        if (!fields.length) { return; }
        fillFieldSelect('vs.classificationField', fields, false, true);
        fillFieldSelect('vs.sizeByField', fields, true, true);
        fillFieldSelect('vs-label-field-picker', fields, true, true);
    }

    /**
     * 字体族候选列表（服务端下发 JVM 实际可用的字体族名）。
     *
     * ⚠ 写一个环境里不存在的字体名时，渲染器回退到默认字体，中文会画成空心方块
     * （观感像「乱码」，实际是缺字形）。因此列表要用服务端的真实字体，
     * 且它位于懒渲染的标注页签，面板出现时要补填。
     */
    function fillFontList() {
        var dl = $('vs-font-list');
        if (!dl) { return; }
        var fonts = (metadata && metadata.fonts) ? metadata.fonts : [];
        if (!fonts.length) { return; }
        dl.innerHTML = fonts.map(function (f) {
            return '<option value="' + esc(f) + '"></option>';
        }).join('');
    }

    function fillFieldSelect(id, fields, withEmpty, preserveValue) {
        var el = $(id);
        if (!el) { return; }
        var prev = preserveValue ? el.value : '';
        var isPicker = id.indexOf('picker') >= 0;
        if (isPicker) {
            // 字段插入选择器：选项点击后填入标注字段输入框
            el.innerHTML = '<option value="">插入字段…</option>' + fields.map(function (f) {
                return '<option value="' + esc(f.name) + '">' + esc(f.label || f.name) + '</option>';
            }).join('');
            return;
        }
        var isRampField = el.getAttribute('name') === 'vs.classificationField';
        var filter = isRampField ? fields.filter(function (f) { return true; }) : fields;
        var html = withEmpty ? '<option value=""></option>' : '';
        html += filter.map(function (f) {
            var suffix = f.numeric ? '' : '  (非数值)';
            return '<option value="' + esc(f.name) + '">' + esc(f.name) + suffix + '</option>';
        }).join('');
        el.innerHTML = html;
        if (prev) { el.value = prev; }
    }

    function fillBandSelects() {
        var n = metadata.bands && metadata.bands > 0 ? metadata.bands : 4;
        ['vs.grayBand', 'vs.redBand', 'vs.greenBand', 'vs.blueBand'].forEach(function (id) {
            var el = $(id);
            if (!el) { return; }
            var prev = el.value;
            var html = '';
            for (var i = 1; i <= Math.max(n, 4); i++) {
                html += '<option value="' + i + '">' + i + '</option>';
            }
            el.innerHTML = html;
            if (prev) { el.value = prev; }
        });
    }

    function fillRampSelects() {
        ['vs.rampPreset', 'vs-ramp-preset-raster'].forEach(function (id) {
            var el = $(id);
            if (!el) { return; }
            var groups = {};
            ramps.forEach(function (r) {
                (groups[r.group] = groups[r.group] || []).push(r);
            });
            var html = '';
            Object.keys(groups).forEach(function (g) {
                html += '<optgroup label="' + esc(groupLabel(g)) + '">';
                groups[g].forEach(function (r) {
                    html += '<option value="' + esc(r.id) + '">' + esc(r.label) + '</option>';
                });
                html += '</optgroup>';
            });
            el.innerHTML = html;
        });
        var dem = $('vs.demPalette');
        if (dem) {
            dem.innerHTML = ramps.filter(function (r) { return r.group === 'dem'; }).map(function (r) {
                return '<option value="' + esc(r.id) + '">' + esc(r.label) + '</option>';
            }).join('');
        }
    }

    function groupLabel(g) {
        return {sequential: '顺序色带', diverging: '发散色带', perceptual: '感知均匀色带',
            dem: 'DEM 高程色阶', special: '单色 / 定性'}[g] || g;
    }

    function renderScaleNotes() {
        var min = num(state.minScale, 0), max = num(state.maxScale, 0);
        var mn = $('vs-min-scale-note'), mx = $('vs-max-scale-note');
        if (mn) { mn.textContent = min > 0 ? ('放大到 1:' + Math.round(min).toLocaleString() + ' 及更近时显示') : '不限制放近距离'; }
        if (mx) { mx.textContent = max > 0 ? ('缩小到 1:' + Math.round(max).toLocaleString() + ' 及更远时隐藏') : '不限制缩远距离'; }
        if (cfg.map) { updateScaleBadge(cfg.map); }
    }

    // ==================================================================
    // 11. 事件绑定
    // ==================================================================

    var pushTimer = null;

    /** 表单变化后稍作延迟再同步，避免拖拽滑块时请求过多。 */
    function pushSoon() {
        if (pushTimer) { clearTimeout(pushTimer); }
        pushTimer = setTimeout(function () { VS.push(); }, 180);
    }

    // ==================================================================
    // 11.1 动态面板补初始化
    //
    // Wicket 的 AjaxTabbedPanel 只渲染「当前标签页」：VS.init 执行时，标注 / 栅格色带 /
    // 比例尺控制 / 高级源码 四个面板的 DOM 尚未产生，凡是「需要往面板里写内容」的初始化
    // 都会落空（色带预设下拉、波段下拉、色带编辑器、图例、源码视图）。
    // doGenerateSource / doParseSource 还会 target.add(tabs) 整体替换标签页 DOM，同样
    // 会造成面板内容丢失。
    //
    // 这里用 MutationObserver 监听 DOM 新增，对「首次出现的面板容器」补做初始化；
    // 事件绑定本身走表单级委托（bindFormEvents），天然不受面板重建影响，无需重绑。
    // ==================================================================

    var seenEnhance = [];

    /** 该元素是否是第一次出现（幂等守卫；用数组而非 WeakSet，兼容老浏览器）。 */
    function firstSeen(el) {
        if (!el) { return false; }
        for (var i = 0; i < seenEnhance.length; i++) {
            if (seenEnhance[i] === el) { return false; }
        }
        seenEnhance.push(el);
        if (seenEnhance.length > 400) { seenEnhance.shift(); }
        return true;
    }

    function enhanceDynamicPanels() {
        try {
            // 先只做「是否出现新面板」的判定，不写任何 DOM：
            // 一旦写入 DOM 又会触发 MutationObserver，若这里无条件渲染就会死循环。
            var freshBand = firstSeen($('vs.grayBand'));
            var freshRamp = firstSeen($('vs-ramp-preset-raster'))
                | firstSeen($('vs.rampPreset')) | firstSeen($('vs.demPalette'));
            // 色带编辑器的宿主是 #vs-ramp（#vs-ramp-rows 是它内部动态生成的，不能当锚点）
            var freshRampHost = firstSeen($('vs-ramp'));
            var freshBreaks = firstSeen($('vs-breaks'));
            var freshLegend = firstSeen($('vs-legend'));
            var freshScale = firstSeen($('vs.minScale'));
            // 懒渲染页签（标注 / 矢量）里的字段控件：出现时也要补填选项
            var freshLabelPicker = firstSeen($('vs-label-field-picker'));
            var freshClassField = firstSeen($('vs.classificationField'));
            var freshFontList = firstSeen($('vs-font-list'));

            if (!freshBand && !freshRamp && !freshRampHost && !freshBreaks
                && !freshLegend && !freshScale && !freshLabelPicker && !freshClassField
                && !freshFontList) {
                return;
            }

            if (freshBand) { fillBandSelects(); }
            if (freshRamp) { fillRampSelects(); }
            // 懒渲染面板里的字段下拉/插入器在这里补填（见 refreshFieldSelects 注释）
            if (freshLabelPicker || freshClassField) { refreshFieldSelects(); }
            if (freshFontList) { fillFontList(); }

            // 新面板里的控件都是「出厂默认值」，先按当前状态回填，再重绘依赖状态的视图
            if (!pushPending) { applyStateToForm(state); }
            renderBreaks(state);
            renderRampEditor(state);
            renderRampPreview();
            renderLegend(state);
            renderSourceView();
            renderScaleNotes();
            applyKindVisibility();
            // 面板内的按钮是元素级绑定，面板重建后要补绑（bindOnce 保证不会重复绑定）
            bindPanelEvents();
        } catch (e) {
            if (window.console && console.warn) { console.warn('面板补初始化失败（不影响其它功能）:', e); }
        }
    }

    function observeDynamicPanels() {
        if (typeof MutationObserver !== 'function' || !document.body) { return; }
        var timer = null;
        var mo = new MutationObserver(function () {
            if (timer) { return; }
            timer = setTimeout(function () { timer = null; enhanceDynamicPanels(); }, 60);
        });
        mo.observe(document.body, {childList: true, subtree: true});
    }

    function bindFormEvents() {
        var form = $(cfg.formId || 'vs-visual-form');
        if (!form) { return; }

        // ⚠ 事件委托必须挂在 document 上，不能挂在 form 元素上：
        // 服务端一旦 target.add(visualForm)（图层切换、保存等会这么做），整个 <form> 会被
        // Ajax 整体替换，挂在旧 form 元素上的监听器随之消失，之后所有字段改动都不再触发同步。
        // document 在整个页面生命周期内不会被替换，因此这里挂一次即永久有效。
        function inOurForm(t) {
            var f = $(cfg.formId || 'vs-visual-form');
            return !!f && !!t && f.contains(t);
        }

        // 文本 / 下拉 / 颜色 / 复选框：change 即同步
        document.addEventListener('change', function (ev) {
            var t = ev.target;
            if (!inOurForm(t) || !t.getAttribute) { return; }
            var name = t.getAttribute('name');
            if (!name || name.indexOf('vs.') !== 0) { return; }
            if (t.type === 'number' || t.type === 'text') { return; } // 交给 input 去抖
            if (t.classList && t.classList.contains('vs-color')) { return; }
            // 切换符号类型：立即显隐对应区块
            if (name === 'vs.symbolizerKind') { applyKindVisibility(); }
            pushSoon();
        });

        // 数值 / 文本输入：去抖同步
        document.addEventListener('input', function (ev) {
            var t = ev.target;
            if (!inOurForm(t) || !t.getAttribute) { return; }

            // 拾色器同步必须在 name 校验【之前】：type=color 的拾色器是纯客户端控件，
            // 没有 name 属性（HTML 里只有 class="vs-color" + data-target），
            // 若先做 name.indexOf('vs.') 校验，事件会在到达下面的 vs-color 分支前
            // 被 return 掉 —— 用户用拾色器选色就永远不同步（hex 框不动、预览不变）。
            if (t.classList && t.classList.contains('vs-color')) {
                var target = $(t.getAttribute('data-target'));
                if (target) { target.value = t.value; }
                pushSoon();
                return;
            }

            var name = t.getAttribute('name') || '';
            // 只处理插件自己的字段；注意这里必须是「不以 vs. 开头就退出」。
            // （早期写成 if (!name.indexOf('vs.')) 是反向判断：indexOf 返回 0 时
            //  !0 === true，会把所有 vs.* 输入全部 return 掉，导致 type=text/number
            //  的字段（标注文字、比例尺等）永远同步不到服务端。）
            if (name.indexOf('vs.') !== 0) { return; }

            if (name.indexOf('fillColor') >= 0 || name.indexOf('strokeColor') >= 0
                || name.indexOf('fontColor') >= 0 || name.indexOf('haloColor') >= 0
                || name.indexOf('markFillColor') >= 0 || name.indexOf('markStrokeColor') >= 0) {
                var picker = document.querySelector('.vs-color[data-target="' + name + '"]');
                var hex = normHex(t.value, null);
                if (picker && hex) { picker.value = hex; }
            }

            // range → output
            if (t.classList && t.classList.contains('vs-range')) {
                var out = document.querySelector('.vs-out[data-for="' + name + '"]');
                if (out) { out.textContent = t.value; }
            }

            // 色带预设切换：立即刷新预览
            if (name === 'vs.rampPreset' || name === 'vs.demPalette'
                || name === 'vs.rampReversed' || name === 'vs-ramp-reversed-raster') {
                syncStateFromFormLite();
                renderRampPreview();
                renderRampEditor({colorStops: [], rampPreset: state.rampPreset,
                    demPalette: state.demPalette, rampReversed: state.rampReversed,
                    rasterMode: state.rasterMode});
                renderLegend(state);
            }

            if (name === 'vs.minScale' || name === 'vs.maxScale') { renderScaleNotes(); }
            pushSoon();
        });

        // 面板内的按钮 / 下拉走元素级绑定；这些面板可能被 Ajax 延迟渲染或整体替换，
        // 所以抽成可重复调用、按元素去重的 bindPanelEvents()，由 enhanceDynamicPanels() 补绑。
        bindPanelEvents();
    }

    /** 元素级绑定：同一元素 + 同一事件类型只绑一次，可安全重复调用。 */
    function bindOnce(el, type, fn) {
        if (!el || !el.getAttribute) { return; }
        var flag = 'data-vs-bound-' + type;
        if (el.getAttribute(flag) === '1') { return; }
        el.setAttribute(flag, '1');
        el.addEventListener(type, fn);
    }

    /**
     * 面板内的元素级绑定。
     *
     * <p>栅格色带 / 比例尺控制 / 标注 / 高级源码这四个面板由 {@code AjaxTabbedPanel}
     * 按需渲染 —— {@code VS.init} 执行时它们的 DOM 还不存在；服务端的
     * {@code doGenerateSource} / {@code doParseSource} 还会 {@code target.add(tabs)}
     * 整体替换标签页。因此这里必须可重复调用（内部用 bindOnce 去重），
     * 由 {@code enhanceDynamicPanels()} 在面板出现后补绑。
     */
    function bindPanelEvents() {
        // 色带预设单选：切换预设后清空自定义控制点（服务端按预设重算色带）
        function onPresetChange() {
            var rows = $('vs-ramp-rows');
            if (rows) { rows.innerHTML = ''; }
            syncStateFromFormLite();
            renderRampEditor({colorStops: []});
            pushSoon();
        }
        bindOnce($('vs.rampPreset'), 'change', onPresetChange);
        bindOnce($('vs-ramp-preset-raster'), 'change', onPresetChange);

        // 分级条目表格：变化即同步（服务器端会按当前断点重建规则）
        var breaksHost = $('vs-breaks');
        bindOnce(breaksHost, 'change', function () { pushSoon(); });
        bindOnce(breaksHost, 'click', function (ev) {
            var del = ev.target.getAttribute && ev.target.getAttribute('data-del');
            if (del === null || del === undefined) { return; }
            var row = ev.target.closest('tr');
            if (row) { row.parentNode.removeChild(row); }
            reindexBreaks();
            pushSoon();
        });

        // 色带按钮
        bindOnce($('vs-ramp-add'), 'click', function () {
            var rowsHost = $('vs-ramp-rows');
            if (!rowsHost) { return; }
            var stops = readRampStops();
            var k = stops.length ? Math.min(1, stops[stops.length - 1].offset + 0.1) : 0.5;
            rowsHost.insertAdjacentHTML('beforeend', '<div class="vs-ramp-row">'
                + '<input type="color" data-role="color" value="' + sampleGradient(stops, k) + '"/>'
                + '<input type="number" class="vs-input" min="0" max="100" step="0.5" data-role="offset" value="'
                + Math.round(k * 1000) / 10 + '"/>'
                + '<span class="vs-note">%</span>'
                + '<button type="button" class="vs-mini-btn" data-del="-1">删除</button></div>');
            bindRampRowEvents();
            rebuildRampBar();
            pushSoon();
        });
        bindOnce($('vs-ramp-even'), 'click', function () {
            var rows = qsa('#vs-ramp-rows .vs-ramp-row');
            var n = rows.length;
            rows.forEach(function (row, i) {
                row.querySelector('[data-role="offset"]').value = String(n <= 1 ? 0 : Math.round(i / (n - 1) * 1000) / 10);
            });
            rebuildRampBar();
            pushSoon();
        });
        bindOnce($('vs-ramp-reset'), 'click', function () {
            var rows = $('vs-ramp-rows');
            if (rows) { rows.innerHTML = ''; }
            syncStateFromFormLite();
            renderRampEditor({colorStops: []});
            pushSoon();
        });

        // 比例尺快捷 chips
        qsa('#vs-scale-chips .vs-chip').forEach(function (chip) {
            bindOnce(chip, 'click', function () {
                var v = chip.getAttribute('data-scale');
                var minEl = $('vs.minScale'), maxEl = $('vs.maxScale');
                if (!minEl || !maxEl) { return; }
                if (v === '0') {
                    minEl.value = '0';
                    maxEl.value = '0';
                } else if (!num(minEl.value, 0)) {
                    minEl.value = v;
                } else if (!num(maxEl.value, 0)) {
                    maxEl.value = v;
                } else {
                    minEl.value = v;
                    maxEl.value = '0';
                }
                var en = $('vs.scaleEnabled');
                if (en) { en.checked = true; }
                renderScaleNotes();
                pushSoon();
            });
        });

        // 字段插入器（标注）
        bindOnce($('vs-label-field-picker'), 'change', function () {
            var picker = $('vs-label-field-picker');
            var v = picker ? picker.value : '';
            if (!v) { return; }
            var f = $('vs.labelField');
            if (f) {
                var cur = f.value.trim();
                f.value = cur ? (cur + " + ' ' + " + v) : v;
            }
            if (picker) { picker.value = ''; }
            pushSoon();
        });

        // 底部源码视图：折叠 / 复制 / 格式切换
        bindOnce($('vs-source-toggle'), 'click', function () {
            var toggle = $('vs-source-toggle');
            var strip = $('vs-source-strip');
            if (!strip) { return; }
            strip.classList.toggle('vs-collapsed');
            var collapsed = strip.classList.contains('vs-collapsed');
            if (toggle) { toggle.textContent = collapsed ? '展开' : '收起'; }
        });
        bindOnce($('vs-source-copy'), 'click', function () {
            var copy = $('vs-source-copy');
            if (navigator.clipboard && navigator.clipboard.writeText) {
                try { navigator.clipboard.writeText(lastSld || ''); } catch (e) { /* 无剪贴板权限时忽略 */ }
            }
            if (copy) {
                copy.textContent = '已复制';
                setTimeout(function () { copy.textContent = '复制'; }, 1200);
            }
        });
        qsa('#vs-source-tabs .vs-chip').forEach(function (chip) {
            bindOnce(chip, 'click', function () {
                qsa('#vs-source-tabs .vs-chip').forEach(function (c) { c.classList.remove('vs-chip-active'); });
                chip.classList.add('vs-chip-active');
                sourceFormat = chip.getAttribute('data-format');
                if (sourceFormat === 'css' && !cfg.hasCss) {
                    setStatus('GeoCSS 扩展未安装，仅支持 SLD', 'vs-err');
                }
                renderSourceView();
            });
        });

        // 图例按钮
        bindOnce($('vs-legend-refresh'), 'click', function () { renderLegend(state); });
        bindOnce($('vs-legend-png'), 'click', function () { exportLegendPng(); });
    }

    function bindRampRowEvents() {
        qsa('#vs-ramp-rows input').forEach(function (inp) {
            inp.addEventListener('input', function () { rebuildRampBar(); });
            inp.addEventListener('change', function () { pushSoon(); });
        });
        qsa('#vs-ramp-rows [data-del]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var row = btn.closest('.vs-ramp-row');
                if (row) { row.parentNode.removeChild(row); }
                rebuildRampBar();
                pushSoon();
            });
        });
    }

    function reindexBreaks() {
        qsa('#vs-breaks tbody tr').forEach(function (tr, i) {
            qsa('[name]', tr).forEach(function (inp) {
                var n = inp.getAttribute('name');
                inp.setAttribute('name', n.replace(/vs\.breaks\.\d+\./, 'vs.breaks.' + i + '.'));
            });
        });
    }

    /** 从表单粗略同步出前端侧的 state（仅用于即时预览，权威模型仍在服务端）。 */
    function syncStateFromFormLite() {
        var form = $(cfg.formId || 'vs-visual-form');
        if (!form) { return; }
        var data = VS.collect(form, {});
        Object.keys(data).forEach(function (k) {
            if (k.indexOf('vs.') !== 0) { return; }
            var key = k.substring(3);
            if (key.indexOf('.') >= 0) { return; }
            var v = data[k];
            if (v === 'true') { state[key] = true; }
            else if (v === 'false') { state[key] = false; }
            else if (v !== '' && !isNaN(v) && state[key] !== undefined
                && typeof state[key] !== 'string') { state[key] = num(v, state[key]); }
            else { state[key] = v; }
        });
    }

    /** 导出图例 PNG。 */
    function exportLegendPng() {
        var html = buildLegendHtml(state);
        var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="260" height="'
            + Math.max(60, 24 + ((state.breaks || []).length || 1) * 20) + '">'
            + '<foreignObject width="100%" height="100%">'
            + '<div xmlns="http://www.w3.org/1999/xhtml" style="font:12px sans-serif;padding:6px">'
            + html + '</div></foreignObject></svg>';
        var blob = new Blob([svg], {type: 'image/svg+xml'});
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = (state.styleName || 'legend') + '-legend.svg';
        a.click();
        setTimeout(function () { URL.revokeObjectURL(url); }, 2000);
    }

    // ==================================================================
    // 12. 初始化
    // ==================================================================

    VS.init = function (options) {
        if (initialised) { return; }
        initialised = true;
        cfg = options || {};
        if (cfg.sourceTaId) { SOURCE_TA_ID = cfg.sourceTaId; }
        state = cfg.state || {};
        lastSld = cfg.sld || '';
        lastPreviewSld = cfg.previewSld || lastSld;
        ramps = cfg.ramps || [];
        rampById = {};
        ramps.forEach(function (r) { rampById[r.id] = r; });
        cfg.hasCss = (cfg.availableFormats || []).indexOf('css') >= 0;

        if (cfg.readOnly) {
            var form = $(cfg.formId);
            if (form) { form.classList.add('vs-readonly'); }
        }

        fillRampSelects();
        // 先绑表单事件：后面的任何初始化异常都不允许影响「表单 → 服务端」同步
        bindFormEvents();
        if (cfg.metadata) {
            try { VS.onMetadata(cfg.metadata); } catch (e) {
                if (window.console && console.warn) { console.warn('图层元数据初始化失败:', e); }
            }
        }
        applyStateToForm(state);
        renderBreaks(state);
        renderRampEditor(state);
        renderRampPreview();
        renderLegend(state);
        renderSourceView();
        renderScaleNotes();
        try { drawNavigationHistogram(); } catch (e) { /* 非关键装饰 */ }
        // 标签页是按需 Ajax 渲染的：先对当前已存在的面板补一次初始化，
        // 再挂 MutationObserver，让后续切换标签页 / 服务端替换面板时自动补做。
        enhanceDynamicPanels();
        try { observeDynamicPanels(); } catch (e) { /* 观察器不可用不影响主流程 */ }
        // 预览初始化（PreviewPanel 也通过 OnDomReady 下发 initPreview）：
        // 两个 OnDomReady 条目的先后顺序由服务端组件渲染顺序决定，因此这里用「暂存 + 补做」
        // 保证 map 一定是在拿到 SLD 之后才创建 —— 否则建 map 那一下会先用图层默认样式渲染一次
        // （海量图层要几十秒），随后才被带 SLD_BODY 的正式请求覆盖掉。
        if (pendingPreviewInit) {
            var pending = pendingPreviewInit;
            pendingPreviewInit = null;
            try {
                buildPreview(pending);
            } catch (e) {
                if (window.console && console.warn) { console.warn('预览初始化失败（不影响其它功能）:', e); }
            }
        }
        setStatus('就绪', 'vs-ok');
    };

    // 暴露少量内部方法便于扩展与调试
    VS._internal = {
        sampleGradient: sampleGradient,
        parseStops: parseStops,
        cssGradient: cssGradient,
        highlight: highlight,
        renderLegend: renderLegend,
        readRampStops: readRampStops
    };

})(window, document);
