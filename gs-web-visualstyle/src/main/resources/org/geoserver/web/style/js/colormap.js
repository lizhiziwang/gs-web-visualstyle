/* =============================================================================
 * 色带 / 颜色 工具模块（gs-web-visualstyle）
 *
 * 与 visualstyle.js 的分工：
 *   visualstyle.js —— 页面主逻辑（表单收集、状态回填、预览、图例、源码高亮）
 *   colormap.js    —— 纯函数式的颜色与色带数学：插值、采样、离散化、可见性检查
 *
 * 说明：这里只做「显示层面」的颜色运算。真正写入 SLD 的 ColorMap 由 Java 端
 * （StyleCompiler.buildColorMap）用 GeoTools 生成，前端结果仅用于即时预览，
 * 因此两边的插值策略保持一致（线性 sRGB 插值）即可。
 * ========================================================================== */
(function (window) {
    'use strict';

    var CM = {};
    window.VisualStyleColor = CM;

    CM.HEX3 = /^#[0-9a-f]{3}$/i;
    CM.HEX6 = /^#[0-9a-f]{6}$/i;
    CM.HEX8 = /^#[0-9a-f]{8}$/i;

    /** 归一化为 #rrggbb（小写）。 */
    CM.normalize = function (hex, dflt) {
        if (!hex) { return dflt || '#000000'; }
        var v = String(hex).trim().toLowerCase();
        if (CM.HEX6.test(v)) { return v; }
        if (CM.HEX3.test(v)) { return '#' + v[1] + v[1] + v[2] + v[2] + v[3] + v[3]; }
        if (CM.HEX8.test(v)) { return v.substring(0, 7); }
        // 支持 rgb() / 常见具名色
        var m = v.match(/^rgba?\(([^)]+)\)$/);
        if (m) {
            var p = m[1].split(',').map(function (x) { return parseFloat(x); });
            return CM.rgb(p[0], p[1], p[2]);
        }
        var named = {black: '#000000', white: '#ffffff', red: '#ff0000', green: '#008000',
            blue: '#0000ff', yellow: '#ffff00', gray: '#808080', grey: '#808080',
            orange: '#ffa500', purple: '#800080', transparent: '#000000'};
        return named[v] || dflt || '#000000';
    };

    CM.rgb = function (r, g, b) {
        function p(x) {
            x = Math.max(0, Math.min(255, Math.round(x)));
            var s = x.toString(16);
            return s.length === 1 ? '0' + s : s;
        }
        return '#' + p(r) + p(g) + p(b);
    };

    CM.toRgb = function (hex) {
        var h = CM.normalize(hex).substring(1);
        return [parseInt(h.substring(0, 2), 16), parseInt(h.substring(2, 4), 16), parseInt(h.substring(4, 6), 16)];
    };

    /** 线性插值。 */
    CM.mix = function (a, b, t) {
        var ca = CM.toRgb(a), cb = CM.toRgb(b);
        var k = Math.max(0, Math.min(1, t));
        return CM.rgb(ca[0] + (cb[0] - ca[0]) * k, ca[1] + (cb[1] - ca[1]) * k, ca[2] + (cb[2] - ca[2]) * k);
    };

    /** 感知亮度（0..1）。 */
    CM.luminance = function (hex) {
        var c = CM.toRgb(hex);
        return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255;
    };

    /** 与给定颜色对比度更好的文字色（图例、按钮）。 */
    CM.textOn = function (hex) {
        return CM.luminance(hex) > 0.6 ? '#111111' : '#ffffff';
    };

    /**
     * 在控制点列表上按 t 采样。
     * @param stops [{offset, color}]
     */
    CM.sample = function (stops, t) {
        if (!stops || !stops.length) { return '#808080'; }
        var s = stops.slice().sort(function (a, b) { return a.offset - b.offset; });
        if (t <= s[0].offset) { return CM.normalize(s[0].color); }
        if (t >= s[s.length - 1].offset) { return CM.normalize(s[s.length - 1].color); }
        for (var i = 0; i < s.length - 1; i++) {
            var a = s[i], b = s[i + 1];
            if (t >= a.offset && t <= b.offset) {
                var span = b.offset - a.offset;
                return CM.mix(a.color, b.color, span <= 0 ? 0 : (t - a.offset) / span);
            }
        }
        return CM.normalize(s[s.length - 1].color);
    };

    /** 等分采样 n 个颜色。 */
    CM.sampleN = function (stops, n) {
        var out = [];
        if (n <= 0) { return out; }
        for (var i = 0; i < n; i++) {
            out.push(CM.sample(stops, n === 1 ? 0.5 : i / (n - 1)));
        }
        return out;
    };

    /** 反转控制点。 */
    CM.reverse = function (stops) {
        return (stops || []).map(function (x) {
            return {offset: 1 - x.offset, color: CM.normalize(x.color)};
        }).sort(function (a, b) { return a.offset - b.offset; });
    };

    /** 生成 CSS linear-gradient。 */
    CM.gradientCss = function (stops, direction) {
        var s = (stops || []).slice().sort(function (a, b) { return a.offset - b.offset; });
        if (!s.length) { return 'linear-gradient(to right, #eeeeee, #eeeeee)'; }
        return 'linear-gradient(' + (direction || 'to right') + ', '
            + s.map(function (x) {
                return (Math.round(x.offset * 1000) / 10) + '% ' + CM.normalize(x.color);
            }).join(', ') + ')';
    };

    /**
     * 离散化：把连续色带切成 n 段，返回每段的代表色。
     * 取段中点颜色，与后端 ColorMap TYPE_INTERVALS 的行为对齐。
     */
    CM.discretize = function (stops, n) {
        var out = [];
        if (n <= 0) { return out; }
        for (var i = 0; i < n; i++) {
            out.push(CM.sample(stops, n === 1 ? 0.5 : (i + 0.5) / n));
        }
        return out;
    };

    /** 解析 "0:#fff,1:#000" 形式的色带串。 */
    CM.parse = function (str) {
        var out = [];
        if (!str) { return out; }
        String(str).split(',').forEach(function (part) {
            var bits = part.split(':');
            if (bits.length < 2) { return; }
            var o = parseFloat(bits[0]);
            out.push({offset: isNaN(o) ? 0 : o, color: CM.normalize(bits[1])});
        });
        return out;
    };

    /**
     * 校验色带是否可用：控制点非空、offset 升序、颜色合法。
     * @return {ok, message}
     */
    CM.validate = function (stops) {
        if (!stops || stops.length < 2) {
            return {ok: false, message: '色带至少需要 2 个控制点'};
        }
        var prev = -1;
        for (var i = 0; i < stops.length; i++) {
            var off = stops[i].offset;
            if (isNaN(off) || off < 0 || off > 1) {
                return {ok: false, message: '第 ' + (i + 1) + ' 个控制点位置必须在 0%~100% 之间'};
            }
            if (off < prev) {
                return {ok: false, message: '控制点位置必须升序排列'};
            }
            prev = off;
        }
        return {ok: true, message: ''};
    };

    /** 依据数值范围生成等间距刻度（用于图例刻度显示）。 */
    CM.ticks = function (min, max, count) {
        var n = Math.max(2, count || 5);
        var out = [];
        if (!isFinite(min) || !isFinite(max)) { return out; }
        if (max <= min) { return [min]; }
        for (var i = 0; i < n; i++) {
            out.push(min + (max - min) * i / (n - 1));
        }
        return out;
    };

    /** 数值格式化（图例刻度用）。 */
    CM.fmt = function (v) {
        if (v === null || v === undefined || isNaN(v)) { return '-'; }
        var a = Math.abs(v);
        if (a !== 0 && (a < 0.001 || a >= 100000)) {
            return Number(v).toExponential(2);
        }
        return String(Math.round(v * 1000) / 1000);
    };

})(window);
