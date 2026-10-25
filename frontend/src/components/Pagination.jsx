/** 0-based server pages; the caller stores `page` in the URL (UX-07). */
/** `label` is [singular, plural], e.g. ['issue', 'issues']. */
export function Pagination({ page, totalPages, totalItems, onPage, label = ['item', 'items'] }) {
  if (totalItems === 0) return null;
  return (
    <nav className="pagination" aria-label="Pagination">
      <span>
        Page {page + 1} of {Math.max(totalPages, 1)} ({totalItems}{' '}
        {totalItems === 1 ? label[0] : label[1]})
      </span>
      <span className="row">
        <button
          type="button"
          className="btn btn-small"
          disabled={page <= 0}
          onClick={() => onPage(page - 1)}
        >
          Previous
        </button>
        <button
          type="button"
          className="btn btn-small"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          Next
        </button>
      </span>
    </nav>
  );
}
