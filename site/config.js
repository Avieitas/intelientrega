/*
  InteliEntrega · tudo o que muda no site fica AQUI.
  Ao lançar uma versão nova do app: troque android.arquivo, versao, tamanho e sha256
  (e o destino de /baixar em vercel.json, que aponta para o mesmo arquivo).

  Campo vazio ("") = ainda não existe. O site mostra um aviso "em breve" no lugar,
  nunca um botão quebrado.
*/
window.SITE_CONFIG = {
  android: {
    // link fixo que sempre baixa a versão atual (redirect em vercel.json)
    download: "/baixar",
    arquivo: "/baixar/intelientrega-1.0.3.apk",
    versao: "1.0.3",
    tamanho: "21 MB",
    minimo: "Android 8 ou mais novo",
    sha256: "30d94dccfcff864559ae0a7e39616e3f2328cbf0e6c7363a96c1cf3cda381cd0",
    // PREENCHER quando o app estiver na Google Play (hoje é distribuído só pelo arquivo)
    playStore: ""
  },
  // O app NÃO tem versão para iPhone. Deixe vazio enquanto não existir.
  iphone: { appStore: "" },

  site: "https://intelientrega.vercel.app",
  preco: "R$ 20",
  precoComCupom: "R$ 18",
  descontoCupom: "10%",

  // PREENCHER: e-mail de contato e suporte (aparece no rodapé e nas perguntas)
  contatoEmail: "",
  // PREENCHER (opcional): link do WhatsApp de suporte, ex.: https://wa.me/55DDDNUMERO
  contatoWhatsapp: "",
  // PREENCHER (opcional): perfil do Instagram, ex.: https://instagram.com/intelientrega
  instagram: "",

  termos: "/termos.html",
  privacidade: "/privacidade.html"
};
