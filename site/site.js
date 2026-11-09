/* Aplica config.js na página. Sem JavaScript, os valores escritos no HTML continuam valendo. */
(function () {
  var C = window.SITE_CONFIG || {};
  var get = function (path) {
    return path.split(".").reduce(function (o, k) { return o == null ? undefined : o[k]; }, C);
  };

  // Um link sem destino vira um aviso visível, nunca um botão quebrado.
  function markMissing(el) {
    var span = document.createElement("span");
    span.className = (el.className || "") + " is-missing";
    span.textContent = el.getAttribute("data-missing") || "Em breve";
    span.setAttribute("data-config-missing", el.getAttribute("data-cfg-href") || "");
    el.replaceWith(span);
  }

  document.querySelectorAll("[data-cfg-href]").forEach(function (el) {
    var v = get(el.getAttribute("data-cfg-href"));
    var prefix = el.getAttribute("data-cfg-prefix") || "";
    if (v) el.setAttribute("href", prefix + v);
    else if (el.hasAttribute("data-optional")) (el.closest("[data-optional-wrap]") || el).remove();
    else markMissing(el);
  });

  document.querySelectorAll("[data-cfg-text]").forEach(function (el) {
    var v = get(el.getAttribute("data-cfg-text"));
    if (v) el.textContent = v;
  });

  // Link de criador: /c/CUPOM ou ?cupom=CUPOM
  var m = location.pathname.match(/^\/c\/([^\/?#]+)/);
  var raw = m ? decodeURIComponent(m[1]) : new URLSearchParams(location.search).get("cupom");
  var code = raw ? raw.trim().toUpperCase() : "";
  if (!/^[A-Z0-9_-]{3,24}$/.test(code)) code = "";

  if (code) {
    document.querySelectorAll("[data-cupom-code]").forEach(function (el) { el.textContent = code; });
    document.querySelectorAll("[data-cupom-only]").forEach(function (el) { el.hidden = false; });
    document.querySelectorAll("[data-sem-cupom]").forEach(function (el) { el.hidden = true; });
    var btn = document.querySelector("[data-copy-cupom]");
    if (btn) btn.addEventListener("click", function () {
      var done = function () { btn.textContent = "Cupom copiado"; setTimeout(function () { btn.textContent = "Copiar o cupom"; }, 1800); };
      if (navigator.clipboard) navigator.clipboard.writeText(code).then(done, function () { window.prompt("Copie o cupom:", code); });
      else window.prompt("Copie o cupom:", code);
    });
  }

  var link = (C.site || location.origin) + (code ? "/c/" + code : "");
  var wa = document.querySelector("[data-share-wa]");
  if (wa) {
    var msg = "Olha esse app que faz a conta da corrida do 99 e do iFood: " + link +
      (code ? " (com o cupom " + code + " sai " + (C.precoComCupom || "R$ 18") + " por mês)" : "");
    wa.href = "https://wa.me/?text=" + encodeURIComponent(msg);
  }
})();
