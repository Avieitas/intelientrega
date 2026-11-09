// POST { refresh?: boolean } → status da assinatura do usuário logado + comprovante de acesso assinado.
import { error, json, requireUser } from "../_shared/http.ts";
import { buildStatus } from "../_shared/status.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST" && req.method !== "GET") return error(405, "method", "Use POST.");
  const user = await requireUser(req);
  if (user instanceof Response) return user;
  const body = req.method === "POST" ? await req.json().catch(() => ({})) : {};
  try {
    return json(await buildStatus(user.id, { refresh: body?.refresh === true }));
  } catch (_) {
    return error(500, "internal", "Não foi possível consultar a assinatura agora.");
  }
});
