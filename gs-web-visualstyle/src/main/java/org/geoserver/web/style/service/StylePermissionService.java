/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.service;

import java.io.Serializable;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.security.GeoServerRoleService;
import org.geoserver.security.GeoServerSecurityManager;
import org.geoserver.security.impl.GeoServerRole;
import org.geoserver.web.ComponentAuthorizer;
import org.geotools.util.logging.Logging;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 样式读写权限判定服务。
 *
 * <p>完全复用 GeoServer 原生权限体系，判定规则与原生样式模块
 * （{@code org.geoserver.wms.web.data.AbstractStylePage}）保持一致：
 * <ul>
 *   <li>页面级授权：{@code ComponentAuthorizer.WORKSPACE_ADMIN}；</li>
 *   <li>全局样式（无工作区）：仅 {@code ROLE_ADMINISTRATOR} 可编辑，其它用户只读；</li>
 *   <li>工作区样式：该用户是任一工作区管理员即可编辑。</li>
 * </ul>
 *
 * <p>另外支持一个可选的细粒度角色名 {@value #STYLE_EDIT_ROLE}（默认关闭语义，
 * 仅在用户确实配置了该角色时生效），用于把「样式编辑」权限从
 * 「工作区管理员」中拆出来单独授予。
 */
public class StylePermissionService implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logging.getLogger(StylePermissionService.class);

    /** 可选的细粒度样式编辑角色。 */
    public static final String STYLE_EDIT_ROLE = "ROLE_STYLE_EDIT";

    /** 是否允许通过细粒度角色授予样式编辑权限。 */
    private boolean granularRoleEnabled = true;

    // ==================================================================
    // 基础判定
    // ==================================================================

    /** 当前登录身份。 */
    public Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** 是否已登录。 */
    public boolean isAuthenticated() {
        Authentication auth = authentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
    }

    /** 是否管理员。 */
    public boolean isAdmin() {
        return ComponentAuthorizer.ADMIN.isAccessAllowed(StylePermissionService.class, authentication());
    }

    /** 是否（任一）工作区管理员。 */
    public boolean isWorkspaceAdmin() {
        return ComponentAuthorizer.WORKSPACE_ADMIN.isAccessAllowed(StylePermissionService.class,
                authentication());
    }

    /** 当前用户是否持有 {@value #STYLE_EDIT_ROLE} 角色。 */
    public boolean hasStyleEditRole() {
        if (!granularRoleEnabled) {
            return false;
        }
        Authentication auth = authentication();
        if (auth == null) {
            return false;
        }
        // 1) 直接看 Spring Security 的授权集合（快）
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities != null) {
            for (GrantedAuthority a : authorities) {
                if (STYLE_EDIT_ROLE.equalsIgnoreCase(a.getAuthority())) {
                    return true;
                }
            }
        }
        // 2) 再看 GeoServer 活动角色服务（有些角色由 role service 动态计算）
        try {
            GeoServerSecurityManager sm = GeoServerExtensions.bean(GeoServerSecurityManager.class);
            if (sm == null) {
                return false;
            }
            GeoServerRoleService rs = sm.getActiveRoleService();
            if (rs == null) {
                return false;
            }
            Collection<GeoServerRole> roles = rs.getRolesForUser(auth.getName());
            if (roles != null) {
                for (GeoServerRole r : roles) {
                    if (STYLE_EDIT_ROLE.equalsIgnoreCase(r.getAuthority())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "查询活动角色服务失败", e);
        }
        return false;
    }

    // ==================================================================
    // 业务判定
    // ==================================================================

    /** 是否可以编辑指定样式。 */
    public boolean canEditStyle(StyleInfo style) {
        if (style == null) {
            // 无样式上下文时（例如「新建」按钮），按全局样式口径判定：仅管理员
            return isAdmin();
        }
        String ws = style.getWorkspace() == null ? null : style.getWorkspace().getName();
        return canEditWorkspace(ws);
    }

    /** 是否可以在指定工作区（{@code null}/空 = 全局）新建/编辑/删除样式。 */
    public boolean canEditWorkspace(String workspace) {
        if (isAdmin()) {
            return true;
        }
        if (workspace == null || workspace.isBlank()) {
            // 全局样式：原生行为是仅管理员可改
            return false;
        }
        return isWorkspaceAdmin() || hasStyleEditRole();
    }

    /** 是否可以把样式绑定到该图层（需要对该图层所在工作区有编辑权限）。 */
    public boolean canBindLayer(LayerInfo layer) {
        if (layer == null || layer.getResource() == null || layer.getResource().getStore() == null) {
            return isAdmin();
        }
        String ws = layer.getResource().getStore().getWorkspace() == null
                ? null
                : layer.getResource().getStore().getWorkspace().getName();
        return canEditWorkspace(ws);
    }

    /** 当前用户可读写的全部工作区名（用于 UI 过滤与提示）。 */
    public boolean isReadOnly() {
        return !isAdmin() && !isWorkspaceAdmin() && !hasStyleEditRole();
    }

    public boolean isGranularRoleEnabled() {
        return granularRoleEnabled;
    }

    public void setGranularRoleEnabled(boolean granularRoleEnabled) {
        this.granularRoleEnabled = granularRoleEnabled;
    }
}
