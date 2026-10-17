/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geoserver.web.style.util.Colors;
import org.geoserver.web.style.util.Json;
import org.geoserver.web.style.util.Ramps;

/**
 * 可视化编辑面板的「表单状态」。
 *
 * <p>这是前后端之间的<b>唯一契约对象</b>，也是 Wicket 组件的可序列化模型：
 * <ul>
 *   <li>前端：字段名与 {@link #applyRequestParams(Map)} 中的键一一对应；</li>
 *   <li>后端：{@code StyleCompiler} 依据本对象生成 GeoTools {@code Style} 与 SLD 1.1.0 XML；</li>
 *   <li>反向：{@code StyleCompiler} 把已有 SLD 解析回填到本对象。</li>
 * </ul>
 *
 * <p>刻意只保存「标量 + 列表」，不持有任何 GeoTools / Wicket 对象，
 * 以确保 Wicket 会话序列化安全（GeoTools 的 Style 对象不可序列化）。
 */
public class VisualStyleState implements Serializable {

    private static final long serialVersionUID = 1L;

    // ============================================================
    // 1. 样式元信息
    // ============================================================

    /** 样式名。 */
    private String styleName = "";

    /** 工作区名；空字符串表示全局样式。 */
    private String workspace = "";

    /** 样式格式：{@code sld} 或 {@code css}。 */
    private String format = "sld";

    /** 格式版本，如 1.1.0。 */
    private String formatVersion = "1.1.0";

    /** 样式文件名（readonly，来源于 Catalog）。 */
    private String filename = "";

    /** 是否为新样式（尚未持久化）。 */
    private boolean newStyle = true;

    /** 绑定的预览图层（全名，含工作区前缀）。 */
    private String previewLayer = "";

    // ============================================================
    // 2. 符号类型与分类方式
    // ============================================================

    /** 符号化类型。 */
    private SymbolizerKind symbolizerKind = SymbolizerKind.POLYGON;

    /** 分类方式。 */
    private ClassificationMethod classificationMethod = ClassificationMethod.SINGLE;

    /** 分类依据字段（属性名 / 栅格波段名）。 */
    private String classificationField = "";

    /** 分级数量。 */
    private int classCount = 5;

    /** 分级/分类结果。 */
    private List<ClassBreak> breaks = new ArrayList<>();

    /** 唯一值分类时可枚举的全部唯一值。 */
    private List<String> uniqueValues = new ArrayList<>();

    // ============================================================
    // 3. 矢量符号 —— 填充
    // ============================================================

    private String fillColor = Colors.DEFAULT_FILL;

    private double fillOpacity = 0.85d;

    /** 是否绘制填充（面符号可关闭填充只留描边）。 */
    private boolean fillEnabled = true;

    /** 填充图案/图元名（面符号的 GraphicFill 可选，留空为纯色）。 */
    private String fillPattern = "";

    // ============================================================
    // 4. 矢量符号 —— 描边
    // ============================================================

    private String strokeColor = Colors.DEFAULT_STROKE;

    private double strokeOpacity = 1d;

    private double strokeWidth = 1d;

    /** 虚线样式，形如 "6 3"；空为实线。 */
    private String strokeDashArray = "";

    private String strokeLineCap = "butt";

    private String strokeLineJoin = "mitre";

    /** 描边偏移（LineSymbolizer 的 PerpendicularOffset）。 */
    private double strokeOffset = 0d;

    // ============================================================
    // 5. 矢量符号 —— 点符号（Graphic）
    // ============================================================

    private double pointSize = 10d;

    private double pointRotation = 0d;

    /** 内置标记名：circle / square / triangle / star / cross / x / arrow。 */
    private String markShape = "circle";

    /** 外部图形（SVG / PNG）URL；非空时优先于内置标记。 */
    private String externalGraphic = "";

    /** 外部图形 MIME 类型。 */
    private String externalGraphicFormat = "image/svg+xml";

    /** 点符号整体透明度。 */
    private double pointOpacity = 1d;

    /** 标记内部填充色（Graphic 的 Mark 填充）。 */
    private String markFillColor = "#ffffff";

    private double markFillOpacity = 1d;

    /** 标记边框色。 */
    private String markStrokeColor = "#333333";

    private double markStrokeWidth = 0.5d;

    /** 是否用「字段值」驱动点大小（气泡图）。 */
    private String sizeByField = "";

    private double sizeMin = 4d;

    private double sizeMax = 28d;

    // ============================================================
    // 6. 标注 TextSymbolizer
    // ============================================================

    private boolean labelEnabled = false;

    /** 标注字段；支持 CQL 表达式。 */
    private String labelField = "";

    private String fontFamily = "Microsoft YaHei, Noto Sans CJK SC, DejaVu Sans, sans-serif";

    private int fontSize = 12;

    private boolean fontBold = false;

    private boolean fontItalic = false;

    private String fontColor = Colors.DEFAULT_LABEL;

    private double fontOpacity = 1d;

    /** 光晕（Halo）开关与参数。 */
    private boolean haloEnabled = true;

    private String haloColor = Colors.DEFAULT_HALO;

    private double haloRadius = 1.5d;

    /** 标注偏移（像素）。 */
    private double labelOffsetX = 0d;

    private double labelOffsetY = -10d;

    /** 锚点：start / middle / end（针对线要素的沿线上标注）。 */
    private String labelAnchor = "middle";

    /** 是否启用标注避让（VendorOption conflictResolution/spaceAround）。 */
    private boolean labelDisplacement = true;

    private int labelSpaceAround = 10;

    /** 是否按比例尺显示标注（与 scale 联动时的独立阈值）。 */
    private boolean labelScaleEnabled = false;

    private double labelMinScale = 0d;

    private double labelMaxScale = 0d;

    /** 标注旋转字段（可选）。 */
    private String labelRotationField = "";

    /** 分组避让字段（VendorOption group）。 */
    private String labelGroupField = "";

    // ============================================================
    // 7. 栅格符号 RasterSymbolizer
    // ============================================================

    private RasterMode rasterMode = RasterMode.GRAY;

    /** 灰度波段序号（1 基）。 */
    private int grayBand = 1;

    private int redBand = 1;

    private int greenBand = 2;

    private int blueBand = 3;

    private double rasterOpacity = 1d;

    /** 通道选择开关（ColorMap 时可用）。 */
    private boolean channelSelectionEnabled = false;

    /** 对比度拉伸（ContrastEnhancement）。 */
    private boolean stretchEnabled = true;

    private String stretchType = "linear";

    /** 归一化方式：none / stretchToMinimumMaximum / clipToMinimumMaximum。 */
    private String stretchNormalize = "stretchToMinimumMaximum";

    private double stretchMin = 0d;

    private double stretchMax = 255d;

    private double gamma = 1d;

    /** 色带控制点。 */
    private List<ColorStop> colorStops = new ArrayList<>();

    /** 色带预设 id（选择预设时使用）。 */
    private String rampPreset = "viridis";

    /** 是否反转色带。 */
    private boolean rampReversed = false;

    /** 连续（interpolate）或离散（interval）。 */
    private boolean rampContinuous = true;

    /** 离散色带的分级数。 */
    private int rampIntervals = 8;

    /** DEM 高程色阶 id。 */
    private String demPalette = "dem_hypso";

    // ============================================================
    // 8. 比例尺控制
    // ============================================================

    private boolean scaleEnabled = false;

    /** MinScaleDenominator；0 表示不限。 */
    private double minScale = 0d;

    /** MaxScaleDenominator；0 表示不限。 */
    private double maxScale = 0d;

    // ============================================================
    // 9. 源码
    // ============================================================

    /** 当前源码正文（SLD XML 或 GeoCSS）。 */
    private String rawStyle = "";

    /** 源码是否为「源码优先」模式（用户手动编辑过，暂不覆盖）。 */
    private boolean sourceDirty = false;

    /** 图例在线资源（LegendURL）。 */
    private String legendOnlineResource = "";

    // ============================================================
    // 便捷访问
    // ============================================================

    public String getQualifiedName() {
        if (workspace == null || workspace.isBlank()) {
            return styleName == null ? "" : styleName;
        }
        return workspace + ":" + styleName;
    }

    public boolean isRaster() {
        return symbolizerKind == SymbolizerKind.RASTER;
    }

    public List<ClassBreak> getBreaks() {
        if (breaks == null) {
            breaks = new ArrayList<>();
        }
        return breaks;
    }

    public List<ColorStop> getColorStops() {
        if (colorStops == null) {
            colorStops = new ArrayList<>();
        }
        return colorStops;
    }

    /** 当前生效的色带控制点：优先用户自定义，否则取预设。 */
    public List<ColorStop> effectiveColorStops() {
        List<ColorStop> custom = getColorStops();
        if (!custom.isEmpty()) {
            return custom;
        }
        String presetId = isRaster() && rasterMode == RasterMode.DEM ? demPalette : rampPreset;
        List<ColorStop> stops = Ramps.parseStops(Ramps.get(presetId).stops);
        return rampReversed ? reverse(stops) : stops;
    }

    public static List<ColorStop> reverse(List<ColorStop> in) {
        List<ColorStop> out = new ArrayList<>(in.size());
        for (ColorStop s : in) {
            out.add(new ColorStop(1d - s.getOffset(), s.getColor()));
        }
        out.sort((a, b) -> Double.compare(a.getOffset(), b.getOffset()));
        return out;
    }

    /** 应用某个内置色带预设到 {@link #colorStops}。 */
    public void applyRampPreset(String presetId) {
        this.rampPreset = presetId;
        String src = Ramps.get(presetId).stops;
        List<ColorStop> stops = Ramps.parseStops(src);
        if (rampReversed) {
            stops = reverse(stops);
        }
        this.colorStops = stops;
    }

    // ============================================================
    // 请求参数绑定（前端表单 → 本对象）
    // ============================================================

    /**
     * 依据前端提交的表单参数更新本对象。
     *
     * <p>约定：字段名与本类字段同名（前缀 {@code vs.}），列表型字段使用
     * {@code vs.breaks.0.min} / {@code vs.colorStops.2.color} 之类的索引命名。
     */
    public void applyRequestParams(Map<String, String[]> params) {
        String v;

        if ((v = get(params, "vs.styleName")) != null) {
            styleName = v.trim();
        }
        if ((v = get(params, "vs.workspace")) != null) {
            workspace = v.trim();
        }
        if ((v = get(params, "vs.format")) != null && !v.isBlank()) {
            format = v.trim();
        }
        if ((v = get(params, "vs.previewLayer")) != null) {
            previewLayer = v.trim();
        }

        if ((v = get(params, "vs.symbolizerKind")) != null) {
            symbolizerKind = SymbolizerKind.fromId(v);
        }
        if ((v = get(params, "vs.classificationMethod")) != null) {
            classificationMethod = ClassificationMethod.fromId(v);
        }
        if ((v = get(params, "vs.classificationField")) != null) {
            classificationField = v.trim();
        }
        classCount = intOf(params, "vs.classCount", classCount);

        // ---- 矢量：填充 ----
        if ((v = get(params, "vs.fillColor")) != null) {
            fillColor = Colors.toHex(v, fillColor);
        }
        fillOpacity = dblOf(params, "vs.fillOpacity", fillOpacity);
        fillEnabled = boolOf(params, "vs.fillEnabled", fillEnabled);
        if ((v = get(params, "vs.fillPattern")) != null) {
            fillPattern = v.trim();
        }

        // ---- 矢量：描边 ----
        if ((v = get(params, "vs.strokeColor")) != null) {
            strokeColor = Colors.toHex(v, strokeColor);
        }
        strokeOpacity = dblOf(params, "vs.strokeOpacity", strokeOpacity);
        strokeWidth = dblOf(params, "vs.strokeWidth", strokeWidth);
        if ((v = get(params, "vs.strokeDashArray")) != null) {
            strokeDashArray = v.trim();
        }
        if ((v = get(params, "vs.strokeLineCap")) != null && !v.isBlank()) {
            strokeLineCap = v.trim();
        }
        if ((v = get(params, "vs.strokeLineJoin")) != null && !v.isBlank()) {
            strokeLineJoin = v.trim();
        }
        strokeOffset = dblOf(params, "vs.strokeOffset", strokeOffset);

        // ---- 矢量：点符号 ----
        pointSize = dblOf(params, "vs.pointSize", pointSize);
        pointRotation = dblOf(params, "vs.pointRotation", pointRotation);
        if ((v = get(params, "vs.markShape")) != null && !v.isBlank()) {
            markShape = v.trim();
        }
        if ((v = get(params, "vs.externalGraphic")) != null) {
            externalGraphic = v.trim();
        }
        if ((v = get(params, "vs.externalGraphicFormat")) != null && !v.isBlank()) {
            externalGraphicFormat = v.trim();
        }
        pointOpacity = dblOf(params, "vs.pointOpacity", pointOpacity);
        if ((v = get(params, "vs.markFillColor")) != null) {
            markFillColor = Colors.toHex(v, markFillColor);
        }
        markFillOpacity = dblOf(params, "vs.markFillOpacity", markFillOpacity);
        if ((v = get(params, "vs.markStrokeColor")) != null) {
            markStrokeColor = Colors.toHex(v, markStrokeColor);
        }
        markStrokeWidth = dblOf(params, "vs.markStrokeWidth", markStrokeWidth);
        if ((v = get(params, "vs.sizeByField")) != null) {
            sizeByField = v.trim();
        }
        sizeMin = dblOf(params, "vs.sizeMin", sizeMin);
        sizeMax = dblOf(params, "vs.sizeMax", sizeMax);

        // ---- 标注 ----
        labelEnabled = boolOf(params, "vs.labelEnabled", labelEnabled);
        if ((v = get(params, "vs.labelField")) != null) {
            labelField = v.trim();
        }
        if ((v = get(params, "vs.fontFamily")) != null && !v.isBlank()) {
            fontFamily = v.trim();
        }
        fontSize = intOf(params, "vs.fontSize", fontSize);
        fontBold = boolOf(params, "vs.fontBold", fontBold);
        fontItalic = boolOf(params, "vs.fontItalic", fontItalic);
        if ((v = get(params, "vs.fontColor")) != null) {
            fontColor = Colors.toHex(v, fontColor);
        }
        fontOpacity = dblOf(params, "vs.fontOpacity", fontOpacity);
        haloEnabled = boolOf(params, "vs.haloEnabled", haloEnabled);
        if ((v = get(params, "vs.haloColor")) != null) {
            haloColor = Colors.toHex(v, haloColor);
        }
        haloRadius = dblOf(params, "vs.haloRadius", haloRadius);
        labelOffsetX = dblOf(params, "vs.labelOffsetX", labelOffsetX);
        labelOffsetY = dblOf(params, "vs.labelOffsetY", labelOffsetY);
        if ((v = get(params, "vs.labelAnchor")) != null && !v.isBlank()) {
            labelAnchor = v.trim();
        }
        labelDisplacement = boolOf(params, "vs.labelDisplacement", labelDisplacement);
        labelSpaceAround = intOf(params, "vs.labelSpaceAround", labelSpaceAround);
        labelScaleEnabled = boolOf(params, "vs.labelScaleEnabled", labelScaleEnabled);
        labelMinScale = dblOf(params, "vs.labelMinScale", labelMinScale);
        labelMaxScale = dblOf(params, "vs.labelMaxScale", labelMaxScale);
        if ((v = get(params, "vs.labelRotationField")) != null) {
            labelRotationField = v.trim();
        }
        if ((v = get(params, "vs.labelGroupField")) != null) {
            labelGroupField = v.trim();
        }

        // ---- 栅格 ----
        if ((v = get(params, "vs.rasterMode")) != null) {
            rasterMode = RasterMode.fromId(v);
        }
        grayBand = intOf(params, "vs.grayBand", grayBand);
        redBand = intOf(params, "vs.redBand", redBand);
        greenBand = intOf(params, "vs.greenBand", greenBand);
        blueBand = intOf(params, "vs.blueBand", blueBand);
        rasterOpacity = dblOf(params, "vs.rasterOpacity", rasterOpacity);
        channelSelectionEnabled = boolOf(params, "vs.channelSelectionEnabled", channelSelectionEnabled);
        stretchEnabled = boolOf(params, "vs.stretchEnabled", stretchEnabled);
        if ((v = get(params, "vs.stretchType")) != null && !v.isBlank()) {
            stretchType = v.trim();
        }
        if ((v = get(params, "vs.stretchNormalize")) != null && !v.isBlank()) {
            stretchNormalize = v.trim();
        }
        stretchMin = dblOf(params, "vs.stretchMin", stretchMin);
        stretchMax = dblOf(params, "vs.stretchMax", stretchMax);
        gamma = dblOf(params, "vs.gamma", gamma);
        if ((v = get(params, "vs.rampPreset")) != null && !v.isBlank()) {
            rampPreset = v.trim();
        }
        rampReversed = boolOf(params, "vs.rampReversed", rampReversed);
        rampContinuous = boolOf(params, "vs.rampContinuous", rampContinuous);
        rampIntervals = intOf(params, "vs.rampIntervals", rampIntervals);
        if ((v = get(params, "vs.demPalette")) != null && !v.isBlank()) {
            demPalette = v.trim();
        }

        // ---- 比例尺 ----
        scaleEnabled = boolOf(params, "vs.scaleEnabled", scaleEnabled);
        minScale = dblOf(params, "vs.minScale", minScale);
        maxScale = dblOf(params, "vs.maxScale", maxScale);

        // ---- 图例 ----
        if ((v = get(params, "vs.legendOnlineResource")) != null) {
            legendOnlineResource = v.trim();
        }

        // ---- 列表型字段 ----
        List<ColorStop> stops = bindColorStops(params);
        if (stops != null) {
            colorStops = stops;
        }
        List<ClassBreak> brks = bindBreaks(params);
        if (brks != null) {
            breaks = brks;
        }
        List<String> uniq = bindUniqueValues(params);
        if (uniq != null) {
            uniqueValues = uniq;
        }
    }

    private static List<ColorStop> bindColorStops(Map<String, String[]> params) {
        boolean touched = false;
        for (String k : params.keySet()) {
            if (k.startsWith("vs.colorStops.")) {
                touched = true;
                break;
            }
        }
        if (!touched) {
            return null;
        }
        int count = intOf(params, "vs.colorStops.count", 0);
        List<ColorStop> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String offset = get(params, "vs.colorStops." + i + ".offset");
            String color = get(params, "vs.colorStops." + i + ".color");
            if (offset == null && color == null) {
                continue;
            }
            ColorStop cs = new ColorStop();
            try {
                cs.setOffset(offset == null ? 0d : Double.parseDouble(offset.trim()));
            } catch (NumberFormatException ignore) {
                cs.setOffset(0d);
            }
            cs.setColor(color == null ? "#000000" : color.trim());
            out.add(cs);
        }
        out.sort((a, b) -> Double.compare(a.getOffset(), b.getOffset()));
        return out;
    }

    private static List<ClassBreak> bindBreaks(Map<String, String[]> params) {
        boolean touched = false;
        for (String k : params.keySet()) {
            if (k.startsWith("vs.breaks.")) {
                touched = true;
                break;
            }
        }
        if (!touched) {
            return null;
        }
        int count = intOf(params, "vs.breaks.count", 0);
        List<ClassBreak> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ClassBreak b = new ClassBreak();
            b.setMin(dblOf(params, "vs.breaks." + i + ".min", 0d));
            b.setMax(dblOf(params, "vs.breaks." + i + ".max", 0d));
            String value = get(params, "vs.breaks." + i + ".value");
            if (value != null && !value.isEmpty()) {
                b.setValue(value);
            }
            String label = get(params, "vs.breaks." + i + ".label");
            if (label != null) {
                b.setLabel(label);
            }
            String fill = get(params, "vs.breaks." + i + ".fill");
            if (fill != null) {
                b.setFill(Colors.toHex(fill, "#3D7EBB"));
            }
            b.setFillOpacity(dblOf(params, "vs.breaks." + i + ".fillOpacity", 1d));
            String stroke = get(params, "vs.breaks." + i + ".stroke");
            if (stroke != null) {
                b.setStroke(Colors.toHex(stroke, "#1F4E79"));
            }
            b.setStrokeWidth(dblOf(params, "vs.breaks." + i + ".strokeWidth", 1d));
            b.setSize(dblOf(params, "vs.breaks." + i + ".size", 8d));
            out.add(b);
        }
        return out;
    }

    private static List<String> bindUniqueValues(Map<String, String[]> params) {
        boolean touched = false;
        for (String k : params.keySet()) {
            if (k.startsWith("vs.uniqueValues.")) {
                touched = true;
                break;
            }
        }
        if (!touched) {
            return null;
        }
        int count = intOf(params, "vs.uniqueValues.count", 0);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String s = get(params, "vs.uniqueValues." + i);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    // ============================================================
    // 序列化给前端
    // ============================================================

    /** 输出完整状态 JSON（供前端恢复 UI）。 */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("styleName", styleName);
        m.put("workspace", workspace);
        m.put("format", format);
        m.put("formatVersion", formatVersion);
        m.put("filename", filename);
        m.put("newStyle", Boolean.valueOf(newStyle));
        m.put("previewLayer", previewLayer);
        m.put("symbolizerKind", symbolizerKind.getId());
        m.put("classificationMethod", classificationMethod.getId());
        m.put("classificationField", classificationField);
        m.put("classCount", Integer.valueOf(classCount));
        m.put("fillColor", fillColor);
        m.put("fillOpacity", Double.valueOf(fillOpacity));
        m.put("fillEnabled", Boolean.valueOf(fillEnabled));
        m.put("fillPattern", fillPattern);
        m.put("strokeColor", strokeColor);
        m.put("strokeOpacity", Double.valueOf(strokeOpacity));
        m.put("strokeWidth", Double.valueOf(strokeWidth));
        m.put("strokeDashArray", strokeDashArray);
        m.put("strokeLineCap", strokeLineCap);
        m.put("strokeLineJoin", strokeLineJoin);
        m.put("strokeOffset", Double.valueOf(strokeOffset));
        m.put("pointSize", Double.valueOf(pointSize));
        m.put("pointRotation", Double.valueOf(pointRotation));
        m.put("markShape", markShape);
        m.put("externalGraphic", externalGraphic);
        m.put("externalGraphicFormat", externalGraphicFormat);
        m.put("pointOpacity", Double.valueOf(pointOpacity));
        m.put("markFillColor", markFillColor);
        m.put("markFillOpacity", Double.valueOf(markFillOpacity));
        m.put("markStrokeColor", markStrokeColor);
        m.put("markStrokeWidth", Double.valueOf(markStrokeWidth));
        m.put("sizeByField", sizeByField);
        m.put("sizeMin", Double.valueOf(sizeMin));
        m.put("sizeMax", Double.valueOf(sizeMax));
        m.put("labelEnabled", Boolean.valueOf(labelEnabled));
        m.put("labelField", labelField);
        m.put("fontFamily", fontFamily);
        m.put("fontSize", Integer.valueOf(fontSize));
        m.put("fontBold", Boolean.valueOf(fontBold));
        m.put("fontItalic", Boolean.valueOf(fontItalic));
        m.put("fontColor", fontColor);
        m.put("fontOpacity", Double.valueOf(fontOpacity));
        m.put("haloEnabled", Boolean.valueOf(haloEnabled));
        m.put("haloColor", haloColor);
        m.put("haloRadius", Double.valueOf(haloRadius));
        m.put("labelOffsetX", Double.valueOf(labelOffsetX));
        m.put("labelOffsetY", Double.valueOf(labelOffsetY));
        m.put("labelAnchor", labelAnchor);
        m.put("labelDisplacement", Boolean.valueOf(labelDisplacement));
        m.put("labelSpaceAround", Integer.valueOf(labelSpaceAround));
        m.put("labelScaleEnabled", Boolean.valueOf(labelScaleEnabled));
        m.put("labelMinScale", Double.valueOf(labelMinScale));
        m.put("labelMaxScale", Double.valueOf(labelMaxScale));
        m.put("labelRotationField", labelRotationField);
        m.put("labelGroupField", labelGroupField);
        m.put("rasterMode", rasterMode.getId());
        m.put("grayBand", Integer.valueOf(grayBand));
        m.put("redBand", Integer.valueOf(redBand));
        m.put("greenBand", Integer.valueOf(greenBand));
        m.put("blueBand", Integer.valueOf(blueBand));
        m.put("rasterOpacity", Double.valueOf(rasterOpacity));
        m.put("channelSelectionEnabled", Boolean.valueOf(channelSelectionEnabled));
        m.put("stretchEnabled", Boolean.valueOf(stretchEnabled));
        m.put("stretchType", stretchType);
        m.put("stretchNormalize", stretchNormalize);
        m.put("stretchMin", Double.valueOf(stretchMin));
        m.put("stretchMax", Double.valueOf(stretchMax));
        m.put("gamma", Double.valueOf(gamma));
        m.put("rampPreset", rampPreset);
        m.put("rampReversed", Boolean.valueOf(rampReversed));
        m.put("rampContinuous", Boolean.valueOf(rampContinuous));
        m.put("rampIntervals", Integer.valueOf(rampIntervals));
        m.put("demPalette", demPalette);
        m.put("scaleEnabled", Boolean.valueOf(scaleEnabled));
        m.put("minScale", Double.valueOf(minScale));
        m.put("maxScale", Double.valueOf(maxScale));
        m.put("legendOnlineResource", legendOnlineResource);

        List<Object> stops = new ArrayList<>();
        for (ColorStop cs : colorStops == null ? new ArrayList<ColorStop>() : colorStops) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("offset", Double.valueOf(cs.getOffset()));
            sm.put("color", cs.getColor());
            stops.add(sm);
        }
        m.put("colorStops", stops);

        List<Object> bs = new ArrayList<>();
        for (ClassBreak b : getBreaks()) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("min", Double.valueOf(b.getMin()));
            bm.put("max", Double.valueOf(b.getMax()));
            bm.put("value", b.getValue());
            bm.put("label", b.getLabel());
            bm.put("fill", b.getFill());
            bm.put("fillOpacity", Double.valueOf(b.getFillOpacity()));
            bm.put("stroke", b.getStroke());
            bm.put("strokeWidth", Double.valueOf(b.getStrokeWidth()));
            bm.put("size", Double.valueOf(b.getSize()));
            bs.add(bm);
        }
        m.put("breaks", bs);
        m.put("uniqueValues", new ArrayList<>(uniqueValues == null ? new ArrayList<String>() : uniqueValues));
        return Json.write(m);
    }

    // ============================================================
    // getter / setter
    // ============================================================

    public String getStyleName() {
        return styleName;
    }

    public void setStyleName(String styleName) {
        this.styleName = styleName;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public boolean isNewStyle() {
        return newStyle;
    }

    public void setNewStyle(boolean newStyle) {
        this.newStyle = newStyle;
    }

    public String getPreviewLayer() {
        return previewLayer;
    }

    public void setPreviewLayer(String previewLayer) {
        this.previewLayer = previewLayer;
    }

    public SymbolizerKind getSymbolizerKind() {
        return symbolizerKind;
    }

    public void setSymbolizerKind(SymbolizerKind symbolizerKind) {
        this.symbolizerKind = symbolizerKind;
    }

    public ClassificationMethod getClassificationMethod() {
        return classificationMethod;
    }

    public void setClassificationMethod(ClassificationMethod classificationMethod) {
        this.classificationMethod = classificationMethod;
    }

    public String getClassificationField() {
        return classificationField;
    }

    public void setClassificationField(String classificationField) {
        this.classificationField = classificationField;
    }

    public int getClassCount() {
        return classCount;
    }

    public void setClassCount(int classCount) {
        this.classCount = classCount;
    }

    public void setBreaks(List<ClassBreak> breaks) {
        this.breaks = breaks;
    }

    public List<String> getUniqueValues() {
        if (uniqueValues == null) {
            uniqueValues = new ArrayList<>();
        }
        return uniqueValues;
    }

    public void setUniqueValues(List<String> uniqueValues) {
        this.uniqueValues = uniqueValues;
    }

    public String getFillColor() {
        return fillColor;
    }

    public void setFillColor(String fillColor) {
        this.fillColor = fillColor;
    }

    public double getFillOpacity() {
        return fillOpacity;
    }

    public void setFillOpacity(double fillOpacity) {
        this.fillOpacity = fillOpacity;
    }

    public boolean isFillEnabled() {
        return fillEnabled;
    }

    public void setFillEnabled(boolean fillEnabled) {
        this.fillEnabled = fillEnabled;
    }

    public String getFillPattern() {
        return fillPattern;
    }

    public void setFillPattern(String fillPattern) {
        this.fillPattern = fillPattern;
    }

    public String getStrokeColor() {
        return strokeColor;
    }

    public void setStrokeColor(String strokeColor) {
        this.strokeColor = strokeColor;
    }

    public double getStrokeOpacity() {
        return strokeOpacity;
    }

    public void setStrokeOpacity(double strokeOpacity) {
        this.strokeOpacity = strokeOpacity;
    }

    public double getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(double strokeWidth) {
        this.strokeWidth = strokeWidth;
    }

    public String getStrokeDashArray() {
        return strokeDashArray;
    }

    public void setStrokeDashArray(String strokeDashArray) {
        this.strokeDashArray = strokeDashArray;
    }

    public String getStrokeLineCap() {
        return strokeLineCap;
    }

    public void setStrokeLineCap(String strokeLineCap) {
        this.strokeLineCap = strokeLineCap;
    }

    public String getStrokeLineJoin() {
        return strokeLineJoin;
    }

    public void setStrokeLineJoin(String strokeLineJoin) {
        this.strokeLineJoin = strokeLineJoin;
    }

    public double getStrokeOffset() {
        return strokeOffset;
    }

    public void setStrokeOffset(double strokeOffset) {
        this.strokeOffset = strokeOffset;
    }

    public double getPointSize() {
        return pointSize;
    }

    public void setPointSize(double pointSize) {
        this.pointSize = pointSize;
    }

    public double getPointRotation() {
        return pointRotation;
    }

    public void setPointRotation(double pointRotation) {
        this.pointRotation = pointRotation;
    }

    public String getMarkShape() {
        return markShape;
    }

    public void setMarkShape(String markShape) {
        this.markShape = markShape;
    }

    public String getExternalGraphic() {
        return externalGraphic;
    }

    public void setExternalGraphic(String externalGraphic) {
        this.externalGraphic = externalGraphic;
    }

    public String getExternalGraphicFormat() {
        return externalGraphicFormat;
    }

    public void setExternalGraphicFormat(String externalGraphicFormat) {
        this.externalGraphicFormat = externalGraphicFormat;
    }

    public double getPointOpacity() {
        return pointOpacity;
    }

    public void setPointOpacity(double pointOpacity) {
        this.pointOpacity = pointOpacity;
    }

    public String getMarkFillColor() {
        return markFillColor;
    }

    public void setMarkFillColor(String markFillColor) {
        this.markFillColor = markFillColor;
    }

    public double getMarkFillOpacity() {
        return markFillOpacity;
    }

    public void setMarkFillOpacity(double markFillOpacity) {
        this.markFillOpacity = markFillOpacity;
    }

    public String getMarkStrokeColor() {
        return markStrokeColor;
    }

    public void setMarkStrokeColor(String markStrokeColor) {
        this.markStrokeColor = markStrokeColor;
    }

    public double getMarkStrokeWidth() {
        return markStrokeWidth;
    }

    public void setMarkStrokeWidth(double markStrokeWidth) {
        this.markStrokeWidth = markStrokeWidth;
    }

    public String getSizeByField() {
        return sizeByField;
    }

    public void setSizeByField(String sizeByField) {
        this.sizeByField = sizeByField;
    }

    public double getSizeMin() {
        return sizeMin;
    }

    public void setSizeMin(double sizeMin) {
        this.sizeMin = sizeMin;
    }

    public double getSizeMax() {
        return sizeMax;
    }

    public void setSizeMax(double sizeMax) {
        this.sizeMax = sizeMax;
    }

    public boolean isLabelEnabled() {
        return labelEnabled;
    }

    public void setLabelEnabled(boolean labelEnabled) {
        this.labelEnabled = labelEnabled;
    }

    public String getLabelField() {
        return labelField;
    }

    public void setLabelField(String labelField) {
        this.labelField = labelField;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }

    public boolean isFontBold() {
        return fontBold;
    }

    public void setFontBold(boolean fontBold) {
        this.fontBold = fontBold;
    }

    public boolean isFontItalic() {
        return fontItalic;
    }

    public void setFontItalic(boolean fontItalic) {
        this.fontItalic = fontItalic;
    }

    public String getFontColor() {
        return fontColor;
    }

    public void setFontColor(String fontColor) {
        this.fontColor = fontColor;
    }

    public double getFontOpacity() {
        return fontOpacity;
    }

    public void setFontOpacity(double fontOpacity) {
        this.fontOpacity = fontOpacity;
    }

    public boolean isHaloEnabled() {
        return haloEnabled;
    }

    public void setHaloEnabled(boolean haloEnabled) {
        this.haloEnabled = haloEnabled;
    }

    public String getHaloColor() {
        return haloColor;
    }

    public void setHaloColor(String haloColor) {
        this.haloColor = haloColor;
    }

    public double getHaloRadius() {
        return haloRadius;
    }

    public void setHaloRadius(double haloRadius) {
        this.haloRadius = haloRadius;
    }

    public double getLabelOffsetX() {
        return labelOffsetX;
    }

    public void setLabelOffsetX(double labelOffsetX) {
        this.labelOffsetX = labelOffsetX;
    }

    public double getLabelOffsetY() {
        return labelOffsetY;
    }

    public void setLabelOffsetY(double labelOffsetY) {
        this.labelOffsetY = labelOffsetY;
    }

    public String getLabelAnchor() {
        return labelAnchor;
    }

    public void setLabelAnchor(String labelAnchor) {
        this.labelAnchor = labelAnchor;
    }

    public boolean isLabelDisplacement() {
        return labelDisplacement;
    }

    public void setLabelDisplacement(boolean labelDisplacement) {
        this.labelDisplacement = labelDisplacement;
    }

    public int getLabelSpaceAround() {
        return labelSpaceAround;
    }

    public void setLabelSpaceAround(int labelSpaceAround) {
        this.labelSpaceAround = labelSpaceAround;
    }

    public boolean isLabelScaleEnabled() {
        return labelScaleEnabled;
    }

    public void setLabelScaleEnabled(boolean labelScaleEnabled) {
        this.labelScaleEnabled = labelScaleEnabled;
    }

    public double getLabelMinScale() {
        return labelMinScale;
    }

    public void setLabelMinScale(double labelMinScale) {
        this.labelMinScale = labelMinScale;
    }

    public double getLabelMaxScale() {
        return labelMaxScale;
    }

    public void setLabelMaxScale(double labelMaxScale) {
        this.labelMaxScale = labelMaxScale;
    }

    public String getLabelRotationField() {
        return labelRotationField;
    }

    public void setLabelRotationField(String labelRotationField) {
        this.labelRotationField = labelRotationField;
    }

    public String getLabelGroupField() {
        return labelGroupField;
    }

    public void setLabelGroupField(String labelGroupField) {
        this.labelGroupField = labelGroupField;
    }

    public RasterMode getRasterMode() {
        return rasterMode;
    }

    public void setRasterMode(RasterMode rasterMode) {
        this.rasterMode = rasterMode;
    }

    public int getGrayBand() {
        return grayBand;
    }

    public void setGrayBand(int grayBand) {
        this.grayBand = grayBand;
    }

    public int getRedBand() {
        return redBand;
    }

    public void setRedBand(int redBand) {
        this.redBand = redBand;
    }

    public int getGreenBand() {
        return greenBand;
    }

    public void setGreenBand(int greenBand) {
        this.greenBand = greenBand;
    }

    public int getBlueBand() {
        return blueBand;
    }

    public void setBlueBand(int blueBand) {
        this.blueBand = blueBand;
    }

    public double getRasterOpacity() {
        return rasterOpacity;
    }

    public void setRasterOpacity(double rasterOpacity) {
        this.rasterOpacity = rasterOpacity;
    }

    public boolean isChannelSelectionEnabled() {
        return channelSelectionEnabled;
    }

    public void setChannelSelectionEnabled(boolean channelSelectionEnabled) {
        this.channelSelectionEnabled = channelSelectionEnabled;
    }

    public boolean isStretchEnabled() {
        return stretchEnabled;
    }

    public void setStretchEnabled(boolean stretchEnabled) {
        this.stretchEnabled = stretchEnabled;
    }

    public String getStretchType() {
        return stretchType;
    }

    public void setStretchType(String stretchType) {
        this.stretchType = stretchType;
    }

    public String getStretchNormalize() {
        return stretchNormalize;
    }

    public void setStretchNormalize(String stretchNormalize) {
        this.stretchNormalize = stretchNormalize;
    }

    public double getStretchMin() {
        return stretchMin;
    }

    public void setStretchMin(double stretchMin) {
        this.stretchMin = stretchMin;
    }

    public double getStretchMax() {
        return stretchMax;
    }

    public void setStretchMax(double stretchMax) {
        this.stretchMax = stretchMax;
    }

    public double getGamma() {
        return gamma;
    }

    public void setGamma(double gamma) {
        this.gamma = gamma;
    }

    public void setColorStops(List<ColorStop> colorStops) {
        this.colorStops = colorStops;
    }

    public String getRampPreset() {
        return rampPreset;
    }

    public void setRampPreset(String rampPreset) {
        this.rampPreset = rampPreset;
    }

    public boolean isRampReversed() {
        return rampReversed;
    }

    public void setRampReversed(boolean rampReversed) {
        this.rampReversed = rampReversed;
    }

    public boolean isRampContinuous() {
        return rampContinuous;
    }

    public void setRampContinuous(boolean rampContinuous) {
        this.rampContinuous = rampContinuous;
    }

    public int getRampIntervals() {
        return rampIntervals;
    }

    public void setRampIntervals(int rampIntervals) {
        this.rampIntervals = rampIntervals;
    }

    public String getDemPalette() {
        return demPalette;
    }

    public void setDemPalette(String demPalette) {
        this.demPalette = demPalette;
    }

    public boolean isScaleEnabled() {
        return scaleEnabled;
    }

    public void setScaleEnabled(boolean scaleEnabled) {
        this.scaleEnabled = scaleEnabled;
    }

    public double getMinScale() {
        return minScale;
    }

    public void setMinScale(double minScale) {
        this.minScale = minScale;
    }

    public double getMaxScale() {
        return maxScale;
    }

    public void setMaxScale(double maxScale) {
        this.maxScale = maxScale;
    }

    public String getRawStyle() {
        return rawStyle;
    }

    public void setRawStyle(String rawStyle) {
        this.rawStyle = rawStyle;
    }

    public boolean isSourceDirty() {
        return sourceDirty;
    }

    public void setSourceDirty(boolean sourceDirty) {
        this.sourceDirty = sourceDirty;
    }

    public String getLegendOnlineResource() {
        return legendOnlineResource;
    }

    public void setLegendOnlineResource(String legendOnlineResource) {
        this.legendOnlineResource = legendOnlineResource;
    }

    // ============================================================
    // 参数读取辅助
    // ============================================================

    private static String get(Map<String, String[]> params, String key) {
        String[] a = params.get(key);
        if (a == null || a.length == 0) {
            return null;
        }
        return a[0];
    }

    private static double dblOf(Map<String, String[]> params, String key, double dflt) {
        String s = get(params, key);
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? dflt : d;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static int intOf(Map<String, String[]> params, String key, int dflt) {
        String s = get(params, key);
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static boolean boolOf(Map<String, String[]> params, String key, boolean dflt) {
        if (!params.containsKey(key)) {
            return dflt;
        }
        String s = get(params, key);
        if (s == null) {
            return dflt;
        }
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) {
            return false;
        }
        return "true".equals(t) || "1".equals(t) || "on".equals(t) || "yes".equals(t);
    }
}
