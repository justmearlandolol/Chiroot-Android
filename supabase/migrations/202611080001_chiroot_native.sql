-- Chiroot native Android: additive, repeatable migration.
-- Run in the Supabase SQL Editor as the project owner, NOT from the Android app.
-- Compatible with the existing Chiroot website's profiles / crate_tracks /
-- play_events names. No table or user data is dropped.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '',
  email text not null default '',
  pfp text not null default '',
  description text not null default '',
  banner_color text not null default '#D84A3A',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.profiles add column if not exists display_name text not null default '';
alter table public.profiles add column if not exists email text not null default '';
alter table public.profiles add column if not exists pfp text not null default '';
alter table public.profiles add column if not exists description text not null default '';
alter table public.profiles add column if not exists banner_color text not null default '#D84A3A';
alter table public.profiles add column if not exists updated_at timestamptz not null default now();

create table if not exists public.crate_tracks (
  id text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(title) between 1 and 120),
  artist text not null default 'Unknown Artist',
  audio_url text,
  audio_path text,
  cover_path text,
  label_color text not null default '#D84A3A',
  vinyl_color text not null default '#1E1E24',
  position integer not null default 0,
  duration_ms bigint not null default 0 check (duration_ms >= 0),
  created_at timestamptz not null default now()
);
alter table public.crate_tracks add column if not exists duration_ms bigint not null default 0;
alter table public.crate_tracks add column if not exists content_hash text;
create unique index if not exists crate_tracks_user_hash_idx on public.crate_tracks(user_id, content_hash) where content_hash is not null;
alter table public.crate_tracks add column if not exists audio_url text;
alter table public.crate_tracks add column if not exists audio_path text;
alter table public.crate_tracks add column if not exists cover_path text;
alter table public.crate_tracks add column if not exists position integer not null default 0;
create index if not exists crate_tracks_user_position_idx on public.crate_tracks(user_id, position);

create table if not exists public.play_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  -- Text snapshot ID: history survives deleting a song and accommodates the
  -- website's legacy non-UUID IDs. Title/artist are snapshots, not fabricated data.
  track_id text not null,
  title text not null default '',
  artist text not null default '',
  duration_ms bigint not null default 0 check (duration_ms >= 0),
  created_at timestamptz not null default now()
);
alter table public.play_events add column if not exists duration_ms bigint not null default 0;
create index if not exists play_events_user_time_idx on public.play_events(user_id, created_at desc);
create index if not exists play_events_user_track_idx on public.play_events(user_id, track_id);

create table if not exists public.user_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  settings_json jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists public.favorite_tracks (
  user_id uuid not null references auth.users(id) on delete cascade,
  track_id text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, track_id)
);
alter table public.favorite_tracks enable row level security;
drop policy if exists chiroot_favorites_own on public.favorite_tracks;
create policy chiroot_favorites_own on public.favorite_tracks
  for all to authenticated using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

alter table public.profiles enable row level security;
alter table public.crate_tracks enable row level security;
alter table public.play_events enable row level security;
alter table public.user_settings enable row level security;

drop policy if exists chiroot_profiles_own on public.profiles;
create policy chiroot_profiles_own on public.profiles
  for all to authenticated using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);

drop policy if exists chiroot_crate_own on public.crate_tracks;
create policy chiroot_crate_own on public.crate_tracks
  for all to authenticated using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists chiroot_events_own on public.play_events;
create policy chiroot_events_own on public.play_events
  for all to authenticated using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists chiroot_settings_own on public.user_settings;
create policy chiroot_settings_own on public.user_settings
  for all to authenticated using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

-- The current website uses this public bucket and public object URLs. Preserve
-- that compatibility rather than silently breaking existing web tracks. Writes
-- remain owner-only. Public URLs are NOT private access control: anyone with an
-- audio URL can read it. See README before changing an existing bucket to private.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chiroot-crate', 'chiroot-crate', true, 62914560,
        array['audio/mpeg','audio/mp3','image/jpeg','image/png','image/webp'])
on conflict (id) do update set
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists chiroot_storage_read on storage.objects;
create policy chiroot_storage_read on storage.objects
  for select to public using (bucket_id = 'chiroot-crate');

drop policy if exists chiroot_storage_insert_own on storage.objects;
create policy chiroot_storage_insert_own on storage.objects
  for insert to authenticated
  with check (bucket_id = 'chiroot-crate' and (storage.foldername(name))[1] = (select auth.uid())::text);

drop policy if exists chiroot_storage_update_own on storage.objects;
create policy chiroot_storage_update_own on storage.objects
  for update to authenticated
  using (bucket_id = 'chiroot-crate' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'chiroot-crate' and (storage.foldername(name))[1] = (select auth.uid())::text);

drop policy if exists chiroot_storage_delete_own on storage.objects;
create policy chiroot_storage_delete_own on storage.objects
  for delete to authenticated
  using (bucket_id = 'chiroot-crate' and (storage.foldername(name))[1] = (select auth.uid())::text);
