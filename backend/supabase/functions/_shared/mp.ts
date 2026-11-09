// Cliente mínimo da API do Mercado Pago (servidor-a-servidor). O app nunca fala com o MP diretamente
// e nunca vê dados de cartão: o pagamento acontece no checkout do próprio Mercado Pago (init_point).
import { env } from "./env.ts";

const BASE = "https://api.mercadopago.com";

export class MpError extends Error {
  constructor(public status: number, public mpCode: string | null) { super(`Mercado Pago HTTP ${status}${mpCode ? ` (${mpCode})` : ""}`); }
}

async function call<T>(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  const headers: Record<string, string> = {
    authorization: `Bearer ${env.mpAccessToken}`,
    "content-type": "application/json",
  };
  if (idempotencyKey) headers["x-idempotency-key"] = idempotencyKey;
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let data: any = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* corpo não-JSON */ }
  if (!res.ok) throw new MpError(res.status, data?.error ?? data?.code ?? null);
  return data as T;
}

export interface Preapproval {
  id: string;
  status: string;
  external_reference?: string;
  init_point?: string;
  next_payment_date?: string;
  auto_recurring?: { transaction_amount?: number; currency_id?: string };
}

export interface AuthorizedPayment {
  id: string | number;
  preapproval_id?: string;
  status?: string; // scheduled | processed | recycling | cancelled
  transaction_amount?: number;
  debit_date?: string;
  date_created?: string;
  payment?: { id?: string | number; status?: string; status_detail?: string };
}

export interface Payment {
  id: string | number;
  status: string;
  status_detail?: string;
  transaction_amount?: number;
  date_approved?: string | null;
}

export const mp = {
  createPreapproval: (body: unknown, idempotencyKey: string) =>
    call<Preapproval>("POST", "/preapproval", body, idempotencyKey),
  getPreapproval: (id: string) => call<Preapproval>("GET", `/preapproval/${encodeURIComponent(id)}`),
  cancelPreapproval: (id: string) =>
    call<Preapproval>("PUT", `/preapproval/${encodeURIComponent(id)}`, { status: "cancelled" }),
  getAuthorizedPayment: (id: string) =>
    call<AuthorizedPayment>("GET", `/authorized_payments/${encodeURIComponent(id)}`),
  searchAuthorizedPayments: (preapprovalId: string) =>
    call<{ results?: AuthorizedPayment[] }>("GET", `/authorized_payments/search?preapproval_id=${encodeURIComponent(preapprovalId)}`),
  getPayment: (id: string) => call<Payment>("GET", `/v1/payments/${encodeURIComponent(id)}`),
};
