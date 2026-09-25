package com.wildlife.recognition.config;

import com.wildlife.recognition.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        AuthService.Session session = authService.verifySession(request.getHeader("X-Auth-Token"));
        if (session == null) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或 token 已过期");
            return false;
        }

        request.setAttribute("username", session.username());
        request.setAttribute("role", session.role());

        if (isAdminOnly(method, path) && !session.isAdmin()) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return false;
        }

        return true;
    }

    /**
     * 管理员专属端点。与前端 {@code router/index.ts} 的 meta.roles 矩阵保持一致：
     * <pre>
     *   ADMIN 全部页面
     *   USER  /dashboard /tasks /tasks/:id /results /reviews
     *   仅 ADMIN：/models /statistics /users
     * </pre>
     *
     * <p>三处刻意的「不设限」，都是为了不让权限收口把普通用户的可用页面打挂：
     * <ul>
     *   <li><b>模型的 GET 开放</b>：{@code DashboardView}（普通用户可见）要调
     *       {@code /api/models/active} 显示当前模型，任务创建弹窗也要列出可选版本；
     *       只有增 / 改 / 删归管理员（对应 {@code /models} 页）。</li>
     *   <li><b>{@code /api/user/me} 与 {@code /api/user/password} 开放</b>：这是「查自己」
     *       与「改自己密码」的自助接口，任何已登录用户都该能用。</li>
     *   <li><b>统计接口不设限</b>：{@code DashboardView} 与 {@code TaskListView}
     *       （均为普通用户可见）都在调 {@code /api/statistics/task-status} 等，
     *       整体限权会让普通用户首页直接白屏；{@code /statistics} 页的管理员限制
     *       属于展示层约定，不当作数据边界。</li>
     * </ul>
     *
     * <p>注意：{@code AuthService.normalizeRole()} 只归一出 ADMIN 与 USER 两种角色，
     * 库里的 REVIEWER 会被降为 USER，所以这里也只区分「是否管理员」。
     * 若日后要让 REVIEWER 真正生效（如仅其可提交复核结论），需先改归一化逻辑，
     * 再在 {@code preHandle} 里加一层比 isAdmin() 更细的判定。
     */
    private static boolean isAdminOnly(String method, String path) {
        if (path.startsWith("/api/models")) {
            return !"GET".equalsIgnoreCase(method);
        }
        if (path.startsWith("/api/user")) {
            if ("GET".equalsIgnoreCase(method) && path.equals("/api/user/me")) {
                return false;
            }
            return !("POST".equalsIgnoreCase(method) && path.equals("/api/user/password"));
        }
        return false;
    }

    private static void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
