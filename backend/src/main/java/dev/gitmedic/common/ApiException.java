package dev.gitmedic.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error with a stable {@code code}, rendered as RFC 9457 problem details. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final Map<String, Object> extra;

  public ApiException(HttpStatus status, String code, String detail) {
    this(status, code, detail, Map.of());
  }

  public ApiException(HttpStatus status, String code, String detail, Map<String, Object> extra) {
    super(detail);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
  }

  public static ApiException badRequest(String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public Map<String, Object> extra() {
    return extra;
  }
}
