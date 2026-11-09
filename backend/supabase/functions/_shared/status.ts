import { db } from "./http.ts";
import { env } from "./env.ts";
import { fullSync } from "./sync.ts";
import { deriveState, entitlementExpiry, signEntitlement, STATE_MESSAGES, type UserState } from "./pure.ts";

const SUB_COLS = "id,status,mp_preapproval_id,base_amount_cents,discount_cents,amount_cents,coupon_code,paid_until,last_payment_status,last_payment_detail,next_payment_date,cancel_requested_at,last_synced_at,created_at";

/** Monta o status da assinatura do PRÓPRIO usuário (userId sempre vem do JWT validado). */
export async function buildStatus(userId: string, opts: { refresh?: boolean } = {}) {
  const load = async () => {
    const { data } = await db().from("subscriptions").select(SUB_COLS).eq("user_id", userId)
      .order("created_at", { ascending: false }).limit(10);
    return data ?? [];
  };
  let subs = await load();
  const open = () => subs.find((s) => ["pending", "authorized", "paused"].includes(s.status));

  // Rede de segurança: consulta o MP direto se o status pode ter mudado e o webhook não chegou.
  const cur = open() ?? subs[0];
  if (cur?.mp_preapproval_id) {
    const last = cur.last_synced_at ? Date.parse(cur.last_synced_at) : 0;
    const age = Date.now() - last;
    const nearEnd = cur.paid_until ? Date.parse(cur.paid_until) - Date.now() < 86_400_000 : true;
    const stale = cur.status === "pending" || nearEnd ? age > 60_000 : age > 6 * 3600_000;
    if ((opts.refresh && age > 15_000) || stale) {
      try { await fullSync(cur.mp_preapproval_id); subs = await load(); }
      catch (_) { /* mantém o estado local; o webhook ou a próxima consulta corrige */ }
    }
  }

  const now = Date.now();
  const display = open() ?? subs[0] ?? null;
  const accessSub = subs
    .filter((s) => s.paid_until && Date.parse(s.paid_until) > now)
    .sort((a, b) => Date.parse(b.paid_until!) - Date.parse(a.paid_until!))[0] ?? null;

  let payments: any[] = [];
  let hasApproved = false;
  if (display) {
    const { data } = await db().from("payments")
      .select("status,status_detail,amount_cents,debit_date,approved_at")
      .eq("subscription_id", display.id).order("created_at", { ascending: false }).limit(6);
    payments = data ?? [];
    hasApproved = payments.some((p) => p.status === "approved");
  }

  let state: UserState = deriveState(display, hasApproved, now);
  if (!state.startsWith("active") && accessSub) state = deriveState(accessSub, true, now);

  // Cortesia (não é pagamento): só aparece como estado quando não há período pago valendo.
  const { data: grant } = await db().from("access_grants").select("until").eq("user_id", userId)
    .is("revoked_at", null).gt("until", new Date(now).toISOString())
    .order("until", { ascending: false }).limit(1).maybeSingle();
  if (grant && !accessSub) state = "courtesy";

  // O acesso vale até o fim mais distante entre período pago e cortesia.
  const ends = [accessSub?.paid_until, grant?.until].filter((x): x is string => !!x);
  const accessUntil = ends.sort((a, b) => Date.parse(b) - Date.parse(a))[0] ?? null;

  let entitlement: string | null = null;
  if (accessUntil) {
    entitlement = await signEntitlement({
      v: 1, sub: userId, iat: Math.floor(now / 1000),
      exp: entitlementExpiry(Date.parse(accessUntil), now), paid_until: accessUntil,
    }, env.entitlementPrivateKey);
  }

  return {
    state,
    ...STATE_MESSAGES[state],
    access: { active: !!accessUntil, paid_until: accessUntil, courtesy: !!grant && !accessSub },
    subscription: display && {
      status: display.status,
      base_amount_cents: display.base_amount_cents,
      discount_cents: display.discount_cents,
      amount_cents: display.amount_cents,
      coupon_code: display.coupon_code,
      next_payment_date: display.next_payment_date,
      can_cancel: ["pending", "authorized", "paused"].includes(display.status),
      payment_detail: display.last_payment_detail,
    },
    payments,
    entitlement,
    sandbox: env.mpEnv === "sandbox",
  };
}
