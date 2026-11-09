-- Assinatura mensal, pagamentos (Mercado Pago), cupons de criadores e atribuição.
--
-- Regra de segurança central: o app (roles anon/authenticated) só LÊ as próprias linhas de
-- subscriptions e payments. Nenhuma escrita é permitida a esses roles. Todas as alterações
-- de status são feitas pelas Edge Functions com a service_role, depois de consultar o
-- Mercado Pago servidor-a-servidor.


-- ---------------------------------------------------------------- Criadores e cupons

create table public.creators (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (length(trim(name)) > 0),
  contact     text,
  created_at  timestamptz not null default now()
);

create table public.coupons (
  id                uuid primary key default gen_random_uuid(),
  creator_id        uuid not null references public.creators(id) on delete restrict,
  -- Sempre normalizado: sem espaços e em maiúsculas (ver public.normalize_coupon).
  code              text not null,
  discount_percent  integer not null default 10 check (discount_percent between 1 and 90),
  active            boolean not null default true,
  starts_at         timestamptz,
  expires_at        timestamptz,
  -- Limite de assinaturas CONFIRMADAS com o código (null = sem limite).
  max_redemptions   integer check (max_redemptions is null or max_redemptions > 0),
  created_at        timestamptz not null default now(),
  constraint coupons_code_format check (code ~ '^[A-Z0-9_-]{3,40}$')
);
create unique index coupons_code_key on public.coupons (code);

create or replace function public.normalize_coupon(raw text)
returns text language sql immutable as $$
  select nullif(upper(regexp_replace(coalesce(raw, ''), '\s', '', 'g')), '')
$$;

-- ---------------------------------------------------------------- Assinaturas

create type public.subscription_status as enum ('pending', 'authorized', 'paused', 'cancelled');

create table public.subscriptions (
  id                   uuid primary key default gen_random_uuid(),
  user_id              uuid not null references auth.users(id) on delete cascade,
  status               public.subscription_status not null default 'pending',
  mp_preapproval_id    text unique,
  init_point           text,
  base_amount_cents    integer not null check (base_amount_cents > 0),
  discount_cents       integer not null default 0 check (discount_cents >= 0),
  amount_cents         integer not null,
  -- Um único cupom por assinatura: o desconto não pode ser aplicado duas vezes.
  coupon_id            uuid references public.coupons(id) on delete restrict,
  coupon_code          text,
  creator_id           uuid references public.creators(id) on delete restrict,
  -- Acesso pago até esta data. Calculado SÓ a partir de pagamentos aprovados.
  paid_until           timestamptz,
  last_payment_status  text,
  last_payment_detail  text,
  next_payment_date    timestamptz,
  cancel_requested_at  timestamptz,
  last_synced_at       timestamptz,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  constraint subscriptions_amount check (amount_cents = base_amount_cents - discount_cents and amount_cents > 0),
  constraint subscriptions_coupon_consistency check (
    (coupon_id is null and coupon_code is null and creator_id is null and discount_cents = 0)
    or (coupon_id is not null and coupon_code is not null and creator_id is not null and discount_cents > 0)
  )
);
-- No máximo uma assinatura "aberta" por usuário.
create unique index subscriptions_one_open_per_user
  on public.subscriptions (user_id) where status in ('pending', 'authorized', 'paused');
create index subscriptions_user_idx on public.subscriptions (user_id);
create index subscriptions_coupon_idx on public.subscriptions (coupon_id);

-- ---------------------------------------------------------------- Pagamentos (cobranças mensais)

create table public.payments (
  id                        uuid primary key default gen_random_uuid(),
  subscription_id           uuid not null references public.subscriptions(id) on delete cascade,
  user_id                   uuid not null references auth.users(id) on delete cascade,
  -- Id da cobrança recorrente no MP (authorized_payment). Único: webhooks repetidos não duplicam.
  mp_authorized_payment_id  text not null unique,
  mp_payment_id             text,
  invoice_status            text,          -- scheduled | processed | recycling | cancelled
  status                    text not null, -- approved | pending | in_process | rejected | refunded | charged_back | cancelled ...
  status_detail             text,
  amount_cents              integer,
  debit_date                timestamptz,
  approved_at               timestamptz,
  created_at                timestamptz not null default now(),
  updated_at                timestamptz not null default now()
);
create index payments_subscription_idx on public.payments (subscription_id);
create index payments_mp_payment_idx on public.payments (mp_payment_id);

-- ---------------------------------------------------------------- Webhooks recebidos (auditoria/idempotência)

create table public.webhook_events (
  id            bigint generated always as identity primary key,
  provider      text not null default 'mercadopago',
  request_id    text not null,
  topic         text,
  resource_id   text,
  received_at   timestamptz not null default now(),
  processed_at  timestamptz,
  error         text,
  unique (provider, request_id)
);

-- ---------------------------------------------------------------- updated_at

create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at := now(); return new; end $$;

create trigger subscriptions_touch before update on public.subscriptions
  for each row execute function public.touch_updated_at();
create trigger payments_touch before update on public.payments
  for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------- Regras de negócio no banco

-- Dias extras após o mês pago, para cobrir as novas tentativas automáticas do Mercado Pago.
create or replace function public.grace_period() returns interval language sql immutable as $$
  select interval '3 days'
$$;

-- Recalcula paid_until só com pagamentos aprovados e com valor correto. Idempotente:
-- reprocessar o mesmo webhook gera o mesmo resultado; estorno/chargeback remove o acesso.
create or replace function public.recompute_paid_until(p_subscription uuid)
returns timestamptz language plpgsql security definer set search_path = public as $$
declare v_until timestamptz;
begin
  select max(coalesce(p.approved_at, p.debit_date, p.created_at)) + interval '1 month' + public.grace_period()
    into v_until
  from public.payments p
  join public.subscriptions s on s.id = p.subscription_id
  where p.subscription_id = p_subscription
    and p.status = 'approved'
    and coalesce(p.amount_cents, 0) >= s.amount_cents;

  update public.subscriptions set paid_until = v_until where id = p_subscription;
  return v_until;
end $$;

create or replace function public.has_active_access(p_user uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.subscriptions
    where user_id = p_user and paid_until is not null and paid_until > now()
  )
$$;

-- Valida um cupom para um usuário. Retorna o motivo da recusa em `error` (texto estável
-- que o app traduz) ou os dados do cupom. Não altera nada.
create or replace function public.check_coupon(p_raw text)
returns table (coupon_id uuid, code text, creator_id uuid, discount_percent integer, error text)
language plpgsql stable security definer set search_path = public as $$
declare
  v_code text := public.normalize_coupon(p_raw);
  c public.coupons;
  v_used integer;
begin
  if v_code is null then
    return query select null::uuid, null::text, null::uuid, null::integer, 'empty'::text; return;
  end if;
  select * into c from public.coupons where coupons.code = v_code;
  if not found then
    return query select null::uuid, v_code, null::uuid, null::integer, 'invalid'::text; return;
  end if;
  if not c.active then
    return query select null::uuid, v_code, null::uuid, null::integer, 'inactive'::text; return;
  end if;
  if c.starts_at is not null and c.starts_at > now() then
    return query select null::uuid, v_code, null::uuid, null::integer, 'not_started'::text; return;
  end if;
  if c.expires_at is not null and c.expires_at <= now() then
    return query select null::uuid, v_code, null::uuid, null::integer, 'expired'::text; return;
  end if;
  if c.max_redemptions is not null then
    select count(distinct s.id) into v_used
    from public.subscriptions s join public.payments p on p.subscription_id = s.id and p.status = 'approved'
    where s.coupon_id = c.id;
    if v_used >= c.max_redemptions then
      return query select null::uuid, v_code, null::uuid, null::integer, 'exhausted'::text; return;
    end if;
  end if;
  return query select c.id, c.code, c.creator_id, c.discount_percent, null::text;
end $$;

-- Cria a assinatura local em 'pending' de forma atômica (cupom revalidado aqui dentro).
-- O valor é calculado SOMENTE no servidor.
create or replace function public.create_pending_subscription(p_user uuid, p_base_cents integer, p_coupon text)
returns public.subscriptions
language plpgsql security definer set search_path = public as $$
declare
  v public.subscriptions;
  cc record;
  v_discount integer := 0;
  v_coupon_id uuid;
  v_coupon_code text;
  v_creator_id uuid;
begin
  if p_base_cents is null or p_base_cents <= 0 then raise exception 'invalid_price'; end if;

  -- Serializa pedidos simultâneos do mesmo usuário.
  perform pg_advisory_xact_lock(hashtext('sub:' || p_user::text));

  if exists (select 1 from public.subscriptions where user_id = p_user and status in ('authorized', 'paused')) then
    raise exception 'already_subscribed';
  end if;

  if public.normalize_coupon(p_coupon) is not null then
    select * into cc from public.check_coupon(p_coupon);
    if cc.error is not null then raise exception 'coupon_%', cc.error; end if;
    perform 1 from public.coupons where id = cc.coupon_id for share;
    v_discount := round(p_base_cents * cc.discount_percent / 100.0);
    v_coupon_id := cc.coupon_id; v_coupon_code := cc.code; v_creator_id := cc.creator_id;
  end if;

  insert into public.subscriptions (user_id, status, base_amount_cents, discount_cents, amount_cents,
                                    coupon_id, coupon_code, creator_id)
  values (p_user, 'pending', p_base_cents, v_discount, p_base_cents - v_discount,
          v_coupon_id, v_coupon_code, v_creator_id)
  returning * into v;
  return v;
end $$;

-- ---------------------------------------------------------------- Administração de cupons (SQL Editor)

create or replace function public.admin_upsert_coupon(
  p_creator_name text, p_code text, p_discount_percent integer default 10,
  p_expires_at timestamptz default null, p_max_redemptions integer default null, p_contact text default null
) returns public.coupons
language plpgsql security definer set search_path = public as $$
declare
  v_creator uuid;
  v_code text := public.normalize_coupon(p_code);
  v public.coupons;
begin
  if v_code is null then raise exception 'Código vazio'; end if;
  select id into v_creator from public.creators where lower(name) = lower(trim(p_creator_name)) limit 1;
  if v_creator is null then
    insert into public.creators (name, contact) values (trim(p_creator_name), p_contact) returning id into v_creator;
  end if;
  insert into public.coupons (creator_id, code, discount_percent, expires_at, max_redemptions)
  values (v_creator, v_code, p_discount_percent, p_expires_at, p_max_redemptions)
  on conflict (code) do update
    set creator_id = excluded.creator_id, discount_percent = excluded.discount_percent,
        expires_at = excluded.expires_at, max_redemptions = excluded.max_redemptions, active = true
  returning * into v;
  return v;
end $$;

-- Relatório de parcerias: iniciadas = assinaturas criadas com o código; confirmadas = com
-- pelo menos um pagamento aprovado; ativas = com acesso vigente agora.
create view public.coupon_stats with (security_invoker = true) as
select
  cr.name                                                   as creator,
  c.code,
  c.discount_percent,
  c.active,
  c.expires_at,
  count(s.id)                                               as started,
  count(s.id) filter (where exists (
    select 1 from public.payments p where p.subscription_id = s.id and p.status = 'approved'
  ))                                                        as confirmed,
  count(s.id) filter (where s.paid_until > now())           as active_now
from public.coupons c
join public.creators cr on cr.id = c.creator_id
left join public.subscriptions s on s.coupon_id = c.id
group by cr.name, c.code, c.discount_percent, c.active, c.expires_at;

-- ---------------------------------------------------------------- Permissões e RLS

alter table public.creators       enable row level security;
alter table public.coupons        enable row level security;
alter table public.subscriptions  enable row level security;
alter table public.payments       enable row level security;
alter table public.webhook_events enable row level security;

-- Leitura apenas das próprias linhas. Sem políticas de insert/update/delete: o app não escreve.
create policy subscriptions_select_own on public.subscriptions
  for select to authenticated using (user_id = auth.uid());
create policy payments_select_own on public.payments
  for select to authenticated using (user_id = auth.uid());

revoke all on public.creators, public.coupons, public.webhook_events, public.coupon_stats from anon, authenticated;
revoke insert, update, delete, truncate on public.subscriptions, public.payments from anon, authenticated;
revoke all on public.subscriptions, public.payments from anon;
grant select on public.subscriptions, public.payments to authenticated;

-- Funções security definer não podem ser chamadas pelo app (por padrão o PUBLIC pode executar).
revoke execute on function public.recompute_paid_until(uuid)                         from public, anon, authenticated;
revoke execute on function public.has_active_access(uuid)                            from public, anon, authenticated;
revoke execute on function public.check_coupon(text)                                 from public, anon, authenticated;
revoke execute on function public.create_pending_subscription(uuid, integer, text)  from public, anon, authenticated;
revoke execute on function public.admin_upsert_coupon(text, text, integer, timestamptz, integer, text)
                                                                                     from public, anon, authenticated;
grant execute on function public.recompute_paid_until(uuid)                        to service_role;
grant execute on function public.has_active_access(uuid)                           to service_role;
grant execute on function public.check_coupon(text)                                to service_role;
grant execute on function public.create_pending_subscription(uuid, integer, text) to service_role;
