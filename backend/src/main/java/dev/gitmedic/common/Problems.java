package dev.gitmedic.common;

import dev.gitmedic.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/** Builds problem details and writes them from servlet filters (outside Spring MVC). */
public final class Problems {

  private Problems() {}

  public static ProblemDetail of(HttpStatus status, String code, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setProperty("code", code);
    pd.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
    return pd;
  }

  public static void write(
      HttpServletResponse response,
      ObjectMapper mapper,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), of(status, code, detail));
  }
}
