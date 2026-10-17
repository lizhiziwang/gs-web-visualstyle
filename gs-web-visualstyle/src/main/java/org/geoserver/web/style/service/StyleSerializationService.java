/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.StyleHandler;
import org.geoserver.catalog.Styles;
import org.geoserver.web.style.model.StyleParseException;
import org.geotools.api.style.StyledLayerDescriptor;
import org.geotools.util.Version;
import org.geotools.util.logging.Logging;
import org.xml.sax.SAXParseException;

/**
 * 样式源码 ⇄ SLD 对象模型 的编解码与校验服务。
 *
 * <p>完全复用 GeoServer 3.0.1 的 {@link StyleHandler} 体系（{@code org.geoserver.catalog.Styles.handler}），
 * 因此：
 * <ul>
 *   <li>格式支持与 GeoServer 原生样式模块完全一致（sld / sld 1.0.0 / sld 1.1.0 / css 等，
 *       取决于已安装的扩展）；</li>
 *   <li>解析用的 EntityResolver 来自 {@code Catalog.getResourcePool()}，与原生一致，
 *       能正确解析 data 目录下的相对引用；</li>
 *   <li>解析异常（含 {@link SAXParseException}）被转换为带行号的 {@link StyleParseException}，
 *       供前端源码视图定位。</li>
 * </ul>
 */
public class StyleSerializationService implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logging.getLogger(StyleSerializationService.class);

    /** SLD 1.1.0（GeoServer 3 默认）。 */
    public static final Version SLD_110 = new Version("1.1.0");

    /** SLD 1.0.0（兼容旧数据）。 */
    public static final Version SLD_100 = new Version("1.0.0");

    /** GeoCSS 格式标识。 */
    public static final String FORMAT_CSS = "css";

    /** SLD 格式标识。 */
    public static final String FORMAT_SLD = "sld";

    // ==================================================================
    // 格式能力探测
    // ==================================================================

    /** 该格式当前是否可用（例如未安装 GeoCSS 扩展时 css 不可用）。 */
    public boolean isFormatAvailable(String format) {
        try {
            Styles.handler(normalizeFormat(format));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 当前可用的全部样式格式标识。 */
    public Set<String> availableFormats() {
        Set<String> out = new LinkedHashSet<>();
        for (StyleHandler h : Styles.handlers()) {
            out.add(h.getFormat());
        }
        return out;
    }

    /** 当前可用格式的展示信息：[{id, label, version, codeMirrorMode}]。 */
    public List<String[]> formatDescriptors() {
        List<String[]> out = new ArrayList<>();
        for (StyleHandler h : Styles.handlers()) {
            List<Version> versions = h.getVersions();
            StringBuilder ver = new StringBuilder();
            for (int i = 0; i < versions.size(); i++) {
                if (i > 0) {
                    ver.append(", ");
                }
                ver.append(versions.get(i).toString());
            }
            out.add(new String[] {h.getFormat(), h.getName(), ver.toString(), h.getCodeMirrorEditMode()});
        }
        return out;
    }

    /** 缺省版本：SLD 用 1.1.0，其它格式用 handler 声明的首个版本。 */
    public String defaultVersion(String format) {
        StyleHandler h;
        try {
            h = handler(format);
        } catch (StyleParseException e) {
            return "1.1.0";
        }
        List<Version> versions = h.getVersions();
        if (versions == null || versions.isEmpty()) {
            return "1.0.0";
        }
        // SLD 优先 1.1.0
        for (Version v : versions) {
            if (SLD_110.compareTo(v) == 0) {
                return v.toString();
            }
        }
        return versions.get(versions.size() - 1).toString();
    }

    /** 供 CodeMirror 使用的高亮模式。 */
    public String codeMirrorMode(String format) {
        try {
            return handler(format).getCodeMirrorEditMode();
        } catch (StyleParseException e) {
            return "xml";
        }
    }

    /** 取格式对应的 {@link StyleHandler}。 */
    public StyleHandler handler(String format) throws StyleParseException {
        String f = normalizeFormat(format);
        try {
            return Styles.handler(f);
        } catch (RuntimeException e) {
            throw new StyleParseException(
                    "未找到样式格式处理器 <" + f + ">。若是 GeoCSS，请安装 GeoServer 的 CSS 扩展"
                            + "（gs-css-plugin），安装后重启即可在本插件中使用。",
                    -1, -1, f, e);
        }
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return FORMAT_SLD;
        }
        return format.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /**
     * 解析样式源码为 SLD 对象模型。
     *
     * @param text    样式源码（SLD XML 或 GeoCSS）
     * @param format  sld / css
     * @param version 期望版本，可为 null（自动探测）
     * @param catalog 用于取 EntityResolver；可为 null
     */
    public StyledLayerDescriptor parse(String text, String format, String version, Catalog catalog)
            throws StyleParseException {
        if (text == null || text.isBlank()) {
            throw new StyleParseException("样式源码为空。", -1, -1, format, null);
        }
        StyleHandler h = handler(format);
        try {
            return h.parse(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                    resolveVersion(format, version, text),
                    null,
                    entityResolver(catalog));
        } catch (Exception e) {
            throw toParseException(e, format, text);
        }
    }

    private Version parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        try {
            return new Version(version.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 SLD 源码探测真实版本（读取根元素 {@code StyledLayerDescriptor} 的 version 属性）。
     *
     * <p>目录里登记的 {@code StyleInfo.formatVersion} 可能与内容不一致 —— 例如样式以
     * GeoServer 默认的 1.0.0 登记，但内容由本插件的表单重建为 1.1.0。校验 / 解析时
     * 必须以内容声明为准，否则会拿 1.0 的 XSD 去验 1.1.0 的文档，产生成片的假错误。
     */
    private String detectSldVersion(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<\\s*(?:[\\w.-]+:)?StyledLayerDescriptor[^>]*?"
                                + "version\\s*=\\s*\"([^\"]+)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 解析 / 校验共用的版本决策：SLD 内容声明优先，其次调用方传入的登记版本。 */
    private Version resolveVersion(String format, String version, String text) {
        Version v = parseVersion(version);
        if (FORMAT_SLD.equals(normalizeFormat(format))) {
            String detected = detectSldVersion(text);
            if (detected != null && (v == null || !detected.equals(v.toString()))) {
                try {
                    return new Version(detected);
                } catch (Exception ignore) {
                    // 探测结果非法时退回登记版本
                }
            }
        }
        return v;
    }

    // ==================================================================
    // 编码
    // ==================================================================

    /**
     * 把 SLD 对象模型编码为源码文本。
     *
     * <p>SLD 走插件自带的 {@link SldWriter}（输出严格符合 SLD 1.1.0 的 {@code se:} 命名空间）；
     * 其它格式（如 GeoCSS）仍交给 GeoServer 的 {@code StyleHandler} 处理。
     *
     * <p>原因见 {@link SldWriter} 类注释：GeoServer 3.0.1 的 SLD 1.1 编码器绑定的是 EMF 对象，
     * 对 {@code StyleFactory} 产出的 {@code org.geotools.styling.*Impl} 会退化成 toString 文本。
     */
    public String encode(StyledLayerDescriptor sld, String format, String version) throws StyleParseException {
        if (sld == null) {
            throw new StyleParseException("没有可输出的样式对象。", -1, -1, format, null);
        }
        String fmt = normalizeFormat(format);
        if (FORMAT_SLD.equals(fmt)) {
            return new SldWriter().write(sld, true, version == null ? "1.1.0" : version);
        }
        StyleHandler h = handler(fmt);
        Version v = parseVersion(version);
        if (v == null) {
            v = SLD_110.compareTo(parseVersion(defaultVersion(fmt))) == 0 ? SLD_110 : null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
        try {
            h.encode(sld, v, true, out);
        } catch (Exception e) {
            throw new StyleParseException("样式序列化失败: " + rootMessage(e), -1, -1, fmt, e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 往返自检：先做严格的 XML 良构性检查，再交给 GeoTools 解析器读回。
     *
     * <p>两道关卡各有用途：
     * <ol>
     *   <li>{@link javax.xml.parsers.DocumentBuilder} —— 只验证 XML 是否良构，
     *       能给出精确到行的错误定位；</li>
     *   <li>GeoTools {@code StyleHandler.parse} —— 验证是否符合 SLD 语义模型。</li>
     * </ol>
     * 这道校验能第一时间发现「序列化产物其实不合规」的情况。
     *
     * @return 解析错误列表；为空表示往返成功
     */
    public List<StyleParseException> verifyRoundTrip(String text, String format, Catalog catalog) {
        List<StyleParseException> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            errors.add(new StyleParseException("往返校验失败：源码为空。", -1, -1, format, null));
            return errors;
        }
        if (FORMAT_SLD.equals(normalizeFormat(format))) {
            try {
                javax.xml.parsers.DocumentBuilderFactory factory =
                        javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                // 关闭外部实体，避免 XXE
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                        text.getBytes(StandardCharsets.UTF_8)));
            } catch (org.xml.sax.SAXParseException e) {
                errors.add(new StyleParseException(e.getMessage(), e.getLineNumber(),
                        e.getColumnNumber(), format, e));
                return errors;
            } catch (Exception e) {
                errors.add(new StyleParseException("XML 良构性检查失败: " + rootMessage(e),
                        -1, -1, format, e));
                return errors;
            }
        }
        try {
            parse(text, format, null, catalog);
        } catch (StyleParseException e) {
            errors.add(e);
        }
        return errors;
    }

    // ==================================================================
    // WMS SLD_BODY 预览支持
    // ==================================================================

    /**
     * 把 SLD 里第一个 {@code NamedLayer} 的名字改写成指定图层名。
     *
     * <p><b>为什么必须做这一步</b>：WMS 用 {@code SLD_BODY} 请求时，GeoServer 会拿
     * {@code LAYERS} 参数的图层名去 SLD 里找同名的 {@code NamedLayer}；找不到就
     * <b>静默回退到该图层的默认样式</b>（既不报错，也不提示）。而本插件生成的 SLD 是按
     * 样式名命名的（{@code <se:Name>样式名</se:Name>}），与预览用的图层名不一致 —— 于是
     * 「实时预览」永远渲染的是图层默认样式，看起来就像预览完全不生效。
     *
     * <p>实测（GeoServer 3.0.1）：{@code LAYERS=test:xxx} 时，NamedLayer 名必须<b>原样</b>写成
     * {@code test:xxx}；写成不带工作区前缀的 {@code xxx} 同样匹配不上。
     *
     * <p>这里只改第一个 Name 元素（SLD 里它必定是 NamedLayer 的名字，紧跟着 UserStyle 的
     * 同名元素），因此不会影响 UserStyle / 规则名。
     *
     * @param sld       SLD 源码（非 SLD 文本原样返回）
     * @param layerName 要用作预览的图层全名（形如 {@code workspace:layer}）
     * @return 改写后的 SLD；无需改写时返回原文
     */
    public String retargetNamedLayer(String sld, String layerName) {
        if (sld == null || sld.isBlank() || layerName == null || layerName.isBlank()) {
            return sld;
        }
        if (!sld.trim().startsWith("<")) {
            // 非 XML 源码（例如 GeoCSS）不做处理：预览侧会退回使用原始内容
            return sld;
        }
        java.util.regex.Matcher m = FIRST_NAME_ELEMENT.matcher(sld);
        if (!m.find()) {
            return sld;
        }
        String escaped = layerName.trim()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        StringBuilder out = new StringBuilder(sld.length() + escaped.length());
        m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                m.group(1) + escaped + m.group(3)));
        m.appendTail(out);
        return out.toString();
    }

    /** 第一个 {@code Name} 元素：<前缀 Name ...>值</前缀 Name>。 */
    private static final java.util.regex.Pattern FIRST_NAME_ELEMENT =
            java.util.regex.Pattern.compile(
                    "(<(?:(?:[A-Za-z_][\\w.-]*):)?Name\\b[^>]*>)([^<]*)(</(?:(?:[A-Za-z_][\\w.-]*):)?Name>)");

    // ==================================================================
    // 校验
    // ==================================================================

    /**
     * 校验样式源码。
     *
     * <p>两步走，与 GeoServer 原生 {@code AbstractStylePage.validateSLD()} 保持一致：
     * <ol>
     *   <li>{@code StyleHandler.validate(...)} —— XSD / 语法层校验；</li>
     *   <li>{@code StyleHandler.parse(...)} —— 对象模型构建校验（很多语义错误只有这一步才暴露）。</li>
     * </ol>
     */
    public List<StyleParseException> validate(String text, String format, String version, Catalog catalog) {
        List<StyleParseException> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            errors.add(new StyleParseException("样式源码为空。", -1, -1, format, null));
            return errors;
        }
        StyleHandler h;
        try {
            h = handler(format);
        } catch (StyleParseException e) {
            errors.add(e);
            return errors;
        }
        Version v = resolveVersion(format, version, text);
        try {
            List<Exception> raw = h.validate(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), v, entityResolver(catalog));
            if (raw != null) {
                for (Exception e : raw) {
                    errors.add(toParseException(e, format, text));
                }
            }
        } catch (Exception e) {
            errors.add(toParseException(e, format, text));
        }
        // 语法校验通过后，再做一次对象模型构建
        if (errors.isEmpty()) {
            try {
                parse(text, format, version, catalog);
            } catch (StyleParseException e) {
                errors.add(e);
            }
        }
        return errors;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 从 Catalog 取 EntityResolver（与原生样式模块一致，保证相对引用可解析）。 */
    private org.xml.sax.EntityResolver entityResolver(Catalog catalog) {
        if (catalog == null) {
            return null;
        }
        try {
            return catalog.getResourcePool().getEntityResolver();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "无法取得 EntityResolver，使用默认行为", e);
            return null;
        }
    }

    /**
     * 把底层异常转换为带行号的 {@link StyleParseException}。
     *
     * <p>GeoTools 的解析异常往往是包装过的（SAXParseException 可能藏在 cause 链里），
     * 这里做一次深度查找。
     */
    private StyleParseException toParseException(Exception e, String format, String text) {
        SAXParseException sax = findSax(e);
        if (sax != null) {
            return new StyleParseException(stripLocation(sax.getMessage()), sax.getLineNumber(),
                    sax.getColumnNumber(), format, e);
        }
        Integer line = findLineHint(e.getMessage());
        if (line != null) {
            return new StyleParseException(rootMessage(e), line.intValue(), -1, format, e);
        }
        return new StyleParseException(rootMessage(e), -1, -1, format, e);
    }

    private SAXParseException findSax(Throwable t) {
        int guard = 0;
        Throwable cur = t;
        while (cur != null && guard++ < 20) {
            if (cur instanceof SAXParseException) {
                return (SAXParseException) cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** 从形如 "line 12, column 5: ..." 的消息里抽取行号。 */
    private Integer findLineHint(String message) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[Ll]ine\\s*[:=]?\\s*(\\d+)")
                .matcher(message);
        if (m.find()) {
            try {
                return Integer.valueOf(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private String stripLocation(String message) {
        if (message == null) {
            return "样式解析失败。";
        }
        // SAXParseException 的消息已含位置信息，去掉前缀避免重复
        return message.replaceFirst("^.*?:\\s*", "").trim();
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur.getCause() != null && guard++ < 20) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cur.getClass().getSimpleName();
        }
        return msg;
    }

    /** 读取 Catalog 中某个样式在数据目录里的源码。 */
    public String readSource(Catalog catalog, org.geoserver.catalog.StyleInfo style) throws StyleParseException {
        if (catalog == null || style == null) {
            throw new StyleParseException("样式不存在。", -1, -1, null, null);
        }
        try (java.io.BufferedReader reader = catalog.getResourcePool().readStyle(style)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new StyleParseException("读取样式文件失败: " + rootMessage(e), -1, -1,
                    style.getFormat(), e);
        }
    }

    /** 空样式模板（供「新建样式」使用）。 */
    public String emptyTemplate(String format, String styleName) {
        String name = styleName == null || styleName.isBlank() ? "new_style" : styleName;
        if (FORMAT_CSS.equalsIgnoreCase(normalizeFormat(format))) {
            return "* {\n  fill: #3d7ebb;\n  stroke: #1f4e79;\n  stroke-width: 1;\n}\n";
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<StyledLayerDescriptor version=\"1.1.0\"\n"
                + "    xmlns=\"http://www.opengis.net/sld\"\n"
                + "    xmlns:ogc=\"http://www.opengis.net/ogc\"\n"
                + "    xmlns:xlink=\"http://www.w3.org/1999/xlink\"\n"
                + "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                + "  <NamedLayer>\n"
                + "    <se:Name xmlns:se=\"http://www.opengis.net/se\">" + escapeXml(name) + "</se:Name>\n"
                + "    <UserStyle>\n"
                + "      <se:Name xmlns:se=\"http://www.opengis.net/se\">" + escapeXml(name) + "</se:Name>\n"
                + "      <se:FeatureTypeStyle xmlns:se=\"http://www.opengis.net/se\">\n"
                + "        <se:Rule>\n"
                + "          <se:PolygonSymbolizer>\n"
                + "            <se:Fill>\n"
                + "              <se:SvgParameter name=\"fill\">#3d7ebb</se:SvgParameter>\n"
                + "            </se:Fill>\n"
                + "            <se:Stroke>\n"
                + "              <se:SvgParameter name=\"stroke\">#1f4e79</se:SvgParameter>\n"
                + "              <se:SvgParameter name=\"stroke-width\">1</se:SvgParameter>\n"
                + "            </se:Stroke>\n"
                + "          </se:PolygonSymbolizer>\n"
                + "        </se:Rule>\n"
                + "      </se:FeatureTypeStyle>\n"
                + "    </UserStyle>\n"
                + "  </NamedLayer>\n"
                + "</StyledLayerDescriptor>\n";
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 只读的可用格式列表。 */
    public List<String> formats() {
        return Collections.unmodifiableList(new ArrayList<>(availableFormats()));
    }
}
