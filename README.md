# InteliEntrega (antes "Vale a Corrida?")

App Android independente que ajuda o motoboy a avaliar se uma oferta de entrega compensa.
Não tem vínculo com a 99, o iFood ou outras plataformas. Não usa APIs nem layouts dessas empresas.

## Como abrir

1. Abra a pasta no **Android Studio** (Ladybug ou mais novo). Ele usa o próprio JDK 17+.
2. Deixe o Studio sincronizar o Gradle (8.11.1, AGP 8.7.3, Kotlin 2.1.0).
3. Rode em um **aparelho físico** com Android 8.0+ (API 26). O ideal é Android 14 ou 15.

Testes unitários (cálculo e parsers, sem Android): `./gradlew :app:testDebugUnitTest`

## Estrutura

| Pacote | Papel |
|---|---|
| `domain/` | Modelos, `OfferCalculator` (fórmulas), `OfferDraft` (dados editáveis, origem de cada campo). Kotlin puro. |
| `parsing/` | `BrTokenizer` (R$, km, m, min, h, faixas), `OfferParser` (interface), `GenericOfferParser`, parsers da 99 e do iFood como pontos de extensão. Kotlin puro. |
| `ocr/` | `OcrEngine` + `MlKitOcrEngine` (modelo latino embutido, local). |
| `capture/` | `ScreenCaptureService` (serviço em primeiro plano `mediaProjection`), `FrameGrabber` (VirtualDisplay + ImageReader). |
| `overlay/` | Bolinha (View arrastável) e painel (Compose) em `TYPE_APPLICATION_OVERLAY`. |
| `data/` | `SettingsRepository` (DataStore). |
| `ui/` | Telas Compose e o card de análise, usado no painel e na aba "Testar texto". |

## Fluxo

1. **Custos e metas**: tipo de veículo; combustão (preço do litro, km/l) ou elétrico (autonomia, kWh, tarifa, bateria mínima); reserva por km (manutenção, pneus, óleo e depreciação num valor só), meta de sobra/h, meta opcional de R$/km, espera, reposicionamento.
2. **Sessão**: permite sobreposição e notificações, toca em *Iniciar sessão* e aceita a tela de consentimento do Android.
3. Abre a oferta no app de entregas e toca na bolinha **R$?**.
4. O painel mostra os dados lidos, a origem de cada um (lido, informado por você, das configurações, estimado), os custos e o veredito. Tudo é editável.

## Ciclo de vida da captura

- Cada sessão pede um consentimento novo. O `Intent` de resultado vai direto para o serviço e é usado uma vez.
- `startForeground(...MEDIA_PROJECTION)` acontece **antes** de `getMediaProjection()`. Depois vem `registerCallback()`, e só então `createVirtualDisplay()` (um único por token).
- A sessão termina e libera tudo com: botão *Encerrar* (app, painel ou notificação), `MediaProjection.Callback.onStop` (chip da barra de status, bloqueio, outra projeção), `ACTION_SCREEN_OFF` e `onDestroy`.
- Se o sistema reiniciar o serviço sem token, ele não tenta retomar (`START_NOT_STICKY`).
- `onCapturedContentResize` redimensiona a captura. `onCapturedContentVisibilityChanged` avisa quando o app compartilhado (modo "um app só") não está visível.
- **Sem OCR contínuo.** O VirtualDisplay mantém só o quadro mais recente. Ele vira Bitmap e passa pelo OCR somente ao toque na bolinha, e o Bitmap é reciclado logo depois. Nenhuma imagem é gravada.
- **Sem contaminação.** Antes de pegar o quadro, a bolinha e o painel ficam invisíveis. O app espera 2 quadros e mais 50 ms e só aceita um quadro chegado depois disso.

## Regras de cálculo

```
custoCombustivelKm = precoLitro / consumoKmLitro
custoMotoKm        = custoCombustivelKm + reservaOutrosCustosKm
custoEstimado      = distanciaTotalKm * custoMotoKm
sobraEstimada      = valorOferta - custoEstimado
valorBrutoKm       = valorOferta / distanciaTotalKm
sobraHora          = sobraEstimada / (tempoTotalMinutos / 60)
```

### Veículo elétrico / autopropelido

Em *Custos e metas*, escolha "Elétrico / autopropelido" e informe a autonomia real (km com carga cheia), a capacidade da bateria (kWh = V × Ah ÷ 1000), a tarifa (R$/kWh) e a bateria mínima que quer manter.

```
custoEnergiaKm   = (capacidadeKwh * tarifaKwh) / autonomiaKm   // substitui o combustível
custoMotoKm      = custoEnergiaKm + reservaOutrosCustosKm      // reserva inclui desgaste da bateria
bateriaUsadaPct  = distanciaTotalKm / autonomiaKm * 100
sobraPorPct      = sobraEstimada / bateriaUsadaPct
kmDisponiveis    = autonomiaKm * (bateriaAgoraPct - reservaMinimaPct) / 100
```

- "Bateria agora (%)" é opcional e fica no painel. Ela é digitada pelo usuário (o app não lê a bateria do veículo) e passa de uma análise para a seguinte na mesma sessão.
- Se a entrega passar de `kmDisponiveis`, o veredito é **"A bateria não garante essa entrega"**.
- Sem a carga atual, o painel mostra só o gasto de bateria e a sobra por 1% de bateria, sem prever a carga final.

### Regras gerais

- Se houver distância total, os trechos não são somados. Sem total, soma coleta + entrega. Com um trecho só, o resultado fica *parcial* e pede conferência. O reposicionamento só entra se o usuário configurar.
- Sem tempo, não há sobra/hora. Espera e reposicionamento não criam um tempo do nada. Com "tempo já inclui espera" ligado, a espera não é somada.
- Divisões protegidas. Valores fora de faixa (oferta acima de R$ 2.000, mais de 300 km, mais de 600 min, NaN, zero ou negativo) viram "Dados insuficientes".
- Ambiguidade (vários preços, distâncias ou tempos diferentes, distância sem rótulo, faixa de tempo, bônus) gera "Confira os dados" até o usuário corrigir ou tocar em *Confirmar dados*.
- O painel deixa claro que a sobra **não é lucro líquido** e que a sobra/h é **projeção desta oferta**.

## O que foi verificado e o que não foi

**Verificado:**
- Domínio, parsers e `EntitlementVerifier` compilados com `kotlinc` 2.1.0: 36 testes JUnit passando, incluindo a verificação de um comprovante assinado pelo código do servidor.
- Migração SQL executada num Postgres (PGlite) com o esquema `auth` simulado: 25 cenários de cupom, preço, idempotência, estorno e RLS.
- Lógica pura do servidor no Node: 21 testes, com a assinatura do webhook conferida contra o validador oficial do SDK `mercadopago@3.6.1`.
- As 5 Edge Functions passam em `deno check`.

**NÃO verificado** (sem Android SDK, Gradle e emulador no ambiente de desenvolvimento):
- Compilação Android (Gradle, Compose, ML Kit, manifesto). Pode haver erro de compilação a corrigir na primeira sync.
- Comportamento real de captura, sobreposição e OCR em aparelho.
- Funcionamento na 99 e no iFood. Pontos de risco:
  - **FLAG_SECURE**: se o app marcar a tela como segura, a captura sai preta e o painel avisa "Nenhum texto foi lido".
  - **`setHideOverlayWindows`** (Android 12+): o app pode esconder sobreposições de terceiros na tela dele, e a bolinha pode sumir.
  - Toques bloqueados (Android 12+): o painel cobre parte da tela, então é preciso fechá-lo para tocar no app de baixo.
  - O tempo de ocultação da sobreposição (2 quadros + 50 ms) precisa de ajuste conforme o aparelho.
- Os parsers da 99 e do iFood **são iguais ao genérico de propósito**. Nenhum layout real foi fornecido. As palavras-chave ("coleta", "entrega", "total", "bônus") são hipóteses.

## Roteiro de teste em aparelho

1. Aba **Testar texto**: cole textos variados e confira cálculo e ambiguidades, sem captura.
2. Inicie uma sessão e escolha "Tela inteira". Abra um app qualquer com texto tipo "R$ 10,00 5 km 20 min" (por exemplo, uma nota) e toque na bolinha.
3. Repita escolhendo "Um app só".
4. Bloqueie a tela: a notificação some e a sessão termina. Toque no chip da barra de status (Android 15+): o mesmo deve acontecer.
5. Na 99 e no iFood, anote se a bolinha aparece, se a imagem sai preta e o que o OCR leu. Com essas leituras (sem dados pessoais), escreva testes e especialize `NoventaNoveOfferParser` e `IfoodOfferParser`.

---

# Assinatura mensal (R$ 20,00) com Mercado Pago

Distribuição **fora da Google Play** (APK). Na Play, assinatura de recurso digital precisaria ser feita pelo Google Play Billing.

## Arquitetura

```
App Android ──(login, JWT)──► Supabase Auth
     │                          │
     ├─ POST /functions/v1/coupon-preview       valida cupom, calcula preço (servidor)
     ├─ POST /functions/v1/subscription-create  cria preapproval no MP, devolve init_point
     ├─ POST /functions/v1/subscription-status  status + comprovante de acesso assinado (ES256)
     └─ POST /functions/v1/subscription-cancel  cancela a renovação no MP
Mercado Pago ──(webhook assinado)──► /functions/v1/mp-webhook ──► consulta a API do MP ──► Postgres
```

- **Cartão**: digitado só no checkout do Mercado Pago (`init_point`). O app e o servidor nunca recebem dados de cartão.
- **Fonte da verdade**: o status só muda depois que o servidor consulta o recurso na API do MP (`/preapproval`, `/authorized_payments`, `/v1/payments`). O corpo do webhook e o app nunca são usados como prova de pagamento.
- **Acesso**: `subscriptions.paid_until` = último pagamento aprovado (com valor ≥ devido) + 1 mês + 3 dias de tolerância para as novas tentativas do MP. Um estorno ou chargeback recalcula e remove o acesso.
- **Bloqueio no app**: as abas Sessão e Testar e o serviço de captura exigem um **comprovante assinado pelo servidor** (ECDSA P-256), do usuário logado e válido por no máximo 72 h. O app só tem a chave pública, então não consegue gerar um comprovante. A aba Custos continua liberada.
- **Cupom**: o desconto de 10% é recorrente. A assinatura é criada "sem plano", com o valor da própria pessoa (R$ 18,00), e todas as renovações saem por esse valor. É um único `coupon_id` por assinatura, e o valor é calculado e checado por constraint no banco.
- **Webhooks repetidos**: `payments.mp_authorized_payment_id` é único (upsert), `paid_until` é recalculado de forma determinística e `webhook_events` registra cada notificação.

## Arquivos

| Onde | O quê |
|---|---|
| `backend/supabase/migrations/20261108120000_assinaturas.sql` | Tabelas `creators`, `coupons`, `subscriptions`, `payments`, `webhook_events`; RLS; funções `check_coupon`, `create_pending_subscription`, `recompute_paid_until`, `has_active_access`, `admin_upsert_coupon`; view `coupon_stats` |
| `backend/supabase/functions/_shared/` | `pure.ts` (cupom, preço, HMAC do webhook, comprovante ES256, estados), `mp.ts`, `sync.ts`, `status.ts`, `http.ts`, `env.ts` |
| `backend/supabase/functions/*/index.ts` | As 5 funções HTTP |
| `backend/supabase/config.toml` | `verify_jwt = false` só no webhook |
| `backend/retorno/index.html` | Página do `back_url` (botão "Abrir o app") |
| `app/.../account/` | `SupabaseApi`, `AccountRepository`, `SecureStore` (Keystore), `EntitlementVerifier` |
| `app/.../ui/AccountScreens.kt` | Entrar, criar conta, recuperar senha, assinatura com cupom, status, cancelar, acesso bloqueado |

## Configuração (passo a passo)

1. **Supabase**: crie um projeto. Instale a CLI e rode `supabase login` e `supabase link --project-ref <ref>`, a partir de `backend/`.
2. **Banco**: `supabase db push`.
3. **Auth** (Dashboard → Authentication):
   - Provedor Email ligado. Se quiser confirmação por e-mail, deixe "Confirm email" ligado; o app trata os dois casos.
   - Em **Email Templates → Reset Password**, inclua `{{ .Token }}` no texto. O app usa o **código de 6 dígitos**, não o link.
   - Para produção, configure um SMTP próprio. O SMTP padrão do Supabase tem limite baixo de envios.
4. **Chaves do comprovante**:
   ```sh
   openssl ecparam -name prime256v1 -genkey -noout -out ent.pem
   openssl pkcs8 -topk8 -nocrypt -in ent.pem -outform DER | base64 | tr -d '\n'   # → ENTITLEMENT_PRIVATE_KEY (servidor)
   openssl ec -in ent.pem -pubout -outform DER | base64 | tr -d '\n'              # → ENTITLEMENT_PUBLIC_KEY (app)
   ```
   Guarde o `ent.pem` fora do repositório.
5. **Página de retorno**: publique `backend/retorno/` (por exemplo na Vercel) e use a URL https como `MP_BACK_URL`.
6. **Segredos**: copie `backend/supabase/functions/.env.example` para `.env`, preencha e rode `supabase secrets set --env-file supabase/functions/.env`.
7. **Funções**: `supabase functions deploy` (o `config.toml` desliga o JWT só no `mp-webhook`).
8. **Webhook no Mercado Pago**: Suas integrações → aplicação → Webhooks. URL: `https://<ref>.supabase.co/functions/v1/mp-webhook`. Eventos: *Planos e assinaturas* (`subscription_preapproval`, `subscription_authorized_payment`) e *Pagamentos*. Copie a assinatura secreta para `MP_WEBHOOK_SECRET`.
   - A documentação do MP diz que a configuração pelo painel "não está disponível para Assinaturas", mas lista esses eventos. Se a sua conta não mostrar os eventos de assinatura, o app ainda funciona: `subscription-status` consulta o MP direto (a cada consulta, no máximo uma vez por minuto quando há pendência). Nesse caso, a liberação só acontece quando o usuário abre o app.
9. **App**: em `local.properties` (não versionado):
   ```
   SUPABASE_URL=https://<ref>.supabase.co
   SUPABASE_ANON_KEY=<anon ou publishable key>
   ENTITLEMENT_PUBLIC_KEY=<base64 da chave pública>
   ```

## Cupons de criadores

No **SQL Editor** do Supabase (só o dono do projeto acessa):

```sql
-- cria/atualiza criador e código (normaliza: " fulano 10 " → FULANO10)
select * from admin_upsert_coupon('Fulano', 'fulano10');
-- com validade e limite de assinaturas confirmadas
select * from admin_upsert_coupon('Ciclana', 'CICLANA', 10, '2027-03-31', 500);
-- desativar
update coupons set active = false where code = 'FULANO10';
-- relatório: iniciadas / confirmadas / ativas agora
select * from coupon_stats order by confirmed desc;
```

O app não consegue ler a tabela de cupons nem o relatório: a RLS bloqueia, e há teste disso.

## Teste no sandbox

1. No MP, em Suas integrações → **Contas de teste**, crie um **vendedor** e um **comprador**. Use o Access Token do **vendedor de teste** (`APP_USR-...` da conta de teste, ou `TEST-...`) em `MP_ACCESS_TOKEN`, `MP_ENV=sandbox` e o e-mail do comprador em `MP_TEST_PAYER_EMAIL`.
2. **Cadastro**: crie uma conta no app, saia e entre de novo. Teste "Esqueci a senha" com o código do e-mail.
3. **Cupom**: crie `TESTE10` pelo SQL. No app, digite ` teste 10 ` e toque em "Aplicar": deve aparecer R$ 20,00 − R$ 2,00 = **R$ 18,00**. Teste também um código inexistente e um vencido.
4. **Assinatura**: "Assinar…" abre o checkout. Entre como o **comprador de teste** e pague com um **cartão de teste** do MP. Para forçar aprovação ou recusa, use o nome do titular indicado na documentação de cartões de teste (por exemplo `APRO` ou `OTHE`).
5. **Webhook**: em Suas integrações → Webhooks, use "Simular notificação" e confira que a resposta é `200`. Confira também que uma requisição sem `x-signature` responde `401`. Em `webhook_events` deve aparecer a notificação, e em `payments` a cobrança.
6. **Acesso**: com o pagamento aprovado, as abas Sessão e Testar são liberadas. Para simular vencimento, rode no SQL `update subscriptions set paid_until = now() - interval '1 minute'` e reabra o app com internet: o servidor deixa de enviar o comprovante e as abas bloqueiam na hora. Sem internet, o comprovante já salvo vale por no máximo 72 h.
7. **Cancelamento**: "Cancelar renovação" mostra a data de fim do acesso. O status muda para "Renovação cancelada" e o MP mostra a assinatura cancelada.

## Limitações conhecidas

- **Meios de pagamento**: a recorrência do MP cobra em **cartão de crédito** (e saldo MP). Pix e boleto não entram na cobrança automática mensal.
- **payer_email**: o MP pode exigir que o pagador entre no checkout com a conta do mesmo e-mail enviado. Em produção, usamos o e-mail da conta do app. Se isso der problema na sua conta, a alternativa é pedir o e-mail do Mercado Pago no checkout.
- **Reassinar com período pago**: enviamos `start_date` igual ao fim do período atual. Confira no sandbox se a sua conta aceita; se não aceitar, a primeira cobrança nova sai na hora.
- **App modificado**: num APK adulterado dá para pular a checagem local, porque os recursos pagos rodam no aparelho (OCR local). O comprovante assinado impede liberar acesso sem pagar no app original e mantém tudo no servidor, mas não protege contra engenharia reversa.
- **Relógio do aparelho**: o comprovante dura no máximo 72 h e é recusado se o relógio estiver mais de 5 min antes da emissão.

## Distribuição (v1.0.2)

- **APK final:** `./gradlew assembleRelease` com `JAVA_HOME` apontando para um JDK 17–21. A assinatura vem de `RELEASE_KEYSTORE_PROPERTIES` (no `local.properties`), arquivo fora do projeto. **Guarde uma cópia da keystore e da senha**: sem elas não dá para publicar atualizações que instalem por cima.
- **Página de download:** pasta `site/` (Vercel, projeto `intelientrega`). Para lançar uma versão: aumente `versionCode`/`versionName`, gere o APK, copie para `site/baixar/`, atualize `site/version.json`, o link e o SHA-256 em `site/index.html` e publique. O app avisa a atualização ao ler `version.json`.
- **Cortesia** (criadores, testes): `select * from admin_grant_access('email@x.com', 30, 'motivo');` e `select admin_revoke_access('email@x.com');`. Cortesia não é pagamento e não entra em `payments`.

### Links de download
- `https://intelientrega.vercel.app/baixar` (ou `/apk`): sempre baixa a versão atual.
- `https://intelientrega.vercel.app/c/CUPOM`: página com o cupom do criador em destaque.
- Versão nova: gerar o APK, copiar para `site/baixar/`, trocar o destino em `site/vercel.json`, atualizar `site/version.json`, o hash e o texto de versão em `site/index.html` e publicar.

### Atualização pelo próprio app (desde 1.0.3)
O app lê `site/version.json`. Quando `versionCode` é maior que o instalado, aparece o aviso
"Versão X disponível" com o botão **Atualizar agora**, que baixa `apkUrl` direto pelo navegador
(sem abrir o site); a pessoa toca em Abrir e em Atualizar. `apkUrl` precisa estar no mesmo
endereço https do `version.json`.

O app **não** usa `REQUEST_INSTALL_PACKAGES` de propósito: testamos e, com essa permissão, o
Google Play Protect classifica o app como "potencialmente nocivo" (sobreposição + captura de
tela + instalar apps). Sem ela, o mesmo app é classificado como seguro.

Para lançar uma versão: aumente `versionCode`/`versionName` em `app/build.gradle.kts`, gere o
APK assinado, copie para `site/baixar/intelientrega-X.apk`, atualize em `site/version.json`
(`versionCode`, `versionName`, `notes`, `apkUrl`, `sha256`), em `site/config.js` e o destino de
`/baixar` em `site/vercel.json`, e publique o site. Os APKs também ficam nas Releases do GitHub.
