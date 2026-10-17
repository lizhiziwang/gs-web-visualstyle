/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.panel;

import org.apache.wicket.markup.html.panel.Panel;
import org.geoserver.web.style.EditorContext;
import org.geoserver.web.style.VisualStyleEditorPage;

/**
 * 比例尺控制面板。
 *
 * <p>通过可视化滑块/数值框设置 {@code MinScaleDenominator} / {@code MaxScaleDenominator}，
 * 并可选为标注单独设置比例尺可见区间。比例尺比对使用 GeoServer/WMS 口径：
 * 地图比例尺 = 0.0254 / 每像素米数 / 90 DPI 的倒数关系，界面上直接以「1:N」展示。
 *
 * <p>面板下半部分是图例实时预览（由前端依据当前状态与分级条目渲染，支持
 * 单符号、唯一值分类、分级设色、栅格色带四种图例形态）。
 */
public class ScaleControlPanel extends Panel {

    private static final long serialVersionUID = 1L;

    public ScaleControlPanel(String id, VisualStyleEditorPage page, EditorContext context) {
        super(id);
        // 面板位于页面 <form wicket:id="visualForm"> 内部：不再嵌套 Wicket Form。
        // 本面板完全由原生表单字段 + 前端 JS 收集构成，无需额外 Wicket 组件。
    }
}
