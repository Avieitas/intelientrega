// POST { code?: string } → preço base, desconto e total, calculados no servidor.
import { error, json, db, requireUser } from "../_shared/http.ts";
import { env } from "../_shared/env.ts";
import { COUPON_MESSAGES, normalizeCoupon, priceWithDiscount } from "../_shared/pure.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return error(405, "method", "Use POST.");
  const user = await requireUser(req);
  if (user instanceof Response) return user;
  const body = await req.json().catch(() => ({}));
  const base = env.priceCents;
  const code = normalizeCoupon(body?.code);
  if (!code) return json({ valid: false, ...priceWithDiscount(base, null) });

  const { data, error: err } = await db().rpc("check_coupon", { p_raw: code }).single();
  if (err) return error(500, "internal", "Não foi possível validar o código agora.");
  const r = data as { error: string | null; code: string; discount_percent: number | null };
  if (r.error) {
    return json({ valid: false, code, reason: r.error, message: COUPON_MESSAGES[r.error] ?? COUPON_MESSAGES.invalid, ...priceWithDiscount(base, null) });
  }
  return json({ valid: true, code: r.code, discount_percent: r.discount_percent, ...priceWithDiscount(base, r.discount_percent) });
});
