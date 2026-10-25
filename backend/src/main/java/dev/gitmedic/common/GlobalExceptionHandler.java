package dev.gitmedic.common;

import dev.gitmedic.config.RequestIdFilter;
import dev.gitmedic.github.GitHubException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves as RFC 9457 problem details with a stable {@code code} and requestId. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> api(ApiException ex) {
    ProblemDetail pd = Problems.of(ex.status(), ex.code(), ex.getMessage());
    ex.extra().forEach(pd::setProperty);
    return ResponseEntity.status(ex.status()).body(pd);
  }

  /** Maps GitHub failures during a request to the §7.1 status codes. */
  @ExceptionHandler(GitHubException.class)
  ResponseEntity<ProblemDetail> github(GitHubException ex) {
    return api(
        switch (ex.kind()) {
          case NOT_FOUND ->
              new ApiException(HttpStatus.NOT_FOUND, "GITHUB_NOT_FOUND", "Not found on GitHub.");
          case TOKEN_INVALID ->
              new ApiException(
                  HttpStatus.UNAUTHORIZED,
                  "GITHUB_TOKEN_INVALID",
                  "GitHub no longer accepts your sign-in. Sign in again.");
          case RATE_LIMITED ->
              new ApiException(
                  HttpStatus.TOO_MANY_REQUESTS,
                  "GITHUB_RATE_LIMITED",
                  "GitHub rate limit reached.",
                  Map.of("retryAfter", ex.resetAt().toString()));
          case UNAVAILABLE ->
              new ApiException(
                  HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "GitHub is unavailable.");
        });
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException ex) {
    return api(ApiException.badRequest("Invalid value for parameter '" + ex.getName() + "'."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception ex) {
    log.error("Unhandled error", ex);
    return api(
        new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Unexpected error. See requestId."));
  }

  /** Adds code/requestId (and field errors) to the problem bodies Spring MVC builds itself. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, status, request);
    if (response != null && response.getBody() instanceof ProblemDetail pd) {
      pd.setProperty("code", codeFor(status));
      pd.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
      if (ex instanceof MethodArgumentNotValidException manv) {
        pd.setProperty(
            "errors",
            manv.getFieldErrors().stream()
                .map(
                    e ->
                        Map.of(
                            "field", e.getField(),
                            "message", String.valueOf(e.getDefaultMessage())))
                .toList());
      } else if (ex instanceof HandlerMethodValidationException hmv) {
        pd.setProperty(
            "errors",
            hmv.getParameterValidationResults().stream()
                .flatMap(
                    r ->
                        r.getResolvableErrors().stream()
                            .map(
                                e ->
                                    Map.of(
                                        "field",
                                        String.valueOf(r.getMethodParameter().getParameterName()),
                                        "message",
                                        String.valueOf(e.getDefaultMessage()))))
                .toList());
      }
    }
    return response;
  }

  private static String codeFor(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "VALIDATION_ERROR";
      case 404 -> "NOT_FOUND";
      case 405 -> "METHOD_NOT_ALLOWED";
      case 413 -> "PAYLOAD_TOO_LARGE";
      case 415 -> "UNSUPPORTED_MEDIA_TYPE";
      default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_ERROR";
    };
  }
}
