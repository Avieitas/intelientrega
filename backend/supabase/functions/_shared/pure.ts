// Lógica pura, sem APIs do Deno nem do Supabase: roda igual no Deno (Edge Functions) e no Node (testes).
// Usa apenas Web Crypto (globalThis.crypto).

export const DEFAULT_PRICE_CENTS = 2000; // R$ 20,00

/** Ignora maiúsculas/minúsculas e espaços acidentais. Mesma regra de public.normalize_coupon. */
export function normalizeCoupon(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const v = raw.replace(/\s/g, "").toUpperCase();
  return v.length ? v : null;
}

export function priceWithDiscount(baseCents: number, percent: number | null) {
  const discount = percent ? Math.round((baseCents * percent) / 100) : 0;
  return { baseCents, discountCents: discount, totalCents: baseCents - discount };
}

export const COUPON_MESSAGES: Record<string, string> = {
  empty: "Digite um código.",
  invalid: "Código inválido. Confira se digitou certo.",
  inactive: "Este código foi desativado e não pode mais ser usado.",
  not_started: "Este código ainda não está valendo.",
  expired: "Este código expirou.",
  exhausted: "Este código atingiu o limite de usos.",
};

// ------------------------------------------------------------ Webhook do Mercado Pago

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  return hex(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message)));
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export function parseSignatureHeader(header: string | null): { ts?: string; v1?: string } {
  const out: { ts?: string; v1?: string } = {};
  for (const part of (header ?? "").split(",")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    const k = part.slice(0, eq).trim().toLowerCase();
    const v = part.slice(eq + 1).trim();
    if (k === "ts") out.ts = v;
    if (k === "v1") out.v1 = v;
  }
  return out;
}

/** Mesmo manifesto do validador oficial do SDK: "id:<data.id>;request-id:<x-request-id>;ts:<ts>;" */
export function buildManifest(dataId: string | null, requestId: string | null, ts: string): string {
  const parts: string[] = [];
  if (dataId) parts.push(`id:${dataId}`);
  if (requestId) parts.push(`request-id:${requestId}`);
  parts.push(`ts:${ts}`);
  return parts.join(";") + ";";
}

/**
 * Valida x-signature (HMAC-SHA256). A documentação antiga do MP pedia data.id em minúsculas
 * quando alfanumérico; testamos o valor recebido e, se diferente, a versão minúscula.
 * toleranceSec limita replays com timestamps antigos.
 */
export async function verifyMpSignature(opts: {
  xSignature: string | null; xRequestId: string | null; dataId: string | null; secret: string;
  nowMs?: number; toleranceSec?: number;
}): Promise<boolean> {
  const { ts, v1 } = parseSignatureHeader(opts.xSignature);
  if (!ts || !v1 || !/^\d+$/.test(ts) || !opts.secret) return false;
  if (opts.toleranceSec !== undefined) {
    const drift = Math.abs((opts.nowMs ?? Date.now()) - Number(ts) * 1000) / 1000;
    if (drift > opts.toleranceSec) return false;
  }
  const ids = new Set([opts.dataId, opts.dataId?.toLowerCase() ?? null]);
  for (const id of ids) {
    const h = await hmacSha256Hex(opts.secret, buildManifest(id, opts.xRequestId, ts));
    if (constantTimeEqual(h, v1.toLowerCase())) return true;
  }
  return false;
}

// ------------------------------------------------------------ Token de acesso (ES256)

function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64decode(b64: string): Uint8Array<ArrayBuffer> {
  const bin = atob(b64.replace(/\s/g, ""));
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export interface EntitlementPayload {
  v: 1;
  sub: string;        // id do usuário
  iat: number;        // segundos
  exp: number;        // segundos
  paid_until: string; // ISO
}

/**
 * Assina o comprovante de acesso com ECDSA P-256 / SHA-256. A assinatura é o formato bruto
 * r||s (64 bytes) do Web Crypto; o app converte para DER ao verificar.
 * privateKeyB64 = PKCS#8 DER em base64.
 */
export async function signEntitlement(p: EntitlementPayload, privateKeyB64: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "pkcs8", b64decode(privateKeyB64), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"],
  );
  const body = b64url(new TextEncoder().encode(JSON.stringify(p)));
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(body));
  return `${body}.${b64url(new Uint8Array(sig))}`;
}

/** Validade do comprovante: até o fim do período pago, no máximo 72 h (o app revalida online). */
export function entitlementExpiry(paidUntilMs: number, nowMs: number, maxHours = 72): number {
  return Math.floor(Math.min(paidUntilMs, nowMs + maxHours * 3600_000) / 1000);
}

// ------------------------------------------------------------ Status para o usuário

export type UserState =
  | "none" | "pending" | "active" | "active_cancelled" | "payment_failed"
  | "paused" | "expired" | "cancelled" | "courtesy";

export interface SubRow {
  status: "pending" | "authorized" | "paused" | "cancelled";
  paid_until: string | null;
  last_payment_status: string | null;
}

export function deriveState(sub: SubRow | null, hasAnyApproved: boolean, nowMs: number): UserState {
  if (!sub) return "none";
  const active = sub.paid_until !== null && Date.parse(sub.paid_until) > nowMs;
  const failed = ["rejected", "cancelled", "refunded", "charged_back"].includes(sub.last_payment_status ?? "");
  if (active) return sub.status === "cancelled" ? "active_cancelled" : "active";
  switch (sub.status) {
    case "pending": return failed ? "payment_failed" : "pending";
    case "authorized": return failed ? "payment_failed" : hasAnyApproved ? "expired" : "pending";
    case "paused": return "paused";
    case "cancelled": return hasAnyApproved ? "expired" : "cancelled";
  }
}

export const STATE_MESSAGES: Record<UserState, { title: string; help: string }> = {
  none: { title: "Sem assinatura", help: "Assine para liberar a análise de ofertas." },
  pending: {
    title: "Aguardando confirmação do pagamento",
    help: "Conclua o pagamento no Mercado Pago. O acesso é liberado assim que o Mercado Pago confirmar.",
  },
  active: { title: "Assinatura ativa", help: "Renova automaticamente todo mês." },
  active_cancelled: {
    title: "Renovação cancelada",
    help: "Você continua com acesso até o fim do período já pago. Depois disso, assine de novo para continuar.",
  },
  payment_failed: {
    title: "Pagamento não aprovado",
    help: "Atualize o cartão em Mercado Pago > Assinaturas, ou cancele e assine de novo com outro cartão.",
  },
  paused: {
    title: "Assinatura pausada",
    help: "A cobrança está pausada no Mercado Pago. Reative por lá ou assine de novo para liberar o acesso.",
  },
  expired: {
    title: "Assinatura vencida",
    help: "O período pago terminou sem uma nova cobrança aprovada. Regularize o pagamento no Mercado Pago ou assine de novo.",
  },
  cancelled: { title: "Assinatura cancelada", help: "Assine de novo para liberar a análise de ofertas." },
  courtesy: {
    title: "Acesso de cortesia",
    help: "Você recebeu acesso sem cobrança por um período. Quando terminar, é só assinar para continuar.",
  },
};

/** Status do MP para o enum local. Desconhecidos não alteram nada (null). */
export function mapPreapprovalStatus(s: unknown): SubRow["status"] | null {
  return s === "pending" || s === "authorized" || s === "paused" || s === "cancelled" ? s : null;
}

/** Reais (número do MP) para centavos, sem erro de ponto flutuante. */
export function toCents(amount: unknown): number | null {
  const n = typeof amount === "number" ? amount : typeof amount === "string" ? Number(amount) : NaN;
  return Number.isFinite(n) ? Math.round(n * 100) : null;
}
