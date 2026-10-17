/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.web.style.model.VisualStyleState;
import org.geoserver.web.style.service.LayerMetadataService;
import org.geoserver.web.style.service.StyleCompiler;
import org.geoserver.web.style.service.StylePermissionService;
import org.geoserver.web.style.service.StylePersistService;
import org.geoserver.web.style.service.StyleSerializationService;
import org.geoserver.web.style.service.ClassificationService;

/**
 * 编辑器会话上下文。
 *
 * <p>承载 Wicket 组件之间共享的可序列化状态，并提供对各类服务单例的便捷访问。
 *
 * <p><b>为什么不直接持有服务实例？</b>
 * 服务由 GeoServer 的 Spring 容器管理（见 {@code applicationContext.xml}），
 * 直接持有会带来反序列化后失效的风险。这里按需通过 {@code GeoServerExtensions.bean(...)} 取，
 * 并做空值兜底（例如在单元测试或 Spring 上下文未就绪时退化为 new）。
 */
public class EditorContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 表单状态（前后端唯一契约）。 */
    private VisualStyleState state = new VisualStyleState();

    /** 当前工作区名（空串 = 全局）。 */
    private String workspace = "";

    /** 已加载的字段列表（懒加载缓存，避免每次 AJAX 都扫 Catalog）。 */
    private List<LayerMetadataService.FieldInfo> fields = new ArrayList<>();

    /** 绑定的图层名。 */
    private String layerName = "";

    /** 是否处于只读模式（无编辑权限）。 */
    private boolean readOnly;

    public EditorContext() {}

    public EditorContext(VisualStyleState state) {
        if (state != null) {
            this.state = state;
        }
    }

    // ==================================================================
    // 状态
    // ==================================================================

    public VisualStyleState getState() {
        return state;
    }

    public void setState(VisualStyleState state) {
        this.state = state == null ? new VisualStyleState() : state;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace == null ? "" : workspace;
    }

    public List<LayerMetadataService.FieldInfo> getFields() {
        if (fields == null) {
            fields = new ArrayList<>();
        }
        return fields;
    }

    public void setFields(List<LayerMetadataService.FieldInfo> fields) {
        this.fields = fields == null ? new ArrayList<>() : fields;
    }

    public String getLayerName() {
        return layerName;
    }

    public void setLayerName(String layerName) {
        this.layerName = layerName == null ? "" : layerName;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /** 数值型字段名列表（分级设色用）。 */
    public List<String> numericFieldNames() {
        List<String> out = new ArrayList<>();
        for (LayerMetadataService.FieldInfo f : getFields()) {
            if (f.numeric) {
                out.add(f.name);
            }
        }
        return out;
    }

    /** 全部字段名。 */
    public List<String> fieldNames() {
        List<String> out = new ArrayList<>();
        for (LayerMetadataService.FieldInfo f : getFields()) {
            out.add(f.name);
        }
        return out;
    }

    // ==================================================================
    // 服务定位
    // ==================================================================

    public static StyleCompiler compiler() {
        StyleCompiler s = GeoServerExtensions.bean(StyleCompiler.class);
        return s == null ? new StyleCompiler() : s;
    }

    public static StyleSerializationService serialization() {
        StyleSerializationService s = GeoServerExtensions.bean(StyleSerializationService.class);
        return s == null ? new StyleSerializationService() : s;
    }

    public static LayerMetadataService metadata() {
        LayerMetadataService s = GeoServerExtensions.bean(LayerMetadataService.class);
        return s == null ? new LayerMetadataService() : s;
    }

    public static ClassificationService classification() {
        ClassificationService s = GeoServerExtensions.bean(ClassificationService.class);
        return s == null ? new ClassificationService() : s;
    }

    public static StylePersistService persistence() {
        StylePersistService s = GeoServerExtensions.bean(StylePersistService.class);
        return s == null ? new StylePersistService() : s;
    }

    public static StylePermissionService permission() {
        StylePermissionService s = GeoServerExtensions.bean(StylePermissionService.class);
        return s == null ? new StylePermissionService() : s;
    }

    // ==================================================================
    // 便捷查询
    // ==================================================================

    /** 解析绑定的图层。 */
    public LayerInfo resolveLayer(Catalog catalog) {
        if (catalog == null || layerName == null || layerName.isBlank()) {
            return null;
        }
        return catalog.getLayerByName(layerName);
    }

    /** 解析当前样式（未保存的新样式返回 null）。 */
    public StyleInfo resolveStyle(Catalog catalog) {
        if (catalog == null || state == null) {
            return null;
        }
        String name = state.getStyleName();
        if (name == null || name.isBlank() || state.isNewStyle()) {
            return null;
        }
        return state.getWorkspace() == null || state.getWorkspace().isBlank()
                ? catalog.getStyleByName(name)
                : catalog.getStyleByName(state.getWorkspace(), name);
    }

    /** 用图层信息填充上下文（符号类型建议、字段列表等）。 */
    public void bindLayer(Catalog catalog, LayerInfo layer) {
        if (layer == null) {
            setLayerName("");
            setFields(new ArrayList<>());
            return;
        }
        setLayerName(layer.prefixedName());
        List<LayerMetadataService.FieldInfo> f = metadata().fields(layer);
        if (f.isEmpty()) {
            f = metadata().fieldsFromSchema(layer, catalog);
        }
        setFields(f);
        if (state != null && (state.getClassificationField() == null
                || state.getClassificationField().isBlank())) {
            List<String> numeric = numericFieldNames();
            if (!numeric.isEmpty()) {
                state.setClassificationField(numeric.get(0));
            }
        }
        if (state != null && !state.isNewStyle() && (state.getPreviewLayer() == null
                || state.getPreviewLayer().isBlank())) {
            state.setPreviewLayer(layer.prefixedName());
        }
    }
}
