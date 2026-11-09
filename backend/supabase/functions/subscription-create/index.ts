// POST { coupon_code?: string } → cria a assinatura (pendente) no MP e devolve o link de checkout.
// Nenhum dado de cartão passa pelo app nem por aqui: o pagamento é feito no checkout do Mercado Pago.
import { error, json, db, log, requireUser } from "../_shared/http.ts";
import { env } from "../_shared/env.ts";
import { mp, MpError } from "../_shared/mp.ts";
import { COUPON_MESSAGES, normalizeCoupon } from "../_shared/pure.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return error(405, "method", "Use POST.");
  const user = await requireUser(req);
  if (user instanceof Response) return user;
  const body = await req.json().catch(() => ({}));
  const coupon = normalizeCoupon(body?.coupon_code);

  // Pendências antigas (checkout abandonado) são canceladas antes de criar outra.
  const { data: pending } = await db().from("subscriptions").select("id,mp_preapproval_id")
    .eq("user_id", user.id).eq("status", "pending");
  for (const p of pending ?? []) {
    if (p.mp_preapproval_id) {
      try { await mp.cancelPreapproval(p.mp_preapproval_id); }
      catch (e) { if (!(e instanceof MpError && e.status === 404)) return error(502, "provider", "Não foi possível encerrar a tentativa anterior. Tente de novo."); }
    }
    await db().from("subscriptions").update({ status: "cancelled", cancel_requested_at: new Date().toISOString() }).eq("id", p.id);
  }

  const { data: sub, error: rpcErr } = await db()
    .rpc("create_pending_subscription", { p_user: user.id, p_base_cents: env.priceCents, p_coupon: coupon }).single();
  if (rpcErr) {
    const m = rpcErr.message ?? "";
    if (m.includes("already_subscribed")) return error(409, "already_subscribed", "Você já tem uma assinatura ativa.");
    const c = m.match(/coupon_(\w+)/);
    if (c) return error(422, `coupon_${c[1]}`, COUPON_MESSAGES[c[1]] ?? COUPON_MESSAGES.invalid);
    log("create_failed", { user: user.id, code: rpcErr.code });
    return error(500, "internal", "Não foi possível iniciar a assinatura.");
  }
  const s = sub as { id: string; amount_cents: number };

  // Se ainda há período pago (renovação cancelada antes), a 1ª cobrança nova começa quando ele acabar.
  const { data: still } = await db().from("subscriptions").select("paid_until")
    .eq("user_id", user.id).gt("paid_until", new Date().toISOString())
    .order("paid_until", { ascending: false }).limit(1).maybeSingle();

  const payerEmail = env.mpEnv === "sandbox" && env.mpTestPayerEmail ? env.mpTestPayerEmail : user.email;
  if (!payerEmail) return error(400, "no_email", "Sua conta precisa de um e-mail.");

  try {
    const pre = await mp.createPreapproval({
      reason: "InteliEntrega - assinatura mensal",
      external_reference: s.id,
      payer_email: payerEmail,
      back_url: env.mpBackUrl,
      status: "pending",
      auto_recurring: {
        frequency: 1,
        frequency_type: "months",
        transaction_amount: s.amount_cents / 100,
        currency_id: "BRL",
        ...(still?.paid_until ? { start_date: new Date(still.paid_until).toISOString() } : {}),
      },
    }, s.id);
    if (!pre.init_point) throw new MpError(502, "no_init_point");
    await db().from("subscriptions").update({ mp_preapproval_id: pre.id, init_point: pre.init_point }).eq("id", s.id);
    log("subscription_created", { sub: s.id, coupon: !!coupon });
    return json({ subscription_id: s.id, init_point: pre.init_point, amount_cents: s.amount_cents });
  } catch (e) {
    await db().from("subscriptions").update({ status: "cancelled" }).eq("id", s.id);
    log("mp_create_failed", { sub: s.id, status: e instanceof MpError ? e.status : 0, code: e instanceof MpError ? e.mpCode : null });
    return error(502, "provider", "O Mercado Pago não aceitou a criação da assinatura. Tente de novo em instantes.");
  }
});
