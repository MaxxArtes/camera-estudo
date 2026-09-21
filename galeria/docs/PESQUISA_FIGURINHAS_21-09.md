# Pesquisa: especificação para app Android de terceiros criar e entregar figurinhas ao WhatsApp

Pesquisa realizada em 21/09/2026. Fonte primária: repositório oficial `WhatsApp/stickers`
no GitHub (branch `main`, último push confirmado em `2025-11-07T13:54:43Z`, repositório não
arquivado) — https://github.com/WhatsApp/stickers. Todos os números abaixo vêm do código-fonte
e do README desse repositório, lidos diretamente (raw) em 21/09/2026, não de memória.

## Metodologia e limitações (leia antes de confiar nos números)

- O código de validação real (`StickerPackValidator.java`) foi lido linha a linha — é a fonte
  mais confiável porque é o que de fato roda no app de exemplo, não só documentação solta.
- O FAQ oficial da Meta/WhatsApp (`faq.whatsapp.com`), citado pelo próprio README como
  referência de design, **não pôde ser acessado ao vivo**: tanto `curl` quanto o fetch de URL
  retornaram HTTP 400 ("Sorry, something went wrong") — é uma SPA protegida por proteção de
  borda que meu acesso automatizado não passa. O mesmo aconteceu com `whatsapp.com/legal`
  (Termos de Serviço), também HTTP 400.
- Para um dado pontual (margem de 16px), usei um snapshot arquivado de 2019 do Wayback Machine,
  sinalizado explicitamente onde aparece, porque não consegui uma versão atual.
- A ferramenta de busca web (WebSearch) esgotou a cota da sessão (200/200) no meio desta
  pesquisa, antes que eu pudesse varrer imprensa/blog oficial da Meta atrás de anúncios de
  produto fora do repositório GitHub (ex.: cobertura de imprensa sobre "figurinhas em vídeo").
  Isso limita minha confiança em afirmar "nada mudou em 2026" — só posso afirmar que não há
  mudança **registrada no repositório oficial** até a data do último commit (07/11/2025).
- Todo item que não encontrei confirmação está marcado explicitamente como **não encontrado**.

---

## Tabela-resumo (figurinha estática vs. animada)

| Item | Estática | Animada |
|---|---|---|
| Formato de arquivo | WebP | WebP (animado) |
| Dimensão exata | 512 x 512 px | 512 x 512 px |
| Tamanho máximo | 100 KB | 500 KB |
| Duração mínima por quadro | não se aplica | 8 ms |
| Duração total máxima | não se aplica | 10.000 ms (10 s) |
| Canal alfa / fundo transparente | obrigatório | obrigatório |
| Pode misturar estática e animada no mesmo pacote | não | não |

Fonte de cada célula: `Android/README.md` e `StickerPackValidator.java`, ambos linkados nas
seções 1 e 8 abaixo, com as constantes exatas do código.

---

## 1. Formato e limites do arquivo de figurinha

- **Formato exigido:** WebP (`[WebP format]`), tanto para estática quanto animada.
  Fonte: [Android/README.md, linha 25](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#sticker-art-and-app-requirements) —
  "Stickers must be in the WebP format."
- **Dimensão exata:** 512 x 512 pixels, para os dois tipos. Fonte: mesmo README — "Stickers
  must be exactly 512 x 512 pixels" — e confirmado no código, que rejeita qualquer imagem cuja
  `webPImage.getHeight()` ou `getWidth()` seja diferente de 512:
  [StickerPackValidator.java, linhas 38-39 e 163-168](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L38-L39)
  (`IMAGE_HEIGHT = 512`, `IMAGE_WIDTH = 512`).
- **Tamanho máximo em KB:**
  - Estática: ≤ 100 KB. Constante `STATIC_STICKER_FILE_LIMIT_KB = 100`.
  - Animada: ≤ 500 KB. Constante `ANIMATED_STICKER_FILE_LIMIT_KB = 500`.
  - Fonte: [StickerPackValidator.java, linhas 35-36](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L35-L36),
    confirmado em texto no README: "Each static sticker must be less than or equal to 100KB
    and each animated sticker must be less than or equal to 500KB."
- **Canal alfa / margem transparente:** fundo transparente é **obrigatório** — "A sticker is an
  image that has a transparent background" ([Android/README.md](https://github.com/WhatsApp/stickers/blob/main/Android/README.md), linha 13). O
  código não valida programaticamente a presença de alfa (isso fica a cargo de quem gera o
  WebP), então a exigência é documental, não uma checagem em `StickerPackValidator.java`.
  Há também uma recomendação de design (não uma trava de código) de contorno branco de 8px:
  "we recommend you add a 8px #FFFFFF stroke to the outside of each sticker" (mesma linha 24
  do README).
  - Sobre margem: o próprio README atual **não** menciona nenhum número de margem em pixels.
    Um snapshot de 2019 do FAQ oficial (Wayback Machine, arquivo consultado em
    [web.archive.org/web/20191225061042](http://web.archive.org/web/20191225061042/https://faq.whatsapp.com/general/26000226))
    recomendava "a 16-pixel margin between the actual sticker image and the edge of the
    512x512 pixel canvas" como recomendação (não requisito obrigatório). Não consegui
    confirmar se esse número de 16px segue valendo no FAQ atual (bloqueado, ver Metodologia).
    Tratar como **não confirmado para 2026**, só como pista histórica.
- **Quadros/duração (animada):** duração mínima por quadro = 8 ms
  (`ANIMATED_STICKER_FRAME_DURATION_MIN = 8`); duração total do laço ≤ 10.000 ms / 10 segundos
  (`ANIMATED_STICKER_TOTAL_DURATION_MAX = 10 * 1000`). Fonte:
  [StickerPackValidator.java, linhas 47-48 e 188-193](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L47-L48).
  Regra de UX (não numérica, documental): o primeiro quadro deve já mostrar a imagem completa
  da figurinha, porque o WhatsApp encerra a animação nesse quadro ao repetir o loop — fonte:
  [Android/README.md, linha 28](https://github.com/WhatsApp/stickers/blob/main/Android/README.md).
- **Suporte a vídeo (mp4/webm) como formato de figurinha de terceiro:** **não encontrado**. O
  validador só reconhece `WebPImage` (estático ou animado); não há nenhum caminho de código
  para outro contêiner de vídeo na API de terceiros documentada neste repositório.

## 2. Ícone de bandeja (tray icon)

- **Formato:** PNG. Fonte direta no código —
  `StickerContentProvider.getType()` devolve `"image/png"` para a URI do ícone de bandeja:
  [StickerContentProvider.java, linha 144](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerContentProvider.java#L144).
  O arquivo de exemplo no repositório também é `.png`
  ([Android/app/src/main/assets/1/tray_Cuppy.png](https://github.com/WhatsApp/stickers/tree/main/Android/app/src/main/assets/1)).
- **Dimensão:** documentada como 96 x 96 pixels, estático —
  [Android/README.md, linhas 29-32](https://github.com/WhatsApp/stickers/blob/main/Android/README.md):
  "This image should be static and 96 x 96 pixels."
  Importante (precisão de número, achado direto no código): o validador **não** trava
  exatamente 96x96 — ele aceita qualquer largura/altura **entre 24 e 512 pixels**, cada
  dimensão checada independente (não exige quadrado exato):
  `TRAY_IMAGE_DIMENSION_MIN = 24`, `TRAY_IMAGE_DIMENSION_MAX = 512`
  ([StickerPackValidator.java, linhas 45-46 e 109-114](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L45-L46)).
  Ou seja: **96x96 é a recomendação oficial**, mas a faixa **tecnicamente aceita pelo
  validador de referência é 24-512px** por lado.
- **Tamanho máximo:** 50 KB (`TRAY_IMAGE_FILE_SIZE_MAX_KB = 50`), mesma fonte, confirmado em
  texto no README ("Max file size of 50KB").

## 3. Quantidade de figurinhas por pacote (e pacotes por app)

- **Mínimo 3 e máximo 30 figurinhas por pacote**, inclusive nos dois limites. Constantes
  `STICKER_SIZE_MIN = 3`, `STICKER_SIZE_MAX = 30`; a mensagem de erro do próprio código diz
  literalmente "sticker pack sticker count should be between 3 to 30 inclusive". Fonte:
  [StickerPackValidator.java, linhas 40-41 e 118-121](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L40-L41).
  Confirmado em texto no README: "Each sticker pack must have a minimum of 3 stickers and a
  maximum of 30 stickers."
- **Bônus (não perguntado, mas correlato): pacotes por app.** Um único app pode conter de 1 a
  10 pacotes ("Your app can contain anywhere from 1 to 10 packs"), e cada pacote precisa do
  seu próprio botão/fluxo de adicionar — o README pede explicitamente para **não** criar um
  botão de "adicionar todos os pacotes de uma vez". Fonte:
  [Android/README.md, linhas 15-19](https://github.com/WhatsApp/stickers/blob/main/Android/README.md).
  Esse limite de 1-10 está descrito no README; não confirmei se há uma trava equivalente no
  código lido (não abri `ContentFileParser.java`), então trato só a regra dos 3-30 por pacote
  como verificada em duas fontes (texto + código); a de 1-10 pacotes por app está confirmada
  em uma fonte (texto do README).
- **Pacote deve ser 100% estático ou 100% animado, nunca misto** — confirmado tanto em texto
  ("Sticker packs must contain either static or animated stickers, never a mix of both") quanto
  em código, que rejeita sticker estático dentro de pacote marcado animado e vice-versa
  ([StickerPackValidator.java, linhas 169-179](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L169-L179)).

## 4. Limites de texto e campos obrigatórios do metadata (`contents.json`)

| Campo | Limite/regra | Obrigatório? | Fonte |
|---|---|---|---|
| `identifier` | até 128 caracteres; caracteres aceitos pelo código: `[\w-.,'\s]` (letras, dígitos, `_ - . , '` e espaço); não pode conter `..` | sim | [StickerPackValidator.java L60-63, L196-204](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L196-L204) |
| `name` (nome do pacote) | até 128 caracteres | sim | [StickerPackValidator.java L73-75](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L73-L75) |
| `publisher` (nome do autor/publisher) | até 128 caracteres | sim | [StickerPackValidator.java L67-69](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L67-L69) |
| `tray_image_file` | precisa existir | sim | [StickerPackValidator.java L76-78](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L76-L78) |
| `image_data_version` | string livre; incrementar a cada atualização de conteúdo | tratado como necessário no fluxo, mas não há checagem de vazio no validador lido | [Android/README.md](https://github.com/WhatsApp/stickers/blob/main/Android/README.md) |
| `animated_sticker_pack` | booleano | obrigatório em pacote animado; opcional em pacote estático | [Android/README.md](https://github.com/WhatsApp/stickers/blob/main/Android/README.md) |
| `emojis` por figurinha | 1 a 3 | sim (ver seção 6) | [StickerPackValidator.java L31, L37, L128-133](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L128-L133) |
| `accessibility_text` por figurinha | máx. 125 caracteres (estática) / máx. 255 (animada) | opcional | [StickerPackValidator.java L32-33, L144-149](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L32-L33) |
| `ios_app_store_link`, `android_play_store_link`, `publisher_website`, `privacy_policy_website`, `license_agreement_website`, `publisher_email` | precisam começar com `http`/`https`; link de loja precisa bater com o domínio certo (`play.google.com` / `itunes.apple.com`); e-mail precisa casar com `Patterns.EMAIL_ADDRESS` | opcionais | [StickerPackValidator.java L79-101](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L79-L101) |
| `avoid_cache` | **depreciado** desde a versão 2.25.9.78 do WhatsApp — o app agora sempre faz cache dos stickers, ignorando esse campo | opcional/obsoleto | [commit de 17/04/2025](https://github.com/WhatsApp/stickers/commit/e4f644cd6edd37fdddab8de15ccfaaf03896ba0d), texto atual do README |

Observação de precisão: o próprio texto do README classifica explicitamente como
**opcionais**: `ios_app_store_link`, `android_play_store_link`, `publisher_website`,
`privacy_policy_website`, `license_agreement_website`, `accessibility_text`. Tudo que não está
nessa lista é, por eliminação e pela checagem em código, obrigatório.

## 5. Mecanismo de entrega (ContentProvider, Intent, Manifest)

### ContentProvider — 4 rotas de URI

Fonte: [Android/README.md, seção "ContentProvider"](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#contentprovider)
e [StickerContentProvider.java](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerContentProvider.java).

1. `<authority>/metadata` — metadados de todos os pacotes do app.
2. `<authority>/metadata/<pack_identifier>` — metadados de um pacote específico.
3. `<authority>/stickers/<pack_identifier>` — lista de figurinhas de um pacote (nome do
   arquivo, emoji e texto de acessibilidade).
4. `<authority>/stickers_asset/<pack_identifier>/<sticker_file_name>` — o binário da
   figurinha (`AssetFileDescriptor`), tipo MIME `image/webp` (ou `image/png` no caso do
   ícone de bandeja, mesma rota).

Colunas devolvidas pelo cursor de metadados (13 colunas, nomes exatos das constantes públicas
em [StickerContentProvider.java, linhas 39-51](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerContentProvider.java#L39-L51)):
`sticker_pack_identifier`, `sticker_pack_name`, `sticker_pack_publisher`, `sticker_pack_icon`,
`android_play_store_link`, `ios_app_download_link`, `sticker_pack_publisher_email`,
`sticker_pack_publisher_website`, `sticker_pack_privacy_policy_website`,
`sticker_pack_license_agreement_website`, `image_data_version`,
`whatsapp_will_not_cache_stickers`, `animated_sticker_pack`.

Colunas do cursor de figurinhas de um pacote: `sticker_file_name`, `sticker_emoji`,
`sticker_accessibility_text` ([mesma classe, linha 221](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerContentProvider.java#L221)).

O provider precisa da permissão de leitura `com.whatsapp.sticker.READ`, `exported="true"` e
`enabled="true"` — ver trecho de manifesto abaixo.

### Intent que abre o WhatsApp para adicionar o pacote

Fonte: [Android/README.md, seção "Intent"](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#intent).

- Action: `com.whatsapp.intent.action.ENABLE_STICKER_PACK`
- Extras: `sticker_pack_id` (identifier do pacote), `sticker_pack_authority` (authority do
  ContentProvider do app) e `sticker_pack_name` (nome do pacote).
- Disparo: `startActivityForResult(intent, 200)`; o usuário precisa confirmar explicitamente
  num alerta apresentado pelo próprio WhatsApp — não é possível adicionar em silêncio.

### AndroidManifest — o que precisa estar declarado

Ver trecho literal na seção dedicada abaixo. Resumo: 1 `<provider>` apontando pro
ContentProvider do app, com `android:readPermission="com.whatsapp.sticker.READ"`,
`android:exported="true"`, `android:enabled="true"`; e, para Android 11+ (API 30+, regra de
**package visibility**), um bloco `<queries>` declarando `com.whatsapp` e `com.whatsapp.w4b`
— sem isso, o app não consegue nem checar se o WhatsApp está instalado nem consultar o
provider de whitelist. Fonte: [AndroidManifest.xml, linhas 48-53](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/AndroidManifest.xml#L48-L53),
que cita a própria documentação do Android sobre o assunto:
https://developer.android.com/training/basics/intents/package-visibility#package-name

### Checar se o pacote já foi adicionado (opcional, mas documentado)

- Authority do provider de whitelist do WhatsApp consumidor:
  `com.whatsapp.provider.sticker_whitelist_check`.
- Authority do provider de whitelist do WhatsApp Business:
  `com.whatsapp.w4b.provider.sticker_whitelist_check`.
- URI de consulta: `content://<authority>/is_whitelisted?authority=<authority do seu
  provider>&identifier=<identifier do pacote>`.
- Coluna de resposta: `result`, valores `0` (não adicionado) ou `1` (adicionado); `null`
  significa consulta inválida ou versão do WhatsApp antiga demais.
- Fonte: [Android/README.md, seção "Check if pack is added"](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#check-if-pack-is-added-optional)
  e [WhitelistCheck.java](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/WhitelistCheck.java).

## 6. Emoji associado a cada figurinha

- **Obrigatório, não opcional.** O código exige pelo menos 1 emoji por figurinha
  (`EMOJI_MIN_LIMIT = 1`) e lança exceção se a lista vier vazia — mensagem literal do erro:
  "To provide best user experience, please associate at least 1 emoji to this sticker".
  O README também rotula o campo como "`emojis` (required)".
- **Máximo de 3 emojis por figurinha** (`EMOJI_MAX_LIMIT = 3`).
- Fonte: [StickerPackValidator.java, linhas 31, 37 e 128-133](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java#L128-L133);
  texto: [Android/README.md](https://github.com/WhatsApp/stickers/blob/main/Android/README.md) — "Add up to a maximum of three emoji for each sticker file."
- Existe uma lista curada de emojis sugeridos por categoria (Love, Happy, Sad, Angry, Greet,
  Celebrate) para ajudar a busca de figurinhas dentro do WhatsApp — fonte:
  [wiki Tag-your-stickers-with-Emojis](https://github.com/WhatsApp/stickers/wiki/Tag-your-stickers-with-Emojis).
  Isso é uma recomendação de curadoria, não uma restrição técnica adicional além do 1-3.

## 7. Restrições de publicação/uso

- **Regra geral, citada de forma idêntica em duas fontes atuais (README raiz e README
  Android):** "Stickers on WhatsApp must be legal, authorized, and acceptable." — remetendo
  aos Termos de Serviço do WhatsApp para "acceptable use":
  https://www.whatsapp.com/legal/#terms-of-service (não consegui abrir essa página ao vivo,
  ver Metodologia — a citação é sobre o texto do README apontar pra ela, não sobre o conteúdo
  da própria página de termos).
- **Regra de marca:** o nome do app não pode conter "WhatsApp" (nem no nome do app nem no
  título da ficha da Play Store); é permitido mencionar "WhatsApp" na descrição da ficha, e é
  recomendado incluir a palavra-chave `WAStickerApps` na descrição para aparecer nas buscas
  que o próprio WhatsApp faz na Play Store. Fonte:
  [Android/README.md, seção "Submit your app"](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#submit-your-app)
  e [README raiz, seção "Brand guidelines"](https://github.com/WhatsApp/stickers/blob/main/README.md#brand-guidelines).
- **Sobre iOS (contexto, não pedido, mas relevante se o mesmo pacote for multiplataforma):** o
  README avisa que a Apple tem revisão rígida e recomenda que apps iOS de figurinha tenham
  mais funcionalidade além de só exportar pra WhatsApp, para não ser rejeitado — não se aplica
  ao app Android em si.
- **Regra específica sobre figurinha feita a partir de foto de pessoa real:** **não
  encontrado**. Nem o repositório GitHub nem o trecho do FAQ que consegui recuperar (snapshot
  de 2019) mencionam qualquer regra específica sobre retrato/foto de pessoa real, direito de
  imagem ou biometria. A única exigência publicada é a genérica "legal, authorized and
  acceptable" acima, que por natureza cobriria uso indevido de imagem de terceiros sem
  autorização, mas isso é inferência minha, não uma regra publicada nomeando esse caso — não
  tratar como confirmado.
- **Revisão/aprovação de conteúdo pelo WhatsApp antes de publicar um pacote:** **não
  encontrado** nenhum processo de submissão/moderação centralizado da Meta para pacotes de
  terceiros — o fluxo descrito é: app entra na Play Store pelas regras normais do Google, e
  o próprio usuário final decide se adiciona o pacote ao abrir o app e confirmar o alerta do
  WhatsApp. Não há um "app review" da Meta específico para conteúdo de figurinha descrito
  nestas fontes.

## 8. Mudanças recentes (o que de fato mudou, com data e commit)

Baseado no histórico de commits do próprio repositório oficial (não em suposição):

- **2021-02-06 / rollout global em 24/03/2021** — suporte a figurinhas animadas no Android
  (PR "Support animated stickers on Android", #737). Rollout global anunciado na wiki do
  repositório para builds Android ≥ 2.21.3.19 e iOS ≥ 2.21.31.2. Fontes:
  [commit 3c039d7](https://github.com/WhatsApp/stickers/commit/3c039d739708e24c12b936012eefc8fc22eb0566),
  [wiki Animated-Stickers](https://github.com/WhatsApp/stickers/wiki/Animated-Stickers).
  (Fora da janela 2024-2026 pedida, citado só como base histórica do recurso "animado".)
- **2024 (commits entre 30/08/2024 e 05/12/2024, branch "A11YText")** — introdução do campo
  `accessibility_text` (texto de acessibilidade por figurinha), com os limites de 125
  caracteres (estática) e 255 (animada) descritos na seção 4. Fonte:
  [commit de merge 6291908, 25/11/2024](https://github.com/WhatsApp/stickers/commit/629190879fa8514e02e0945563c4c504107bd0e4).
- **2025-04-17** — campo `avoid_cache` marcado como depreciado: "As of version 2.25.9.78
  WhatsApp will ignore this flag and always cache stickers." Fonte:
  [commit e4f644c](https://github.com/WhatsApp/stickers/commit/e4f644cd6edd37fdddab8de15ccfaaf03896ba0d).
- **2025-11-07** — último commit do repositório até a data desta pesquisa: "Add copyright
  headers to all source files" — mudança administrativa/legal, **sem impacto de
  especificação técnica** (confirmei lendo o diff via metadata do commit: só cabeçalhos de
  licença). Fonte: [commit 06144a1](https://github.com/WhatsApp/stickers/commit/06144a1f6077bbb346e1230032fc4e0bce996d03).
- **2026** — **não encontrado**. Não há nenhum commit em 2026 no repositório oficial até
  21/09/2026 (último push continua sendo 07/11/2025, confirmado via API do GitHub). Não
  consegui varrer imprensa/blog da Meta em busca de um anúncio de produto fora do repositório
  (ex.: "figurinhas em vídeo") porque a cota de busca web da sessão se esgotou no meio desta
  pesquisa (ver Metodologia) — portanto isto é "nada encontrado no repositório oficial", não
  "confirmado que nada mudou em 2026" de forma geral.
- **Suporte a figurinha em vídeo (formato de vídeo, não WebP animado) na API de terceiros:**
  **não encontrado** em nenhuma fonte consultada.

---

## Nome exato dos pacotes usados na verificação de instalação

- WhatsApp (consumidor): `com.whatsapp`
- WhatsApp Business: `com.whatsapp.w4b`

Fonte tripla — mesmo valor em três lugares independentes do repositório oficial:
[Android/README.md](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#check-if-pack-is-added-optional)
("`com.whatsapp.provider.sticker_whitelist_check` for the WhatsApp consumer app;
`com.whatsapp.w4b.provider.sticker_whitelist_check` for the WhatsApp Business app"),
[WhitelistCheck.java, linhas 25-26](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/WhitelistCheck.java#L25-L26)
(`CONSUMER_WHATSAPP_PACKAGE_NAME = "com.whatsapp"`, `SMB_WHATSAPP_PACKAGE_NAME =
"com.whatsapp.w4b"`) e
[AndroidManifest.xml, linhas 50-53](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/AndroidManifest.xml#L50-L53).

## Trecho de manifesto oficial (provider + queries)

Copiado literalmente de
[Android/app/src/main/AndroidManifest.xml](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/AndroidManifest.xml)
(repositório oficial, branch `main`, lido em 21/09/2026):

```xml
<provider
    android:name=".StickerContentProvider"
    android:authorities="${contentProviderAuthority}"
    android:enabled="true"
    android:exported="true"
    android:readPermission="com.whatsapp.sticker.READ" />
</application>

<!-- to be able to query the whitelist status in WhatsApp
 https://developer.android.com/training/basics/intents/package-visibility#package-name -->
<queries>
    <package android:name="com.whatsapp" />
    <package android:name="com.whatsapp.w4b" />
</queries>
```

`${contentProviderAuthority}` é uma variável de build (`BuildConfig.CONTENT_PROVIDER_AUTHORITY`
no código) que precisa começar com o nome do pacote do próprio app — o `onCreate()` do
`StickerContentProvider` lança exceção em tempo de execução se não começar:
"your authority (...) for the content provider should start with your package name" (fonte:
[StickerContentProvider.java, linhas 81-84](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerContentProvider.java#L81-L84)).

---

## Lista consolidada de fontes

- Repositório oficial (raiz): https://github.com/WhatsApp/stickers
- README Android: https://github.com/WhatsApp/stickers/blob/main/Android/README.md
- AndroidManifest.xml de exemplo: https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/AndroidManifest.xml
- StickerPackValidator.java: https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerPackValidator.java
- StickerContentProvider.java: https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerContentProvider.java
- WhitelistCheck.java: https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/WhitelistCheck.java
- contents.json de exemplo: https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/assets/contents.json
- Wiki — anúncio de figurinhas animadas: https://github.com/WhatsApp/stickers/wiki/Animated-Stickers
- Wiki — lista de emojis por categoria: https://github.com/WhatsApp/stickers/wiki/Tag-your-stickers-with-Emojis
- Commit — suporte a animadas (06/02/2021): https://github.com/WhatsApp/stickers/commit/3c039d739708e24c12b936012eefc8fc22eb0566
- Commit — accessibility_text (25/11/2024): https://github.com/WhatsApp/stickers/commit/629190879fa8514e02e0945563c4c504107bd0e4
- Commit — avoid_cache depreciado (17/04/2025): https://github.com/WhatsApp/stickers/commit/e4f644cd6edd37fdddab8de15ccfaaf03896ba0d
- Commit — cabeçalhos de copyright, último push (07/11/2025): https://github.com/WhatsApp/stickers/commit/06144a1f6077bbb346e1230032fc4e0bce996d03
- Metadados do repositório (branch padrão, data do último push): https://api.github.com/repos/WhatsApp/stickers
- FAQ oficial referenciado pelo README (não acessível ao vivo no momento desta pesquisa, HTTP 400): https://faq.whatsapp.com/general/26000226
- FAQ arquivado (Wayback Machine, snapshot de 25/12/2019, usado só para a menção de margem de 16px): http://web.archive.org/web/20191225061042/https://faq.whatsapp.com/general/26000226
- Termos de Serviço do WhatsApp, referenciado pelo README (não acessível ao vivo, HTTP 400): https://www.whatsapp.com/legal/#terms-of-service
- Documentação Android sobre package visibility, citada no próprio manifesto oficial: https://developer.android.com/training/basics/intents/package-visibility#package-name

## Itens marcados "não encontrado" (resumo)

- Regra específica da Meta/WhatsApp sobre figurinha feita de foto de pessoa real.
- Processo formal de revisão/aprovação de conteúdo de pacotes pela Meta (além da revisão
  normal da Play Store).
- Qualquer mudança de especificação datada de 2026 no repositório oficial.
- Suporte a formato de vídeo (não WebP) para figurinha de terceiro.
- Confirmação de que a margem de 16px (achada só num FAQ arquivado de 2019) ainda vale hoje.
- Conteúdo atual do FAQ oficial e dos Termos de Serviço (páginas bloqueadas para acesso
  automatizado no momento desta pesquisa).
