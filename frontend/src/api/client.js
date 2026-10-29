// The only way the UI reaches data: same-origin calls to Spring through the Vite proxy.
// The session lives in the HttpOnly GM_SESSION cookie; nothing is kept in web storage.

export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || `Request failed (${status})`);
    this.status = status;
    this.code = problem?.code;
    this.problem = problem;
  }
}

const UNREACHABLE = 'Cannot reach the GitMedic server. Check that it is running, then try again.';

let onUnauthenticated = () => {};

/** Registered by the app shell: any 401 clears client state and routes to /login. */
export function setUnauthenticatedHandler(handler) {
  onUnauthenticated = handler;
}

export async function api(path, { method = 'GET', body } = {}) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') {
    headers['X-Requested-With'] = 'GitMedic'; // SEC-05
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  let res;
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    // No status: TanStack Query retries it as a transient failure.
    throw new ApiError(undefined, { detail: UNREACHABLE });
  }
  if (res.status === 204) {
    return null;
  }
  const isJson = (res.headers.get('content-type') || '').includes('json');
  const data = isJson ? await res.json().catch(() => null) : null;
  if (res.ok && data === null) {
    // A proxy or error page answered instead of the API: never hand `null` to a page as data.
    throw new ApiError(res.status, {
      detail: 'The server sent an unexpected response. Try again.',
    });
  }
  if (!res.ok) {
    const error = new ApiError(res.status, data);
    if (res.status === 401) {
      onUnauthenticated(error);
    }
    throw error;
  }
  return data;
}

/** Only same-app paths are accepted as a post-login destination (no open redirect). */
export function safeNext(next) {
  return typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') ? next : null;
}

export function query(params) {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') {
      q.set(k, String(v));
    }
  });
  const s = q.toString();
  return s ? `?${s}` : '';
}
