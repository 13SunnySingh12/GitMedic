create index issues_repo_state_idx on gitmedic.issues (repo_id, state);
create index issues_repo_created_idx on gitmedic.issues (repo_id, created_at_gh);
create index issues_repo_dup_idx on gitmedic.issues (repo_id, is_possible_duplicate);
create index pull_requests_repo_state_idx on gitmedic.pull_requests (repo_id, state);
create index pull_requests_repo_merged_idx on gitmedic.pull_requests (repo_id, merged_at_gh);
create index commits_repo_committed_idx on gitmedic.commits (repo_id, committed_at);
create index embeddings_vec_idx on gitmedic.embeddings using hnsw (embedding extensions.vector_cosine_ops);
create index embeddings_repo_type_idx on gitmedic.embeddings (repo_id, source_type);
create index repo_health_repo_computed_idx on gitmedic.repo_health (repo_id, computed_at desc);

-- SEC-15: RLS on, no policies for anon/authenticated => deny by default. The owner (gitmedic_app)
-- bypasses RLS; gitmedic_ml gets explicit policies. TBD-07 resolved: Spring writes predictions,
-- so gitmedic_ml is read-only except on embeddings.
alter table gitmedic.users enable row level security;
alter table gitmedic.repos enable row level security;
alter table gitmedic.issues enable row level security;
alter table gitmedic.pull_requests enable row level security;
alter table gitmedic.commits enable row level security;
alter table gitmedic.embeddings enable row level security;
alter table gitmedic.repo_health enable row level security;
alter table gitmedic.weekly_reports enable row level security;

-- The ML role exists only where setup-roles.sql ran (Supabase, docker compose); skipped in Testcontainers.
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'gitmedic_ml') then
    grant usage on schema gitmedic to gitmedic_ml;
    grant select (id) on gitmedic.repos to gitmedic_ml;
    grant select on gitmedic.issues, gitmedic.pull_requests, gitmedic.commits, gitmedic.repo_health to gitmedic_ml;
    grant select, insert, update, delete on gitmedic.embeddings to gitmedic_ml;
    create policy ml_read on gitmedic.repos for select to gitmedic_ml using (true);
    create policy ml_read on gitmedic.issues for select to gitmedic_ml using (true);
    create policy ml_read on gitmedic.pull_requests for select to gitmedic_ml using (true);
    create policy ml_read on gitmedic.commits for select to gitmedic_ml using (true);
    create policy ml_read on gitmedic.repo_health for select to gitmedic_ml using (true);
    create policy ml_all on gitmedic.embeddings for all to gitmedic_ml using (true) with check (true);
  end if;
end $$;
