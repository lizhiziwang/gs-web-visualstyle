/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.panel;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.html.panel.Panel;
import org.geoserver.web.style.EditorContext;
import org.geoserver.web.style.VisualStyleEditorPage;

/**
 * 栅格符号化面板（RasterSymbolizer）。
 *
 * <p>支持：灰度单波段、多波段 RGB 合成、连续色带 Colormap、离散分级色带、
 * DEM 高程色阶；并提供直方图拉伸（ContrastEnhancement）、Gamma、透明度与
 * 色带编辑器（连续控制点 / 离散分级断点）。
 *
 * <p>「读取栅格统计」按钮会把服务端算出的波段数、值域与直方图推给颜色编辑器，
 * 使色带断点可以对齐真实数据分布。
 */
public class RasterSymbolizerPanel extends Panel {

    private static final long serialVersionUID = 1L;

    private final VisualStyleEditorPage page;
    private final EditorContext context;

    public RasterSymbolizerPanel(String id, VisualStyleEditorPage page, EditorContext context) {
        super(id);
        this.page = page;
        this.context = context;

        // 面板位于页面表单内部，不再嵌套 Wicket Form（HTML/Wicket 均不允许嵌套表单）
        add(new AjaxLink<Void>("rasterStats") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                page.loadRasterStats(target);
            }

            @Override
            protected void updateAjaxAttributes(
                    org.apache.wicket.ajax.attributes.AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(
                        "var f=document.getElementById('" + VisualStyleEditorPage.VISUAL_FORM_ID + "');"
                                + "var r={};if(f&&window.VisualStyle){window.VisualStyle.collect(f,r);}return r;");
            }
        }.setEnabled(!context.isReadOnly()));
    }
}
