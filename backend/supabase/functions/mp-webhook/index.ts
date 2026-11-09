// Webhook do Mercado Pago. Público (sem JWT), mas toda notificação precisa de x-signature válida.
// O corpo serve só para saber O QUE mudou; o estado real é sempre buscado na API do MP.
import { db, json, log } from "../_shared/http.ts";
import { env } from "../_shared/env.ts";
import { verifyMpSignature } from "../_shared/pure.ts";
import { syncAuthorizedPayment, syncPayment, syncPreapproval } from "../_shared/sync.ts";
import { MpError } from "../_shared/mp.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response(null, { status: 405 });
  const url = new URL(req.url);
  const body = await req.json().catch(() => ({} as any));
  const topic: string | null = body?.type ?? url.searchParams.get("type") ?? url.searchParams.get("topic");
  const dataId: string | null = url.searchParams.get("data.id") ?? (body?.data?.id != null ? String(body.data.id) : null);
  const requestId = req.headers.get("x-request-id");

  let secret: string;
  try { secret = env.mpWebhookSecret; } catch {
    log("webhook_not_configured");
    return new Response(null, { status: 503 }); // MP reenvia depois que o segredo for configurado
  }
  const valid = await verifyMpSignature({
    xSignature: req.headers.get("x-signature"), xRequestId: requestId, dataId, secret,
  });
  if (!valid) {
    log("webhook_bad_signature", { topic, request_id: requestId });
    return new Response(null, { status: 401 });
  }
  if (!topic || !dataId) return json({ ignored: true });

  const eventKey = requestId ?? `${topic}:${dataId}`;
  await db().from("webhook_events")
    .upsert({ request_id: eventKey, topic, resource_id: dataId }, { onConflict: "provider,request_id", ignoreDuplicates: true });

  try {
    switch (topic) {
      case "subscription_preapproval": await syncPreapproval(dataId); break;
      case "subscription_authorized_payment": await syncAuthorizedPayment(dataId); break;
      case "payment": await syncPayment(dataId); break;
      default: break; // outros tópicos não afetam a assinatura
    }
    await db().from("webhook_events").update({ processed_at: new Date().toISOString(), error: null })
      .eq("provider", "mercadopago").eq("request_id", eventKey);
    return json({ ok: true });
  } catch (e) {
    const msg = e instanceof Error ? e.message.slice(0, 200) : "erro";
    await db().from("webhook_events").update({ error: msg }).eq("provider", "mercadopago").eq("request_id", eventKey);
    // 404: o recurso não existe nesta conta (ex.: "Simular notificação" do painel). Reenviar não muda nada.
    // Se for atraso do MP, a sincronização do status no app cobre.
    if (e instanceof MpError && e.status === 404) {
      log("webhook_not_found", { topic, request_id: requestId });
      return json({ ignored: "not_found" });
    }
    log("webhook_failed", { topic, request_id: requestId });
    // 500 faz o MP reenviar; o processamento é idempotente.
    return new Response(null, { status: 500 });
  }
});
