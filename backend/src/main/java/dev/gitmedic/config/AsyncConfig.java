package dev.gitmedic.config;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

@Configuration
class AsyncConfig {

  /** Carries the request id into @Async jobs (picked up by Boot's task executor auto-config). */
  @Bean
  TaskDecorator mdcTaskDecorator() {
    return task -> {
      Map<String, String> context = MDC.getCopyOfContextMap();
      return () -> {
        if (context != null) {
          MDC.setContextMap(context);
        }
        try {
          task.run();
        } finally {
          MDC.clear();
        }
      };
    };
  }
}
