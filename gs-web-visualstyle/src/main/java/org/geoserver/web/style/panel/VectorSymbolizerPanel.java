/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.panel;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.html.panel.Panel;
import org.geoserver.web.style.EditorContext;
import org.geoserver.web.style.VisualStyleEditorPage;

/**
 * 矢量符号化面板（PointSymbolizer / LineSymbolizer / PolygonSymbolizer）。
 *
 * <p>UI 由「Wicket 组件 + 原生 HTML 表单」混合构成：
 * 表单字段名与 {@link org.geoserver.web.style.model.VisualStyleState} 字段一一对应，
 * 变更经 {@code VisualStyle.push()} 回传服务端重建 SLD。
 * 需要读取数据的操作（枚举唯一值、计算分级断点）走 Wicket AJAX。
 */
public class VectorSymbolizerPanel extends Panel {

    private static final long serialVersionUID = 1L;

    /** 把可视化表单全部字段回传服务端的 JS 片段。 */
    private static final String COLLECT =
            "var f=document.getElementById('" + VisualStyleEditorPage.VISUAL_FORM_ID + "');"
                    + "var r={};if(f&&window.VisualStyle){window.VisualStyle.collect(f,r);}return r;";

    private final VisualStyleEditorPage page;
    private final EditorContext context;

    public VectorSymbolizerPanel(String id, VisualStyleEditorPage page, EditorContext context) {
        super(id);
        this.page = page;
        this.context = context;

        // 说明：面板位于页面的 <form wicket:id="visualForm"> 内部，
        // 不能（也无需）再嵌套 Wicket Form —— HTML 与 Wicket 均不允许嵌套表单。
        // 数据类按钮统一用 AjaxLink + 动态附加参数，把整个可视化表单回传服务端。
        add(new AjaxLink<Void>("enumerateUnique") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                page.computeUniqueValues(target);
            }

            @Override
            protected void updateAjaxAttributes(
                    org.apache.wicket.ajax.attributes.AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(COLLECT);
            }
        }.setEnabled(!context.isReadOnly()));

        add(new AjaxLink<Void>("classify") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                page.computeClassification(target);
            }

            @Override
            protected void updateAjaxAttributes(
                    org.apache.wicket.ajax.attributes.AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(COLLECT);
            }
        }.setEnabled(!context.isReadOnly()));
    }
}
