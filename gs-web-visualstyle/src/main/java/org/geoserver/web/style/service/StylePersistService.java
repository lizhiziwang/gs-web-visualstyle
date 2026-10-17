/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.LegendInfo;
import org.geoserver.catalog.StyleHandler;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.Styles;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.web.style.model.StyleParseException;
import org.geotools.util.Version;
import org.geotools.util.logging.Logging;

/**
 * 样式的持久化服务。
 *
 * <p>写入路径与原生 {@code StyleEditPage.onStyleFormSubmit()} 完全一致，
 * 保证样式文件落在数据目录的正确位置、格式版本正确、Catalog 缓存被正确刷新：
 * <ol>
 *   <li>按源码推断格式版本（{@code StyleHandler.version(source)}）；</li>
 *   <li>通过 {@code ResourcePool.writeStyle(style, InputStream)} 写样式文件；</li>
 *   <li>{@code Catalog.save(style)} / {@code Catalog.add(style)} 更新目录；</li>
 *   <li>非 SLD（如 GeoCSS）再取一次样式实例，触发 GeoServer 内部缓存的重新编译。</li>
 * </ol>
 */
public class StylePersistService implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logging.getLogger(StylePersistService.class);

    // ==================================================================
    // 新建 / 复制
    // ==================================================================

    /**
     * 新建一份样式。
     *
     * @param workspace 工作区名；{@code null}/空 = 全局样式
     * @return 新建的 {@link StyleInfo}（已写入 Catalog）
     */
    public StyleInfo createStyle(Catalog catalog, String name, String workspace, String format,
            String source) throws StyleParseException {
        if (name == null || name.isBlank()) {
            throw new StyleParseException("样式名不能为空。", -1, -1, format, null);
        }
        WorkspaceInfo ws = resolveWorkspace(catalog, workspace);
        if (workspace != null && !workspace.isBlank() && ws == null) {
            throw new StyleParseException("工作区 \"" + workspace + "\" 不存在。", -1, -1, format, null);
        }
        StyleInfo existing = ws == null
                ? catalog.getStyleByName(name.trim())
                : catalog.getStyleByName(ws.getName(), name.trim());
        if (existing != null) {
            throw new StyleParseException("样式 \"" + name + "\" 已存在。", -1, -1, format, null);
        }
        StyleHandler handler = Styles.handler(normalizeFormat(format));

        StyleInfo style = catalog.getFactory().createStyle();
        style.setName(name.trim());
        style.setFormat(handler.getFormat());
        style.setFilename(name.trim() + "." + handler.getFileExtension());
        if (ws != null) {
            style.setWorkspace(ws);
        }
        style.setLegend(catalog.getFactory().createLegend());

        String body = source == null || source.isBlank() ? emptyTemplateFor(handler, name) : source;
        try {
            catalog.add(style);
            writeSource(catalog, style, body, handler);
        } catch (Exception e) {
            // 回滚：避免留下半成品目录项
            safeRemove(catalog, style);
            throw new StyleParseException("新建样式失败: " + rootMessage(e), -1, -1,
                    handler.getFormat(), e);
        }
        return style;
    }

    /**
     * 复制一份样式。
     *
     * @param source 被复制的样式
     * @param newName 新样式名
     * @param targetWorkspace 目标工作区；{@code null}/空 = 全局
     */
    public StyleInfo copyStyle(Catalog catalog, StyleInfo source, String newName, String targetWorkspace)
            throws StyleParseException {
        if (source == null) {
            throw new StyleParseException("源样式不存在。", -1, -1, null, null);
        }
        String body;
        try {
            body = readSourceText(catalog, source);
        } catch (IOException e) {
            throw new StyleParseException("读取源样式内容失败: " + rootMessage(e), -1, -1,
                    source.getFormat(), e);
        }
        String format = source.getFormat() == null ? "sld" : source.getFormat();
        StyleInfo created = createStyle(catalog, newName, targetWorkspace, format, body);

        // ⚠ createStyle 返回的是「工厂新建的普通对象」，不是 Catalog 内部持有的修饰代理。
        // CatalogImpl.save(StyleInfo) 第一行就是
        //   ModificationProxy h = (ModificationProxy) Proxy.getInvocationHandler(style);
        // 对非代理对象，Proxy.getInvocationHandler 会直接抛
        //   IllegalArgumentException: not a proxy instance
        // 因此必须先按名字从 Catalog 重新取一次（拿到代理）再保存。
        String lookupName = created.getName() == null ? newName : created.getName();
        StyleInfo managed = findByName(catalog, targetWorkspace, lookupName);
        if (managed == null) {
            return created;
        }

        // 保留图例配置：图例同样不能用「别的样式身上的代理对象」，这里按字段新建一份。
        LegendInfo src = source.getLegend();
        if (src != null) {
            try {
                LegendInfo legend = catalog.getFactory().createLegend();
                legend.setWidth(src.getWidth());
                legend.setHeight(src.getHeight());
                legend.setFormat(src.getFormat());
                legend.setOnlineResource(src.getOnlineResource());
                managed.setLegend(legend);
                catalog.save(managed);
            } catch (Exception e) {
                // 样式本体已复制成功，图例只是配置项，失败不能影响整体结果
                LOGGER.log(Level.WARNING,
                        "样式已复制，但图例配置未能一并复制: " + managed.prefixedName(), e);
            }
        }
        return managed;
    }

    /** 按「工作区 + 名称」从 Catalog 取样式（拿到的是修饰代理）。 */
    private StyleInfo findByName(Catalog catalog, String workspace, String name) {
        if (catalog == null || name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim();
        if (workspace == null || workspace.isBlank()) {
            return catalog.getStyleByName(n);
        }
        return catalog.getStyleByName(workspace, n);
    }

    // ==================================================================
    // 保存
    // ==================================================================

    /**
     * 保存样式源码（含格式与版本推断）。
     *
     * @return 实际写入的格式版本
     */
    public Version saveStyle(Catalog catalog, StyleInfo style, String source, String format)
            throws StyleParseException {
        if (style == null) {
            throw new StyleParseException("样式不存在，无法保存。", -1, -1, format, null);
        }
        if (source == null || source.isBlank()) {
            throw new StyleParseException("样式源码为空，无法保存。", -1, -1, format, null);
        }
        String fmt = normalizeFormat(format == null ? style.getFormat() : format);
        StyleHandler handler = Styles.handler(fmt);
        style.setFormat(handler.getFormat());

        Version version;
        try {
            version = handler.version(source);
        } catch (Exception e) {
            throw new StyleParseException("无法识别样式版本: " + rootMessage(e), -1, -1, fmt, e);
        }
        style.setFormatVersion(version);
        if (style.getLegend() == null) {
            style.setLegend(catalog.getFactory().createLegend());
        }

        try {
            writeSource(catalog, style, source, handler);
        } catch (Exception e) {
            throw new StyleParseException("写入样式文件失败: " + rootMessage(e), -1, -1, fmt, e);
        }
        try {
            catalog.save(style);
        } catch (Exception e) {
            throw new StyleParseException("更新目录失败: " + rootMessage(e), -1, -1, fmt, e);
        }
        // 非 SLD 格式（如 GeoCSS）：主动取一次，触发服务端样式缓存重建
        try {
            if (!"sld".equalsIgnoreCase(handler.getFormat())) {
                catalog.getResourcePool().getStyle(style);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "样式已保存，但预热解析失败: " + style.prefixedName(), e);
        }
        return version;
    }

    private void writeSource(Catalog catalog, StyleInfo style, String source, StyleHandler handler)
            throws IOException {
        StyleInfo path = catalog.getStyle(style.getId());
        if (path == null) {
            path = style;
        }
        catalog.getResourcePool().writeStyle(path,
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
    }

    // ==================================================================
    // 删除
    // ==================================================================

    /** 样式被哪些图层引用（删除前提示用）。 */
    public List<LayerInfo> styleReferences(Catalog catalog, StyleInfo style) {
        List<LayerInfo> refs = new ArrayList<>();
        if (catalog == null || style == null) {
            return refs;
        }
        for (LayerInfo layer : catalog.getLayers(style)) {
            refs.add(layer);
        }
        return refs;
    }

    /** 删除样式。被引用的样式由调用方先提示用户。 */
    public void deleteStyle(Catalog catalog, StyleInfo style) throws StyleParseException {
        if (style == null) {
            throw new StyleParseException("样式不存在。", -1, -1, null, null);
        }
        try {
            catalog.remove(style);
        } catch (Exception e) {
            throw new StyleParseException("删除样式失败: " + rootMessage(e), -1, -1,
                    style.getFormat(), e);
        }
    }

    private void safeRemove(Catalog catalog, StyleInfo style) {
        try {
            catalog.remove(style);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "回滚删除样式失败", e);
        }
    }

    // ==================================================================
    // 图层绑定
    // ==================================================================

    /** 把样式设为图层的默认样式。 */
    public void setDefaultStyle(Catalog catalog, LayerInfo layer, StyleInfo style)
            throws StyleParseException {
        if (layer == null || style == null) {
            throw new StyleParseException("图层或样式不存在。", -1, -1, null, null);
        }
        try {
            layer.setDefaultStyle(style);
            catalog.save(layer);
        } catch (Exception e) {
            throw new StyleParseException("绑定默认样式失败: " + rootMessage(e), -1, -1,
                    style.getFormat(), e);
        }
    }

    /** 把样式追加为图层的可选样式。 */
    public void addAlternativeStyle(Catalog catalog, LayerInfo layer, StyleInfo style)
            throws StyleParseException {
        if (layer == null || style == null) {
            throw new StyleParseException("图层或样式不存在。", -1, -1, null, null);
        }
        try {
            boolean exists = false;
            for (StyleInfo s : layer.getStyles()) {
                if (s.getId() != null && s.getId().equals(style.getId())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                layer.getStyles().add(style);
                catalog.save(layer);
            }
        } catch (Exception e) {
            throw new StyleParseException("添加可选样式失败: " + rootMessage(e), -1, -1,
                    style.getFormat(), e);
        }
    }

    /** 解除图层的可选样式（默认样式不可解除）。 */
    public void removeAlternativeStyle(Catalog catalog, LayerInfo layer, StyleInfo style)
            throws StyleParseException {
        if (layer == null || style == null) {
            return;
        }
        try {
            layer.getStyles().removeIf(s -> s.getId() != null && s.getId().equals(style.getId()));
            catalog.save(layer);
        } catch (Exception e) {
            throw new StyleParseException("移除可选样式失败: " + rootMessage(e), -1, -1,
                    style.getFormat(), e);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 读取样式源码文本。 */
    public String readSourceText(Catalog catalog, StyleInfo style) throws IOException {
        try (java.io.BufferedReader reader = catalog.getResourcePool().readStyle(style)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /** 解析工作区名 → WorkspaceInfo。 */
    public WorkspaceInfo resolveWorkspace(Catalog catalog, String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return null;
        }
        return catalog.getWorkspaceByName(workspace.trim());
    }

    private String emptyTemplateFor(StyleHandler handler, String name) {
        StyleSerializationService ser = new StyleSerializationService();
        return ser.emptyTemplate(handler.getFormat(), name);
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "sld";
        }
        return format.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur.getCause() != null && guard++ < 20) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
