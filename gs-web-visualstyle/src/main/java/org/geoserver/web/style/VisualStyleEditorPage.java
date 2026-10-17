/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.form.AjaxFormComponentUpdatingBehavior;
import org.apache.wicket.ajax.markup.html.form.AjaxSubmitLink;
import org.apache.wicket.extensions.ajax.markup.html.tabs.AjaxTabbedPanel;
import org.apache.wicket.extensions.markup.html.tabs.AbstractTab;
import org.apache.wicket.extensions.markup.html.tabs.ITab;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.ResourceModel;
import org.apache.wicket.request.IRequestParameters;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.resource.PackageResourceReference;
import org.apache.wicket.util.string.StringValue;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.web.ComponentAuthorizer;
import org.geoserver.web.GeoServerSecuredPage;
import org.geoserver.web.style.model.StyleParseException;
import org.geoserver.web.style.model.VisualStyleState;
import org.geoserver.web.style.service.ClassificationService;
import org.geoserver.web.style.panel.NavigationPanel;
import org.geoserver.web.style.panel.PreviewPanel;
import org.geoserver.web.style.panel.RasterSymbolizerPanel;
import org.geoserver.web.style.panel.ScaleControlPanel;
import org.geoserver.web.style.panel.SourceCodePanel;
import org.geoserver.web.style.panel.TextSymbolizerPanel;
import org.geoserver.web.style.panel.VectorSymbolizerPanel;
import org.geoserver.web.style.util.Json;
import org.geotools.api.style.StyledLayerDescriptor;
import org.geotools.util.logging.Logging;

/**
 * 可视化样式编辑面板 —— 插件主页面。
 *
 * <p>嵌入 GeoServer 自带 Web 管理控制台，路由：
 * <pre>/geoserver/web/?wicket:bookmarkablePage=:org.geoserver.web.style.VisualStyleEditorPage</pre>
 *
 * <p>页面版式（四块）：
 * <ol>
 *   <li><b>左侧导航</b> {@link NavigationPanel} —— 工作区 / 样式列表 / 新建·复制·删除 / 图层绑定与元数据；</li>
 *   <li><b>中间配置</b> 标签页 —— 矢量符号化 / 标注 / 栅格色带 / 比例尺控制 / 高级源码；</li>
 *   <li><b>右侧预览</b> {@link PreviewPanel} —— OpenLayers WMS 实时预览 + 图例；</li>
 *   <li><b>底部源码视图</b> —— SLD 1.1 / GeoCSS 语法高亮与错误定位（纯前端渲染，数据由服务端推送）。</li>
 * </ol>
 *
 * <p><b>状态同步机制（核心）</b>：
 * 前端表单字段名与 {@link VisualStyleState} 字段一一对应。任一表单变化时，
 * JS 通过 {@code VisualStyle.push()} 触发隐藏字段 {@code vsPush} 的 change 事件，
 * Wicket 的 {@link AjaxFormComponentUpdatingBehavior} 把整个可视化表单 +
 * SLD 源码回传服务端；服务端用 GeoTools 重建 SLD 对象模型、序列化为 SLD 1.1.0，
 * 再通过 {@code AjaxRequestTarget.appendJavaScript} 把新状态推送回前端，
 * 前端据此刷新源码视图、图例与 OpenLayers 预览。业务模型始终由 Java 端维护。
 */
public class VisualStyleEditorPage extends GeoServerSecuredPage {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logging.getLogger(VisualStyleEditorPage.class);

    /** 页面参数：工作区名。 */
    public static final String PARAM_WORKSPACE = "workspace";

    /** 页面参数：样式名。 */
    public static final String PARAM_STYLE = "style";

    /** 可视化表单 DOM id（前端收集字段用）。 */
    public static final String VISUAL_FORM_ID = "vs-visual-form";

    /**
     * 源码编辑器底层 textarea 的 markupId。
     *
     * <p><b>必须是合法 JS 标识符</b>：GeoServer 原生 {@code CodeMirrorEditor} 会把该 id
     * 直接拼进 JS（{@code document.gsEditors.<id> = editor}），一旦含 {@code -} 等字符，
     * 整段 header-contribution 脚本都会语法错误，编辑器初始化与同块 Ajax 绑定一起失效。
     */
    public static final String SOURCE_TEXTAREA_ID = "vsSourceEditor";

    /** 编辑器上下文（会话级状态）。 */
    private final EditorContext context;

    private Form<EditorContext> visualForm;
    private AjaxTabbedPanel<ITab> tabs;
    private TextField<String> pushField;
    private NavigationPanel navigationPanel;
    private PreviewPanel previewPanel;
    private SourceCodePanel sourceCodePanel;

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    public VisualStyleEditorPage() {
        this(new PageParameters());
    }

    public VisualStyleEditorPage(PageParameters parameters) {
        super();
        this.context = buildContext(parameters);
        buildUi();
        // 重定向前暂存的提示（新建/复制后跳转）在页面构建完成、表单已存在时再登记，
        // 这样它会被 GeoServer 基类的 feedbackPanel 渲染出来。
        consumePendingNotice();
    }

    /**
     * 会话级「跨重定向反馈」的存放键。
     *
     * <p>{@link #selectStyle} 用的是 {@code setResponsePage}（整页跳转）：Wicket 会把浏览器重定向到
     * 新 URL，当前 AJAX 响应里渲染的 FeedbackPanel 内容随即被丢弃。因此「新建 / 复制成功」这类
     * 需要跨跳转的提示必须先暂存到会话，由新页面实例取走并渲染。
     */
    private static final String SESSION_NOTICE = "org.geoserver.web.style.pendingNotice";

    /** 暂存一条提示，供跳转后的新页面显示。level 取 info / warn / error。 */
    private void setPendingNotice(String level, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        getSession().setAttribute(SESSION_NOTICE, (level == null ? "info" : level) + "\n" + text);
    }

    /** 取出并渲染跳转前暂存的提示（只取一次）。 */
    private void consumePendingNotice() {
        Object raw = getSession().getAttribute(SESSION_NOTICE);
        if (raw == null) {
            return;
        }
        getSession().removeAttribute(SESSION_NOTICE);
        String s = String.valueOf(raw);
        int nl = s.indexOf('\n');
        String level = nl < 0 ? "info" : s.substring(0, nl);
        String text = nl < 0 ? s : s.substring(nl + 1);
        if ("error".equals(level)) {
            error(text);
        } else if ("warn".equals(level)) {
            warn(text);
        } else {
            info(text);
        }
    }

    /**
     * 依据页面参数准备上下文：加载已有样式并解析回表单，或初始化一份新样式。
     */
    private EditorContext buildContext(PageParameters parameters) {
        EditorContext ctx = new EditorContext();
        Catalog catalog = getCatalog();

        String workspace = parameters.get(PARAM_WORKSPACE).toOptionalString();
        if (workspace == null) {
            workspace = parameters.get("ws").toOptionalString();
        }
        ctx.setWorkspace(workspace == null ? "" : workspace.trim());

        String styleName = parameters.get(PARAM_STYLE).toOptionalString();
        if (styleName == null) {
            styleName = parameters.get("name").toOptionalString();
        }

        VisualStyleState state = ctx.getState();
        state.setNewStyle(true);
        state.setFormat("sld");
        state.setFormatVersion(serializationDefaultVersion());
        state.setWorkspace(ctx.getWorkspace());

        // 权限
        boolean readOnly = !Services.permission().canEditWorkspace(ctx.getWorkspace());
        ctx.setReadOnly(readOnly);

        if (styleName != null && !styleName.isBlank()) {
            StyleInfo info = ctx.getWorkspace() == null || ctx.getWorkspace().isBlank()
                    ? catalog.getStyleByName(styleName.trim())
                    : catalog.getStyleByName(ctx.getWorkspace(), styleName.trim());
            if (info == null) {
                error(msg("VisualStyleEditorPage.styleNotFound", "样式不存在")
                        + ": " + styleName);
            } else {
                loadStyle(ctx, info);
            }
        }

        // 绑定预览图层：优先样式已绑定图层，否则取工作区第一个兼容图层
        resolveInitialLayer(ctx, catalog);
        return ctx;
    }

    /** 把 Catalog 中的样式读入上下文（源码 → SLD → 表单）。 */
    private void loadStyle(EditorContext ctx, StyleInfo info) {
        ctx.setReadOnly(ctx.isReadOnly() || !Services.permission().canEditStyle(info));
        VisualStyleState state = ctx.getState();
        state.setNewStyle(false);
        state.setStyleName(info.getName());
        state.setFilename(info.getFilename());
        state.setFormat(info.getFormat() == null ? "sld" : info.getFormat());
        if (info.getWorkspace() != null) {
            state.setWorkspace(info.getWorkspace().getName());
            ctx.setWorkspace(info.getWorkspace().getName());
        }
        if (info.getFormatVersion() != null) {
            state.setFormatVersion(info.getFormatVersion().toString());
        }
        if (info.getLegend() != null && info.getLegend().getOnlineResource() != null) {
            state.setLegendOnlineResource(info.getLegend().getOnlineResource());
        }
        try {
            String source = Services.serialization().readSource(getCatalog(), info);
            state.setRawStyle(source);
            applySourceToState(ctx, source, state.getFormat());
        } catch (StyleParseException e) {
            error(e.getDisplayMessage());
            LOGGER.log(Level.WARNING, "读取样式失败: " + info.prefixedName(), e);
        }
    }

    /** 源码 → SLD → 表单状态。 */
    private void applySourceToState(EditorContext ctx, String source, String format) {
        VisualStyleState state = ctx.getState();
        state.setRawStyle(source);
        StyleParseException failure = null;
        try {
            StyledLayerDescriptor sld = Services.serialization().parse(source, format,
                    state.getFormatVersion(), getCatalog());
            boolean ok = Services.compiler().readInto(state, sld);
            if (!ok) {
                LOGGER.fine("样式中未找到可编辑的符号化规则，表单保持默认值");
            }
        } catch (StyleParseException e) {
            failure = e;
        }
        if (failure != null) {
            error(failure.getDisplayMessage());
            LOGGER.log(Level.FINE, "解析样式源码失败", failure);
        }
    }

    private String serializationDefaultVersion() {
        return Services.serialization().defaultVersion("sld");
    }

    /** 选择初始预览图层。 */
    private void resolveInitialLayer(EditorContext ctx, Catalog catalog) {
        VisualStyleState state = ctx.getState();
        LayerInfo layer = null;
        StyleInfo style = ctx.resolveStyle(catalog);
        if (style != null) {
            List<LayerInfo> layers = catalog.getLayers(style);
            if (!layers.isEmpty()) {
                layer = layers.get(0);
            }
        }
        if (layer == null) {
            List<LayerInfo> candidates = Services.metadata().layers(catalog, ctx.getWorkspace());
            boolean raster = state.isRaster();
            for (LayerInfo l : candidates) {
                if (Services.metadata().isCompatible(l, raster)) {
                    layer = l;
                    break;
                }
            }
            if (layer == null && !candidates.isEmpty()) {
                layer = candidates.get(0);
            }
        }
        ctx.bindLayer(catalog, layer);
        if (layer != null) {
            state.setPreviewLayer(layer.prefixedName());
            if (state.isNewStyle()) {
                state.setSymbolizerKind(
                        org.geoserver.web.style.model.SymbolizerKind.fromId(
                                Services.metadata().suggestSymbolizerKind(layer)));
            }
        }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        CompoundPropertyModel<EditorContext> model = new CompoundPropertyModel<>(context);
        visualForm = new Form<EditorContext>("visualForm", model) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit() {
                // 表单整体提交（保存）走 onSubmitVisualForm
                onSubmitVisualForm();
            }
        };
        visualForm.setOutputMarkupId(true);
        visualForm.setMarkupId(VISUAL_FORM_ID);
        add(visualForm);

        // 状态回推通道
        pushField = new TextField<String>("vsPush", Model.of(""));
        pushField.setMarkupId("vsPush");
        pushField.setOutputMarkupId(true);
        pushField.add(new AjaxFormComponentUpdatingBehavior("change") {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onUpdate(AjaxRequestTarget target) {
                onVisualFormChanged(target);
            }

            @Override
            protected void onError(AjaxRequestTarget target, RuntimeException e) {
                LOGGER.log(Level.FINE, "状态回推失败", e);
                addFeedbackPanels(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(
                        "var f=document.getElementById('" + VISUAL_FORM_ID + "');"
                                + "var r={};"
                                + "if(f && window.VisualStyle){window.VisualStyle.collect(f, r);}"
                                + "return r;");
            }
        });
        visualForm.add(pushField);

        List<ITab> tabList = new ArrayList<>();
        tabList.add(tab("vector", VectorSymbolizerPanel.class));
        tabList.add(tab("text", TextSymbolizerPanel.class));
        tabList.add(tab("raster", RasterSymbolizerPanel.class));
        tabList.add(tab("scale", ScaleControlPanel.class));
        // 高级源码标签页：保留实例引用，便于服务端重建源码后同步编辑器内容
        tabList.add(new AbstractTab(new Model<>(msg("VisualStyleEditorPage.tab.source", "高级源码"))) {
            private static final long serialVersionUID = 1L;

            @Override
            public Panel getPanel(String panelId) {
                sourceCodePanel = new SourceCodePanel(panelId, VisualStyleEditorPage.this, context);
                return sourceCodePanel;
            }
        });

        tabs = new AjaxTabbedPanel<>("tabs", tabList) {
            private static final long serialVersionUID = 1L;

            @Override
            protected String getTabContainerCssClass() {
                return "tab-row tab-row-compact vs-tab-row";
            }
        };
        tabs.setOutputMarkupId(true);
        tabs.setMarkupId("vsTabs");
        visualForm.add(tabs);

        navigationPanel = new NavigationPanel("navigationPanel", this, context);
        visualForm.add(navigationPanel);

        previewPanel = new PreviewPanel("previewPanel", this, context);
        visualForm.add(previewPanel);

        // 底部工具栏按钮
        visualForm.add(saveButton("save"));
        visualForm.add(applyButton("apply"));
        visualForm.add(bindButton("bindLayer"));
        visualForm.add(validateButton("validate"));
        visualForm.add(parseSourceButton("parseSource"));
        visualForm.add(generateSourceButton("generateSource"));
    }

    /** 声明一个标签页。 */
    private ITab tab(final String key, final Class<? extends Panel> panelClass) {
        return new AbstractTab(new Model<String>(msg("VisualStyleEditorPage.tab." + key, key))) {
            private static final long serialVersionUID = 1L;

            @Override
            public Panel getPanel(String panelId) {
                try {
                    return panelClass
                            .getConstructor(String.class, VisualStyleEditorPage.class, EditorContext.class)
                            .newInstance(panelId, VisualStyleEditorPage.this, context);
                } catch (Exception e) {
                    throw new org.apache.wicket.WicketRuntimeException(
                            "无法创建面板 " + panelClass.getName(), e);
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // 工具栏按钮
    // ------------------------------------------------------------------

    private Component saveButton(String id) {
        AjaxSubmitLink link = new AjaxSubmitLink(id, visualForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                doSave(target, false);
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                addFeedbackPanels(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(collectFormScript());
            }
        };
        link.setEnabled(!context.isReadOnly());
        return link;
    }

    private Component applyButton(String id) {
        AjaxSubmitLink link = new AjaxSubmitLink(id, visualForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                doSave(target, true);
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                addFeedbackPanels(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(collectFormScript());
            }
        };
        link.setEnabled(!context.isReadOnly());
        return link;
    }

    private Component bindButton(String id) {
        AjaxSubmitLink link = new AjaxSubmitLink(id, visualForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                doBindLayer(target);
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                addFeedbackPanels(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(collectFormScript());
            }
        };
        link.setEnabled(!context.isReadOnly());
        return link;
    }

    private Component validateButton(String id) {
        AjaxSubmitLink link = new AjaxSubmitLink(id, visualForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                doValidate(target, true);
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                addFeedbackPanels(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(collectFormScript());
            }
        };
        return link;
    }

    private Component parseSourceButton(String id) {
        AjaxSubmitLink link = new AjaxSubmitLink(id, visualForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                doParseSource(target);
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                addFeedbackPanels(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(collectFormScript());
            }
        };
        return link;
    }

    private Component generateSourceButton(String id) {
        AjaxSubmitLink link = new AjaxSubmitLink(id, visualForm) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSubmit(AjaxRequestTarget target) {
                context.getState().setSourceDirty(false);
                doGenerateSource(target);
            }

            @Override
            protected void onError(AjaxRequestTarget target) {
                addFeedbackPanels(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(collectFormScript());
            }
        };
        return link;
    }

    private String collectFormScript() {
        return "var f=document.getElementById('" + VISUAL_FORM_ID + "');"
                + "var r={};"
                + "if(f && window.VisualStyle){window.VisualStyle.collect(f, r);}"
                // 源码编辑器优先取 CodeMirror 实例内容（textarea 只在没有 CM 时才准），
                // 且未初始化 / 未激活源码标签页时 editorValue() 返回 null，不会污染其它标签页。
                + "var ev=(window.VisualStyle && window.VisualStyle.editorValue)"
                + "?window.VisualStyle.editorValue():null;"
                + "if(ev===null||ev===undefined){"
                + "var ta=document.getElementById('" + SOURCE_TEXTAREA_ID + "');"
                + "ev=ta?ta.value:null;}"
                // ⚠ 编辑器内容与服务端最近一次推送完全一致 = 用户没有手工改过源码，
                // 此时必须丢弃 ev，绝不能带 vs.rawStyle 上去。否则在「表单已改、
                // 推送响应未回」的窗口期里编辑器里还是旧 SLD，服务端会按源码优先
                // 拿旧内容覆盖表单参数 —— 用户刚做的修改被静默丢弃，保存后图层与
                // 预览毫无变化（2026-10-17 用户报告并已在 diag27 复现）。
                // 只有用户真的手改过源码（内容与服务端不一致）时才走源码优先。
                + "if(ev&&window.VisualStyle&&window.VisualStyle.serverSld){"
                + "var ss=window.VisualStyle.serverSld();"
                + "if(ss!==null&&ss!==undefined&&ev===ss){ev=null;}}"
                + "if(ev!==null&&ev!==undefined){r['vs.rawStyle']=ev;}"
                + "return r;";
    }

    // ------------------------------------------------------------------
    // 状态同步
    // ------------------------------------------------------------------

    /** 表单变化 → 更新状态 → 重建 SLD → 推回前端。 */
    private void onVisualFormChanged(AjaxRequestTarget target) {
        applyRequestToState();
        pushStateToClient(target, true);
    }

    /** 把请求参数写入 {@link VisualStyleState}。 */
    private void applyRequestToState() {
        IRequestParameters params = getRequest().getRequestParameters();
        Map<String, String[]> map = new HashMap<>();
        for (String name : params.getParameterNames()) {
            StringValue value = params.getParameterValue(name);
            map.put(name, new String[] {value == null ? "" : value.toString("")});
        }
        // 源码：若前端编辑器有内容则同步（源码优先模式）
        // ⚠ 只有「携带的源码 ≠ 服务端当前源码」才说明用户真的手改过源码，此时采用并
        // 置 sourceDirty，保存/校验不得按表单重建。若两者一致，说明编辑器内容只是
        // 上一次推送的回显——在「表单已改、推送响应未回」的窗口期里它是过期的，
        // 绝不能拿来覆盖表单参数（否则保存会静默丢掉用户刚做的修改，diag27 复现）。
        String[] raw = map.get("vs.rawStyle");
        if (raw != null && raw.length > 0 && raw[0] != null && !raw[0].isBlank()) {
            String incoming = raw[0];
            String current = context.getState().getRawStyle();
            if (current == null || !current.equals(incoming)) {
                context.getState().setRawStyle(incoming);
                context.getState().setSourceDirty(true);
            }
        }
        context.getState().applyRequestParams(map);
        context.getState().setWorkspace(context.getWorkspace());
    }

    /**
     * 重新编译并推送状态。
     *
     * @param regenerateSource 是否用表单重建 SLD 源码（false 时保留编辑器内容，用于源码优先模式）
     */
    void pushStateToClient(AjaxRequestTarget target, boolean regenerateSource) {
        VisualStyleState state = context.getState();
        String sldText = state.getRawStyle() == null ? "" : state.getRawStyle();
        String errors = "[]";

        if (regenerateSource && !state.isSourceDirty()) {
            try {
                StyledLayerDescriptor sld = Services.compiler().buildSld(state);
                sldText = Services.serialization().encode(sld, "sld", "1.1.0");
                state.setRawStyle(sldText);
                // 目录里登记的版本可能仍是 1.0.0（GeoServer 默认）；表单重建出的内容
                // 是 1.1.0，必须同步登记版本，否则保存 / 后续校验会拿错 XSD。
                if (!"1.1.0".equals(state.getFormatVersion())) {
                    state.setFormatVersion("1.1.0");
                }
                // 往返自检：把刚生成的 SLD 交回 GeoTools 解析器读一遍。
                // 这一步能立刻发现「序列化产物其实不合规」的情况，并给出带行号的错误。
                List<StyleParseException> roundTrip =
                        Services.serialization().verifyRoundTrip(sldText, "sld", getCatalog());
                if (!roundTrip.isEmpty()) {
                    errors = errorJson(roundTrip.get(0));
                    LOGGER.log(Level.WARNING, "生成的 SLD 未能通过往返校验: "
                            + roundTrip.get(0).getDisplayMessage());
                }
            } catch (StyleParseException e) {
                errors = errorJson(e);
                LOGGER.log(Level.FINE, "生成 SLD 失败", e);
            }
        } else {
            // 源码优先：校验并把可解析内容回填表单
            try {
                StyledLayerDescriptor sld = Services.serialization().parse(sldText, state.getFormat(),
                        state.getFormatVersion(), getCatalog());
                Services.compiler().readInto(state, sld);
            } catch (StyleParseException e) {
                errors = errorJson(e);
            }
        }

        StringBuilder js = new StringBuilder(256);
        js.append("window.VisualStyle && window.VisualStyle.onState({");
        js.append("state:").append(state.toJson()).append(',');
        js.append("sld:").append(Json.quoted(sldText)).append(',');
        js.append("previewSld:").append(Json.quoted(previewSld(sldText))).append(',');
        js.append("errors:").append(errors).append(',');
        js.append("overwriteSource:").append(regenerateSource && !state.isSourceDirty()).append(',');
        js.append("readOnly:").append(context.isReadOnly());
        js.append("});");
        target.appendJavaScript(js.toString());

        if (sourceCodePanel != null && regenerateSource && !state.isSourceDirty()) {
            // 同步服务端模型；编辑器内容的刷新由上面的 onState(overwriteSource) 在前端完成
            // （该面板位于 AjaxTabbedPanel 内，未激活时不能在 Ajax 响应里替换，否则报
            //  "cannot update component that does not have setOutputMarkupId"）。
            sourceCodePanel.setSource(sldText);
        }
        if (previewPanel != null) {
            previewPanel.appendRefresh(target);
        }
        if (navigationPanel != null) {
            navigationPanel.appendRefresh(target);
        }
    }

    /**
     * 生成给 WMS {@code SLD_BODY} 用的 SLD：把 NamedLayer 名改成当前预览图层名。
     *
     * <p>不改写的话，GeoServer 会因为 {@code LAYERS} 与 SLD 内的 NamedLayer 名不匹配而
     * 静默回退到图层默认样式 —— 表现就是「预览永远是默认样式，改什么都不生效」。
     */
    private String previewSld(String sldText) {
        String layer = layerForPreview();
        if (sldText == null || sldText.isBlank() || layer == null || layer.isBlank()) {
            return sldText;
        }
        try {
            return Services.serialization().retargetNamedLayer(sldText, layer);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "预览 SLD 图层名改写失败，退回原始 SLD", e);
            return sldText;
        }
    }

    private String errorJson(StyleParseException e) {
        StringBuilder sb = new StringBuilder("[{");
        sb.append("\"line\":").append(e.getLine()).append(',');
        sb.append("\"column\":").append(e.getColumn()).append(',');
        sb.append("\"message\":").append(Json.quoted(e.getDisplayMessage()));
        sb.append("}]");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 业务动作
    // ------------------------------------------------------------------

    private void onSubmitVisualForm() {
        applyRequestToState();
    }

    /**
     * 用当前表单状态重建 SLD 源码（表单优先）。
     *
     * <p>⚠ 保存 / 校验前必须调用：{@code state.rawStyle} 平时只在「表单变化推送」里重建，
     * 如果用户改完表单立刻点保存（推送还在途或尚未触发），服务端手里的仍是上一次推送
     * 生成的旧源码，直接保存就会把用户刚做的修改<b>静默丢弃</b>——表现为「保存提示成功、
     * 图层与预览却毫无变化」（2026-10-17 用户报告，diag27/diag28 复现定位）。</p>
     *
     * @return true = 已按表单重建；false = 源码优先（用户手改过源码）或重建失败，保留原 rawStyle
     */
    private boolean regenerateSldFromForm(VisualStyleState state) {
        if (state.isSourceDirty()) {
            return false;   // 源码优先：保留用户手改的源码
        }
        try {
            StyledLayerDescriptor sld = Services.compiler().buildSld(state);
            String sldText = Services.serialization().encode(sld, "sld", "1.1.0");
            state.setRawStyle(sldText);
            // 与推送链路保持一致：表单重建出的内容是 1.1.0，登记版本必须同步
            if (!"1.1.0".equals(state.getFormatVersion())) {
                state.setFormatVersion("1.1.0");
            }
            return true;
        } catch (StyleParseException e) {
            // 表单参数不完整等原因重建失败：保留原 rawStyle，让后续校验给出错误提示
            LOGGER.log(Level.FINE, "保存/校验前按表单重建 SLD 失败，回退为现有源码", e);
            return false;
        }
    }

    /** 保存样式（可选同时绑定到图层）。 */
    private void doSave(AjaxRequestTarget target, boolean bindAfterSave) {
        applyRequestToState();
        VisualStyleState state = context.getState();
        regenerateSldFromForm(state);

        // 保存前校验
        List<StyleParseException> errors = Services.serialization().validate(
                state.getRawStyle(), state.getFormat(), state.getFormatVersion(), getCatalog());
        if (!errors.isEmpty()) {
            for (StyleParseException e : errors) {
                visualForm.error(e.getDisplayMessage());
            }
            pushStateToClient(target, false);
            addFeedbackPanels(target);
            return;
        }

        try {
            if (state.isNewStyle()) {
                StyleInfo created = Services.persistence().createStyle(getCatalog(),
                        state.getStyleName(), context.getWorkspace(), state.getFormat(),
                        state.getRawStyle());
                state.setNewStyle(false);
                state.setFilename(created.getFilename());
                if (created.getFormatVersion() != null) {
                    state.setFormatVersion(created.getFormatVersion().toString());
                }
                visualForm.info(msg("VisualStyleEditorPage.saved", "样式已保存")
                        + ": " + created.prefixedName());
            } else {
                StyleInfo info = context.resolveStyle(getCatalog());
                if (info == null) {
                    visualForm.error(msg("VisualStyleEditorPage.styleNotFound", "样式不存在"));
                    addFeedbackPanels(target);
                    return;
                }
                Services.persistence().saveStyle(getCatalog(), info, state.getRawStyle(), state.getFormat());
                visualForm.info(msg("VisualStyleEditorPage.saved", "样式已保存")
                        + ": " + info.prefixedName());
            }
            if (bindAfterSave) {
                bindStyleToLayer(true);
            }
            // 保存成功后解除「源码优先」：本次保存的内容已成为基线，
            // 否则后续表单修改会被源码优先分支吞掉（表现为改了没反应）
            state.setSourceDirty(false);
            // 保存后刷新导航列表与预览（预览改为走服务端已保存样式，避免 URL 过长）
            if (navigationPanel != null) {
                navigationPanel.reloadStyles();
            }
        } catch (StyleParseException e) {
            visualForm.error(e.getDisplayMessage());
        }
        pushStateToClient(target, false);
        addFeedbackPanels(target);
        if (navigationPanel != null) {
            target.add(navigationPanel);
        }
    }

    /** 绑定为图层默认样式。 */
    private void doBindLayer(AjaxRequestTarget target) {
        applyRequestToState();
        try {
            doSave(target, false);
            bindStyleToLayer(true);
            visualForm.info(msg("VisualStyleEditorPage.bound", "已绑定到图层"));
        } catch (StyleParseException e) {
            visualForm.error(e.getDisplayMessage());
        }
        addFeedbackPanels(target);
    }

    private void bindStyleToLayer(boolean asDefault) throws StyleParseException {
        VisualStyleState state = context.getState();
        Catalog catalog = getCatalog();
        LayerInfo layer = state.getPreviewLayer() == null || state.getPreviewLayer().isBlank()
                ? null
                : catalog.getLayerByName(state.getPreviewLayer());
        if (layer == null) {
            return;
        }
        StyleInfo info = context.resolveStyle(catalog);
        if (info == null) {
            return;
        }
        if (asDefault) {
            Services.persistence().setDefaultStyle(catalog, layer, info);
        } else {
            Services.persistence().addAlternativeStyle(catalog, layer, info);
        }
    }

    /** 校验（源码优先）。 */
    private void doValidate(AjaxRequestTarget target, boolean pushToClient) {
        applyRequestToState();
        VisualStyleState state = context.getState();
        // 与 doSave 同理：校验的应是「当前表单会保存成什么」，而不是上一次推送留下的旧源码
        regenerateSldFromForm(state);
        List<StyleParseException> errors = Services.serialization().validate(
                state.getRawStyle(), state.getFormat(), state.getFormatVersion(), getCatalog());
        if (errors.isEmpty()) {
            visualForm.info(msg("VisualStyleEditorPage.noErrors", "校验通过，未发现错误"));
        } else {
            for (StyleParseException e : errors) {
                visualForm.error(e.getDisplayMessage());
            }
        }
        if (pushToClient) {
            pushStateToClient(target, false);
        }
        addFeedbackPanels(target);
    }

    /** 从源码解析回表单（双向绑定的反向流程）。 */
    private void doParseSource(AjaxRequestTarget target) {
        applyRequestToState();
        VisualStyleState state = context.getState();
        state.setSourceDirty(false);
        try {
            StyledLayerDescriptor sld = Services.serialization().parse(state.getRawStyle(),
                    state.getFormat(), state.getFormatVersion(), getCatalog());
            boolean ok = Services.compiler().readInto(state, sld);
            if (ok) {
                visualForm.info(msg("VisualStyleEditorPage.parsed", "已由源码同步到表单"));
            } else {
                visualForm.warn(msg("VisualStyleEditorPage.nothingParsed", "源码中未找到可映射到表单的符号化规则"));
            }
        } catch (StyleParseException e) {
            visualForm.error(e.getDisplayMessage());
        }
        pushStateToClient(target, false);
        addFeedbackPanels(target);
        target.add(tabs);
    }

    /** 用表单生成源码（双向绑定的正向流程）。 */
    private void doGenerateSource(AjaxRequestTarget target) {
        applyRequestToState();
        context.getState().setSourceDirty(false);
        pushStateToClient(target, true);
        visualForm.info(msg("VisualStyleEditorPage.generated", "已由表单生成样式源码"));
        addFeedbackPanels(target);
        target.add(tabs);
    }

    // ------------------------------------------------------------------
    // 供面板调用
    // ------------------------------------------------------------------

    /** 切换样式（左侧导航点击）。 */
    public void selectStyle(AjaxRequestTarget target, String workspace, String styleName) {
        PageParameters params = new PageParameters();
        if (workspace != null && !workspace.isBlank()) {
            params.add(PARAM_WORKSPACE, workspace);
        }
        if (styleName != null && !styleName.isBlank()) {
            params.add(PARAM_STYLE, styleName);
        }
        setResponsePage(VisualStyleEditorPage.class, params);
    }

    /** 重新加载当前样式（新建/复制后使用）。 */
    public void reloadStyle(AjaxRequestTarget target, String workspace, String styleName) {
        selectStyle(target, workspace, styleName);
    }

    /** 删除样式。 */
    public void deleteStyle(AjaxRequestTarget target, StyleInfo style) {
        if (style == null || context.isReadOnly()) {
            return;
        }
        try {
            List<LayerInfo> refs = Services.persistence().styleReferences(getCatalog(), style);
            if (!refs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(5, refs.size()); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(refs.get(i).prefixedName());
                }
                visualForm.warn(msg("VisualStyleEditorPage.styleInUse", "样式仍被图层引用") + ": " + sb);
            }
            Services.persistence().deleteStyle(getCatalog(), style);
            visualForm.info(msg("VisualStyleEditorPage.deleted", "样式已删除")
                    + ": " + style.prefixedName());
        } catch (StyleParseException e) {
            visualForm.error(e.getDisplayMessage());
        }
        if (navigationPanel != null) {
            navigationPanel.reloadStyles();
            target.add(navigationPanel);
        }
        addFeedbackPanels(target);
    }

    /** 复制样式。 */
    public void copyStyle(AjaxRequestTarget target, StyleInfo source, String newName) {
        if (source == null || context.isReadOnly()) {
            return;
        }
        try {
            String ws = source.getWorkspace() == null ? context.getWorkspace()
                    : source.getWorkspace().getName();
            StyleInfo created = Services.persistence().copyStyle(getCatalog(), source, newName, ws);
            setPendingNotice("info", msg("VisualStyleEditorPage.copied", "样式已复制")
                    + ": " + created.prefixedName());
            selectStyle(target, ws, created.getName());
        } catch (StyleParseException e) {
            visualForm.error(e.getDisplayMessage());
            addFeedbackPanels(target);
        }
    }

    /** 新建样式。 */
    public void createStyle(AjaxRequestTarget target, String name, String format) {
        if (context.isReadOnly()) {
            return;
        }
        try {
            VisualStyleState state = context.getState();
            String source = Services.serialization().emptyTemplate(format, name);
            StyleInfo created = Services.persistence().createStyle(getCatalog(), name,
                    context.getWorkspace(), format, source);
            setPendingNotice("info", msg("VisualStyleEditorPage.created", "样式已创建")
                    + ": " + created.prefixedName());
            selectStyle(target, context.getWorkspace(), created.getName());
        } catch (StyleParseException e) {
            visualForm.error(e.getDisplayMessage());
            addFeedbackPanels(target);
        }
    }

    /** 切换工作区（左上角下拉）。 */
    public void selectWorkspace(AjaxRequestTarget target, String workspace) {
        PageParameters params = new PageParameters();
        if (workspace != null && !workspace.isBlank()) {
            params.add(PARAM_WORKSPACE, workspace);
        }
        setResponsePage(VisualStyleEditorPage.class, params);
    }

    /** 切换绑定图层。 */
    public void selectLayer(AjaxRequestTarget target, String layerName) {
        applyRequestToState();
        LayerInfo layer = layerName == null || layerName.isBlank()
                ? null
                : getCatalog().getLayerByName(layerName);
        context.bindLayer(getCatalog(), layer);
        context.getState().setPreviewLayer(layer == null ? "" : layer.prefixedName());
        // 字段 / 波段 / 包围盒随之变化，先推元数据再推状态
        pushMetadata(target);
        // 字段变化后重新把状态推给前端（字段下拉、波段下拉需要重建）
        pushStateToClient(target, false);
        // ⚠ 这里绝对不能 target.add(visualForm) / target.add(navigationPanel)：
        // 整体替换表单（或导航面板）后，被替换范围内所有 Wicket 组件的 Ajax 事件绑定都会失效
        // （它们的绑定走 OnDomReadyHeaderItem，在 Ajax 响应里不会重新生效），结果是
        // #vsPush 的推送通道、工具栏「保存并应用」、导航栏「复制 / 删除 / 图层下拉」全部变成死按钮。
        // 元数据与状态已由 pushMetadata / pushStateToClient 以 appendJavaScript 推送，
        // 字段表 / 直方图由调用方 NavigationPanel 自行 target.add(layerMetaContainer) 刷新。
        addFeedbackPanels(target);
    }

    /** 推送状态给客户端（供面板在数据变化后调用）。 */
    public void refreshClient(AjaxRequestTarget target) {
        pushStateToClient(target, false);
    }

    /** 「源码 → 表单」：解析源码并回填可视化表单。 */
    public void requireParseFromSource(AjaxRequestTarget target) {
        doParseSource(target);
    }

    /** 「表单 → 源码」：按表单重建 SLD 覆盖编辑器。 */
    public void requireGenerateFromForm(AjaxRequestTarget target) {
        doGenerateSource(target);
    }

    // ------------------------------------------------------------------
    // 数据驱动的服务端计算（分级 / 唯一值 / 栅格统计）
    // ------------------------------------------------------------------

    /**
     * 枚举分类字段的唯一值并生成唯一值分类方案。
     *
     * <p>对应前端「批量配色 / 枚举唯一值」按钮。
     */
    public void computeUniqueValues(AjaxRequestTarget target) {
        applyRequestToState();
        VisualStyleState state = context.getState();
        LayerInfo layer = resolveLayerForAnalysis();
        if (layer == null) {
            visualForm.error(msg("VisualStyleEditorPage.needLayer", "请先绑定一个图层，才能枚举字段唯一值"));
            addFeedbackPanels(target);
            return;
        }
        String field = state.getClassificationField();
        if (field == null || field.isBlank()) {
            visualForm.error(msg("VisualStyleEditorPage.needField", "请先选择分类字段"));
            addFeedbackPanels(target);
            return;
        }
        List<String> values = Services.metadata().uniqueValues(layer, getCatalog(), field,
                ClassificationService.MAX_UNIQUE_VALUES);
        if (values.isEmpty()) {
            visualForm.warn(msg("VisualStyleEditorPage.noValues", "该字段没有可用的取值"));
            addFeedbackPanels(target);
            return;
        }
        state.setUniqueValues(values);
        // 唯一值分类的配色策略：
        //   用户手动编辑过色带 → 按色带梯度采样；
        //   未编辑（colorStops 为空）→ 交给 ClassificationService 用定性色板循环配色，
        //   避免相邻类别颜色过于接近。
        List<org.geoserver.web.style.model.ClassBreak> breaks = Services.classification()
                .uniqueValues(values, state.getColorStops(), state.getStrokeColor());
        state.setBreaks(breaks);
        state.setClassificationMethod(org.geoserver.web.style.model.ClassificationMethod.UNIQUE_VALUES);
        state.setClassCount(breaks.size());
        visualForm.info(msg("VisualStyleEditorPage.uniqueValuesDone", "已枚举唯一值") + ": " + breaks.size());
        pushStateToClient(target, true);
        addFeedbackPanels(target);
    }

    /** 按当前分级方法计算断点与配色（等间隔 / 分位数 / Jenks）。 */
    public void computeClassification(AjaxRequestTarget target) {
        applyRequestToState();
        VisualStyleState state = context.getState();
        LayerInfo layer = resolveLayerForAnalysis();
        if (layer == null) {
            visualForm.error(msg("VisualStyleEditorPage.needLayer", "请先绑定一个图层，才能计算分级断点"));
            addFeedbackPanels(target);
            return;
        }
        String field = state.getClassificationField();
        if (field == null || field.isBlank()) {
            visualForm.error(msg("VisualStyleEditorPage.needField", "请先选择分类字段"));
            addFeedbackPanels(target);
            return;
        }
        double[] values = Services.metadata().numericValues(layer, getCatalog(), field);
        if (values.length == 0) {
            visualForm.error(msg("VisualStyleEditorPage.fieldNotNumeric", "该字段没有可用数值，无法分级；请改用唯一值分类"));
            addFeedbackPanels(target);
            return;
        }
        String method = state.getClassificationMethod().getId();
        if (!state.getClassificationMethod().isNumericBreakMethod()) {
            method = "equal";
            state.setClassificationMethod(
                    org.geoserver.web.style.model.ClassificationMethod.EQUAL_INTERVAL);
        }
        List<org.geoserver.web.style.model.ClassBreak> breaks = Services.classification()
                .classify(values, method, Math.max(1, state.getClassCount()),
                        state.effectiveColorStops(), state.getStrokeColor());
        state.setBreaks(breaks);
        state.setClassCount(breaks.size());
        double[] stats = Services.classification().statistics(values);
        visualForm.info(String.format(java.util.Locale.ROOT,
                "%s: n=%d min=%.4f max=%.4f mean=%.4f -> %d 级",
                msg("VisualStyleEditorPage.classifyDone", "已计算分级"),
                Long.valueOf((long) stats[0]), Double.valueOf(stats[1]), Double.valueOf(stats[2]),
                Double.valueOf(stats[3]), Integer.valueOf(breaks.size())));
        pushStateToClient(target, true);
        addFeedbackPanels(target);
    }

    /** 读取栅格统计（波段数、值域、直方图）并推送给前端色带编辑器。 */
    public void loadRasterStats(AjaxRequestTarget target) {
        applyRequestToState();
        LayerInfo layer = resolveLayerForAnalysis();
        if (layer == null) {
            visualForm.error(msg("VisualStyleEditorPage.needLayer", "请先绑定一个图层"));
            addFeedbackPanels(target);
            return;
        }
        org.geoserver.web.style.service.LayerMetadataService.RasterStats stats =
                Services.metadata().rasterStats(layer, getCatalog());
        VisualStyleState state = context.getState();
        if (stats.available) {
            state.setStretchMin(stats.min);
            state.setStretchMax(stats.max);
            if (stats.bandCount > 3) {
                state.setRedBand(1);
                state.setGreenBand(2);
                state.setBlueBand(3);
            }
            visualForm.info(String.format(java.util.Locale.ROOT,
                    "栅格统计: bands=%d %dx%d min=%.4f max=%.4f mean=%.4f",
                    Integer.valueOf(stats.bandCount), Integer.valueOf(stats.width),
                    Integer.valueOf(stats.height), Double.valueOf(stats.min),
                    Double.valueOf(stats.max), Double.valueOf(stats.mean)));
        } else {
            visualForm.warn(stats.message);
        }
        StringBuilder js = new StringBuilder(128);
        js.append("window.VisualStyle && window.VisualStyle.onRasterStats(");
        js.append("{\"bandCount\":").append(stats.bandCount);
        js.append(",\"min\":").append(stats.min);
        js.append(",\"max\":").append(stats.max);
        js.append(",\"mean\":").append(stats.mean);
        js.append(",\"width\":").append(stats.width);
        js.append(",\"height\":").append(stats.height);
        js.append(",\"available\":").append(stats.available);
        js.append(",\"message\":").append(Json.quoted(stats.message == null ? "" : stats.message));
        js.append(",\"histogram\":").append(Json.write(stats.histogram));
        js.append(",\"histogramMin\":").append(stats.histogramMin);
        js.append(",\"histogramMax\":").append(stats.histogramMax);
        js.append(",\"bandNames\":").append(Json.write(stats.bandNames));
        js.append("});");
        target.appendJavaScript(js.toString());
        pushStateToClient(target, true);
        addFeedbackPanels(target);
    }

    /** 解析当前用于分析（字段枚举 / 栅格统计）的图层。 */
    private LayerInfo resolveLayerForAnalysis() {
        VisualStyleState state = context.getState();
        String name = state.getPreviewLayer();
        if (name == null || name.isBlank()) {
            name = context.getLayerName();
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        return getCatalog().getLayerByName(name);
    }

    public EditorContext getContext() {
        return context;
    }

    public Catalog catalog() {
        return getCatalog();
    }

    // ------------------------------------------------------------------
    // 页面基础
    // ------------------------------------------------------------------

    @Override
    protected ComponentAuthorizer getPageAuthorizer() {
        // 与原生样式模块一致：工作区管理员即可访问，页面内部再按样式归属细化只读
        return ComponentAuthorizer.WORKSPACE_ADMIN;
    }

    @Override
    protected String getTitle() {
        VisualStyleState state = context.getState();
        String name = state.getQualifiedName();
        if (name == null || name.isBlank()) {
            name = msg("VisualStyleEditorPage.newStyle", "新建样式");
        }
        return msg("VisualStyleEditorPage.title", "可视化样式编辑器")
                + " - " + name;
    }

    @Override
    public void renderHead(IHeaderResponse response) {
        super.renderHead(response);
        PackageResourceReference css = new PackageResourceReference(
                VisualStyleEditorPage.class, "css/visualstyle.css");
        response.render(CssHeaderItem.forReference(css));
        response.render(JavaScriptHeaderItem.forReference(new PackageResourceReference(
                VisualStyleEditorPage.class, "js/visualstyle.js")));
        response.render(JavaScriptHeaderItem.forReference(new PackageResourceReference(
                VisualStyleEditorPage.class, "js/colormap.js")));

        // 初始化上下文
        VisualStyleState state = context.getState();
        // 首屏兜底：新建样式（或源码为空）时先按默认状态生成一次 SLD，
        // 保证源码视图 / 图例 / 预览在首次加载就有内容，而不是空白。
        if (state.getRawStyle() == null || state.getRawStyle().isBlank()) {
            try {
                StyledLayerDescriptor sld = Services.compiler().buildSld(state);
                state.setRawStyle(Services.serialization().encode(sld, "sld", "1.1.0"));
                if (!"1.1.0".equals(state.getFormatVersion())) {
                    state.setFormatVersion("1.1.0");
                }
            } catch (StyleParseException e) {
                LOGGER.log(Level.FINE, "首屏生成默认 SLD 失败（不影响表单编辑）", e);
            }
        }
        StringBuilder init = new StringBuilder(512);
        init.append("window.VisualStyle && window.VisualStyle.init({");
        init.append("formId:").append(Json.quoted(VISUAL_FORM_ID)).append(',');
        init.append("pushId:'vsPush',");
        init.append("sourceTaId:").append(Json.quoted(SOURCE_TEXTAREA_ID)).append(',');
        init.append("state:").append(state.toJson()).append(',');
        init.append("sld:").append(Json.quoted(state.getRawStyle())).append(',');
        init.append("previewSld:").append(Json.quoted(previewSld(state.getRawStyle()))).append(',');
        init.append("readOnly:").append(context.isReadOnly()).append(',');
        init.append("wmsUrl:").append(Json.quoted(contextPath() + "/wms")).append(',');
        init.append("layer:").append(Json.quoted(layerForPreview())).append(',');
        init.append("styleName:").append(Json.quoted(state.getStyleName())).append(',');
        init.append("workspace:").append(Json.quoted(context.getWorkspace())).append(',');
        init.append("ramps:").append(rampsJson()).append(',');
        init.append("metadata:").append(metadataJson()).append(',');
        init.append("labels:").append(labelsJson());
        init.append("});");
        response.render(OnDomReadyHeaderItem.forScript(init.toString()));
    }

    /** 色带目录（id / 名称 / 分组 / 控制点），供前端下拉与渐变预览使用。 */
    private String rampsJson() {
        List<Object> out = new ArrayList<>();
        for (org.geoserver.web.style.util.Ramps.Def d : org.geoserver.web.style.util.Ramps.all()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", d.id);
            m.put("label", d.label);
            m.put("group", d.group);
            m.put("stops", d.stops);
            out.add(m);
        }
        return Json.write(out);
    }

    /** 图层元数据（字段列表、波段数、包围盒），供前端动态构建下拉框。 */
    private String metadataJson() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        List<Object> fields = new ArrayList<>();
        for (org.geoserver.web.style.service.LayerMetadataService.FieldInfo f : context.getFields()) {
            Map<String, Object> fm = new java.util.LinkedHashMap<>();
            fm.put("name", f.name);
            fm.put("type", f.type);
            fm.put("numeric", Boolean.valueOf(f.numeric));
            fm.put("label", f.label);
            fields.add(fm);
        }
        m.put("fields", fields);

        LayerInfo layer = resolveLayerForAnalysis();
        m.put("layer", layer == null ? "" : layer.prefixedName());
        m.put("bands", Integer.valueOf(bandCount(layer)));
        m.put("bounds", EditorContext.metadata().previewBounds(layer));
        m.put("suggestedKind", layer == null ? "polygon"
                : EditorContext.metadata().suggestSymbolizerKind(layer));
        return Json.write(m);
    }

    /** 波段数量：Coverage 用维度数快速取得，避免读取栅格。 */
    private int bandCount(LayerInfo layer) {
        if (layer == null || !(layer.getResource() instanceof org.geoserver.catalog.CoverageInfo)) {
            return 0;
        }
        org.geoserver.catalog.CoverageInfo ci =
                (org.geoserver.catalog.CoverageInfo) layer.getResource();
        try {
            List<org.geoserver.catalog.CoverageDimensionInfo> dims = ci.getDimensions();
            if (dims != null && !dims.isEmpty()) {
                return dims.size();
            }
        } catch (Exception ignore) {
            // 忽略，退回默认
        }
        return 3;
    }

    /** 把元数据推送给前端（图层切换后调用）。 */
    public void pushMetadata(AjaxRequestTarget target) {
        target.appendJavaScript("window.VisualStyle && window.VisualStyle.onMetadata("
                + metadataJson() + ");");
    }

    private String labelsJson() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("uniqueValues", msg("VisualStyleEditorPage.uniqueValues", "枚举唯一值"));
        m.put("classify", msg("VisualStyleEditorPage.classify", "计算分级"));
        m.put("noLayer", msg("VisualStyleEditorPage.noLayer", "未绑定图层"));
        m.put("previewError", msg("VisualStyleEditorPage.previewError", "预览加载失败"));
        return Json.write(m);
    }

    /** Wicket 上下文路径（形如 /geoserver）。 */
    private String contextPath() {
        String ctx = getRequest().getContextPath();
        return ctx == null ? "" : ctx;
    }

    /** 预览使用的图层名。 */
    private String layerForPreview() {
        VisualStyleState state = context.getState();
        if (state.getPreviewLayer() != null && !state.getPreviewLayer().isBlank()) {
            return state.getPreviewLayer();
        }
        return context.getLayerName();
    }

    /** 只读标记（供面板使用）。 */
    public boolean readOnly() {
        return context.isReadOnly();
    }

    /** 便于面板触发反馈面板刷新。 */
    public void feedback(AjaxRequestTarget target) {
        addFeedbackPanels(target);
    }

    /** 序列化兼容：模板方法中的匿名内部类需要。 */
    static class FormState implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    /** 供外部（如其它页面）构造跳转链接。 */
    public static PageParameters params(String workspace, String styleName) {
        PageParameters p = new PageParameters();
        if (workspace != null && !workspace.isBlank()) {
            p.add(PARAM_WORKSPACE, workspace);
        }
        if (styleName != null && !styleName.isBlank()) {
            p.add(PARAM_STYLE, styleName);
        }
        return p;
    }

    /**
     * 内部服务定位快捷方式。
     *
     * <p>所有服务都由 GeoServer 的 Spring 容器管理（见 {@code applicationContext.xml}），
     * 这里按需从容器取，并委托给 {@link EditorContext} 中的空值兜底实现。
     * 页面本身不持有服务实例，避免 Wicket 会话反序列化后拿到失效引用。
     */
    private static final class Services {
        private Services() {}

        static org.geoserver.web.style.service.StyleCompiler compiler() {
            return EditorContext.compiler();
        }

        static org.geoserver.web.style.service.StyleSerializationService serialization() {
            return EditorContext.serialization();
        }

        static org.geoserver.web.style.service.LayerMetadataService metadata() {
            return EditorContext.metadata();
        }

        static org.geoserver.web.style.service.ClassificationService classification() {
            return EditorContext.classification();
        }

        static org.geoserver.web.style.service.StylePersistService persistence() {
            return EditorContext.persistence();
        }

        static org.geoserver.web.style.service.StylePermissionService permission() {
            return EditorContext.permission();
        }
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
