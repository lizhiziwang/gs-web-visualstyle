/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.panel;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.head.CssUrlReferenceHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptUrlReferenceHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.panel.Panel;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.ows.URLMangler;
import org.geoserver.ows.util.ResponseUtils;
import org.geoserver.web.GeoServerApplication;
import org.geoserver.web.style.EditorContext;
import org.geoserver.web.style.VisualStyleEditorPage;
import org.geoserver.web.style.util.Json;

/**
 * 右侧实时预览面板。
 *
 * <p>内嵌 OpenLayers（复用 GeoServer 自身提供的 {@code /openlayers3/ol.js} 与 {@code ol.css}，
 * 与原生「图层预览 / 样式编辑预览」使用同一份库）加载 GeoServer WMS 服务。
 *
 * <p><b>所见即所得的关键</b>：预览使用 {@code SLD_BODY} 参数把<em>尚未保存</em>的样式
 * 直接交给 WMS 渲染；由于 SLD 可能超过 GET 的 URL 长度限制，这里通过 OpenLayers 的
 * {@code imageLoadFunction} 把 WMS 请求改写为 <b>POST</b>，彻底规避长度限制。
 *
 * <p>同时渲染图例预览：单符号、唯一值分类、分级设色、栅格色带四种形态均由前端依据
 * 服务端推送的状态绘制，无需再请求 GetLegendGraphic。
 */
public class PreviewPanel extends Panel {

    private static final long serialVersionUID = 1L;

    private final VisualStyleEditorPage page;
    private final EditorContext context;
    private final WebMarkupContainer mapContainer = new WebMarkupContainer("mapContainer");

    public PreviewPanel(String id, VisualStyleEditorPage page, EditorContext context) {
        super(id);
        this.page = page;
        this.context = context;

        mapContainer.setOutputMarkupId(true);
        mapContainer.setMarkupId("vs-map");
        add(mapContainer);

        add(new AjaxLink<Void>("refreshPreview") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                page.refreshClient(target);
                target.appendJavaScript("window.VisualStyle && window.VisualStyle.refreshPreview(true);");
            }
        });

        add(new AjaxLink<Void>("resetView") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                target.appendJavaScript("window.VisualStyle && window.VisualStyle.resetView();");
            }
        });

        add(new AjaxLink<Void>("toggleBaseMap") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                target.appendJavaScript("window.VisualStyle && window.VisualStyle.toggleBaseMap();");
            }
        });

        setOutputMarkupId(true);
    }

    @Override
    public void renderHead(IHeaderResponse response) {
        super.renderHead(response);
        HttpServletRequest req = GeoServerApplication.get().servletRequest();
        String base = ResponseUtils.baseURL(req);
        String olJs = ResponseUtils.buildURL(base, "/openlayers3/ol.js", null, URLMangler.URLType.RESOURCE);
        String olCss = ResponseUtils.buildURL(base, "/openlayers3/ol.css", null, URLMangler.URLType.RESOURCE);
        response.render(new CssUrlReferenceHeaderItem(olCss, null, null));
        response.render(new JavaScriptUrlReferenceHeaderItem(olJs, null));

        // 预览初始化参数：把几何范围/比例尺等交给前端
        LayerInfo layer = context.resolveLayer(page.catalog());
        Map<String, Object> init = new HashMap<>();
        init.put("layer", context.getState().getPreviewLayer());
        init.put("bounds", bounds(layer));
        init.put("resolution", resolution(layer));
        init.put("wmsUrl", req.getContextPath() + "/wms");
        response.render(OnDomReadyHeaderItem.forScript(
                "window.VisualStyle && window.VisualStyle.initPreview(" + Json.write(init) + ");"));
    }

    private double[] bounds(LayerInfo layer) {
        return EditorContext.metadata().previewBounds(layer);
    }

    private double resolution(LayerInfo layer) {
        return EditorContext.metadata().previewResolution(layer);
    }

    /** 由页面在状态变化后调用，触发预览刷新。 */
    public void appendRefresh(AjaxRequestTarget target) {
        target.appendJavaScript("window.VisualStyle && window.VisualStyle.refreshPreview(false);");
    }
}
