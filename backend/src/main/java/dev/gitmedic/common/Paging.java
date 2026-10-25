package dev.gitmedic.common;

import java.util.List;

/** 0-based paging per §7.1: negative page → 400, size clamped to 1–100 (default 20). */
public record Paging(int page, int size) {

  public static Paging of(Integer page, Integer size) {
    int p = page == null ? 0 : page;
    if (p < 0) {
      throw ApiException.badRequest("page must be >= 0.");
    }
    int s = size == null ? 20 : Math.clamp(size, 1, 100);
    return new Paging(p, s);
  }

  public long offset() {
    return (long) page * size;
  }

  public <T> Page<T> page(List<T> items, long total) {
    return new Page<>(items, page, size, total, (int) ((total + size - 1) / size));
  }

  public record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}
}
