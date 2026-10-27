package vn.tr.budget.config;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.tr.common.core.domain.model.LoginUser;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.satoken.utils.LoginHelper;

/**
 * Cấu hình hỗ trợ khởi chạy độc lập (Standalone Local Profile) cho {@code tr-budget-service}
 * và đồng bộ phiên xác thực Sa-Token Bearer / Cookie từ Gateway từ xa ({@code huydq1@danang.gov.vn}).
 *
 * @author tyran8x
 */
@Slf4j
@Configuration
@Profile("local")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalStandaloneConfig implements WebMvcConfigurer {

  @Bean
  @ConditionalOnMissingBean(AuditRollbackHelper.class)
  public AuditRollbackHelper auditRollbackHelper() {
    return new AuditRollbackHelper(null);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                request.setAttribute("TR_API_GOV_EXPLICIT_ALLOW", Boolean.TRUE);
                String rawToken = extractToken(request);
                if (rawToken == null || rawToken.isBlank()) {
                  rawToken = "local-huydq1-budget-token";
                }
                try {
                  Object existingLoginId = StpUtil.getLoginIdByToken(rawToken);
                  if (existingLoginId == null) {
                    LoginUser user = new LoginUser();
                    user.setUserId("12");
                    user.setUsername("huydq1@danang.gov.vn");
                    user.setFullName("Đinh Quang Huy");
                    user.setUserType("INTERNAL");
                    user.setAppCodes(Set.of("budget", "plan", "sys"));
                    user.setRoleCodes(Set.of("ROLE_SYS_ADMIN"));
                    user.setPermissionCodes(Set.of("*"));
                    user.setPermissionsByApp(
                        Map.of(
                            "*",
                            Set.of("*"),
                            "budget",
                            Set.of("*"),
                            "plan",
                            Set.of("*"),
                            "sys",
                            Set.of("*")));
                    user.setPermissionsLoaded(true);
                    user.setPermVersion(System.currentTimeMillis());
                    LoginHelper.login(user, new SaLoginParameter().setToken(rawToken));
                    log.info(
                        "[LocalStandaloneConfig] Bridged remote Sa-Token session for {} ({})",
                        user.getFullName(),
                        user.getUsername());
                  }
                } catch (Exception e) {
                  log.debug(
                      "[LocalStandaloneConfig] Could not bridge token session: {}",
                      e.getMessage());
                }
                return true;
              }

              private String extractToken(HttpServletRequest request) {
                String authHeader = request.getHeader("Authorization");
                if (authHeader != null && !authHeader.isBlank()) {
                  return authHeader.startsWith("Bearer ")
                      ? authHeader.substring(7).trim()
                      : authHeader.trim();
                }
                String saHeader = request.getHeader("satoken");
                if (saHeader != null && !saHeader.isBlank()) {
                  return saHeader.trim();
                }
                Cookie[] cookies = request.getCookies();
                if (cookies != null) {
                  for (Cookie c : cookies) {
                    if ("satoken".equals(c.getName())
                        || "_tr_sec_sid".equals(c.getName())
                        || "_tr_client_sid".equals(c.getName())) {
                      if (c.getValue() != null && !c.getValue().isBlank()) {
                        return c.getValue().trim();
                      }
                    }
                  }
                }
                return null;
              }
            })
        .addPathPatterns("/**")
        .order(Ordered.HIGHEST_PRECEDENCE);
  }
}
