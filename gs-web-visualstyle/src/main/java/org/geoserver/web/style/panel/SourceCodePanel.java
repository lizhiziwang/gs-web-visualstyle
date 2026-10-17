/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.panel;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxCallListener;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.PropertyModel;
import org.geoserver.web.style.EditorContext;
import org.geoserver.web.style.VisualStyleEditorPage;
import org.geoserver.web.style.model.VisualStyleState;
import org.geoserver.web.wicket.CodeMirrorEditor;

/**
 * 高级源码面板 —— 双向绑定的「源码侧」。
 *
 * <p>直接复用 GeoServer 原生 {@link CodeMirrorEditor}（Wicket 组件），
 * 因此天然获得 GeoServer 自带能力：
 * <ul>
 *   <li>语法高亮：模式由 {@code StyleHandler.getCodeMirrorEditMode()} 决定
 *       （SLD 为 {@code text/sld10}，GeoCSS 为 {@code text/geocss}）；</li>
 *   <li>行号、撤销/重做、跳转行、格式化、查找替换；</li>
 *   <li>SLD 标签自动补全（show-hint + sld10-hint）与 GeoCSS 补全；</li>
 *   <li>GeoServer 的保存装饰器（{@code getSaveDecorator()}），保证 AJAX 提交前
 *       编辑器内容已同步回底层 textarea。</li>
 * </ul>
 *
 * <p>本面板不嵌套 {@code <form>}：组件挂在页面级 {@code visualForm} 之下，
 * 既满足 Wicket 对 FormComponent 必须位于 Form 内的要求，又避免嵌套表单。
 */
public class SourceCodePanel extends Panel {

    private static final long serialVersionUID = 1L;

    /** 把可视化表单全部字段回传服务端的 JS 片段。 */
    private static final String COLLECT =
            "var f=document.getElementById('" + VisualStyleEditorPage.VISUAL_FORM_ID + "');"
                    + "var r={};if(f&&window.VisualStyle){window.VisualStyle.collect(f,r);}"
                    + "var ed=window.VisualStyle?window.VisualStyle.editorValue():null;"
                    + "if(ed!==null&&ed!==undefined){r['vs.rawStyle']=ed;}"
                    + "return r;";

    private final VisualStyleEditorPage page;
    private final EditorContext context;
    private final CodeMirrorEditor editor;

    public SourceCodePanel(String id, VisualStyleEditorPage page, EditorContext context) {
        super(id);
        this.page = page;
        this.context = context;

        VisualStyleState state = context.getState();
        String mode = EditorContext.serialization().codeMirrorMode(state.getFormat());

        IModel<String> model = new PropertyModel<String>(context.getState(), "rawStyle");
        editor = new CodeMirrorEditor("sourceEditor", mode, model);
        // ⚠ 必须是合法 JS 标识符：GeoServer 的 CodeMirrorEditor 会把该 id 直接拼进 JS
        // （document.gsEditors.<id> = editor / document.gsEditors.<id>.setOption(...)），
        // 一旦含 '-' 之类的字符，整段 header 脚本语法错误 → 编辑器初始化与同块 Ajax 绑定全部失效。
        editor.setTextAreaMarkupId(VisualStyleEditorPage.SOURCE_TEXTAREA_ID);
        editor.setOutputMarkupId(true);
        add(editor);
        // 面板自身可被 Ajax 单独替换（例如「表单 → 源码」后刷新编辑器内容）
        setOutputMarkupId(true);

        // 「源码 → 表单」：解析后回填可视化表单
        add(new AjaxLink<Void>("applySource") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                context.getState().setSourceDirty(true);
                page.requireParseFromSource(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(COLLECT);
                attributes.getAjaxCallListeners().add(saveDecorator());
            }
        });

        // 「表单 → 源码」：按表单重新生成 SLD 覆盖编辑器
        add(new AjaxLink<Void>("resetSource") {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                context.getState().setSourceDirty(false);
                page.requireGenerateFromForm(target);
            }

            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getDynamicExtraParameters().add(COLLECT);
            }
        });
    }

    /** 取原生编辑器提供的保存装饰器（会把 CodeMirror 内容同步回 textarea）。 */
    private AjaxCallListener saveDecorator() {
        try {
            return (AjaxCallListener) editor.getSaveDecorator();
        } catch (Exception e) {
            return new AjaxCallListener();
        }
    }

    /** 服务端重新生成源码后同步编辑器内容。 */
    public void setSource(String source) {
        if (source != null) {
            editor.setModelObject(source);
        }
    }

    /** 当前编辑器文本长度（用于诊断）。 */
    public int sourceLength() {
        String s = context.getState().getRawStyle();
        return s == null ? 0 : s.length();
    }
}
