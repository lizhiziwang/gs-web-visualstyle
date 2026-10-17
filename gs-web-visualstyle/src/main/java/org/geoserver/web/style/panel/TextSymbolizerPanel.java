/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.panel;

import org.apache.wicket.markup.html.panel.Panel;
import org.geoserver.web.style.EditorContext;
import org.geoserver.web.style.VisualStyleEditorPage;

/**
 * 标注面板（TextSymbolizer）。
 *
 * <p>覆盖字体、字号、颜色、Halo 光晕、偏移量、锚点、标注避让（conflictResolution /
 * spaceAround）以及标注自身的比例尺开关。表单字段由前端 JS 收集后回传服务端，
 * 由 GeoTools 生成 {@code se:TextSymbolizer} 与对应的 {@code VendorOption}。
 */
public class TextSymbolizerPanel extends Panel {

    private static final long serialVersionUID = 1L;

    public TextSymbolizerPanel(String id, VisualStyleEditorPage page, EditorContext context) {
        super(id);
        // 面板位于页面 <form wicket:id="visualForm"> 内部：不再嵌套 Wicket Form。
        // 本面板完全由原生表单字段 + 前端 JS 收集构成，无需额外 Wicket 组件。
    }
}
