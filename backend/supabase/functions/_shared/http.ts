import { createClient, type SupabaseClient, type User } from "npm:@supabase/supabase-js@2";
import { env } from "./env.ts";

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export function error(status: number, code: string, message: string): Response {
  return json({ error: code, message }, status);
}

let admin: SupabaseClient | null = null;
/** Cliente com service_role: ignora RLS. Usado SOMENTE no servidor. */
export function db(): SupabaseClient {
  admin ??= createClient(env.supabaseUrl, env.serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return admin;
}

/** Valida o JWT do usuário no Supabase Auth. Nunca confia em user_id vindo do corpo da requisição. */
export async function requireUser(req: Request): Promise<User | Response> {
  const auth = req.headers.get("authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!token) return error(401, "unauthenticated", "Entre na sua conta.");
  const { data, error: err } = await db().auth.getUser(token);
  if (err || !data.user) return error(401, "unauthenticated", "Sessão expirada. Entre de novo.");
  return data.user;
}

/** Log sem dados sensíveis: só ids e códigos de erro. */
export function log(event: string, fields: Record<string, string | number | boolean | null | undefined> = {}) {
  console.log(JSON.stringify({ event, ...fields }));
}
