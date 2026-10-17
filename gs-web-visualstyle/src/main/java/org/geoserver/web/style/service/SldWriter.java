/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.geoserver.web.style.model.StyleParseException;
import org.geotools.api.filter.And;
import org.geotools.api.filter.BinaryComparisonOperator;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.Not;
import org.geotools.api.filter.Or;
import org.geotools.api.filter.PropertyIsLike;
import org.geotools.api.filter.PropertyIsNull;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.filter.expression.Literal;
import org.geotools.api.filter.expression.PropertyName;
import org.geotools.api.style.AnchorPoint;
import org.geotools.api.style.ChannelSelection;
import org.geotools.api.style.ColorMap;
import org.geotools.api.style.ColorMapEntry;
import org.geotools.api.style.ContrastEnhancement;
import org.geotools.api.style.Displacement;
import org.geotools.api.style.ExternalGraphic;
import org.geotools.api.style.FeatureTypeStyle;
import org.geotools.api.style.Fill;
import org.geotools.api.style.Font;
import org.geotools.api.style.Graphic;
import org.geotools.api.style.GraphicalSymbol;
import org.geotools.api.style.Halo;
import org.geotools.api.style.LabelPlacement;
import org.geotools.api.style.LinePlacement;
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
import org.geotools.api.style.StyledLayer;
import org.geotools.api.style.StyledLayerDescriptor;
import org.geotools.api.style.Symbolizer;
import org.geotools.api.style.TextSymbolizer;
import org.geotools.api.style.UserLayer;

/**
 * SLD 1.1.0 XML 序列化器。
 *
 * <p><b>为什么不直接用 GeoServer 的 {@code SLDHandler.encode(...)}？</b>
 * GeoServer 3.0.1 的 SLD 1.1 输出走的是 EMF 路线：
 * <pre>
 *   Encoder e = new Encoder(new SLDConfiguration());
 *   e.encode(sld, SLD.StyledLayerDescriptor, output);
 * </pre>
 * 其中 {@code org.geotools.sld.v1_1.SLDConfiguration} 的绑定只认 EMF 生成的
 * {@code net.opengis.sld} 对象；而 {@code org.geotools.styling.StyleFactory} 产出的是
 * {@code org.geotools.styling.*Impl} 对象。把后者交给该 Encoder 时，编码器无法匹配绑定，
 * 会退化成写出 {@code StyledLayerDescriptorImpl@hash} 这样的 toString 文本
 * （GeoServer 自身从不走这条路径 —— 它保存的是编辑器里的原始文本，所以这个缺陷不会被暴露）。
 *
 * <p>因此本类遍历 <b>GeoTools 样式对象模型</b> 直接产出符合 OGC SLD 1.1.0 规范的
 * {@code se:} 命名空间 XML，保证：
 * <ul>
 *   <li>输出与 GeoServer 3.x 默认样式格式（SLD 1.1.0）一致；</li>
 *   <li>对象模型仍然是唯一权威数据源（本类只做序列化，不做业务判断）；</li>
 *   <li>产物可被 GeoTools 的 SLD 解析器原样读回（{@link StyleSerializationService} 会做往返校验）。</li>
 * </ul>
 */
public class SldWriter implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String NS_SLD = "http://www.opengis.net/sld";
    private static final String NS_SE = "http://www.opengis.net/se";
    private static final String NS_OGC = "http://www.opengis.net/ogc";
    private static final String NS_GML = "http://www.opengis.net/gml";
    private static final String NS_XLINK = "http://www.w3.org/1999/xlink";
    private static final String NS_XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private final StringBuilder out = new StringBuilder(8 * 1024);
    private int depth;
    private boolean pretty = true;
    private String version = "1.1.0";

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 序列化为 SLD 1.1.0 XML 文本。
     *
     * @param sld     对象模型
     * @param pretty  是否缩进换行（保存到数据目录建议 true）
     * @param version SLD 版本，默认 1.1.0
     */
    public String write(StyledLayerDescriptor sld, boolean pretty, String version)
            throws StyleParseException {
        this.pretty = pretty;
        this.version = version == null || version.isBlank() ? "1.1.0" : version;
        this.depth = 0;
        this.out.setLength(0);

        if (sld == null) {
            throw new StyleParseException("样式对象为空，无法序列化。", -1, -1, "sld", null);
        }

        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        nl();
        open("StyledLayerDescriptor", new String[][] {
                {"xmlns", NS_SLD},
                {"xmlns:se", NS_SE},
                {"xmlns:ogc", NS_OGC},
                {"xmlns:gml", NS_GML},
                {"xmlns:xlink", NS_XLINK},
                {"xmlns:xsi", NS_XSI},
                {"version", this.version},
        });

        StyledLayer[] layers = sld.getStyledLayers();
        if (layers == null || layers.length == 0) {
            throw new StyleParseException("样式对象中没有图层（StyledLayer）节点，无法序列化。",
                    -1, -1, "sld", null);
        }
        for (StyledLayer layer : layers) {
            writeStyledLayer(layer);
        }

        close("StyledLayerDescriptor");
        nl();
        return out.toString();
    }

    // ==================================================================
    // 图层 / 样式 / 规则
    // ==================================================================

    private void writeStyledLayer(StyledLayer layer) throws StyleParseException {
        if (layer instanceof UserLayer) {
            writeUserLayer((UserLayer) layer);
        } else if (layer instanceof NamedLayer) {
            writeNamedLayer((NamedLayer) layer);
        }
    }

    private void writeNamedLayer(NamedLayer layer) throws StyleParseException {
        open("NamedLayer", null);
        text("se:Name", emptyTo(layer.getName(), ""));
        Style[] styles = layer.getStyles();
        if (styles != null) {
            for (Style style : styles) {
                writeUserStyle(style);
            }
        }
        close("NamedLayer");
    }

    private void writeUserLayer(UserLayer layer) throws StyleParseException {
        open("UserLayer", null);
        text("se:Name", emptyTo(layer.getName(), ""));
        Style[] styles = layer.getUserStyles();
        if (styles != null) {
            for (Style style : styles) {
                writeUserStyle(style);
            }
        }
        close("UserLayer");
    }

    private void writeUserStyle(Style style) throws StyleParseException {
        open("UserStyle", null);
        text("se:Name", emptyTo(style.getName(), "style"));
        if (style.getDescription() != null && style.getDescription().getTitle() != null) {
            text("se:Title", style.getDescription().getTitle().toString());
        }
        if (style.isDefault()) {
            text("se:IsDefault", "1");
        }
        List<FeatureTypeStyle> ftss = style.featureTypeStyles();
        if (ftss != null) {
            for (FeatureTypeStyle fts : ftss) {
                writeFeatureTypeStyle(fts);
            }
        }
        close("UserStyle");
    }

    private void writeFeatureTypeStyle(FeatureTypeStyle fts) throws StyleParseException {
        // SLD 1.1：se:FeatureTypeStyle 不允许 name 属性，名称是子元素 <se:Name>
        open("se:FeatureTypeStyle", null);
        if (fts.getName() != null && !fts.getName().isBlank()) {
            text("se:Name", fts.getName());
        }
        if (fts.getTransformation() != null) {
            open("se:Transformation", null);
            open("ogc:Function", new String[][] {{"name", "Transformation"}});
            close("ogc:Function");
            close("se:Transformation");
        }
        List<Rule> rules = fts.rules();
        if (rules != null) {
            for (Rule rule : rules) {
                writeRule(rule);
            }
        }
        close("se:FeatureTypeStyle");
    }

    private void writeRule(Rule rule) throws StyleParseException {
        open("se:Rule", null);
        text("se:Name", emptyTo(rule.getName(), "rule"));

        if (rule.isElseFilter()) {
            empty("se:ElseFilter");
        } else if (rule.getFilter() != null && rule.getFilter() != Filter.INCLUDE) {
            writeFilter(rule.getFilter());
        }
        double min = rule.getMinScaleDenominator();
        double max = rule.getMaxScaleDenominator();
        if (min > 0d) {
            text("se:MinScaleDenominator", trimNumber(min));
        }
        if (max > 0d && !Double.isInfinite(max)) {
            text("se:MaxScaleDenominator", trimNumber(max));
        }

        List<Symbolizer> symbolizers = rule.symbolizers();
        if (symbolizers != null) {
            for (Symbolizer symbolizer : symbolizers) {
                writeSymbolizer(symbolizer);
            }
        }

        // VendoredOption：Rule 级（GeoServer 支持）
        Map<String, String> ruleOptions = rule.getOptions();
        if (ruleOptions != null && !ruleOptions.isEmpty()) {
            for (Map.Entry<String, String> e : ruleOptions.entrySet()) {
                vendoredOption(e.getKey(), e.getValue());
            }
        }
        close("se:Rule");
    }

    // ==================================================================
    // 符号化器
    // ==================================================================

    private void writeSymbolizer(Symbolizer symbolizer) throws StyleParseException {
        if (symbolizer instanceof PolygonSymbolizer) {
            writePolygon((PolygonSymbolizer) symbolizer);
        } else if (symbolizer instanceof LineSymbolizer) {
            writeLine((LineSymbolizer) symbolizer);
        } else if (symbolizer instanceof PointSymbolizer) {
            writePoint((PointSymbolizer) symbolizer);
        } else if (symbolizer instanceof TextSymbolizer) {
            writeText((TextSymbolizer) symbolizer);
        } else if (symbolizer instanceof RasterSymbolizer) {
            writeRaster((RasterSymbolizer) symbolizer);
        }
        // 其它扩展符号化器（ExtensionSymbolizer 等）不落盘，直接跳过
    }

    private void writeGeometry(Symbolizer symbolizer) {
        if (symbolizer.getGeometry() != null) {
            open("se:Geometry", null);
            expression(symbolizer.getGeometry());
            close("se:Geometry");
        }
        if (symbolizer.getUnitOfMeasure() != null) {
            text("se:uom", symbolizer.getUnitOfMeasure().toString());
        }
    }

    private void writePolygon(PolygonSymbolizer ps) throws StyleParseException {
        open("se:PolygonSymbolizer", null);
        writeGeometry(ps);
        if (ps.getFill() != null) {
            writeFill(ps.getFill());
        }
        if (ps.getStroke() != null) {
            writeStroke(ps.getStroke(), null, null, null, null);
        }
        close("se:PolygonSymbolizer");
    }

    private void writeLine(LineSymbolizer ls) throws StyleParseException {
        open("se:LineSymbolizer", null);
        writeGeometry(ls);
        if (ls.getStroke() != null) {
            writeStroke(ls.getStroke(), null, null, null, null);
        }
        if (ls.getPerpendicularOffset() != null) {
            text("se:PerpendicularOffset", expressionText(ls.getPerpendicularOffset()));
        }
        close("se:LineSymbolizer");
    }

    private void writePoint(PointSymbolizer psym) throws StyleParseException {
        open("se:PointSymbolizer", null);
        writeGeometry(psym);
        if (psym.getGraphic() != null) {
            writeGraphic(psym.getGraphic());
        }
        close("se:PointSymbolizer");
    }

    private void writeText(TextSymbolizer ts) throws StyleParseException {
        open("se:TextSymbolizer", null);
        writeGeometry(ts);
        if (ts.getLabel() != null) {
            open("se:Label", null);
            expression(ts.getLabel());
            close("se:Label");
        }
        if (ts.getFont() != null) {
            writeFont(ts.getFont());
        }
        if (ts.getLabelPlacement() != null) {
            writeLabelPlacement(ts.getLabelPlacement());
        }
        if (ts.getHalo() != null) {
            writeHalo(ts.getHalo());
        }
        if (ts.getFill() != null) {
            writeFill(ts.getFill());
        }
        if (ts.getPriority() != null) {
            text("se:Priority", expressionText(ts.getPriority()));
        }
        Map<String, String> options = ts.getOptions();
        if (options != null && !options.isEmpty()) {
            for (Map.Entry<String, String> e : options.entrySet()) {
                vendoredOption(e.getKey(), e.getValue());
            }
        }
        close("se:TextSymbolizer");
    }

    private void writeRaster(RasterSymbolizer rs) throws StyleParseException {
        open("se:RasterSymbolizer", null);
        writeGeometry(rs);
        if (rs.getOpacity() != null) {
            text("se:Opacity", expressionText(rs.getOpacity()));
        }
        ChannelSelection cs = rs.getChannelSelection();
        if (cs != null) {
            open("se:ChannelSelection", null);
            SelectedChannelType[] rgb = cs.getRGBChannels();
            if (rgb != null && rgb.length >= 3 && rgb[0] != null) {
                channel("se:RedChannel", rgb[0]);
                channel("se:GreenChannel", rgb[1]);
                channel("se:BlueChannel", rgb[2]);
            } else if (cs.getGrayChannel() != null) {
                channel("se:GrayChannel", cs.getGrayChannel());
            }
            close("se:ChannelSelection");
        }
        ContrastEnhancement ce = rs.getContrastEnhancement();
        if (ce != null) {
            writeContrastEnhancement("se:ContrastEnhancement", ce);
        }
        ColorMap cm = rs.getColorMap();
        if (cm != null) {
            writeColorMap(cm);
        }
        close("se:RasterSymbolizer");
    }

    private void channel(String tag, SelectedChannelType sct) throws StyleParseException {
        open(tag, null);
        text("se:SourceChannelName", expressionText(sct.getChannelName()));
        if (sct.getContrastEnhancement() != null) {
            writeContrastEnhancement("se:ContrastEnhancement", sct.getContrastEnhancement());
        }
        close(tag);
    }

    private void writeContrastEnhancement(String tag, ContrastEnhancement ce) {
        open(tag, null);
        if (ce.getMethod() != null) {
            String m = ce.getMethod().name().toUpperCase(Locale.ROOT);
            if ("NORMALIZE".equals(m)) {
                empty("se:Normalize");
            } else if ("HISTOGRAM".equals(m)) {
                empty("se:Histogram");
            } else if ("LOGARITHMIC".equals(m)) {
                empty("se:Logarithmic");
            } else if ("EXPONENTIAL".equals(m)) {
                empty("se:Exponential");
            }
        }
        if (ce.getGammaValue() != null) {
            text("se:GammaValue", expressionText(ce.getGammaValue()));
        }
        close(tag);
    }

    /**
     * 输出 ColorMap。
     *
     * <p><b>SLD 1.1 与 1.0 的关键差异</b>：1.1 的 {@code se:ColorMap} 不允许 {@code type}
     * 属性，也不能直接放 {@code ColorMapEntry}；必须包裹在 {@code se:Categorize} 里，
     * 结构为 {@code LookupValue + Value + (Threshold, Value)*}
     * （见 GeoTools SE 1.1 的 {@code CategorizeBinding} / {@code ColorMapBinding}：
     * 缺少 Categorize 或 Interpolate 会直接抛
     * {@code IllegalArgumentException: Categorize or Interpolate not specified}）。
     *
     * <p>注意 SE 1.1 的 {@code Interpolate} 只接受 {@code InterpolationPoint}，
     * 无法承载色带控制点，因此连续色带同样使用 Categorize 形态输出（阈值即控制点）。
     */
    private void writeColorMap(ColorMap cm) {
        ColorMapEntry[] entries = cm.getColorMapEntries();
        if (entries == null || entries.length == 0) {
            return;
        }
        open("se:ColorMap", null);
        open("se:Categorize", null);
        text("se:LookupValue", "Raster");
        // 第一个 Value 表示「低于首个阈值」的颜色，之后每对 (Threshold, Value) 一段
        text("se:Value", colorOf(entries[0]));
        for (int i = 1; i < entries.length; i++) {
            if (entries[i].getQuantity() == null) {
                continue;
            }
            text("se:Threshold", expressionText(entries[i].getQuantity()));
            text("se:Value", colorOf(entries[i]));
        }
        close("se:Categorize");
        close("se:ColorMap");
    }

    /** 取色带条目颜色（缺省黑色）。 */
    private String colorOf(ColorMapEntry e) {
        if (e.getColor() == null) {
            return "#000000";
        }
        return expressionText(e.getColor());
    }

    // ==================================================================
    // 通用绘制部件
    // ==================================================================

    private void writeFill(Fill fill) throws StyleParseException {
        open("se:Fill", null);
        if (fill.getGraphicFill() != null) {
            open("se:GraphicFill", null);
            writeGraphic(fill.getGraphicFill());
            close("se:GraphicFill");
        } else {
            svgParam("fill", expressionText(fill.getColor()));
            if (fill.getOpacity() != null) {
                svgParam("fill-opacity", expressionText(fill.getOpacity()));
            }
        }
        close("se:Fill");
    }

    private void writeStroke(Stroke stroke, String dashArrayOverride, String widthOverride,
            String colorOverride, String opacityOverride) throws StyleParseException {
        open("se:Stroke", null);
        if (stroke.getGraphicStroke() != null) {
            open("se:GraphicStroke", null);
            writeGraphic(stroke.getGraphicStroke());
            close("se:GraphicStroke");
        } else {
            svgParam("stroke", colorOverride != null ? colorOverride : expressionText(stroke.getColor()));
            if (stroke.getWidth() != null) {
                svgParam("stroke-width",
                        widthOverride != null ? widthOverride : expressionText(stroke.getWidth()));
            }
            if (stroke.getOpacity() != null) {
                svgParam("stroke-opacity",
                        opacityOverride != null ? opacityOverride : expressionText(stroke.getOpacity()));
            }
            String dash = dashArrayOverride != null ? dashArrayOverride : dashArrayText(stroke);
            if (dash != null && !dash.isBlank()) {
                svgParam("stroke-dasharray", dash);
            }
            if (stroke.getDashOffset() != null) {
                svgParam("stroke-dashoffset", expressionText(stroke.getDashOffset()));
            }
            if (stroke.getLineJoin() != null) {
                svgParam("stroke-linejoin", expressionText(stroke.getLineJoin()));
            }
            if (stroke.getLineCap() != null) {
                svgParam("stroke-linecap", expressionText(stroke.getLineCap()));
            }
        }
        close("se:Stroke");
    }

    private String dashArrayText(Stroke stroke) {
        float[] dash = stroke.getDashArray();
        if (dash == null || dash.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dash.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(trimNumber(dash[i]));
        }
        return sb.toString();
    }

    private void writeGraphic(Graphic graphic) throws StyleParseException {
        open("se:Graphic", null);
        List<GraphicalSymbol> symbols = graphic.graphicalSymbols();
        if (symbols != null) {
            for (GraphicalSymbol symbol : symbols) {
                if (symbol instanceof Mark) {
                    writeMark((Mark) symbol);
                } else if (symbol instanceof ExternalGraphic) {
                    writeExternalGraphic((ExternalGraphic) symbol);
                }
            }
        }
        if (graphic.getOpacity() != null) {
            text("se:Opacity", expressionText(graphic.getOpacity()));
        }
        if (graphic.getSize() != null) {
            text("se:Size", expressionText(graphic.getSize()));
        }
        if (graphic.getRotation() != null) {
            text("se:Rotation", expressionText(graphic.getRotation()));
        }
        close("se:Graphic");
    }

    private void writeMark(Mark mark) throws StyleParseException {
        open("se:Mark", null);
        text("se:WellKnownName", expressionText(mark.getWellKnownName()));
        if (mark.getFill() != null) {
            writeFill(mark.getFill());
        }
        if (mark.getStroke() != null) {
            writeStroke(mark.getStroke(), null, null, null, null);
        }
        close("se:Mark");
    }

    private void writeExternalGraphic(ExternalGraphic eg) {
        open("se:ExternalGraphic", null);
        String uri = eg.getURI();
        String format = eg.getFormat();
        text("se:OnlineResource", uri == null ? "" : uri,
                new String[][] {{"xlink:type", "simple"}, {"xlink:href", uri == null ? "" : uri}});
        text("se:Format", emptyTo(format, "image/svg+xml"));
        close("se:ExternalGraphic");
    }

    private void writeFont(Font font) {
        open("se:Font", null);
        List<Expression> families = font.getFamily();
        if (families != null && !families.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Expression e : families) {
                names.add(expressionText(e));
            }
            svgParam("font-family", String.join(", ", names));
        }
        if (font.getSize() != null) {
            svgParam("font-size", expressionText(font.getSize()));
        }
        if (font.getStyle() != null) {
            svgParam("font-style", expressionText(font.getStyle()));
        }
        if (font.getWeight() != null) {
            svgParam("font-weight", expressionText(font.getWeight()));
        }
        close("se:Font");
    }

    private void writeLabelPlacement(LabelPlacement placement) {
        open("se:LabelPlacement", null);
        if (placement instanceof LinePlacement) {
            LinePlacement lp = (LinePlacement) placement;
            open("se:LinePlacement", null);
            if (lp.getPerpendicularOffset() != null) {
                text("se:PerpendicularOffset", expressionText(lp.getPerpendicularOffset()));
            }
            if (lp.getInitialGap() != null) {
                text("se:InitialGap", expressionText(lp.getInitialGap()));
            }
            if (lp.getGap() != null) {
                text("se:Gap", expressionText(lp.getGap()));
            }
            text("se:IsRepeated", Boolean.toString(lp.isRepeated()));
            text("se:IsAligned", Boolean.toString(lp.isAligned()));
            close("se:LinePlacement");
        } else if (placement instanceof PointPlacement) {
            PointPlacement pp = (PointPlacement) placement;
            open("se:PointPlacement", null);
            AnchorPoint ap = pp.getAnchorPoint();
            if (ap != null) {
                open("se:AnchorPoint", null);
                if (ap.getAnchorPointX() != null) {
                    text("se:AnchorPointX", expressionText(ap.getAnchorPointX()));
                }
                if (ap.getAnchorPointY() != null) {
                    text("se:AnchorPointY", expressionText(ap.getAnchorPointY()));
                }
                close("se:AnchorPoint");
            }
            Displacement d = pp.getDisplacement();
            if (d != null) {
                open("se:Displacement", null);
                if (d.getDisplacementX() != null) {
                    text("se:DisplacementX", expressionText(d.getDisplacementX()));
                }
                if (d.getDisplacementY() != null) {
                    text("se:DisplacementY", expressionText(d.getDisplacementY()));
                }
                close("se:Displacement");
            }
            if (pp.getRotation() != null) {
                text("se:Rotation", expressionText(pp.getRotation()));
            }
            close("se:PointPlacement");
        }
        close("se:LabelPlacement");
    }

    private void writeHalo(Halo halo) throws StyleParseException {
        open("se:Halo", null);
        if (halo.getRadius() != null) {
            text("se:Radius", expressionText(halo.getRadius()));
        }
        if (halo.getFill() != null) {
            writeFill(halo.getFill());
        }
        close("se:Halo");
    }

    private void vendoredOption(String name, String value) {
        if (name == null || name.isBlank()) {
            return;
        }
        text("se:VendorOption", value == null ? "" : value, new String[][] {{"name", name}});
    }

    // ==================================================================
    // 过滤器（OGC Filter 1.1）
    // ==================================================================

    private void writeFilter(Filter filter) throws StyleParseException {
        open("ogc:Filter", null);
        filterBody(filter);
        close("ogc:Filter");
    }

    private void filterBody(Filter filter) throws StyleParseException {
        if (filter == null || filter == Filter.INCLUDE) {
            return;
        }
        if (filter instanceof And) {
            open("ogc:And", null);
            for (Filter child : ((And) filter).getChildren()) {
                filterBody(child);
            }
            close("ogc:And");
            return;
        }
        if (filter instanceof Or) {
            open("ogc:Or", null);
            for (Filter child : ((Or) filter).getChildren()) {
                filterBody(child);
            }
            close("ogc:Or");
            return;
        }
        if (filter instanceof Not) {
            open("ogc:Not", null);
            filterBody(((Not) filter).getFilter());
            close("ogc:Not");
            return;
        }
        if (filter instanceof PropertyIsLike) {
            PropertyIsLike like = (PropertyIsLike) filter;
            open("ogc:PropertyIsLike", new String[][] {
                    {"wildCard", like.getWildCard()},
                    {"singleChar", like.getSingleChar()},
                    {"escape", like.getEscape()},
            });
            text("ogc:PropertyName", expressionText(like.getExpression()));
            // GeoTools 35 中 PropertyIsLike.getLiteral() 已经是 String，不再返回 Expression
            text("ogc:Literal", like.getLiteral());
            close("ogc:PropertyIsLike");
            return;
        }
        if (filter instanceof PropertyIsNull) {
            open("ogc:PropertyIsNull", null);
            text("ogc:PropertyName", expressionText(((PropertyIsNull) filter).getExpression()));
            close("ogc:PropertyIsNull");
            return;
        }
        if (filter instanceof BinaryComparisonOperator) {
            BinaryComparisonOperator op = (BinaryComparisonOperator) filter;
            String tag;
            if (filter instanceof org.geotools.api.filter.PropertyIsEqualTo) {
                tag = "ogc:PropertyIsEqualTo";
            } else if (filter instanceof org.geotools.api.filter.PropertyIsNotEqualTo) {
                tag = "ogc:PropertyIsNotEqualTo";
            } else if (filter instanceof org.geotools.api.filter.PropertyIsLessThan) {
                tag = "ogc:PropertyIsLessThan";
            } else if (filter instanceof org.geotools.api.filter.PropertyIsLessThanOrEqualTo) {
                tag = "ogc:PropertyIsLessThanOrEqualTo";
            } else if (filter instanceof org.geotools.api.filter.PropertyIsGreaterThan) {
                tag = "ogc:PropertyIsGreaterThan";
            } else if (filter instanceof org.geotools.api.filter.PropertyIsGreaterThanOrEqualTo) {
                tag = "ogc:PropertyIsGreaterThanOrEqualTo";
            } else {
                throw new StyleParseException(
                        "暂不支持的比较运算过滤器，无法序列化为 SLD：" + filter.getClass().getName(),
                        -1, -1, "sld", null);
            }
            open(tag, null);
            text("ogc:PropertyName", expressionText(op.getExpression1()));
            text("ogc:Literal", literalText(op.getExpression2()));
            close(tag);
            return;
        }
        if (filter instanceof org.geotools.api.filter.PropertyIsBetween) {
            org.geotools.api.filter.PropertyIsBetween between =
                    (org.geotools.api.filter.PropertyIsBetween) filter;
            open("ogc:PropertyIsBetween", null);
            text("ogc:PropertyName", expressionText(between.getExpression()));
            open("ogc:LowerBoundary", null);
            text("ogc:Literal", literalText(between.getLowerBoundary()));
            close("ogc:LowerBoundary");
            open("ogc:UpperBoundary", null);
            text("ogc:Literal", literalText(between.getUpperBoundary()));
            close("ogc:UpperBoundary");
            close("ogc:PropertyIsBetween");
            return;
        }
        throw new StyleParseException(
                "暂不支持的过滤器类型，无法序列化为 SLD：" + filter.getClass().getName(),
                -1, -1, "sld", null);
    }

    // ==================================================================
    // 表达式
    // ==================================================================

    /** 写表达式内容（属性名 → ogc:PropertyName，其余 → 文本/CQL）。 */
    private void expression(Expression e) {
        if (e == null) {
            return;
        }
        if (e instanceof PropertyName) {
            text("ogc:PropertyName", ((PropertyName) e).getPropertyName());
            return;
        }
        out.append(escapeXml(expressionText(e)));
    }

    /** 表达式压平成文本（属性名去掉 [] 包裹）。 */
    private String expressionText(Expression e) {
        if (e == null) {
            return "";
        }
        if (e instanceof Literal) {
            Object v = ((Literal) e).getValue();
            return v == null ? "" : String.valueOf(v);
        }
        if (e instanceof PropertyName) {
            return ((PropertyName) e).getPropertyName();
        }
        String s = e.toString();
        if (s.startsWith("[") && s.endsWith("]")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String literalText(Expression e) {
        if (e instanceof Literal) {
            Object v = ((Literal) e).getValue();
            return v == null ? "" : String.valueOf(v);
        }
        return expressionText(e);
    }

    // ==================================================================
    // XML 输出原语
    // ==================================================================

    private void open(String tag, String[][] attrs) {
        indent();
        out.append('<').append(tag);
        if (attrs != null) {
            for (String[] a : attrs) {
                if (a == null || a.length < 2 || a[1] == null) {
                    continue;
                }
                out.append(' ').append(a[0]).append("=\"").append(escapeXml(a[1])).append('"');
            }
        }
        out.append('>');
        depth++;
        nl();
    }

    private void close(String tag) {
        depth--;
        indent();
        out.append("</").append(tag).append('>');
        nl();
    }

    private void empty(String tag) {
        empty(tag, null);
    }

    private void empty(String tag, String[][] attrs) {
        indent();
        out.append('<').append(tag);
        if (attrs != null) {
            for (String[] a : attrs) {
                if (a == null || a.length < 2 || a[1] == null) {
                    continue;
                }
                out.append(' ').append(a[0]).append("=\"").append(escapeXml(a[1])).append('"');
            }
        }
        out.append("/>");
        nl();
    }

    private void text(String tag, String value) {
        text(tag, value, null);
    }

    private void text(String tag, String value, String[][] attrs) {
        indent();
        out.append('<').append(tag);
        if (attrs != null) {
            for (String[] a : attrs) {
                if (a == null || a.length < 2 || a[1] == null) {
                    continue;
                }
                out.append(' ').append(a[0]).append("=\"").append(escapeXml(a[1])).append('"');
            }
        }
        out.append('>').append(escapeXml(value == null ? "" : value)).append("</").append(tag).append('>');
        nl();
    }

    private void svgParam(String name, String value) {
        if (name == null || value == null) {
            return;
        }
        text("se:SvgParameter", value, new String[][] {{"name", name}});
    }

    private void indent() {
        if (!pretty) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private void nl() {
        if (pretty) {
            out.append('\n');
        }
    }

    private String trimNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1.0e15) {
            return Long.toString((long) d);
        }
        return String.format(Locale.ROOT, "%.9f", Double.valueOf(d)).replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    // 过滤 XML 1.0 非法控制字符
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        break;
                    }
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
