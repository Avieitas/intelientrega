// POST → cancela a RENOVAÇÃO no Mercado Pago. O acesso segue até o fim do período já pago.
import { error, json, db, log, requireUser } from "../_shared/http.ts";
import { mp } from "../_shared/mp.ts";
import { buildStatus } from "../_shared/status.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return error(405, "method", "Use POST.");
  const user = await requireUser(req);
  if (user instanceof Response) return user;

  const { data: sub } = await db().from("subscriptions").select("id,mp_preapproval_id")
    .eq("user_id", user.id).in("status", ["pending", "authorized", "paused"]).maybeSingle();
  if (!sub) return error(404, "no_subscription", "Não há assinatura com renovação ativa.");

  if (sub.mp_preapproval_id) {
    try {
      const res = await mp.cancelPreapproval(sub.mp_preapproval_id);
      // Só marcamos como cancelada depois que o MP confirmar.
      if (res.status !== "cancelled") return error(502, "provider", "O Mercado Pago não confirmou o cancelamento. Tente de novo.");
    } catch (_) {
      return error(502, "provider", "Não foi possível cancelar agora. Tente de novo em instantes.");
    }
  }
  await db().from("subscriptions")
    .update({ status: "cancelled", cancel_requested_at: new Date().toISOString() }).eq("id", sub.id);
  log("subscription_cancelled", { sub: sub.id });
  return json(await buildStatus(user.id));
});
