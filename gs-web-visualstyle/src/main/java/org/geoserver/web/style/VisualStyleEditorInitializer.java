/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.web.GeoServerApplication;
import org.geoserver.web.GeoServerApplicationInitializer;
import org.geoserver.web.style.service.StyleSerializationService;
import org.geotools.util.logging.Logging;

/**
 * Wicket 应用初始化钩子。
 *
 * <p>GeoServer 3.0.1 的 {@code GeoServerApplication.init()} 会遍历所有
 * {@link GeoServerApplicationInitializer} bean 并调用 {@code init(app)}，
 * 这是插件在 Wicket 应用启动时执行自定义逻辑（挂载页面 / 注册资源 / 能力自检）的
 * 官方扩展点。
 *
 * <p>本插件<b>不主动 mountPage</b>：页面通过 Wicket 的可书签化 URL 访问
 * （{@code ?wicket:bookmarkablePage=:org.geoserver.web.style.VisualStyleEditorPage}），
 * 与左侧菜单入口同时可用，避免与 GeoServer 自身的 URL 映射策略相互干扰。
 */
public class VisualStyleEditorInitializer implements GeoServerApplicationInitializer {

    private static final Logger LOGGER = Logging.getLogger(VisualStyleEditorInitializer.class);

    @Override
    public void init(GeoServerApplication application) {
        try {
            StyleSerializationService serialization = new StyleSerializationService();
            Set<String> formats = serialization.availableFormats();
            LOGGER.info("[gs-web-visualstyle] 可视化样式编辑面板已加载；可用样式格式: " + formats);
            if (!formats.contains(StyleSerializationService.FORMAT_CSS)) {
                LOGGER.info("[gs-web-visualstyle] 未检测到 GeoCSS 处理器（gs-css-plugin 未安装）："
                        + "GeoCSS 源码视图将不可用，SLD 1.1.0 不受影响。");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[gs-web-visualstyle] 初始化自检失败", e);
        }

        // 显式挂载一个稳定的短 URL。
        //
        // 关于路由的重要说明（GeoServer 3.0.1 / Wicket 10 的行为变更）：
        //   GeoServer 2.x 时代的 `?wicket:bookmarkablePage=:FQCN` 形式在 Wicket 10 中
        //   已不再被默认请求映射器解析（该查询参数映射器已被移除），
        //   即使原生页面（如 StylePage）用这种 URL 也只会被重定向回首页。
        //   Wicket 10 的等价形式是：
        //       /geoserver/web/wicket/bookmarkable/<FQCN>
        //   本插件额外挂载一个短路径，使入口更稳定、可书签：
        //       /geoserver/web/visualstyle
        try {
            application.mountPage("/visualstyle", VisualStyleEditorPage.class);
            LOGGER.info("[gs-web-visualstyle] 页面已挂载: /geoserver/web/visualstyle "
                    + "(等价 URL: /geoserver/web/wicket/bookmarkable/"
                    + "org.geoserver.web.style.VisualStyleEditorPage)");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "[gs-web-visualstyle] 挂载 /visualstyle 失败（不影响 wicket/bookmarkable 访问方式）", e);
        }
    }
}
