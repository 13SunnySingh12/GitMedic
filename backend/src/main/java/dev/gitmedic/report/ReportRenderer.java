package dev.gitmedic.report;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

/** FR-021: Markdown and HTML rendered on demand from stored metrics (no stored HTML, SEC-10). */
final class ReportRenderer {

  private ReportRenderer() {}

  private static String day(JsonNode m, String field) {
    return DateTimeFormatter.ISO_LOCAL_DATE.format(
        Instant.parse(m.path(field).asString())
            .atZone(ZoneId.of(m.path("timezone").asString("UTC"))));
  }

  private static String num(JsonNode v) {
    return v == null || v.isNull() || v.isMissingNode() ? "n/a" : v.asString();
  }

  /** Markdown that never carries raw HTML: every repository-derived value is escaped. */
  static String markdown(String repo, JsonNode m) {
    JsonNode issues = m.path("issues");
    JsonNode prs = m.path("pullRequests");
    JsonNode risk = m.path("risk");
    JsonNode delta = risk.path("delta");
    StringBuilder b = new StringBuilder();
    b.append("# Weekly health report: ").append(md(repo)).append("\n\n");
    b.append("Period: ")
        .append(day(m, "periodStart"))
        .append(" to ")
        .append(day(m, "periodEnd"))
        .append(" (")
        .append(md(m.path("timezone").asString()))
        .append(")\n\n");
    b.append("## Risk\n\n");
    if (risk.path("score").isNull() || risk.path("score").isMissingNode()) {
      b.append("Risk score unavailable for this period.\n\n");
    } else {
      b.append("- Score: ")
          .append(risk.path("score").asInt())
          .append(" (")
          .append(md(risk.path("level").asString()))
          .append(")\n- Change vs previous report: ")
          .append(delta.isNumber() ? String.format("%+d", delta.asInt()) : "n/a")
          .append("\n\n");
    }
    b.append("## Issues\n\n| Metric | Value |\n|---|---|\n")
        .append("| New | ")
        .append(num(issues.path("new")))
        .append(" |\n")
        .append("| Closed | ")
        .append(num(issues.path("closed")))
        .append(" |\n")
        .append("| Open at period end | ")
        .append(num(issues.path("open")))
        .append(" |\n")
        .append("| Possible duplicates caught | ")
        .append(num(issues.path("duplicatesCaught")))
        .append(" |\n\n");
    b.append("## Pull requests\n\n| Metric | Value |\n|---|---|\n")
        .append("| Merged | ")
        .append(num(prs.path("merged")))
        .append(" |\n")
        .append("| Avg. merge time, actual (days) | ")
        .append(num(prs.path("avgMergeDaysActual")))
        .append(" |\n")
        .append("| Avg. merge time, predicted (days) | ")
        .append(num(prs.path("avgMergeDaysPredicted")))
        .append(" |\n\n");
    JsonNode bf = m.path("busFactor");
    b.append("## Contributors\n\n- Bus factor (12 months): ")
        .append(bf.path("value").asInt() == 0 ? "n/a" : bf.path("value").asString())
        .append(bf.path("risky").asBoolean() ? " (risky)" : "")
        .append("\n");
    if (m.path("topContributors").isEmpty()) {
      b.append("- No commits on the default branch in this period.\n");
    } else {
      b.append("- Top contributors this period:\n");
      for (JsonNode c : m.path("topContributors")) {
        b.append("  - ")
            .append(md(c.path("login").asString()))
            .append(": ")
            .append(c.path("commits").asLong())
            .append(" commits\n");
      }
    }
    b.append("\nPredictions are estimates from models trained on this repository's history.\n");
    return b.toString();
  }

  /** Backslash-escapes Markdown syntax and HTML so values render as plain text. */
  static String md(String s) {
    return s == null ? "" : s.replaceAll("([\\\\`*_{}\\[\\]()#+\\-.!|<>&])", "\\\\$1");
  }

  /** A standalone HTML document; every value is HTML-escaped (QA-SEC-09). */
  static String html(String repo, JsonNode m) {
    String text = markdown(repo, m);
    StringBuilder body = new StringBuilder();
    boolean inList = false;
    boolean inTable = false;
    for (String raw : text.split("\n")) {
      String line = unescapeMd(raw);
      boolean listItem = line.startsWith("- ") || line.startsWith("  - ");
      boolean tableRow = line.startsWith("|");
      if (inList && !listItem) {
        body.append("</ul>\n");
        inList = false;
      }
      if (inTable && !tableRow) {
        body.append("</table>\n");
        inTable = false;
      }
      if (line.startsWith("# ")) {
        body.append("<h1>").append(esc(line.substring(2))).append("</h1>\n");
      } else if (line.startsWith("## ")) {
        body.append("<h2>").append(esc(line.substring(3))).append("</h2>\n");
      } else if (listItem) {
        if (!inList) {
          body.append("<ul>\n");
          inList = true;
        }
        body.append("<li>").append(esc(line.trim().substring(2))).append("</li>\n");
      } else if (tableRow) {
        if (line.startsWith("|---")) {
          continue;
        }
        String[] cells = line.substring(1, line.length() - 1).split("\\|");
        String tag = inTable ? "td" : "th";
        if (!inTable) {
          body.append("<table>\n");
          inTable = true;
        }
        body.append("<tr>");
        for (String c : cells) {
          body.append('<')
              .append(tag)
              .append('>')
              .append(esc(c.trim()))
              .append("</")
              .append(tag)
              .append('>');
        }
        body.append("</tr>\n");
      } else if (!line.isBlank()) {
        body.append("<p>").append(esc(line)).append("</p>\n");
      }
    }
    if (inList) {
      body.append("</ul>\n");
    }
    if (inTable) {
      body.append("</table>\n");
    }
    return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>"
        + esc("GitMedic report: " + repo)
        + "</title><style>body{font:16px/1.5 system-ui,sans-serif;max-width:42rem;margin:2rem auto;"
        + "padding:0 1rem;color:#1d2433}table{border-collapse:collapse}th,td{border:1px solid #c9ced8;"
        + "padding:.3rem .6rem;text-align:left}</style></head><body>\n"
        + body
        + "</body></html>\n";
  }

  private static String unescapeMd(String s) {
    return s.replaceAll("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|<>&])", "$1");
  }

  private static String esc(String s) {
    return HtmlUtils.htmlEscape(s);
  }
}
