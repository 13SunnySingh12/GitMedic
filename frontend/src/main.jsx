import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import App from './App.jsx';
import { setUnauthenticatedHandler } from './api/client.js';
import { createQueryClient } from './queryClient.js';
import '@fontsource-variable/inter';
import './index.css';

const queryClient = createQueryClient();

// §3.5 session expiry: any 401 clears client state and goes to /login?next=<path>.
setUnauthenticatedHandler(() => {
  if (window.location.pathname === '/login') return;
  queryClient.clear();
  const next = encodeURIComponent(window.location.pathname + window.location.search);
  window.location.assign(`/login?next=${next}`);
});

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
