package dev.gitmedic.report;

import dev.gitmedic.common.ApiException;
import dev.gitmedic.common.Paging;
import dev.gitmedic.repo.JobLimiter;
import dev.gitmedic.repo.RepoRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
class ReportController {

  record ReportSummary(
      long id,
      Instant periodStart,
      Instant periodEnd,
      Instant generatedAt,
      Integer riskScore,
      String riskLevel) {}

  record GenerateRequest(String period) {}

  private record Stored(
      long id,
      String fullName,
      Instant start,
      Instant end,
      Instant generatedAt,
      JsonNode metrics) {}

  private final RepoRepository repos;
  private final ReportService reports;
  private final JobLimiter limiter;
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  ReportController(
      RepoRepository repos,
      ReportService reports,
      JobLimiter limiter,
      JdbcClient jdbc,
      ObjectMapper mapper) {
    this.repos = repos;
    this.reports = reports;
    this.limiter = limiter;
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** API-18 / FR-020: newest first, paginated. */
  @GetMapping("/repos/{repoId}/reports")
  Paging.Page<ReportSummary> list(
      @AuthenticationPrincipal Long userId,
      @PathVariable long repoId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    long repo = repos.owned(userId, repoId).id();
    Paging paging = Paging.of(page, size);
    long total =
        jdbc.sql("select count(*) from gitmedic.weekly_reports where repo_id = ?")
            .param(repo)
            .query(Long.class)
            .single();
    List<ReportSummary> items =
        jdbc.sql(
                """
                select id, period_start, period_end, generated_at,
                  (metrics -> 'risk' ->> 'score')::int score, metrics -> 'risk' ->> 'level' level
                from gitmedic.weekly_reports where repo_id = ?
                order by period_start desc limit ? offset ?
                """)
            .params(repo, paging.size(), paging.offset())
            .query(
                (rs, n) ->
                    new ReportSummary(
                        rs.getLong("id"),
                        rs.getTimestamp("period_start").toInstant(),
                        rs.getTimestamp("period_end").toInstant(),
                        rs.getTimestamp("generated_at").toInstant(),
                        (Integer) rs.getObject("score"),
                        rs.getString("level")))
            .list();
    return paging.page(items, total);
  }

  /** API-19 / FR-022: "Generate now" for the current (default) or previous week. */
  @PostMapping("/repos/{repoId}/reports")
  ResponseEntity<Map<String, Object>> generate(
      @AuthenticationPrincipal Long userId,
      @PathVariable long repoId,
      @RequestBody(required = false) GenerateRequest req) {
    long repo = repos.owned(userId, repoId).id();
    String which = req == null || req.period() == null ? "current" : req.period();
    if (!which.equals("current") && !which.equals("previous")) {
      throw ApiException.badRequest("period must be current or previous.");
    }
    limiter.acquire(userId);
    long id;
    try {
      id = reports.generate(repo, reports.period(which.equals("previous")));
    } finally {
      limiter.release(userId);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(view(load(userId, id)));
  }

  /** API-20. */
  @GetMapping("/reports/{reportId}")
  Map<String, Object> detail(@AuthenticationPrincipal Long userId, @PathVariable long reportId) {
    return view(load(userId, reportId));
  }

  /** API-21 / FR-021. */
  @GetMapping("/reports/{reportId}/download")
  ResponseEntity<byte[]> download(
      @AuthenticationPrincipal Long userId,
      @PathVariable long reportId,
      @RequestParam(defaultValue = "md") String format) {
    if (!format.equals("md") && !format.equals("html")) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT", "format must be md or html.");
    }
    Stored r = load(userId, reportId);
    boolean html = format.equals("html");
    String content =
        html
            ? ReportRenderer.html(r.fullName(), r.metrics())
            : ReportRenderer.markdown(r.fullName(), r.metrics());
    String day =
        DateTimeFormatter.ISO_LOCAL_DATE.format(
            r.start().atZone(ZoneId.of(r.metrics().path("timezone").asString("UTC"))));
    String filename = "gitmedic-" + r.fullName().replace('/', '-') + "-" + day + "." + format;
    return ResponseEntity.ok()
        .contentType(new MediaType("text", html ? "html" : "markdown", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .body(content.getBytes(StandardCharsets.UTF_8));
  }

  /** Owner-scoped: another user's report id resolves to 404 (SEC-04). */
  private Stored load(long userId, long reportId) {
    return jdbc.sql(
            """
            select w.id, r.full_name, w.period_start, w.period_end, w.generated_at, w.metrics::text metrics
            from gitmedic.weekly_reports w join gitmedic.repos r on r.id = w.repo_id
            where w.id = ? and r.user_id = ?
            """)
        .params(reportId, userId)
        .query(
            (rs, n) ->
                new Stored(
                    rs.getLong("id"),
                    rs.getString("full_name"),
                    rs.getTimestamp("period_start").toInstant(),
                    rs.getTimestamp("period_end").toInstant(),
                    rs.getTimestamp("generated_at").toInstant(),
                    mapper.readTree(rs.getString("metrics"))))
        .optional()
        .orElseThrow(ApiException::notFound);
  }

  private static Map<String, Object> view(Stored r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", r.id());
    out.put("repoFullName", r.fullName());
    out.put("periodStart", r.start());
    out.put("periodEnd", r.end());
    out.put("generatedAt", r.generatedAt());
    out.put("metrics", r.metrics());
    out.put("markdown", ReportRenderer.markdown(r.fullName(), r.metrics()));
    return out;
  }
}
