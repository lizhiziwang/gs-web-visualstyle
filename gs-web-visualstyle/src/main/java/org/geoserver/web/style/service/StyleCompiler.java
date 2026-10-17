/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.web.style.model.ClassBreak;
import org.geoserver.web.style.model.ColorStop;
import org.geoserver.web.style.model.ClassificationMethod;
import org.geoserver.web.style.model.RasterMode;
import org.geoserver.web.style.model.SymbolizerKind;
import org.geoserver.web.style.model.VisualStyleState;
import org.geoserver.web.style.util.Colors;
import org.geoserver.web.style.util.Ramps;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.style.ChannelSelection;
import org.geotools.api.style.ColorMap;
import org.geotools.api.style.ColorMapEntry;
import org.geotools.api.style.ContrastEnhancement;
import org.geotools.api.style.ContrastMethod;
import org.geotools.api.style.ExternalGraphic;
import org.geotools.api.style.FeatureTypeStyle;
import org.geotools.api.style.Fill;
import org.geotools.api.style.Font;
import org.geotools.api.style.Graphic;
import org.geotools.api.style.Halo;
import org.geotools.api.style.LabelPlacement;
import org.geotools.api.style.LineSymbolizer;
import org.geotools.api.style.Mark;
import org.geotools.api.style.NamedLayer;
import org.geotools.api.style.PointPlacement;
import org.geotools.api.style.PointSymbolizer;
import org.geotools.api.style.PolygonSymbolizer;
import org.geotools.api.style.RasterSymbolizer;
import org.geotools.api.style.Rule;
import org.geotools.api.style.SelectedChannelType;
import org.geotools.api.style.Stroke;
import org.geotools.api.style.Style;
import org.geotools.api.style.StyleFactory;
import org.geotools.api.style.StyledLayerDescriptor;
import org.geotools.api.style.Symbolizer;
import org.geotools.api.style.TextSymbolizer;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.util.logging.Logging;

/**
 * SLD 对象模型 ⇄ 可视化表单状态 的双向编译器。
 *
 * <p>职责：
 * <ul>
 *   <li><b>正向</b>：{@link VisualStyleState} → GeoTools {@code StyledLayerDescriptor}（SLD 1.1.0 对象模型）；</li>
 *   <li><b>反向</b>：已有 SLD（用户手工编辑或 Catalog 中既有样式）→ 回填 {@link VisualStyleState}，
 *       实现「改源码 → 表单自动同步」；</li>
 *   <li>不负责 XML 编解码（由 {@link StyleSerializationService} 通过 GeoServer {@code StyleHandler} 完成），
 *       也不负责 IO / Catalog 交互。</li>
 * </ul>
 *
 * <p>本类无状态且线程安全（{@code StyleFactory} 为线程安全单例），可直接注册为 Spring 单例 bean。
 */
public class StyleCompiler implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logging.getLogger(StyleCompiler.class);

    /** 未指定比例尺时的默认值：0 = 不限。 */
    public static final double NO_SCALE_LIMIT = 0d;

    private transient StyleFactory styleFactory;
    private transient FilterFactory filterFactory;

    private StyleFactory sf() {
        if (styleFactory == null) {
            styleFactory = CommonFactoryFinder.getStyleFactory(null);
        }
        return styleFactory;
    }

    private FilterFactory ff() {
        if (filterFactory == null) {
            filterFactory = CommonFactoryFinder.getFilterFactory(null);
        }
        return filterFactory;
    }

    // ==================================================================
    // 一、正向：表单状态 → SLD 对象模型
    // ==================================================================

    /** 依据表单状态构建完整 SLD 对象模型。 */
    public StyledLayerDescriptor buildSld(VisualStyleState state) {
        StyleFactory f = sf();
        Style style = f.createStyle();
        style.setName(emptyTo(state.getStyleName(), "style"));
        style.setDefault(false);

        FeatureTypeStyle fts = f.createFeatureTypeStyle();
        fts.setName(emptyTo(state.getStyleName(), "style"));

        List<Rule> rules = buildRules(state);
        fts.rules().addAll(rules);
        style.featureTypeStyles().add(fts);

        StyledLayerDescriptor sld = f.createStyledLayerDescriptor();
        sld.setName(emptyTo(state.getStyleName(), "style"));
        NamedLayer layer = f.createNamedLayer();
        layer.setName(emptyTo(state.getStyleName(), "style"));
        layer.addStyle(style);
        sld.setStyledLayers(layer);
        return sld;
    }

    /** 依据分类方式生成规则列表。 */
    public List<Rule> buildRules(VisualStyleState state) {
        ClassificationMethod method = state.getClassificationMethod();
        if (method == ClassificationMethod.UNIQUE_VALUES) {
            return buildUniqueValueRules(state);
        }
        if (method != null && method.isNumericBreakMethod()) {
            return buildClassifiedRules(state);
        }
        return buildSingleRule(state);
    }

    private List<Rule> buildSingleRule(VisualStyleState state) {
        List<Rule> out = new ArrayList<>();
        List<Symbolizer> syms = buildSymbolizers(state, null);
        if (syms.isEmpty()) {
            return out;
        }
        Rule rule = sf().createRule();
        rule.setName(emptyTo(state.getStyleName(), "rule") + "-single");
        for (Symbolizer sym : syms) {
            rule.symbolizers().add(sym);
        }
        applyScale(state, rule, false);
        out.add(rule);
        return out;
    }

    private List<Rule> buildUniqueValueRules(VisualStyleState state) {
        List<Rule> out = new ArrayList<>();
        String field = state.getClassificationField();
        int i = 0;
        for (ClassBreak brk : state.getBreaks()) {
            if (brk.getValue() == null) {
                continue;
            }
            Rule rule = sf().createRule();
            rule.setName(emptyTo(state.getStyleName(), "rule") + "-u" + (i++));
            if (field != null && !field.isBlank()) {
                rule.setFilter(ff().equals(prop(field), literal(brk.getValue())));
            }
            for (Symbolizer sym : buildSymbolizers(state, brk)) {
                rule.symbolizers().add(sym);
            }
            applyScale(state, rule, false);
            out.add(rule);
        }
        if (out.isEmpty()) {
            // 没有任何有效分类 → 退化为单一符号，避免生成空样式
            return buildSingleRule(state);
        }
        return out;
    }

    private List<Rule> buildClassifiedRules(VisualStyleState state) {
        List<Rule> out = new ArrayList<>();
        List<ClassBreak> breaks = state.getBreaks();
        if (breaks == null || breaks.isEmpty()) {
            return buildSingleRule(state);
        }
        String field = state.getClassificationField();
        int last = breaks.size() - 1;
        for (int i = 0; i < breaks.size(); i++) {
            ClassBreak brk = breaks.get(i);
            Rule rule = sf().createRule();
            rule.setName(emptyTo(state.getStyleName(), "rule") + "-c" + i);
            if (field != null && !field.isBlank()) {
                Filter min = ff().greaterOrEqual(prop(field), literal(Double.valueOf(brk.getMin())));
                // 最后一个区间取闭区间，其余为左闭右开，避免边界重复命中
                Filter max = i == last
                        ? ff().lessOrEqual(prop(field), literal(Double.valueOf(brk.getMax())))
                        : ff().less(prop(field), literal(Double.valueOf(brk.getMax())));
                rule.setFilter(ff().and(min, max));
            }
            for (Symbolizer sym : buildSymbolizers(state, brk)) {
                rule.symbolizers().add(sym);
            }
            applyScale(state, rule, false);
            out.add(rule);
        }
        return out;
    }

    /** 应用比例尺控制。 */
    private void applyScale(VisualStyleState state, Rule rule, boolean labelOnly) {
        if (rule == null) {
            return;
        }
        double min;
        double max;
        if (labelOnly) {
            if (!state.isLabelScaleEnabled()) {
                return;
            }
            min = state.getLabelMinScale();
            max = state.getLabelMaxScale();
        } else if (state.isScaleEnabled()) {
            min = state.getMinScale();
            max = state.getMaxScale();
        } else if (state.isLabelScaleEnabled()) {
            // 只设置了标注比例尺：整条规则跟随标注阈值
            min = state.getLabelMinScale();
            max = state.getLabelMaxScale();
        } else {
            return;
        }
        if (min > 0d) {
            rule.setMinScaleDenominator(min);
        }
        if (max > 0d) {
            rule.setMaxScaleDenominator(max);
        }
    }

    /**
     * 生成某条规则的符号化器列表。
     *
     * @param brk 分类条目；{@code null} 表示使用状态中的全局配色
     */
    public List<Symbolizer> buildSymbolizers(VisualStyleState state, ClassBreak brk) {
        List<Symbolizer> out = new ArrayList<>();
        SymbolizerKind kind = state.getSymbolizerKind();
        if (kind == null) {
            kind = SymbolizerKind.POLYGON;
        }
        switch (kind) {
            case POINT:
                out.add(buildPoint(state, brk));
                break;
            case LINE:
                out.add(buildLine(state, brk));
                break;
            case POLYGON:
                out.add(buildPolygon(state, brk));
                break;
            case RASTER:
                out.add(buildRaster(state));
                break;
            default:
                break;
        }
        if (kind.isVector() && state.isLabelEnabled() && brk == null) {
            TextSymbolizer ts = buildText(state);
            if (ts != null) {
                out.add(ts);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 面符号
    // ------------------------------------------------------------------

    private PolygonSymbolizer buildPolygon(VisualStyleState state, ClassBreak brk) {
        String fill = resolveFill(state, brk);
        double fillOpacity = resolveFillOpacity(state, brk);
        String stroke = resolveStroke(state, brk);
        double strokeWidth = resolveStrokeWidth(state, brk);

        Fill fillObj = null;
        if (state.isFillEnabled()) {
            fillObj = buildFill(fill, fillOpacity);
        }
        Stroke strokeObj = buildStroke(stroke, state.getStrokeOpacity(), strokeWidth, state);
        return sf().createPolygonSymbolizer(strokeObj, fillObj, null);
    }

    // ------------------------------------------------------------------
    // 线符号
    // ------------------------------------------------------------------

    private LineSymbolizer buildLine(VisualStyleState state, ClassBreak brk) {
        // 线要素：填充色被忽略，统一用描边色
        String color = brk != null && brk.getFill() != null ? brk.getFill() : state.getStrokeColor();
        double opacity = brk != null ? brk.getFillOpacity() : state.getStrokeOpacity();
        double width = resolveStrokeWidth(state, brk);
        Stroke stroke = buildStroke(color, opacity, width, state);
        LineSymbolizer ls = sf().createLineSymbolizer(stroke, null);
        if (state.getStrokeOffset() != 0d) {
            ls.setPerpendicularOffset(literal(Double.valueOf(state.getStrokeOffset())));
        }
        return ls;
    }

    // ------------------------------------------------------------------
    // 点符号
    // ------------------------------------------------------------------

    private PointSymbolizer buildPoint(VisualStyleState state, ClassBreak brk) {
        String fill = resolveFill(state, brk);
        double fillOpacity = resolveFillOpacity(state, brk);
        String strokeColor = resolveStroke(state, brk);
        double size = brk != null ? brk.getSize() : state.getPointSize();
        Expression sizeExpr;
        if (state.getSizeByField() != null && !state.getSizeByField().isBlank() && brk == null) {
            // 气泡图：用 CQL 把字段值线性映射到 [sizeMin, sizeMax]
            sizeExpr = interpolateSize(state);
        } else {
            sizeExpr = literal(Double.valueOf(size <= 0d ? 8d : size));
        }

        Graphic graphic;
        String external = state.getExternalGraphic();
        if (external != null && !external.isBlank()) {
            ExternalGraphic eg = sf().createExternalGraphic(external.trim(),
                    emptyTo(state.getExternalGraphicFormat(), "image/svg+xml"));
            graphic = sf().createGraphic(new ExternalGraphic[] {eg}, null, null, sizeExpr,
                    literal(Double.valueOf(state.getPointRotation())),
                    literal(Double.valueOf(state.getPointOpacity())));
        } else {
            Mark mark = sf().createMark();
            mark.setWellKnownName(literal(emptyTo(state.getMarkShape(), "circle")));
            mark.setFill(buildFill(state.getMarkFillColor(), state.getMarkFillOpacity()));
            mark.setStroke(buildStroke(state.getMarkStrokeColor(), 1d, state.getMarkStrokeWidth(), null));
            graphic = sf().createGraphic(null, new Mark[] {mark}, null, sizeExpr,
                    literal(Double.valueOf(state.getPointRotation())),
                    literal(Double.valueOf(state.getPointOpacity())));
        }
        return sf().createPointSymbolizer(graphic, null);
    }

    private Expression interpolateSize(VisualStyleState state) {
        double dmin = state.getSizeMin();
        double dmax = state.getSizeMax();
        if (dmax <= dmin) {
            dmax = dmin + 1d;
        }
        // CQL: (sizeMax-sizeMin)/(max(field)-min(field)) * (field-min(field)) + sizeMin
        // 为避免依赖数据统计，这里用 "字段归一化" 的简化写法交给 SLD 表达式求值：
        // size = sizeMin + (sizeMax-sizeMin) * field / maxFieldSpan（无 span 时退化为线性按值域 0..1）
        String cql = String.format(java.util.Locale.ROOT,
                "%.4f + (%.4f * %s)", Double.valueOf(dmin), Double.valueOf(dmax - dmin),
                state.getSizeByField().trim());
        try {
            return CQL.toExpression(cql);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "推导气泡尺寸表达式失败，退回固定尺寸", e);
            return literal(Double.valueOf(dmin));
        }
    }

    // ------------------------------------------------------------------
    // 标注
    // ------------------------------------------------------------------

    private TextSymbolizer buildText(VisualStyleState state) {
        String field = state.getLabelField();
        if (field == null || field.isBlank()) {
            return null;
        }
        Expression label = toExpression(field);

        Font font = sf().createFont(
                literal(state.getFontFamily()),
                literal(state.isFontItalic() ? Font.Style.ITALIC : Font.Style.NORMAL),
                literal(state.isFontBold() ? Font.Weight.BOLD : Font.Weight.NORMAL),
                literal(Integer.valueOf(Math.max(1, state.getFontSize()))));

        Halo halo = null;
        if (state.isHaloEnabled()) {
            halo = sf().createHalo(buildFill(state.getHaloColor(), 1d),
                    literal(Double.valueOf(state.getHaloRadius())));
        }

        PointPlacement placement = sf().createPointPlacement(
                sf().createAnchorPoint(literal(Double.valueOf(anchorX(state.getLabelAnchor()))),
                        literal(Double.valueOf(anchorY(state.getLabelAnchor())))),
                sf().createDisplacement(literal(Double.valueOf(state.getLabelOffsetX())),
                        literal(Double.valueOf(state.getLabelOffsetY()))),
                state.getLabelRotationField() == null || state.getLabelRotationField().isBlank()
                        ? null
                        : toExpression(state.getLabelRotationField()));

        TextSymbolizer ts = sf().createTextSymbolizer(
                buildFill(state.getFontColor(), state.getFontOpacity()),
                new Font[] {font},
                halo,
                label,
                placement,
                null);

        Map<String, String> options = ts.getOptions();
        options.put(TextSymbolizer.CONFLICT_RESOLUTION_KEY,
                Boolean.toString(state.isLabelDisplacement()));
        if (state.isLabelDisplacement()) {
            options.put(TextSymbolizer.SPACE_AROUND_KEY,
                    Integer.toString(Math.max(1, state.getLabelSpaceAround())));
        }
        options.put(TextSymbolizer.ALLOW_OVERRUNS_KEY, "true");
        options.put(TextSymbolizer.FOLLOW_LINE_KEY, "false");
        if (state.getLabelGroupField() != null && !state.getLabelGroupField().isBlank()) {
            options.put(TextSymbolizer.GROUP_KEY, "true");
        }
        return ts;
    }

    private double anchorX(String anchor) {
        if ("start".equalsIgnoreCase(anchor)) {
            return 0d;
        }
        if ("end".equalsIgnoreCase(anchor)) {
            return 1d;
        }
        return 0.5d;
    }

    private double anchorY(String anchor) {
        // 保持基线居中
        return 0.5d;
    }

    // ------------------------------------------------------------------
    // 栅格符号
    // ------------------------------------------------------------------

    private RasterSymbolizer buildRaster(VisualStyleState state) {
        RasterSymbolizer rs = sf().createRasterSymbolizer();
        rs.setOpacity(literal(Double.valueOf(state.getRasterOpacity())));

        RasterMode mode = state.getRasterMode() == null ? RasterMode.GRAY : state.getRasterMode();
        ContrastEnhancement ce = buildContrastEnhancement(state);

        if (mode == RasterMode.RGB) {
            ChannelSelection cs = sf().createChannelSelection();
            cs.setRGBChannels(
                    sf().createSelectedChannelType(String.valueOf(state.getRedBand()),
                            mode == RasterMode.RGB ? ce : null),
                    sf().createSelectedChannelType(String.valueOf(state.getGreenBand()),
                            mode == RasterMode.RGB ? ce : null),
                    sf().createSelectedChannelType(String.valueOf(state.getBlueBand()),
                            mode == RasterMode.RGB ? ce : null));
            rs.setChannelSelection(cs);
        } else {
            // 单波段：灰度 / 色带 / DEM 都基于同一波段
            ChannelSelection cs = sf().createChannelSelection();
            cs.setGrayChannel(sf().createSelectedChannelType(String.valueOf(state.getGrayBand()), ce));
            rs.setChannelSelection(cs);
            if (mode.usesColorRamp()) {
                rs.setColorMap(buildColorMap(state, mode));
            }
        }
        return rs;
    }

    private ContrastEnhancement buildContrastEnhancement(VisualStyleState state) {
        if (!state.isStretchEnabled()) {
            return null;
        }
        ContrastEnhancement ce = sf().createContrastEnhancement();
        ce.setMethod(contrastMethod(state.getStretchType()));
        if (state.getGamma() != 1d && state.getGamma() > 0d) {
            ce.setGammaValue(literal(Double.valueOf(state.getGamma())));
        }
        return ce;
    }

    private ContrastMethod contrastMethod(String type) {
        if (type == null) {
            return ContrastMethod.NORMALIZE;
        }
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "histogram":
                return ContrastMethod.HISTOGRAM;
            case "logarithmic":
                return ContrastMethod.LOGARITHMIC;
            case "exponential":
                return ContrastMethod.EXPONENTIAL;
            case "none":
                return ContrastMethod.NONE;
            case "normalize":
            case "linear":
            default:
                return ContrastMethod.NORMALIZE;
        }
    }

    private ColorMap buildColorMap(VisualStyleState state, RasterMode mode) {
        ColorMap cm = sf().createColorMap();
        List<ColorStop> stops = state.effectiveColorStops();
        if (stops.isEmpty()) {
            stops = Ramps.parseStops(Ramps.get("viridis").stops);
        }
        double min = state.getStretchMin();
        double max = state.getStretchMax();
        boolean continuous = mode == RasterMode.COLORMAP ? state.isRampContinuous() : false;
        if (mode == RasterMode.DEM) {
            continuous = state.isRampContinuous();
        }
        cm.setType(continuous ? ColorMap.TYPE_RAMP : ColorMap.TYPE_INTERVALS);

        List<ColorStop> sorted = new ArrayList<>(stops);
        sorted.sort((a, b) -> Double.compare(a.getOffset(), b.getOffset()));

        if (continuous) {
            for (ColorStop stop : sorted) {
                ColorMapEntry e = sf().createColorMapEntry();
                e.setColor(literal(stop.getNormalizedColor()));
                e.setQuantity(literal(Double.valueOf(quantify(stop.getOffset(), min, max))));
                e.setOpacity(literal(Double.valueOf(stop.getAlpha())));
                cm.addColorMapEntry(e);
            }
        } else {
            // 离散分级：把 [min,max] 等分为 n 段，每段取色带中点的颜色
            int n = Math.max(2, state.getRampIntervals());
            double span = (max - min) / n;
            for (int i = 0; i < n; i++) {
                double q = min + span * (i + 1);
                double t = n == 1 ? 0d : (double) i / (double) (n - 1);
                ColorStop sample = new ColorStop(t, Ramps.sampleAt(sorted, t));
                ColorMapEntry e = sf().createColorMapEntry();
                e.setColor(literal(sample.getNormalizedColor()));
                e.setQuantity(literal(Double.valueOf(q)));
                e.setOpacity(literal(Double.valueOf(sample.getAlpha())));
                e.setLabel(String.format(java.util.Locale.ROOT, "%.2f ~ %.2f", Double.valueOf(min + span * i),
                        Double.valueOf(q)));
                cm.addColorMapEntry(e);
            }
        }
        return cm;
    }

    private double quantify(double offset, double min, double max) {
        if (max <= min) {
            return offset;
        }
        return min + (max - min) * offset;
    }

    // ------------------------------------------------------------------
    // 颜色 / 通用部件
    // ------------------------------------------------------------------

    /**
     * 构造 Fill。
     *
     * <p>注意：{@code color()} 只产出 {@code #RRGGBB}，透明度必须通过
     * {@code createFill(color, opacity)} 的第二参数单独携带，否则会被丢弃。
     */
    private Fill buildFill(String hex, double opacity) {
        return sf().createFill(color(hex, opacity),
                literal(Double.valueOf(Math.max(0d, Math.min(1d, opacity)))));
    }

    private Stroke buildStroke(String hex, double opacity, double width, VisualStyleState state) {
        float[] dash = null;
        String lineJoin = "mitre";
        String lineCap = "butt";
        if (state != null) {
            if (state.getStrokeDashArray() != null && !state.getStrokeDashArray().isBlank()) {
                dash = parseDash(state.getStrokeDashArray());
            }
            lineJoin = emptyTo(state.getStrokeLineJoin(), "mitre");
            lineCap = emptyTo(state.getStrokeLineCap(), "butt");
        }
        // createStroke(color, width, opacity, lineJoin, lineCap, dashArray, dashOffset,
        //              graphicFill, graphicStroke)
        return sf().createStroke(
                color(hex, opacity),
                literal(Double.valueOf(Math.max(0d, width))),
                literal(Double.valueOf(Math.max(0d, Math.min(1d, opacity)))),
                literal(lineJoin),
                literal(lineCap),
                dash,
                null,
                null,
                null);
    }

    private float[] parseDash(String dashed) {
        String[] bits = dashed.trim().split("[\\s,]+");
        List<Float> values = new ArrayList<>();
        for (String b : bits) {
            if (b.isBlank()) {
                continue;
            }
            try {
                values.add(Float.valueOf(Float.parseFloat(b)));
            } catch (NumberFormatException ignore) {
                // 忽略非法分段
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        float[] arr = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i).floatValue();
        }
        return arr;
    }

    private String resolveFill(VisualStyleState state, ClassBreak brk) {
        if (brk != null && brk.getFill() != null) {
            return Colors.toHex(brk.getFill(), state.getFillColor());
        }
        return state.getFillColor();
    }

    private double resolveFillOpacity(VisualStyleState state, ClassBreak brk) {
        return brk != null ? brk.getFillOpacity() : state.getFillOpacity();
    }

    private String resolveStroke(VisualStyleState state, ClassBreak brk) {
        if (brk != null && brk.getStroke() != null) {
            return Colors.toHex(brk.getStroke(), state.getStrokeColor());
        }
        return state.getStrokeColor();
    }

    private double resolveStrokeWidth(VisualStyleState state, ClassBreak brk) {
        return brk != null && brk.getStrokeWidth() > 0d ? brk.getStrokeWidth() : state.getStrokeWidth();
    }

    // ------------------------------------------------------------------
    // 表达式辅助
    // ------------------------------------------------------------------

    private Expression literal(Object value) {
        return ff().literal(value);
    }

    private Expression prop(String name) {
        return ff().property(name);
    }

    /** 颜色字面量：SLD 中用 {@code #RRGGBB} + 独立 opacity 表达更稳。 */
    private Expression color(String hex, double opacity) {
        // SLD 1.1 允许 #RRGGBBAA；这里统一输出 #RRGGBB 并把透明度交给 opacity 属性，
        // 兼容性最好（部分渲染后端对 8 位 hex 支持不一致）。
        return literal(Colors.toHex(hex, "#000000"));
    }

    /**
     * 把用户输入解析为 SLD 表达式。
     *
     * <p>优先按 CQL 解析（支持 {@code concat(NAME,' ',POP)} 之类），失败则退化为属性名，
     * 再失败退化为字符串字面量。
     */
    public Expression toExpression(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return literal("");
        }
        if (isPlainIdentifier(text)) {
            return prop(text);
        }
        try {
            return CQL.toExpression(text);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "CQL 表达式解析失败，按属性名处理: " + text, e);
            return prop(text);
        }
    }

    private boolean isPlainIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':';
            if (!ok) {
                return false;
            }
        }
        return Character.isLetter(s.charAt(0)) || s.charAt(0) == '_';
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ==================================================================
    // 二、反向：SLD 对象模型 → 表单状态
    // ==================================================================

    /**
     * 把 SLD 对象模型的第一个用户样式回填到表单状态。
     *
     * <p>只覆盖样式本身（符号/标注/栅格/比例尺/分类），不触碰样式名、工作区等元信息。
     *
     * @return 是否成功识别到可编辑的样式
     */
    public boolean readInto(VisualStyleState state, StyledLayerDescriptor sld) {
        Style style = firstUserStyle(sld);
        if (style == null) {
            return false;
        }
        List<FeatureTypeStyle> ftss = style.featureTypeStyles();
        if (ftss == null || ftss.isEmpty()) {
            return false;
        }
        FeatureTypeStyle fts = ftss.get(0);
        List<Rule> rules = fts.rules();
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        readClassification(state, rules);

        Rule rule = rules.get(0);
        List<Symbolizer> syms = rule.symbolizers();
        if (syms == null || syms.isEmpty()) {
            return false;
        }

        // 比例尺
        double min = rule.getMinScaleDenominator();
        double max = rule.getMaxScaleDenominator();
        if (min > 0d || max > 0d) {
            state.setScaleEnabled(true);
            state.setMinScale(min);
            state.setMaxScale(max);
        }

        boolean kindSet = false;
        for (Symbolizer sym : syms) {
            if (sym instanceof RasterSymbolizer) {
                if (!kindSet) {
                    state.setSymbolizerKind(SymbolizerKind.RASTER);
                    kindSet = true;
                }
                readRaster(state, (RasterSymbolizer) sym);
            } else if (sym instanceof TextSymbolizer) {
                readText(state, (TextSymbolizer) sym);
            } else {
                if (!kindSet) {
                    if (sym instanceof PointSymbolizer) {
                        state.setSymbolizerKind(SymbolizerKind.POINT);
                    } else if (sym instanceof LineSymbolizer) {
                        state.setSymbolizerKind(SymbolizerKind.LINE);
                    } else if (sym instanceof PolygonSymbolizer) {
                        state.setSymbolizerKind(SymbolizerKind.POLYGON);
                    }
                    kindSet = true;
                }
                readVector(state, sym);
            }
        }
        return true;
    }

    /** 从多个规则推断分类方式与断点。 */
    private void readClassification(VisualStyleState state, List<Rule> rules) {
        if (rules.size() <= 1) {
            state.setClassificationMethod(ClassificationMethod.SINGLE);
            return;
        }
        List<ClassBreak> breaks = new ArrayList<>();
        String equalityField = null;
        boolean allEquality = true;
        double prevMax = Double.NaN;
        boolean monotonic = true;

        for (Rule rule : rules) {
            if (rule.isElseFilter()) {
                continue;
            }
            ClassBreak brk = new ClassBreak();
            brk.setLabel(rule.getName());
            Symbolizer first = firstVisualSymbolizer(rule);
            if (first != null) {
                String fill = extractFillHex(first);
                if (fill != null) {
                    brk.setFill(fill);
                }
                brk.setFillOpacity(extractOpacity(first));
                brk.setStrokeWidth(extractStrokeWidth(first));
                Double size = extractSize(first);
                if (size != null) {
                    brk.setSize(size.doubleValue());
                }
            }
            Filter filter = rule.getFilter();
            if (filter == null) {
                allEquality = false;
                continue;
            }
            Range r = extractRange(filter);
            if (r == null) {
                allEquality = false;
                continue;
            }
            if (r.equality) {
                brk.setValue(r.value);
                if (equalityField == null) {
                    equalityField = r.field;
                }
            } else {
                brk.setMin(r.min);
                brk.setMax(r.max);
                if (!Double.isNaN(prevMax) && Math.abs(prevMax - r.min) > 1.0e-9) {
                    monotonic = false;
                }
                prevMax = r.max;
                if (equalityField == null) {
                    equalityField = r.field;
                }
            }
            breaks.add(brk);
        }
        if (breaks.isEmpty()) {
            state.setClassificationMethod(ClassificationMethod.SINGLE);
            return;
        }
        state.setBreaks(breaks);
        if (equalityField != null) {
            state.setClassificationField(equalityField);
        }
        if (allEquality) {
            state.setClassificationMethod(ClassificationMethod.UNIQUE_VALUES);
            List<String> values = new ArrayList<>();
            for (ClassBreak b : breaks) {
                if (b.getValue() != null) {
                    values.add(b.getValue());
                }
            }
            state.setUniqueValues(values);
            state.setClassCount(values.size());
        } else if (monotonic) {
            state.setClassificationMethod(ClassificationMethod.EQUAL_INTERVAL);
            state.setClassCount(breaks.size());
        } else {
            state.setClassificationMethod(ClassificationMethod.MANUAL);
            state.setClassCount(breaks.size());
        }
        // 同步颜色到全局配色，避免切换回单符号时颜色丢失
        if (breaks.get(0).getFill() != null) {
            state.setFillColor(breaks.get(0).getFill());
        }
    }

    private Symbolizer firstVisualSymbolizer(Rule rule) {
        for (Symbolizer s : rule.symbolizers()) {
            if (!(s instanceof TextSymbolizer)) {
                return s;
            }
        }
        return null;
    }

    private void readVector(VisualStyleState state, Symbolizer sym) {
        if (sym instanceof PolygonSymbolizer) {
            PolygonSymbolizer ps = (PolygonSymbolizer) sym;
            readFill(state, ps.getFill());
            readStroke(state, ps.getStroke());
        } else if (sym instanceof LineSymbolizer) {
            LineSymbolizer ls = (LineSymbolizer) sym;
            readStroke(state, ls.getStroke());
            if (ls.getPerpendicularOffset() != null) {
                state.setStrokeOffset(asDouble(ls.getPerpendicularOffset(), 0d));
            }
        } else if (sym instanceof PointSymbolizer) {
            readPoint(state, ((PointSymbolizer) sym).getGraphic());
        }
    }

    private void readPoint(VisualStyleState state, Graphic graphic) {
        if (graphic == null) {
            return;
        }
        if (graphic.getSize() != null) {
            state.setPointSize(asDouble(graphic.getSize(), state.getPointSize()));
        }
        if (graphic.getRotation() != null) {
            state.setPointRotation(asDouble(graphic.getRotation(), 0d));
        }
        if (graphic.getOpacity() != null) {
            state.setPointOpacity(asDouble(graphic.getOpacity(), 1d));
        }
        List<org.geotools.api.style.GraphicalSymbol> symbols = graphic.graphicalSymbols();
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        for (org.geotools.api.style.GraphicalSymbol s : symbols) {
            if (s instanceof ExternalGraphic) {
                ExternalGraphic eg = (ExternalGraphic) s;
                // getLocation() 会抛 MalformedURLException，getURI() 更稳妥
                String uri = eg.getURI();
                state.setExternalGraphic(uri == null ? "" : uri);
                state.setExternalGraphicFormat(emptyTo(eg.getFormat(), "image/svg+xml"));
                break;
            }
            if (s instanceof Mark) {
                Mark mark = (Mark) s;
                state.setExternalGraphic("");
                if (mark.getWellKnownName() != null) {
                    state.setMarkShape(asString(mark.getWellKnownName(), "circle"));
                }
                if (mark.getFill() != null && mark.getFill().getColor() != null) {
                    state.setMarkFillColor(asString(mark.getFill().getColor(), state.getMarkFillColor()));
                }
                if (mark.getFill() != null && mark.getFill().getOpacity() != null) {
                    state.setMarkFillOpacity(asDouble(mark.getFill().getOpacity(), 1d));
                }
                if (mark.getStroke() != null) {
                    if (mark.getStroke().getColor() != null) {
                        state.setMarkStrokeColor(asString(mark.getStroke().getColor(), state.getMarkStrokeColor()));
                    }
                    if (mark.getStroke().getWidth() != null) {
                        state.setMarkStrokeWidth(asDouble(mark.getStroke().getWidth(), 0.5d));
                    }
                }
            }
        }
    }

    private void readFill(VisualStyleState state, Fill fill) {
        if (fill == null) {
            state.setFillEnabled(false);
            return;
        }
        state.setFillEnabled(true);
        if (fill.getColor() != null) {
            state.setFillColor(asString(fill.getColor(), state.getFillColor()));
        }
        if (fill.getOpacity() != null) {
            state.setFillOpacity(asDouble(fill.getOpacity(), state.getFillOpacity()));
        }
    }

    private void readStroke(VisualStyleState state, Stroke stroke) {
        if (stroke == null) {
            return;
        }
        if (stroke.getColor() != null) {
            state.setStrokeColor(asString(stroke.getColor(), state.getStrokeColor()));
        }
        if (stroke.getOpacity() != null) {
            state.setStrokeOpacity(asDouble(stroke.getOpacity(), state.getStrokeOpacity()));
        }
        if (stroke.getWidth() != null) {
            state.setStrokeWidth(asDouble(stroke.getWidth(), state.getStrokeWidth()));
        }
        float[] dash = stroke.getDashArray();
        if (dash != null && dash.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dash.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(trimNumber(dash[i]));
            }
            state.setStrokeDashArray(sb.toString());
        } else {
            state.setStrokeDashArray("");
        }
        if (stroke.getLineCap() != null) {
            state.setStrokeLineCap(asString(stroke.getLineCap(), state.getStrokeLineCap()));
        }
        if (stroke.getLineJoin() != null) {
            state.setStrokeLineJoin(asString(stroke.getLineJoin(), state.getStrokeLineJoin()));
        }
    }

    private void readText(VisualStyleState state, TextSymbolizer ts) {
        state.setLabelEnabled(true);
        if (ts.getLabel() != null) {
            state.setLabelField(expressionToText(ts.getLabel()));
        }
        Fill fill = ts.getFill();
        if (fill != null) {
            if (fill.getColor() != null) {
                state.setFontColor(asString(fill.getColor(), state.getFontColor()));
            }
            if (fill.getOpacity() != null) {
                state.setFontOpacity(asDouble(fill.getOpacity(), 1d));
            }
        }
        Font font = ts.getFont();
        if (font != null) {
            List<Expression> family = font.getFamily();
            if (family != null && !family.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Expression e : family) {
                    String n = asString(e, null);
                    if (n != null) {
                        names.add(n);
                    }
                }
                if (!names.isEmpty()) {
                    state.setFontFamily(String.join(", ", names));
                }
            }
            if (font.getSize() != null) {
                state.setFontSize((int) Math.round(asDouble(font.getSize(), 12d)));
            }
            String weight = asString(font.getWeight(), null);
            state.setFontBold("bold".equalsIgnoreCase(weight));
            String style = asString(font.getStyle(), null);
            state.setFontItalic("italic".equalsIgnoreCase(style) || "oblique".equalsIgnoreCase(style));
        }
        Halo halo = ts.getHalo();
        if (halo != null) {
            state.setHaloEnabled(true);
            if (halo.getRadius() != null) {
                state.setHaloRadius(asDouble(halo.getRadius(), 1.5d));
            }
            if (halo.getFill() != null && halo.getFill().getColor() != null) {
                state.setHaloColor(asString(halo.getFill().getColor(), state.getHaloColor()));
            }
        } else {
            state.setHaloEnabled(false);
        }
        LabelPlacement placement = ts.getLabelPlacement();
        if (placement instanceof PointPlacement) {
            PointPlacement pp = (PointPlacement) placement;
            if (pp.getDisplacement() != null) {
                state.setLabelOffsetX(asDouble(pp.getDisplacement().getDisplacementX(), 0d));
                state.setLabelOffsetY(asDouble(pp.getDisplacement().getDisplacementY(), 0d));
            }
            if (pp.getAnchorPoint() != null) {
                double ax = asDouble(pp.getAnchorPoint().getAnchorPointX(), 0.5d);
                state.setLabelAnchor(ax <= 0.01d ? "start" : (ax >= 0.99d ? "end" : "middle"));
            }
            if (pp.getRotation() != null) {
                state.setLabelRotationField(expressionToText(pp.getRotation()));
            }
        }
        Map<String, String> options = ts.getOptions();
        if (options != null) {
            String cr = options.get(TextSymbolizer.CONFLICT_RESOLUTION_KEY);
            if (cr != null) {
                state.setLabelDisplacement(Boolean.parseBoolean(cr));
            }
            String sa = options.get(TextSymbolizer.SPACE_AROUND_KEY);
            if (sa != null) {
                try {
                    state.setLabelSpaceAround((int) Math.round(Double.parseDouble(sa)));
                } catch (NumberFormatException ignore) {
                    // 保持默认
                }
            }
            String group = options.get(TextSymbolizer.GROUP_KEY);
            if (group != null && Boolean.parseBoolean(group)) {
                state.setLabelGroupField(asString(ts.getLabel(), state.getLabelGroupField()));
            }
        }
    }

    private void readRaster(VisualStyleState state, RasterSymbolizer rs) {
        if (rs.getOpacity() != null) {
            state.setRasterOpacity(asDouble(rs.getOpacity(), 1d));
        }
        ChannelSelection cs = rs.getChannelSelection();
        if (cs != null) {
            SelectedChannelType[] rgb = cs.getRGBChannels();
            if (rgb != null && rgb.length >= 3 && rgb[0] != null) {
                state.setRasterMode(RasterMode.RGB);
                state.setRedBand(channelIndex(rgb[0], 1));
                state.setGreenBand(channelIndex(rgb[1], 2));
                state.setBlueBand(channelIndex(rgb[2], 3));
            } else if (cs.getGrayChannel() != null) {
                state.setGrayBand(channelIndex(cs.getGrayChannel(), 1));
            }
        }
        ColorMap cm = rs.getColorMap();
        if (cm != null && cm.getColorMapEntries() != null && cm.getColorMapEntries().length > 0) {
            ColorMapEntry[] entries = cm.getColorMapEntries();
            List<ColorStop> stops = new ArrayList<>();
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (ColorMapEntry e : entries) {
                double q = asDouble(e.getQuantity(), 0d);
                min = Math.min(min, q);
                max = Math.max(max, q);
                stops.add(new ColorStop(0d, e.getColor() == null ? "#000000" : asString(e.getColor(), "#000000")));
            }
            // 用 quantity 的 min/max 归一化 offset
            double span = max - min;
            for (int i = 0; i < stops.size(); i++) {
                double q = asDouble(entries[i].getQuantity(), 0d);
                stops.get(i).setOffset(span <= 0d ? 0d : (q - min) / span);
            }
            state.setColorStops(stops);
            if (span > 0d) {
                state.setStretchMin(min);
                state.setStretchMax(max);
            }
            boolean intervals = cm.getType() == ColorMap.TYPE_INTERVALS;
            state.setRampContinuous(!intervals);
            if (intervals) {
                state.setRampIntervals(Math.max(2, stops.size()));
                if (state.getRasterMode() != RasterMode.RGB) {
                    state.setRasterMode(RasterMode.CLASSIFIED_RAMP);
                }
            } else if (state.getRasterMode() != RasterMode.RGB) {
                state.setRasterMode(RasterMode.COLORMAP);
            }
        }
        ContrastEnhancement ce = rs.getContrastEnhancement();
        if (ce != null) {
            state.setStretchEnabled(true);
            if (ce.getMethod() != null) {
                state.setStretchType(ce.getMethod().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (ce.getGammaValue() != null) {
                state.setGamma(asDouble(ce.getGammaValue(), 1d));
            }
        } else {
            state.setStretchEnabled(false);
        }
    }

    private int channelIndex(SelectedChannelType sct, int dflt) {
        if (sct == null || sct.getChannelName() == null) {
            return dflt;
        }
        String name = asString(sct.getChannelName(), null);
        if (name == null) {
            return dflt;
        }
        // 形如 "1" 或 "Band1"
        String digits = name.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------
    // 过滤器反向解析
    // ------------------------------------------------------------------

    private static class Range {
        boolean equality;
        /** true 表示该比较给出区间下界，false 表示上界。 */
        boolean lower;
        String field;
        String value;
        double min = Double.NaN;
        double max = Double.NaN;
    }

    /** 从规则过滤器中提取「字段 + 等值」或「字段 + 区间」。 */
    private Range extractRange(Filter filter) {
        if (filter == null) {
            return null;
        }
        if (filter instanceof org.geotools.api.filter.And) {
            org.geotools.api.filter.And and = (org.geotools.api.filter.And) filter;
            List<Filter> children = and.getChildren();
            if (children == null || children.size() != 2) {
                return null;
            }
            Range lo = extractComparison(children.get(0));
            Range hi = extractComparison(children.get(1));
            // 兼容顺序颠倒
            if (lo == null || hi == null || !lo.lower) {
                Range t = lo;
                lo = hi;
                hi = t;
            }
            if (lo == null || hi == null || !lo.lower) {
                return null;
            }
            if (lo.field == null || !lo.field.equals(hi.field)) {
                return null;
            }
            Range r = new Range();
            r.equality = false;
            r.field = lo.field;
            r.min = lo.min;
            r.max = hi.max;
            if (Double.isNaN(r.min) && !Double.isNaN(lo.max)) {
                // 只有上界的情况
                r.min = Double.NEGATIVE_INFINITY;
            }
            if (Double.isNaN(r.max)) {
                r.max = Double.POSITIVE_INFINITY;
            }
            return r;
        }
        return null;
    }

    private Range extractComparison(Filter filter) {
        if (!(filter instanceof org.geotools.api.filter.BinaryComparisonOperator)) {
            return null;
        }
        org.geotools.api.filter.BinaryComparisonOperator op =
                (org.geotools.api.filter.BinaryComparisonOperator) filter;
        String field = asString(op.getExpression1(), null);
        Double value = asDoubleObj(op.getExpression2());
        if (value == null) {
            // 兼容 literal 在左侧
            field = asString(op.getExpression2(), null);
            value = asDoubleObj(op.getExpression1());
        }
        if (field == null || value == null) {
            return null;
        }
        Range r = new Range();
        r.field = field;
        if (filter instanceof org.geotools.api.filter.PropertyIsEqualTo) {
            r.equality = true;
            r.value = literalToText(op.getExpression2());
            return r;
        }
        if (filter instanceof org.geotools.api.filter.PropertyIsGreaterThanOrEqualTo) {
            r.min = value.doubleValue();
            r.lower = true;
            return r;
        }
        if (filter instanceof org.geotools.api.filter.PropertyIsGreaterThan) {
            r.min = value.doubleValue();
            r.lower = true;
            return r;
        }
        if (filter instanceof org.geotools.api.filter.PropertyIsLessThan) {
            r.max = value.doubleValue();
            r.lower = false;
            return r;
        }
        if (filter instanceof org.geotools.api.filter.PropertyIsLessThanOrEqualTo) {
            r.max = value.doubleValue();
            r.lower = false;
            return r;
        }
        LOGGER.log(Level.FINEST, "未识别的比较过滤器: {0}", filter.getClass().getName());
        return null;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private Style firstUserStyle(StyledLayerDescriptor sld) {
        if (sld == null || sld.getStyledLayers() == null) {
            return null;
        }
        for (org.geotools.api.style.StyledLayer sl : sld.getStyledLayers()) {
            Style[] styles = null;
            if (sl instanceof NamedLayer) {
                styles = ((NamedLayer) sl).getStyles();
            } else if (sl instanceof org.geotools.api.style.UserLayer) {
                styles = ((org.geotools.api.style.UserLayer) sl).getUserStyles();
            }
            if (styles == null) {
                continue;
            }
            for (Style s : styles) {
                if (s != null && !(s instanceof org.geotools.api.style.NamedStyle)) {
                    return s;
                }
            }
        }
        return null;
    }

    private String extractFillHex(Symbolizer sym) {
        Fill fill = null;
        if (sym instanceof PolygonSymbolizer) {
            fill = ((PolygonSymbolizer) sym).getFill();
        } else if (sym instanceof PointSymbolizer) {
            Graphic g = ((PointSymbolizer) sym).getGraphic();
            if (g != null && g.graphicalSymbols() != null && !g.graphicalSymbols().isEmpty()) {
                org.geotools.api.style.GraphicalSymbol gs = g.graphicalSymbols().get(0);
                if (gs instanceof Mark) {
                    fill = ((Mark) gs).getFill();
                }
            }
        } else if (sym instanceof LineSymbolizer) {
            Stroke st = ((LineSymbolizer) sym).getStroke();
            if (st != null && st.getColor() != null) {
                return asString(st.getColor(), null);
            }
        }
        if (fill != null && fill.getColor() != null) {
            return asString(fill.getColor(), null);
        }
        return null;
    }

    private double extractOpacity(Symbolizer sym) {
        if (sym instanceof PolygonSymbolizer && ((PolygonSymbolizer) sym).getFill() != null
                && ((PolygonSymbolizer) sym).getFill().getOpacity() != null) {
            return asDouble(((PolygonSymbolizer) sym).getFill().getOpacity(), 1d);
        }
        return 1d;
    }

    private double extractStrokeWidth(Symbolizer sym) {
        Stroke st = null;
        if (sym instanceof PolygonSymbolizer) {
            st = ((PolygonSymbolizer) sym).getStroke();
        } else if (sym instanceof LineSymbolizer) {
            st = ((LineSymbolizer) sym).getStroke();
        }
        if (st != null && st.getWidth() != null) {
            return asDouble(st.getWidth(), 1d);
        }
        return 1d;
    }

    private Double extractSize(Symbolizer sym) {
        if (sym instanceof PointSymbolizer) {
            Graphic g = ((PointSymbolizer) sym).getGraphic();
            if (g != null && g.getSize() != null) {
                return Double.valueOf(asDouble(g.getSize(), 8d));
            }
        }
        return null;
    }

    /** 把表达式压平成可读文本（属性名 / 字面量 / CQL）。 */
    private String expressionToText(Expression e) {
        if (e == null) {
            return "";
        }
        if (e instanceof org.geotools.api.filter.expression.PropertyName) {
            return ((org.geotools.api.filter.expression.PropertyName) e).getPropertyName();
        }
        if (e instanceof org.geotools.api.filter.expression.Literal) {
            Object v = ((org.geotools.api.filter.expression.Literal) e).getValue();
            return v == null ? "" : String.valueOf(v);
        }
        String s = e.toString();
        // PropertyNameImpl 的 toString 形如 "[NAME]"
        if (s.startsWith("[") && s.endsWith("]")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String literalToText(Expression e) {
        return expressionToText(e);
    }

    private String asString(Expression e, String dflt) {
        if (e == null) {
            return dflt;
        }
        if (e instanceof org.geotools.api.filter.expression.Literal) {
            Object v = ((org.geotools.api.filter.expression.Literal) e).getValue();
            return v == null ? dflt : String.valueOf(v);
        }
        String s = e.toString();
        if (s.startsWith("[") && s.endsWith("]")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private double asDouble(Expression e, double dflt) {
        Double d = asDoubleObj(e);
        return d == null || d.isNaN() ? dflt : d.doubleValue();
    }

    private Double asDoubleObj(Expression e) {
        if (e == null) {
            return null;
        }
        if (e instanceof org.geotools.api.filter.expression.Literal) {
            Object v = ((org.geotools.api.filter.expression.Literal) e).getValue();
            if (v instanceof Number) {
                return Double.valueOf(((Number) v).doubleValue());
            }
            if (v != null) {
                try {
                    return Double.valueOf(Double.parseDouble(String.valueOf(v).trim()));
                } catch (NumberFormatException ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String trimNumber(float f) {
        if (f == Math.rint(f)) {
            return Integer.toString((int) f);
        }
        return Float.toString(f);
    }
}
