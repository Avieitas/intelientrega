-- Acesso de cortesia (testes, criadores, suporte). NÃO é pagamento: fica separado de
-- payments/subscriptions, não entra em relatórios de receita e só o administrador cria.

create table public.access_grants (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  until       timestamptz not null,
  reason      text not null check (length(trim(reason)) > 0),
  created_at  timestamptz not null default now(),
  revoked_at  timestamptz
);
create index access_grants_user_idx on public.access_grants (user_id, until desc);

alter table public.access_grants enable row level security;
-- Sem políticas: o app não lê nem altera; o status chega pela função subscription-status.
revoke all on public.access_grants from anon, authenticated;

-- Concede (ou estende) cortesia por e-mail. Uso: select * from admin_grant_access('x@y.com', 30, 'teste');
create or replace function public.admin_grant_access(p_email text, p_days integer, p_reason text)
returns public.access_grants language plpgsql security definer set search_path = public as $$
declare
  v_user uuid;
  g public.access_grants;
begin
  if p_days is null or p_days < 1 or p_days > 366 then raise exception 'Dias deve ficar entre 1 e 366'; end if;
  select id into v_user from auth.users where lower(email) = lower(trim(p_email));
  if v_user is null then raise exception 'Nenhuma conta com esse e-mail'; end if;
  insert into public.access_grants (user_id, until, reason)
  values (v_user, now() + make_interval(days => p_days), p_reason)
  returning * into g;
  return g;
end $$;

-- Encerra todas as cortesias ativas de um e-mail. Retorna quantas foram encerradas.
create or replace function public.admin_revoke_access(p_email text)
returns integer language plpgsql security definer set search_path = public as $$
declare v_count integer;
begin
  update public.access_grants g set revoked_at = now()
  from auth.users u
  where u.id = g.user_id and lower(u.email) = lower(trim(p_email))
    and g.revoked_at is null and g.until > now();
  get diagnostics v_count = row_count;
  return v_count;
end $$;

create or replace function public.has_active_access(p_user uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.subscriptions
    where user_id = p_user and paid_until is not null and paid_until > now()
  ) or exists (
    select 1 from public.access_grants
    where user_id = p_user and revoked_at is null and until > now()
  )
$$;

revoke execute on function public.admin_grant_access(text, integer, text) from public, anon, authenticated;
revoke execute on function public.admin_revoke_access(text)               from public, anon, authenticated;
revoke execute on function public.has_active_access(uuid)                 from public, anon, authenticated;
grant execute on function public.has_active_access(uuid)                  to service_role;
