-- DS-001 … DS-008. Plain SQL (no Flyway placeholders): the ML test suite applies these files directly.

-- On Supabase / setup-roles.sql the schema and extension already exist; the guarded forms keep this
-- migration runnable by a non-superuser owner there and by the superuser in Testcontainers.
do $$
begin
  if not exists (select 1 from pg_namespace where nspname = 'extensions') then
    create schema extensions;
  end if;
end $$;
create extension if not exists vector with schema extensions;

create table gitmedic.users (
  id               bigint generated always as identity primary key,
  github_id        bigint       not null unique,
  login            varchar(100) not null,
  name             varchar(255),
  avatar_url       text,
  access_token_enc bytea        not null,
  token_scopes     varchar(255),
  created_at       timestamptz  not null default now(),
  updated_at       timestamptz  not null default now()
);

create table gitmedic.repos (
  id             bigint generated always as identity primary key,
  user_id        bigint       not null references gitmedic.users (id) on delete cascade,
  github_repo_id bigint       not null,
  full_name      varchar(200) not null,
  default_branch varchar(255) not null,
  is_private     boolean      not null,
  sync_status    varchar(20)  not null default 'PENDING'
    check (sync_status in ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'RATE_LIMITED')),
  sync_error     text,
  last_synced_at timestamptz,
  created_at     timestamptz  not null default now(),
  unique (user_id, github_repo_id)
);

create table gitmedic.issues (
  id                    bigint generated always as identity primary key,
  repo_id               bigint      not null references gitmedic.repos (id) on delete cascade,
  github_issue_id       bigint      not null,
  number                int         not null,
  title                 text        not null,
  body                  text check (char_length(body) <= 65535),
  state                 varchar(10) not null check (state in ('open', 'closed')),
  author_login          varchar(100),
  gh_labels             text[]      not null default '{}',
  comments_count        int         not null default 0,
  html_url              text        not null,
  created_at_gh         timestamptz not null,
  updated_at_gh         timestamptz not null,
  closed_at_gh          timestamptz,
  predicted_label       varchar(10) check (predicted_label in ('bug', 'feature', 'question')),
  predicted_confidence  real check (predicted_confidence between 0 and 1),
  predicted_at          timestamptz,
  is_possible_duplicate boolean     not null default false,
  duplicate_score       real,
  duplicate_flagged_at  timestamptz,
  unique (repo_id, number)
);

create table gitmedic.pull_requests (
  id                          bigint generated always as identity primary key,
  repo_id                     bigint      not null references gitmedic.repos (id) on delete cascade,
  github_pr_id                bigint      not null,
  number                      int         not null,
  title                       text        not null,
  state                       varchar(10) not null check (state in ('open', 'closed')),
  author_login                varchar(100),
  additions                   int         not null default 0 check (additions >= 0),
  deletions                   int         not null default 0 check (deletions >= 0),
  changed_files               int         not null default 0 check (changed_files >= 0),
  review_count                int         not null default 0 check (review_count >= 0),
  html_url                    text        not null,
  created_at_gh               timestamptz not null,
  merged_at_gh                timestamptz,
  closed_at_gh                timestamptz,
  predicted_merge_days        real,
  predicted_at                timestamptz,
  latest_predicted_merge_days real,
  unique (repo_id, number)
);

create table gitmedic.commits (
  id                bigint generated always as identity primary key,
  repo_id           bigint       not null references gitmedic.repos (id) on delete cascade,
  sha               char(40)     not null,
  author_login      varchar(100),
  author_email_hash char(64),
  committed_at      timestamptz  not null,
  unique (repo_id, sha)
);

create table gitmedic.embeddings (
  id           bigint generated always as identity primary key,
  repo_id      bigint       not null references gitmedic.repos (id) on delete cascade,
  source_type  varchar(10)  not null check (source_type in ('readme', 'doc', 'issue')),
  source_ref   varchar(500) not null,
  source_url   text         not null,
  chunk_index  int          not null default 0,
  content      text         not null,
  content_hash char(64)     not null,
  embedding    extensions.vector(384) not null,
  created_at   timestamptz  not null default now(),
  unique (repo_id, source_type, source_ref, chunk_index)
);

create table gitmedic.repo_health (
  id          bigint generated always as identity primary key,
  repo_id     bigint      not null references gitmedic.repos (id) on delete cascade,
  computed_at timestamptz not null default now(),
  score       smallint    not null check (score between 0 and 100),
  level       varchar(6)  not null check (level in ('LOW', 'MEDIUM', 'HIGH')),
  components  jsonb       not null
);

create table gitmedic.weekly_reports (
  id           bigint generated always as identity primary key,
  repo_id      bigint      not null references gitmedic.repos (id) on delete cascade,
  period_start timestamptz not null,
  period_end   timestamptz not null,
  generated_at timestamptz not null default now(),
  metrics      jsonb       not null,
  unique (repo_id, period_start)
);
