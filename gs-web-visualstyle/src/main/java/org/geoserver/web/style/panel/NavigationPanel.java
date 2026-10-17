/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.panel;

import java.util.ArrayList;
import java.util.List;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.form.AjaxFormComponentUpdatingBehavior;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxSubmitLink;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.web.style.EditorContext;
import org.geoserver.web.style.VisualStyleEditorPage;
import org.geoserver.web.style.service.LayerMetadataService;

/**
 * 左侧导航面板。
 *
 * <p>职责：工作区切换、当前工作区样式列表、新建 / 复制 / 删除样式、
 * 绑定预览图层，并展示所选图层的要素字段与栅格统计信息（供分类设色使用）。
 */
public class NavigationPanel extends Panel {

    private static final long serialVersionUID = 1L;

    private final VisualStyleEditorPage page;
    private final EditorContext context;

    /** 样式列表容器（重新加载样式后整块刷新）。 */
    private final WebMarkupContainer styleListContainer;
    private final WebMarkupContainer layerMetaContainer;

    /** 新建 / 复制对话框输入。 */
    private String newStyleName = "";
    private String newStyleFormat = "sld";
    private String copyStyleName = "";

    public NavigationPanel(String id, VisualStyleEditorPage page, EditorContext context) {
        super(id);
        this.page = page;
        this.context = context;

        final Catalog catalog = page.catalog();

        // ---------- 工作区下拉 ----------
        List<String> workspaceChoices = new ArrayList<>();
        workspaceChoices.add(""); // 全局
        for (WorkspaceInfo ws : EditorContext.metadata().workspaces(catalog)) {
            workspaceChoices.add(ws.getName());
        }
        DropDownChoice<String> workspaceChoice = new DropDownChoice<>("workspaceChoice",
                new PropertyModel<String>(this, "currentWorkspace"), workspaceChoices);
        workspaceChoice.setNullValid(false);
        workspaceChoice.add(new AjaxFormComponentUpdatingBehavior("change") {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onUpdate(AjaxRequestTarget target) {
                page.selectWorkspace(target, getCurrentWorkspace());
            }
        });
        add(workspaceChoice);

        // ---------- 样式列表 ----------
        styleListContainer = new WebMarkupContainer("styleListContainer");
        styleListContainer.setOutputMarkupId(true);
        add(styleListContainer);
        styleListContainer.add(buildStyleList());

        // ---------- 新建 / 复制 / 删除 ----------
        Form<?> actionForm = new Form<Void>("actionForm");
        actionForm.setOutputMarkupId(true);
        add(actionForm);

        TextField<String> newName = new TextField<>("newStyleName",
                new PropertyModel<String>(this, "newStyleName"));
        actionForm.add(newName);
        DropDownChoice<String> formatChoice = new DropDownChoice<>("newStyleFormat",
                new PropertyModel<String>(this, "newStyleFormat"), availableFormats());
        actionForm.add(formatChoice);

        actionForm.add(new AjaxSubmitLink("createStyle", actionForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                String name = getNewStyleName();
                if (name == null || name.isBlank()) {
                    page.error(msg("NavigationPanel.nameRequired", "请填写样式名"));
                    page.feedback(target);
                    return;
                }
                page.createStyle(target, name.trim(), getNewStyleFormat());
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                page.feedback(target);
            }
        });

        TextField<String> copyName = new TextField<>("copyStyleName",
                new PropertyModel<String>(this, "copyStyleName"));
        actionForm.add(copyName);
        actionForm.add(new AjaxSubmitLink("copyStyle", actionForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                StyleInfo current = context.resolveStyle(page.catalog());
                if (current == null) {
                    page.error(msg("NavigationPanel.noStyleSelected", "请先在样式列表中选择一个样式"));
                    page.feedback(target);
                    return;
                }
                String name = getCopyStyleName();
                if (name == null || name.isBlank()) {
                    name = current.getName() + "_copy";
                }
                page.copyStyle(target, current, name.trim());
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                page.feedback(target);
            }
        });

        actionForm.add(new AjaxLink<Void>("deleteStyle") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                StyleInfo current = context.resolveStyle(page.catalog());
                if (current == null) {
                    page.error(msg("NavigationPanel.noStyleSelected", "请先在样式列表中选择一个样式"));
                } else {
                    page.deleteStyle(target, current);
                }
                page.feedback(target);
            }
        }.setEnabled(!context.isReadOnly()));

        // ---------- 图层绑定 ----------
        List<String> layerChoices = new ArrayList<>();
        layerChoices.add("");
        for (LayerInfo l : EditorContext.metadata().layers(catalog, context.getWorkspace())) {
            layerChoices.add(l.prefixedName());
        }
        DropDownChoice<String> layerChoice = new DropDownChoice<>("layerChoice",
                new PropertyModel<String>(this, "previewLayer"), layerChoices);
        layerChoice.setNullValid(true);
        layerChoice.add(new AjaxFormComponentUpdatingBehavior("change") {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onUpdate(AjaxRequestTarget target) {
                page.selectLayer(target, getPreviewLayer());
                target.add(layerMetaContainer);
            }
        });
        add(layerChoice);

        // ---------- 图层元数据 ----------
        layerMetaContainer = new WebMarkupContainer("layerMetaContainer");
        layerMetaContainer.setOutputMarkupId(true);
        add(layerMetaContainer);
        layerMetaContainer.add(buildFieldList());
        layerMetaContainer.add(new Label("rasterSummary", Model.of(rasterSummary(catalog))));
        layerMetaContainer.add(buildHistogram(catalog));

        setOutputMarkupId(true);
    }

    // ------------------------------------------------------------------
    // 样式列表
    // ------------------------------------------------------------------

    private ListView<StyleInfo> buildStyleList() {
        IModel<List<StyleInfo>> model = new org.apache.wicket.model.LoadableDetachableModel<List<StyleInfo>>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected List<StyleInfo> load() {
                return EditorContext.metadata().styles(page.catalog(), context.getWorkspace());
            }
        };
        ListView<StyleInfo> list = new ListView<StyleInfo>("styleItem", model) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void populateItem(ListItem<StyleInfo> item) {
                final StyleInfo style = item.getModelObject();
                boolean current = style.getName() != null
                        && style.getName().equals(context.getState().getStyleName())
                        && !context.getState().isNewStyle();
                item.add(new org.apache.wicket.behavior.AttributeAppender("class",
                        Model.of(current ? " vs-style-item vs-style-item-current" : " vs-style-item"),
                        ""));
                AjaxLink<Void> link = new AjaxLink<Void>("select") {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onClick(AjaxRequestTarget target) {
                        String ws = style.getWorkspace() == null ? "" : style.getWorkspace().getName();
                        page.selectStyle(target, ws, style.getName());
                    }
                };
                String fmt = style.getFormat() == null ? "sld" : style.getFormat();
                item.add(link.add(new Label("styleName", Model.of(style.getName() + "  [" + fmt + "]"))));
            }
        };
        list.setOutputMarkupId(true);
        return list;
    }

    // ------------------------------------------------------------------
    // 图层元数据
    // ------------------------------------------------------------------

    /**
     * 字段列表。
     *
     * <p>注意：模型内部<b>不能</b>捕获 {@code Catalog} —— 匿名类会把局部变量提升为字段，
     * 而 GeoServer 的 Catalog 实现不可序列化，会导致 Wicket 页存（page store）序列化失败。
     * 因此这里改为惰性从 {@link VisualStyleEditorPage#catalog()} 取。
     */
    private ListView<LayerMetadataService.FieldInfo> buildFieldList() {
        IModel<List<LayerMetadataService.FieldInfo>> model =
                new org.apache.wicket.model.LoadableDetachableModel<List<LayerMetadataService.FieldInfo>>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected List<LayerMetadataService.FieldInfo> load() {
                        Catalog catalog = page.catalog();
                        LayerInfo layer = currentLayer(catalog);
                        if (layer == null) {
                            return new ArrayList<>();
                        }
                        List<LayerMetadataService.FieldInfo> f =
                                EditorContext.metadata().fields(layer);
                        if (f.isEmpty()) {
                            f = EditorContext.metadata().fieldsFromSchema(layer, catalog);
                        }
                        return f;
                    }
                };
        ListView<LayerMetadataService.FieldInfo> list =
                new ListView<LayerMetadataService.FieldInfo>("fieldRow", model) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected void populateItem(ListItem<LayerMetadataService.FieldInfo> item) {
                        LayerMetadataService.FieldInfo f = item.getModelObject();
                        item.add(new Label("fieldName", Model.of(f.name)));
                        item.add(new Label("fieldType", Model.of(f.label)));
                        item.add(new org.apache.wicket.behavior.AttributeAppender("class",
                                Model.of(f.numeric ? " vs-num" : ""), ""));
                    }
                };
        list.setOutputMarkupId(true);
        return list;
    }

    private String rasterSummary(Catalog catalog) {
        LayerInfo layer = currentLayer(catalog);
        if (layer == null) {
            return msg("NavigationPanel.noLayerBound", "未绑定图层");
        }
        if (!(layer.getResource() instanceof org.geoserver.catalog.CoverageInfo)) {
            int n = EditorContext.metadata().fields(layer).size();
            return msg("NavigationPanel.vectorSummary", "矢量图层字段数") + ": " + n;
        }
        LayerMetadataService.RasterStats stats = EditorContext.metadata().rasterStats(layer, catalog);
        if (!stats.available) {
            return stats.message;
        }
        return String.format(java.util.Locale.ROOT,
                "%s: %d  |  %dx%d  |  min=%.3f max=%.3f mean=%.3f",
                msg("NavigationPanel.rasterSummary", "栅格统计"), Integer.valueOf(stats.bandCount),
                Integer.valueOf(stats.width), Integer.valueOf(stats.height),
                Double.valueOf(stats.min), Double.valueOf(stats.max), Double.valueOf(stats.mean));
    }

    private WebMarkupContainer buildHistogram(Catalog catalog) {
        WebMarkupContainer box = new WebMarkupContainer("histogramBox");
        LayerInfo layer = currentLayer(catalog);
        boolean isRaster = layer != null && layer.getResource() instanceof org.geoserver.catalog.CoverageInfo;
        box.setVisible(isRaster);
        box.add(new Label("histogramJson", Model.of(histogramJson(catalog, layer))));
        return box;
    }

    private String histogramJson(Catalog catalog, LayerInfo layer) {
        if (layer == null) {
            return "[0]";
        }
        LayerMetadataService.RasterStats stats = EditorContext.metadata().rasterStats(layer, catalog);
        return org.geoserver.web.style.util.Json.write(stats.histogram);
    }

    private LayerInfo currentLayer(Catalog catalog) {
        String name = context.getState().getPreviewLayer();
        if (name == null || name.isBlank()) {
            name = context.getLayerName();
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        return catalog.getLayerByName(name);
    }

    private List<String> availableFormats() {
        List<String> out = new ArrayList<>();
        out.add("sld");
        if (EditorContext.serialization().isFormatAvailable("css")) {
            out.add("css");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------

    /** 重新加载样式列表。 */
    public void reloadStyles() {
        styleListContainer.addOrReplace(buildStyleList());
    }

    /** 追加前端刷新指令。 */
    public void appendRefresh(AjaxRequestTarget target) {
        target.add(styleListContainer);
    }

    // ------------------------------------------------------------------
    // 模型访问
    // ------------------------------------------------------------------

    public String getCurrentWorkspace() {
        return context.getWorkspace();
    }

    public void setCurrentWorkspace(String ws) {
        context.setWorkspace(ws);
    }

    public String getPreviewLayer() {
        String v = context.getState().getPreviewLayer();
        return v == null ? "" : v;
    }

    public void setPreviewLayer(String layer) {
        context.getState().setPreviewLayer(layer);
    }

    public String getNewStyleName() {
        return newStyleName;
    }

    public void setNewStyleName(String v) {
        this.newStyleName = v;
    }

    public String getNewStyleFormat() {
        return newStyleFormat;
    }

    public void setNewStyleFormat(String v) {
        this.newStyleFormat = v;
    }

    public String getCopyStyleName() {
        return copyStyleName;
    }

    public void setCopyStyleName(String v) {
        this.copyStyleName = v;
    }

    /** 本地化取词，缺词条时回落到默认文案（避免 Wicket 抛 MissingResourceException）。 */
    private String msg(String key, String defaultValue) {
        try {
            return getLocalizer().getString(key, this, defaultValue);
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

}
