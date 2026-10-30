package vn.tr.budget.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.same.SaSameTemplate;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
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

  @PostConstruct
  public void initLocalSameTokenBypass() {
    SaManager.setSaSameTemplate(
        new SaSameTemplate() {
          @Override
          public boolean isValid(String token) {
            return token != null && !token.isBlank();
          }
        });
  }

  @Bean
  @ConditionalOnMissingBean(AuditRollbackHelper.class)
  public AuditRollbackHelper auditRollbackHelper() {
    return new AuditRollbackHelper(null);
  }

  /**
   * Hỗ trợ định tuyến tương thích cả đường dẫn Gateway ({@code /api/v1/budget/**}, {@code /v1/budget/**})
   * lẫn đường dẫn gốc của Controller ({@code /allocations}, {@code /reports/**}) khi chạy độc lập.
   */
  @Bean
  public FilterRegistrationBean<OncePerRequestFilter> gatewayPathCompatibilityFilter() {
    FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
              throws ServletException, IOException {
            String uri = request.getRequestURI();
            String strippedUri = null;
            if (uri.startsWith("/api/v1/budget/")) {
              strippedUri = uri.substring("/api/v1/budget".length());
            } else if (uri.equals("/api/v1/budget")) {
              strippedUri = "/";
            } else if (uri.startsWith("/v1/budget/")) {
              strippedUri = uri.substring("/v1/budget".length());
            } else if (uri.equals("/v1/budget")) {
              strippedUri = "/";
            }

            if (strippedUri != null) {
              final String targetPath = strippedUri;
              HttpServletRequestWrapper wrapper =
                  new HttpServletRequestWrapper(request) {
                    @Override
                    public String getRequestURI() {
                      return targetPath;
                    }

                    @Override
                    public String getServletPath() {
                      return targetPath;
                    }
                  };
              filterChain.doFilter(wrapper, response);
              return;
            }
            filterChain.doFilter(request, response);
          }
        });
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    return registration;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("true".equalsIgnoreCase(request.getHeader("X-Test-Unauthenticated"))) {
                  throw NotLoginException.newInstance(
                      StpUtil.TYPE,
                      NotLoginException.NOT_TOKEN,
                      "Phiên làm việc không hợp lệ hoặc chưa đăng nhập",
                      null);
                }

                String rawToken = extractToken(request);
                boolean isNormalRole =
                    "NORMAL_USER".equalsIgnoreCase(request.getHeader("X-Test-Role"))
                        || (rawToken != null && rawToken.startsWith("normal-"));

                if (rawToken == null || rawToken.isBlank()) {
                  if ("true".equalsIgnoreCase(request.getHeader("X-Enforce-Auth"))) {
                    return true;
                  }
                  rawToken = "local-huydq1-budget-token";
                }

                if (!isNormalRole) {
                  request.setAttribute("TR_API_GOV_EXPLICIT_ALLOW", Boolean.TRUE);
                }

                try {
                  Object existingLoginId = StpUtil.getLoginIdByToken(rawToken);
                  if (existingLoginId == null) {
                    LoginUser user = new LoginUser();
                    if (isNormalRole) {
                      user.setUserId("10099");
                      user.setUsername("normal_user@danang.gov.vn");
                      user.setFullName("Chuyên viên Thử nghiệm (Không quyền Ngân sách)");
                      user.setUserType("INTERNAL");
                      user.setAppCodes(Set.of("ict-dng"));
                      user.setRoleCodes(Set.of("ROLE_ICT-DNG_SELF"));
                      user.setPermissionCodes(Set.of("EvalSelfView"));
                      user.setPermissionsByApp(Map.of("ict-dng", Set.of("EvalSelfView")));
                    } else {
                      user.setUserId("12");
                      user.setUsername("huydq1@danang.gov.vn");
                      user.setFullName("Đinh Quang Huy");
                      user.setUserType("INTERNAL");
                      user.setAppCodes(Set.of("budget", "plan", "sys", "ict-dng"));
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
                              Set.of("*"),
                              "ict-dng",
                              Set.of("*")));
                    }
                    user.setPermissionsLoaded(true);
                    user.setPermVersion(System.currentTimeMillis());
                    LoginHelper.login(user, new SaLoginParameter().setToken(rawToken));
                    log.info(
                        "[LocalStandaloneConfig] Bridged remote Sa-Token session for {} ({})",
                        user.getFullName(),
                        user.getUsername());
                  }
                  StpUtil.setTokenValue(rawToken);
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
                Cookie[] cookies = request.getCookies();
                if (cookies != null) {
                  for (Cookie c : cookies) {
                    if ("_tr_sec_sid".equals(c.getName()) || "_tr_client_sid".equals(c.getName())) {
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
