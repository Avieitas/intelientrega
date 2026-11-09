// Sincroniza o estado local com o Mercado Pago. É a ÚNICA forma de mudar status e acesso:
// sempre buscamos o recurso na API do MP (nunca confiamos no corpo do webhook ou no app).
// Todas as operações são idempotentes: reprocessar o mesmo evento produz o mesmo resultado.
import { db, log } from "./http.ts";
import { mp } from "./mp.ts";
import { mapPreapprovalStatus, toCents } from "./pure.ts";

type Sub = { id: string; user_id: string; amount_cents: number; mp_preapproval_id: string | null };

async function findSubByPreapproval(preapprovalId: string, externalRef?: string): Promise<Sub | null> {
  const sel = "id,user_id,amount_cents,mp_preapproval_id";
  const byId = await db().from("subscriptions").select(sel).eq("mp_preapproval_id", preapprovalId).maybeSingle();
  if (byId.data) return byId.data as Sub;
  // Assinatura recém-criada cujo id do MP ainda não foi gravado: external_reference = id local.
  if (externalRef && /^[0-9a-f-]{36}$/i.test(externalRef)) {
    const byRef = await db().from("subscriptions").select(sel).eq("id", externalRef).is("mp_preapproval_id", null).maybeSingle();
    if (byRef.data) return byRef.data as Sub;
  }
  return null;
}

export async function syncPreapproval(preapprovalId: string): Promise<Sub | null> {
  const pre = await mp.getPreapproval(preapprovalId);
  const sub = await findSubByPreapproval(pre.id, pre.external_reference);
  if (!sub) { log("preapproval_unknown", { preapproval: pre.id }); return null; }

  const status = mapPreapprovalStatus(pre.status);
  const amount = toCents(pre.auto_recurring?.transaction_amount);
  if (amount !== null && amount < sub.amount_cents) {
    // Valor no MP menor que o devido: não liberamos nada e registramos para análise.
    log("preapproval_amount_mismatch", { sub: sub.id, expected: sub.amount_cents, got: amount });
  }
  const patch: Record<string, unknown> = { mp_preapproval_id: pre.id, last_synced_at: new Date().toISOString() };
  if (status) patch.status = status;
  if (pre.next_payment_date) patch.next_payment_date = pre.next_payment_date;
  await db().from("subscriptions").update(patch).eq("id", sub.id);
  return { ...sub, mp_preapproval_id: pre.id };
}

export async function syncAuthorizedPayment(authorizedPaymentId: string): Promise<void> {
  const ap = await mp.getAuthorizedPayment(authorizedPaymentId);
  if (!ap.preapproval_id) return;
  let sub = await findSubByPreapproval(ap.preapproval_id);
  if (!sub) sub = await syncPreapproval(ap.preapproval_id);
  if (!sub) return;
  await upsertPayment(sub, ap);
}

async function upsertPayment(sub: Sub, ap: { id: string | number } & Record<string, any>) {
  // O status do pagamento em si vem de /v1/payments quando existir (fonte mais confiável).
  let status: string = ap.payment?.status ?? (ap.status === "scheduled" ? "scheduled" : "pending");
  let detail: string | null = ap.payment?.status_detail ?? null;
  let approvedAt: string | null = null;
  let amount = toCents(ap.transaction_amount);
  const paymentId = ap.payment?.id != null ? String(ap.payment.id) : null;
  if (paymentId) {
    const p = await mp.getPayment(paymentId);
    status = p.status; detail = p.status_detail ?? null;
    approvedAt = p.status === "approved" ? (p.date_approved ?? null) : null;
    amount = toCents(p.transaction_amount) ?? amount;
  }

  const { error } = await db().from("payments").upsert({
    subscription_id: sub.id,
    user_id: sub.user_id,
    mp_authorized_payment_id: String(ap.id),
    mp_payment_id: paymentId,
    invoice_status: ap.status ?? null,
    status,
    status_detail: detail,
    amount_cents: amount,
    debit_date: ap.debit_date ?? ap.date_created ?? null,
    approved_at: approvedAt,
  }, { onConflict: "mp_authorized_payment_id" });
  if (error) throw new Error(`payments upsert: ${error.code}`);

  // Só pagamentos "approved" com valor >= devido contam (regra no banco).
  await db().rpc("recompute_paid_until", { p_subscription: sub.id });
  await db().from("subscriptions")
    .update({ last_payment_status: status, last_payment_detail: detail })
    .eq("id", sub.id);
  log("payment_synced", { sub: sub.id, authorized_payment: String(ap.id), status });
}

/** Notificação do tópico "payment": localiza a cobrança recorrente correspondente e ressincroniza. */
export async function syncPayment(paymentId: string): Promise<void> {
  const { data } = await db().from("payments").select("mp_authorized_payment_id").eq("mp_payment_id", paymentId).maybeSingle();
  if (data?.mp_authorized_payment_id) await syncAuthorizedPayment(data.mp_authorized_payment_id);
  // Pagamento ainda não associado: será capturado pelo evento subscription_authorized_payment.
}

/** Usado pela tela de status: puxa a assinatura e as cobranças direto do MP (rede de segurança se um webhook se perder). */
export async function fullSync(preapprovalId: string): Promise<void> {
  const sub = await syncPreapproval(preapprovalId);
  if (!sub) return;
  const found = await mp.searchAuthorizedPayments(preapprovalId);
  for (const ap of found.results ?? []) {
    if (ap.preapproval_id && ap.preapproval_id !== preapprovalId) continue;
    await upsertPayment(sub, ap);
  }
}
