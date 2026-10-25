import { render } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import axe from 'axe-core';
import { http, HttpResponse } from 'msw';
import App from '../App.jsx';
import { createQueryClient } from '../queryClient.js';
import { server } from './server.js';

export const ME = { id: 1, githubId: 9, login: 'octo', name: 'Octo', avatarUrl: null };

function LocationProbe() {
  const { pathname, search } = useLocation();
  return (
    <output data-testid="location" hidden>
      {pathname + search}
    </output>
  );
}

/** The router location of the rendered app, as a URL (pathname + search). */
export const currentLocation = () =>
  new URL(document.querySelector('[data-testid="location"]').textContent, 'http://test');

/** Renders the real app tree at `route` with a fresh cache; /me succeeds unless overridden. */
export function renderApp(route, { signedIn = true } = {}) {
  if (signedIn) {
    server.use(http.get('/api/v1/me', () => HttpResponse.json(ME)));
  }
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false, staleTime: 0 } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <App />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

export const problem = (status, code, detail = 'Problem') =>
  HttpResponse.json(
    { status, code, detail },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );

/** Fails the test with readable output when axe finds violations. */
export async function expectNoAxeViolations(container) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  const messages = result.violations.map(
    (v) => `${v.id}: ${v.nodes.map((n) => n.target).join(', ')}`,
  );
  if (messages.length) {
    throw new Error(`axe violations:\n${messages.join('\n')}`);
  }
}

export function page(items, { page = 0, size = 20, totalItems = items.length } = {}) {
  return { items, page, size, totalItems, totalPages: Math.ceil(totalItems / size) };
}
