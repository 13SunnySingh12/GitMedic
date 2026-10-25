import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, query } from './client.js';

const busy = (s) => s === 'PENDING' || s === 'RUNNING';

export const useMe = () => useQuery({ queryKey: ['me'], queryFn: () => api('/api/v1/me') });

export const useRepos = () =>
  useQuery({
    queryKey: ['repos'],
    queryFn: () => api('/api/v1/repos'),
    // Sync runs on the server; while one is in progress, re-read its status.
    refetchInterval: (q) => (q.state.data?.some((r) => busy(r.syncStatus)) ? 2000 : false),
  });

export const useGithubRepos = (params, enabled) =>
  useQuery({
    queryKey: ['github-repos', params],
    queryFn: () => api(`/api/v1/github/repos${query(params)}`),
    enabled,
    placeholderData: (prev) => prev,
  });

export const useDashboard = (repoId) =>
  useQuery({
    queryKey: ['dashboard', repoId],
    queryFn: () => api(`/api/v1/repos/${repoId}/dashboard`),
    refetchInterval: (q) => (busy(q.state.data?.repo?.syncStatus) ? 2000 : false),
  });

export const useHeatmap = (repoId) =>
  useQuery({
    queryKey: ['heatmap', repoId],
    queryFn: () => api(`/api/v1/repos/${repoId}/heatmap`),
  });

export const useBusFactor = (repoId) =>
  useQuery({
    queryKey: ['bus-factor', repoId],
    queryFn: () => api(`/api/v1/repos/${repoId}/bus-factor`),
  });

export const useIssues = (repoId, params) =>
  useQuery({
    queryKey: ['issues', repoId, params],
    queryFn: () => api(`/api/v1/repos/${repoId}/issues${query(params)}`),
    placeholderData: (prev) => prev,
  });

export const useIssue = (repoId, number) =>
  useQuery({
    queryKey: ['issue', repoId, number],
    queryFn: () => api(`/api/v1/repos/${repoId}/issues/${number}`),
  });

export const usePulls = (repoId, params) =>
  useQuery({
    queryKey: ['pulls', repoId, params],
    queryFn: () => api(`/api/v1/repos/${repoId}/pulls${query(params)}`),
    placeholderData: (prev) => prev,
  });

export const useReports = (repoId, params) =>
  useQuery({
    queryKey: ['reports', repoId, params],
    queryFn: () => api(`/api/v1/repos/${repoId}/reports${query(params)}`),
    placeholderData: (prev) => prev,
  });

export const useReport = (reportId) =>
  useQuery({
    queryKey: ['report', reportId],
    queryFn: () => api(`/api/v1/reports/${reportId}`),
  });

/** A mutation that refreshes the listed query keys when it settles. */
function useAction(fn, keys) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => keys.forEach((k) => qc.invalidateQueries({ queryKey: k })),
  });
}

export const useTrack = () =>
  useAction(
    (repo) => api('/api/v1/repos', { method: 'POST', body: repo }),
    [['repos'], ['github-repos']],
  );

export const useUntrack = () =>
  useAction(
    (id) => api(`/api/v1/repos/${id}`, { method: 'DELETE' }),
    [['repos'], ['github-repos']],
  );

export const useSync = (repoId) =>
  useAction(
    (id = repoId) => api(`/api/v1/repos/${id}/sync`, { method: 'POST' }),
    [['repos'], ['dashboard']],
  );

export const useTrain = (repoId) =>
  useAction(
    (model) => api(`/api/v1/repos/${repoId}/models/${model}/train`, { method: 'POST' }),
    [
      ['dashboard', repoId],
      ['issues', repoId],
      ['pulls', repoId],
    ],
  );

export const useGenerateReport = (repoId) =>
  useAction(
    () => api(`/api/v1/repos/${repoId}/reports`, { method: 'POST' }),
    [['reports', repoId]],
  );

export const useLogout = () =>
  useMutation({ mutationFn: () => api('/api/v1/auth/logout', { method: 'POST' }) });
