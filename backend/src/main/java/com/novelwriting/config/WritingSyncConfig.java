package com.novelwriting.config;

import com.novelwriting.service.WritingCloudService;
import jakarta.servlet.http.*;
import java.util.regex.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnWebApplication
public class WritingSyncConfig implements WebMvcConfigurer {

  private final WritingCloudService cloud;

  public WritingSyncConfig(WritingCloudService cloud) {
    this.cloud = cloud;
  }

  @Scheduled(
    fixedDelayString = "${writing.sync.delay-ms:300000}",
    initialDelay = 60000
  )
  public void autoSync() {
    for (Long id : cloud.pending())
      try {
        cloud.push(id);
      } catch (Exception e) {
        cloud.note(id, "自动同步未完成，请在写作页检查云端版本或连接设置");
      }
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(
      new HandlerInterceptor() {
        final Pattern novel = Pattern.compile("^/api/novels/(\\d+)(?:/(.*))?$");

        @Override
        public void afterCompletion(
          HttpServletRequest request,
          HttpServletResponse response,
          Object handler,
          Exception error
        ) {
          if (
            error != null ||
            response.getStatus() >= 300 ||
            !java.util.Set.of("POST", "PUT", "DELETE").contains(
              request.getMethod()
            )
          ) return;
          Matcher m = novel.matcher(request.getRequestURI());
          if (!m.matches()) return;
          String path = m.group(2) == null ? "" : m.group(2);
          if (
            path.startsWith("writing") ||
            path.startsWith("webdav") ||
            path.startsWith("export") ||
            (request.getMethod().equals("DELETE") && path.isEmpty())
          ) return;
          try {
            cloud.markResourceChange(Long.parseLong(m.group(1)));
          } catch (Exception ignored) {
            /* Never convert a completed user save into a retry that duplicates it. */
          }
        }
      }
    );
  }
}
