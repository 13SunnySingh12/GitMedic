import { QueryClient } from '@tanstack/react-query';

/** Retries only for network/5xx failures, never for 4xx (§4.6). */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (count, error) => (error?.status === undefined || error.status >= 500) && count < 2,
        refetchOnWindowFocus: false,
        staleTime: 15_000,
      },
    },
  });
}
