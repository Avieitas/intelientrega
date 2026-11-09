// Lê e valida variáveis de ambiente. Nunca registre estes valores em log.
import { DEFAULT_PRICE_CENTS } from "./pure.ts";

function req(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`Variável de ambiente ausente: ${name}`);
  return v;
}

export const env = {
  get supabaseUrl() { return req("SUPABASE_URL"); },
  get serviceRoleKey() { return req("SUPABASE_SERVICE_ROLE_KEY"); },
  get mpAccessToken() {
    const t = req("MP_ACCESS_TOKEN");
    // Impede misturar credencial de teste com produção (e vice-versa).
    // Sandbox: credencial TEST- da sua aplicação ou APP_USR- de uma CONTA DE TESTE (vendedor de teste).
    if (this.mpEnv === "sandbox" && !t.startsWith("TEST-") && !t.startsWith("APP_USR-")) throw new Error("MP_ACCESS_TOKEN em formato inesperado");
    if (this.mpEnv === "production" && t.startsWith("TEST-")) throw new Error("MP_ENV=production não aceita credencial TEST-");
    return t;
  },
  get mpEnv(): "sandbox" | "production" {
    const v = req("MP_ENV");
    if (v !== "sandbox" && v !== "production") throw new Error("MP_ENV deve ser sandbox ou production");
    return v;
  },
  get mpWebhookSecret() { return req("MP_WEBHOOK_SECRET"); },
  get mpBackUrl() {
    const u = req("MP_BACK_URL");
    if (!u.startsWith("https://")) throw new Error("MP_BACK_URL precisa ser https://");
    return u;
  },
  /** Só no sandbox: e-mail do comprador de teste do MP, usado como payer_email. */
  get mpTestPayerEmail() { return Deno.env.get("MP_TEST_PAYER_EMAIL") ?? null; },
  get priceCents() {
    const raw = Deno.env.get("SUBSCRIPTION_PRICE_CENTS");
    const n = raw ? Number(raw) : DEFAULT_PRICE_CENTS;
    if (!Number.isInteger(n) || n <= 0) throw new Error("SUBSCRIPTION_PRICE_CENTS inválido");
    return n;
  },
  get entitlementPrivateKey() { return req("ENTITLEMENT_PRIVATE_KEY"); },
};
