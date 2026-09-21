# Pesquisa: serviços para busca e geração de imagem de fundo (editor de fotos Android)

**Data:** 21 de setembro de 2026
**Escopo:** app Android pessoal, fora da Play Store, um usuário hoje (pode virar produto pago), sem servidor de API próprio — qualquer chave só pode ir embutida no APK (portanto extraível), precisa funcionar a partir do Brasil, imagem vira fundo de uma foto pessoal (pessoa recortada).

## Metodologia e limitação a declarar

A pesquisa rodou em 4 sub-agentes paralelos (2 para busca, 2 para geração), mais uma rodada complementar feita diretamente. O orçamento de busca web da sessão (WebSearch, compartilhado entre todos) esgotou (200/200) muito cedo — praticamente antes da primeira pesquisa terminar. Por isso, quase toda a informação abaixo veio de **WebFetch direto em documentação oficial, página de preços e termos de uso já conhecidos**, e de **algumas chamadas HTTP ao vivo contra a própria API** (indicadas como "resposta ao vivo"), não de busca livre na web. Duas consequências valem registrar:

1. Onde a documentação oficial não publica um número ou cláusula, está escrito **"não encontrado"** — significa que não consegui confirmar, não que a informação não exista.
2. `api.openverse.org` e `openverse.org` bloquearam toda tentativa de acesso automatizado (desafio anti-bot Cloudflare "Just a moment...", inclusive via `curl` direto com User-Agent de navegador). `pollinations.ai/terms` e o Wayback Machine (`web.archive.org`) também não retornaram conteúdo legível (página renderizada em JavaScript / archive.org indisponível neste ambiente de pesquisa). Isso **não prova** que o app Android real vá ser bloqueado do mesmo jeito — um cliente HTTP de app não é necessariamente tratado como bot —, mas não pude confirmar aqui.

---

## Parte A — Busca de imagem de fundo por palavra-chave

### Tabela comparativa

| Serviço | Chave obrigatória | Limite grátis | Licença da imagem | Atribuição visível | Uso comercial | Endpoint | Formato |
|---|---|---|---|---|---|---|---|
| **Openverse** | Não (OAuth2 opcional, tier maior) | não encontrado | Varia por imagem (CC0, BY, BY-SA, PDM...) | Depende da imagem | Depende da licença (evitar NC/ND) | `api.openverse.org/v1/images/?q=` | não encontrado |
| **Pexels** | Sim | 200 req/hora · 20.000/mês | Pexels License (própria) | Não exigida | Sim, com restrição de "wallpaper app" — ver nota | `api.pexels.com/v1/search?query=` | JSON |
| **Unsplash** | Sim (Access Key) | Demo 50/hora · Produção 1.000/hora | Unsplash License (própria) | Não exigida | Sim (2 restrições — ver nota) | `api.unsplash.com/search/photos?query=` | JSON |
| **Pixabay** | Sim | 100 req/60s (~6.000/h) | CC0 (pré-2019) / Pixabay Content License | Não exigida | Sim (2 restrições — ver nota) | `pixabay.com/api/?key=&q=` | JSON |
| **Wikimedia Commons** | Não | 10/min (IP anônimo) até 2.000/min (autenticado) | Varia por arquivo | Depende do arquivo (`AttributionRequired`) | Depende do arquivo | `commons.wikimedia.org/w/api.php?action=query&list=search` | JSON |
| **Flickr** | Sim | 3.600 req/hora por key | Varia por foto | Depende da licença da foto | Restrito — exige key/permissão comercial | `flickr.com/services/rest/?method=flickr.photos.search` | XML (padrão) / JSON |

### Notas e fontes

**Openverse**
- Chave: busca funciona sem autenticação; OAuth2 (client id/secret) opcional dá tier maior (fonte: https://docs.openverse.org/api/reference/authentication_and_throttling.html).
- Limite: a doc descreve 3 tiers (standard/enhanced/exempt) qualitativamente ("limite um pouco maior", "rajadas significativamente maiores") sem publicar números — confirmado em duas leituras independentes da mesma página, 21/09/2026 (fonte: https://docs.openverse.org/api/reference/authentication_and_throttling.html).
- Licença: varia por imagem — agrega CC0/domínio público/CC BY/CC BY-SA de várias fontes, com filtro de licença na própria busca (fonte: https://docs.openverse.org/api/reference/search_algorithm.html).
- Atribuição: depende da licença de cada imagem (BY/BY-SA exigem, CC0/PDM não) — o texto exato não pôde ser lido porque `openverse.org/terms-of-service` retornou bloqueio anti-bot em todas as tentativas (curl direto incluso), 21/09/2026.
- Uso comercial: depende da licença de cada imagem — variantes NC não permitem, ND não permite modificar (o app precisa compor a imagem, então ND também deveria ser evitado); filtrar na busca (fonte: https://docs.openverse.org/api/reference/search_algorithm.html).
- Endpoint/formato: `https://api.openverse.org/v1/images/?q=praia` — não confirmado nesta pesquisa; toda tentativa (WebFetch e `curl` com User-Agent de navegador) recebeu a página de desafio Cloudflare "Just a moment...", HTTP 403, 21/09/2026.
- Filtro de conteúdo: sim — por padrão a busca exclui itens marcados "mature" (fonte: https://docs.openverse.org/api/reference/search_algorithm.html).
- ToS sobre o caso do app: não encontrado (página de termos bloqueada).
- Data: 21/09/2026.

**Pexels**
- Chave: obrigatória (fonte: https://www.pexels.com/api/documentation/).
- Limite: 200 requisições/hora e 20.000/mês (fonte: https://www.pexels.com/api/documentation/).
- Licença: "Pexels License", própria, não é Creative Commons (fonte: https://www.pexels.com/license/).
- Atribuição: "Attribution is not required... appreciated" (fonte: https://www.pexels.com/license/).
- Uso comercial: permitido, mas com restrição textual que toca direto no que o app faz: **"replicate core functionality of Pexels (including making Pexels content available as a wallpaper app)"** é proibido (fonte: https://www.pexels.com/license/). Também proíbe vender cópia não alterada, usar como marca/logo, redistribuir em outro banco de imagens, e implicar endosso.
- Endpoint: `GET https://api.pexels.com/v1/search?query=praia&page=1&per_page=15` — JSON, `photos[]` com `id`, `width`, `height`, `photographer`, `src.{original,large2x,large,medium,small,portrait,landscape,tiny}` (fonte: https://www.pexels.com/api/documentation/).
- Filtro de conteúdo: não encontrado.
- ToS sobre o caso do app: a cláusula de "wallpaper app" acima é a mais relevante encontrada em toda a pesquisa (ver seção "riscos"); nenhuma proibição encontrada sobre embutir a key num app cliente ou chamar direto do device (fontes: https://www.pexels.com/api/documentation/, https://www.pexels.com/terms-of-service/).
- Data: 21/09/2026.

**Unsplash**
- Chave: Access Key/Client-ID obrigatória, gratuita ao registrar o app (fonte: https://unsplash.com/documentation).
- Limite: modo Demo 50 req/hora; aprovado para Produção 1.000 req/hora (fonte: https://unsplash.com/documentation).
- Licença: "Unsplash License" (fonte: https://unsplash.com/license).
- Atribuição: "No permission needed (though attribution is appreciated!)" (fonte: https://unsplash.com/license).
- Uso comercial: permitido, sem distinguir comercial de não-comercial; só proíbe revender sem modificação significativa e compilar fotos do Unsplash para montar um serviço concorrente (fonte: https://unsplash.com/license).
- Endpoint: `GET https://api.unsplash.com/search/photos?query=praia&page=1&per_page=10&client_id=SUA_KEY`; a diretriz de uso pede disparar `GET /photos/:id/download` a cada vez que uma foto é efetivamente usada, para contabilizar o download (fonte: https://unsplash.com/documentation).
- Filtro de conteúdo: não encontrado.
- ToS sobre o caso do app: não há proibição explícita de embutir a Access Key num app cliente distribuído nem de chamar a API direto do device — os termos só proíbem compartilhar "Credentials" com terceiros (uma key embutida num APK público é tecnicamente extraível por qualquer um, o que é uma zona cinzenta frente a essa cláusula) e mencionam "Dynamic Client Registration" como recomendado (não obrigatório) para apps descentralizados onde não dá para usar uma única key fixa (fonte: https://unsplash.com/api-terms). A Unsplash reserva o direito de cobrar uma "technology fee" de quem usa a API pesadamente.
- Data: 21/09/2026.

**Pixabay**
- Chave: obrigatória (fonte: https://pixabay.com/api/docs/).
- Limite: 100 requisições por 60 segundos (~6.000/hora), contado por key e não por IP (fonte: https://pixabay.com/api/docs/).
- Licença: conteúdo publicado antes de 9/jan/2019 é CC0; o resto segue a "Pixabay Content License", licença própria sem nome formal CC (fonte: https://pixabay.com/service/terms/).
- Atribuição: "Use Content without having to attribute the author" — não obrigatória (fonte: https://pixabay.com/service/license/, https://pixabay.com/service/terms/).
- Uso comercial: permitido, com 2 restrições — não vender/redistribuir o conteúdo "standalone" (sem edição/composição) e não usar conteúdo com marca reconhecível para fins comerciais ligados a bens/serviços (fonte: https://pixabay.com/service/terms/). Como o app compõe a foto da pessoa sobre o fundo (obra derivada, não redistribui o arquivo original isolado), a restrição de "standalone" não deveria se aplicar — isso é interpretação minha sobre o texto, não confirmação literal da Pixabay para o caso específico.
- Endpoint: `GET https://pixabay.com/api/?key={KEY}&q=praia` — JSON, campos `previewURL` (150px), `webformatURL` (640px), `largeImageURL` (1280px), `fullHDURL`/`imageURL` (acesso restrito) (fonte: https://pixabay.com/api/docs/).
- Filtro de conteúdo: sim, parâmetro `safesearch=true|false` (fonte: https://pixabay.com/api/docs/).
- ToS sobre o caso do app: nenhuma cláusula encontrada proibindo embutir a key num app cliente distribuído ou chamar direto do device (fontes: https://pixabay.com/api/docs/, https://pixabay.com/service/terms/).
- Data: 21/09/2026.

**Wikimedia Commons**
- Chave: não é exigida — só um cabeçalho `User-Agent` identificável, conforme a política de etiqueta (fonte: https://www.mediawiki.org/wiki/Wikimedia_APIs, https://www.mediawiki.org/wiki/API:Etiquette).
- Limite: por minuto — IP anônimo 10; navegador não-autenticado 200; autenticado novato 200; autenticado estabelecido 2.000 (fonte: https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits).
- Licença: não é uma licença única — cada arquivo carrega a sua (CC0, CC BY, CC BY-SA, domínio público etc.), lida via `iiprop=extmetadata`, campos `LicenseShortName`, `UsageTerms`, `AttributionRequired` (fonte: https://www.mediawiki.org/wiki/API:Imageinfo).
- Atribuição: depende do arquivo — o app precisa checar `AttributionRequired`/`LicenseShortName` de cada resultado antes de usar, não dá para assumir uma regra fixa para o serviço inteiro (fonte: https://www.mediawiki.org/wiki/API:Imageinfo; mecânica geral de atribuição em https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use).
- Uso comercial: depende da licença de cada arquivo; a maior parte do acervo (CC BY/CC BY-SA/CC0) permite, mas não é garantido para 100% — precisa filtrar por resultado (fonte: https://commons.wikimedia.org/wiki/Commons:API, https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use).
- Endpoint: busca com `action=query&list=search`, ex. `https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=praia&format=json`; para pegar URL da imagem e a licença é preciso uma segunda chamada com `prop=imageinfo` e `iiprop=extmetadata` mais `url` sobre o título retornado (fontes: https://www.mediawiki.org/wiki/API:Search, https://www.mediawiki.org/wiki/API:Imageinfo, https://commons.wikimedia.org/wiki/Commons:API). Um exemplo pronto de busca já filtrada só por arquivos (namespace 6) não foi encontrado nesta pesquisa.
- Filtro de conteúdo: não encontrado — nenhuma página oficial consultada menciona filtro de conteúdo adulto/NSFW na Action API de busca.
- ToS sobre o caso do app: não encontrada proibição de chamar a API direto do device (não há key para embutir) — os Termos de Uso remetem só a políticas de uso responsável (fonte: https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use).
- Data: 21/09/2026.

**Flickr**
- Chave: obrigatória em todo método, listada como REQUIRED (fonte: https://www.flickr.com/services/api/misc.api_keys.html, https://www.flickr.com/services/api/flickr.photos.search.html).
- Limite: **3.600 requisições por hora**, agregado por key — "If your application stays under 3600 queries per hour across the whole key... you'll be fine" (fonte: https://www.flickr.com/services/developer/api/, verificado 21/09/2026).
- Licença: varia por foto — o autor escolhe entre "todos os direitos reservados", várias Creative Commons ou domínio público; o nome exato do campo de resposta que traz a licença por foto não foi confirmado nesta pesquisa (não encontrado).
- Atribuição: depende da licença escolhida pelo autor da foto (CC BY/BY-SA exigem, CC0/PD não) — campo exato não encontrado.
- Uso comercial: **restrito por padrão** — "If the primary purpose of your application is to derive revenue, it is considered a commercial application", e isso exige solicitar permissão/key comercial separada; para projeto pessoal/gratuito não é preciso (fonte: https://www.flickr.com/services/api/misc.api_keys.html). Se o app virar produto pago, a key pessoal atual deixa de ser suficiente.
- Endpoint: `https://www.flickr.com/services/rest/?method=flickr.photos.search&api_key=KEY&text=praia&license=1,2,4&format=json` — resposta em **XML por padrão**, JSON opcional via `format=json`; URLs de imagem via parâmetro `extras` (`url_s`, `url_m`, `url_l`, `url_o`) (fonte: https://www.flickr.com/services/api/flickr.photos.search.html).
- Filtro de conteúdo: sim — parâmetro `safe_search` (1 seguro, 2 moderado, 3 restrito); chamadas não-autenticadas só veem conteúdo seguro por padrão (fonte: https://www.flickr.com/services/api/flickr.photos.search.html).
- ToS sobre o caso do app: uso comercial exige key/permissão separada (ver acima, achado mais concreto); cache de fotos de usuário é restrito a "período razoável para prestar o serviço", com remoção em até 24h se o autor pedir, e máximo de 30 fotos de usuário por página (fonte: https://www.flickr.com/services/api/tos/). Nenhuma proibição encontrada especificamente sobre embutir a key num app cliente ou chamar direto do device.
- Data: 21/09/2026.

---

## Parte B — Geração de imagem de fundo por IA a partir de texto

### Tabela comparativa

| Serviço | Chave obrigatória | Limite grátis | Direitos sobre a saída | Atribuição | Uso comercial | Endpoint | Formato |
|---|---|---|---|---|---|---|---|
| **Pollinations.ai** | Não | Anônimo 1 req/15s · cadastrado 1 req/5s (marca d'água desde mar/2025) | não encontrado | não encontrado | não encontrado | `image.pollinations.ai/prompt/{prompt}` (GET) | Imagem binária direta |
| **Cloudflare Workers AI** | Sim | 10.000 neurons/dia (~2.083 img 512×512 no flux-1-schnell) | Cloudflare: usuário retém direitos; modelo terceiro não verificado | não encontrado | não encontrado (depende do modelo) | `POST .../ai/run/@cf/black-forest-labs/flux-1-schnell` | JSON, imagem base64 |
| **Together.ai** | Sim | não encontrado valor fixo; sem crédito grátis confirmado | Favorável — usuário é dono exclusivo do Output | não encontrado | Sim (só veda concorrência direta) | `POST api.together.xyz/v1/images/generations` | JSON, `url` ou `b64_json` |
| **fal.ai** | Sim | Nenhum (100% pré-pago) | Fraco — sem garantia de IP | não encontrado | não encontrado | `fal-ai/flux/schnell` (client/`fal.run`) | JSON, `images[].url` |
| **Replicate** | Sim | não encontrado (100% pay-as-you-go) | Favorável — direitos comerciais sobre o Output | não encontrado | Sim (sujeito a termos por modelo) | `POST api.replicate.com/v1/predictions` | JSON, `output` (URL) |
| **Hugging Face Inference** | Sim | US$0,10/mês (Free) — baixo demais na prática | não encontrado | não encontrado | não encontrado | SDK `text_to_image()` | Imagem binária |
| **Google AI Studio (Gemini/Imagen)** | Sim | **Nenhum** (imagem fora do free tier) | Google não reivindica; watermark SynthID obrigatório | Não (watermark técnico) | Moot — sem free tier | `POST generativelanguage.googleapis.com/v1beta/interactions` | JSON, base64 |
| **Segmind** | não encontrado | US$10 crédito único (trial) | não encontrado | não encontrado | não encontrado | não encontrado | não encontrado |
| **Stability AI** | Sim (confirmado ao vivo) | não encontrado | não encontrado | não encontrado | não encontrado | `api.stability.ai/...` | não encontrado |

### Notas e fontes

**Pollinations.ai**
- Chave: não exigida — "no signup required to get started!" (fonte: https://github.com/pollinations/pollinations/blob/master/APIDOCS.md).
- Limite: anônimo 1 requisição a cada 15s; com cadastro gratuito (tier "Seed") 1 a cada 5s. Desde 31/03/2025 imagens do tier grátis podem sair com marca d'água (fonte: mesma).
- Direitos sobre a saída / atribuição / uso comercial: **não encontrado**. A doc só cobre a licença do código da API em si ("MIT License"), não das imagens geradas. `pollinations.ai/terms` não retornou conteúdo de texto em nenhuma das 3 tentativas desta pesquisa (WebFetch direto, tentativa via Wayback Machine — indisponível neste ambiente —, e nova tentativa direta) porque é uma página renderizada em JavaScript. **Recomendação prática: abrir essa página num navegador de verdade antes de confiar o produto nela.**
- Endpoint: `GET https://image.pollinations.ai/prompt/{prompt}` — resposta é a imagem binária direta (ex. `curl -o fundo.jpg`) (fonte: https://github.com/pollinations/pollinations/blob/master/APIDOCS.md).
- Filtro de conteúdo: sim, parâmetro `safe=true` ativa "strict NSFW filtering" (fonte: mesma).
- Data: 21/09/2026 (a doc cita a mudança de 31/03/2025 como referência própria).

**Cloudflare Workers AI**
- Chave: API Token + Account ID obrigatórios (fonte: https://developers.cloudflare.com/workers-ai/get-started/rest-api/).
- Limite: 10.000 "Neurons" por dia, recorrente, resetando à meia-noite UTC — "Our free allocation allows anyone to use a total of 10,000 Neurons per day at no charge" (fonte: https://developers.cloudflare.com/workers-ai/platform/pricing/, página datada 17/09/2026). O modelo `flux-1-schnell` custa 4,80 neurons por tile 512×512 (9,60 por step), o que dá **~2.083 imagens de 512×512 por dia** de graça; modelos maiores custam muito mais (`leonardo/lucid-origin` 636/tile, `leonardo/phoenix-1.0` 530/tile) (fonte: mesma).
- Direitos sobre a saída: os termos gerais da Cloudflare dizem "you retain all applicable intellectual property or other proprietary rights in Inputs and Outputs" (Seção 1), mas ressalvam que modelos de terceiro (aqui, FLUX.1-schnell é da Black Forest Labs) têm termos próprios adicionais, não verificados nesta pesquisa (fonte: https://www.cloudflare.com/service-specific-terms-developer-platform/; termos da Black Forest Labs em https://bfl.ai/legal/terms-of-service não verificados).
- Atribuição / uso comercial: não encontrado conclusivamente (depende dos termos do modelo de terceiro, não verificados).
- Endpoint: `POST https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/run/@cf/black-forest-labs/flux-1-schnell`, corpo `{"prompt": "..."}`, resposta JSON com campo `image` em base64 (fonte: https://developers.cloudflare.com/workers-ai/models/flux-1-schnell/).
- Filtro de conteúdo: não encontrado na página do modelo.
- ToS sobre o caso do app: não encontrada proibição de embutir o token num app cliente distribuído nem de chamar direto do device (fonte: https://www.cloudflare.com/service-specific-terms-developer-platform/).
- Data: página de preços datada 17/09/2026; verificação 21/09/2026.

**Together.ai**
- Chave: obrigatória — confirmado ao vivo: `POST https://api.together.xyz/v1/images/generations` sem header `Authorization` devolve `401` com `"Missing API key. You need to provide your API key in an Authorization header using Bearer auth"` (fonte: resposta ao vivo do próprio endpoint, 21/09/2026).
- Limite grátis: não encontrado um valor fixo — a doc de rate limit diz que os limites são "dinâmicos por modelo, ajustados pelo uso recente" (fonte: https://docs.together.ai/docs/rate-limits); nem a doc de geração de imagem nem a página de preços mencionam crédito de boas-vindas (fontes: https://docs.together.ai/docs/inference/images/overview.md, https://www.together.ai/pricing). **Achado à parte, não confirmado:** a doc de parâmetros cita que o filtro de segurança roda "on every model except FLUX Schnell Free and FLUX Pro" — sugerindo que existe um modelo literalmente chamado "FLUX Schnell Free", mas o limite/custo exato desse modelo específico não foi verificado nesta pesquisa (fonte: https://docs.together.ai/docs/inference/images/parameters.md). Vale investigar depois.
- Direitos sobre a saída: favorável — "As between you and Company, you exclusively own all right, title, and interest in Your Content and Output" (fonte: https://www.together.ai/terms-of-service, Seção 3.1/7.2).
- Atribuição: não encontrada exigência.
- Uso comercial: permitido; a única restrição encontrada é não usar o serviço "to develop a product or service that is competitive with the Company's products or services" (fonte: https://www.together.ai/terms-of-service, Seção 4.3(c)) — não se aplica a este app.
- Endpoint: `POST https://api.together.xyz/v1/images/generations` (confirmado ao vivo; a doc também usa `https://api.together.ai/v1/images/generations`), corpo mínimo `{"model": "black-forest-labs/FLUX.2-dev", "prompt": "..."}`. Parâmetros adicionais: `steps`, `n`, `height`/`width` (padrão 1.024×1.024; paisagem 1.344×768, retrato 768×1.344), `seed`, `negative_prompt`, `response_format` (`url` ou `base64`), `guidance_scale`, `output_format` (`jpeg`/`png`), `disable_safety_checker`. Resposta JSON: `{"data": [{"index": 0, "url": "..."}]}` (ou `b64_json` se `response_format=base64`) (fontes: https://docs.together.ai/docs/inference/images/overview.md, https://docs.together.ai/docs/inference/images/parameters.md, https://docs.together.ai/reference/post-images-generations.md).
- Filtro de conteúdo: sim — "Disables the built-in NSFW safety checker. By default, requests that trigger the checker return `422 Unprocessable Entity`. The checker runs on every model except FLUX Schnell Free and FLUX Pro" (fonte: https://docs.together.ai/docs/inference/images/parameters.md).
- ToS sobre o caso do app: não encontrada proibição de embutir a key ou chamar direto do device; a cláusula de não-concorrência já citada é a única restrição relevante achada.
- Data: 21/09/2026.

**fal.ai**
- Chave: obrigatória (fonte: https://fal.ai/docs/model-endpoints).
- Limite grátis: não encontrado — 100% pré-pago (ex. Seedream V4 US$0,03/imagem, Flux Kontext Pro US$0,04/imagem) (fonte: https://fal.ai/pricing).
- Direitos sobre a saída: fraco — fal.ai não concede propriedade nem licença explícita sobre o Output; a única cláusula relacionada é uma isenção de responsabilidade: "Company does not represent, warrant, or covenant that any Output Content will be original, will not infringe rights of any third party (...), or otherwise entitle Company to any intellectual property rights in any Output Content" (fonte: https://fal.ai/legal/terms-of-service, Seção 6(c)).
- Atribuição / uso comercial: não encontrada exigência de atribuição; uso comercial sem proibição textual explícita mas também sem garantia de direitos suficiente (ver item acima).
- Endpoint: modelo chamado como `fal-ai/flux/schnell` via client oficial ou `fal.run`; resposta JSON com `images[].url` (hospedada) ou data URI se `sync_mode=true`; URL REST completa não confirmada nesta pesquisa (fonte: https://fal.ai/models/fal-ai/flux/schnell/api).
- Filtro de conteúdo: não encontrado.
- **ToS sobre o caso do app — achado crítico:** "Client will not expose any of the Services APIs directly to any End Users" (fonte: https://fal.ai/legal/terms-of-service, Seção 4(b)(ii)). Isso proíbe textualmente o padrão que este app usaria (chamar a API fal.ai direto do dispositivo do usuário final, sem servidor). **fal.ai está descartado para este projeto enquanto ele não tiver backend próprio.**
- Data: 21/09/2026.

**Replicate**
- Chave: obrigatória em toda requisição — "All API requests must include a valid API token in the `Authorization` request header" (fonte: https://replicate.com/docs/reference/http).
- Limite grátis: não encontrado — a página de preços descreve modelo 100% pay-as-you-go, sem mencionar crédito de trial (fonte: https://replicate.com/pricing).
- Direitos sobre a saída: favorável — "Replicate hereby grants to you all right, title and interest, if any, in and to Output, including your use of Output for commercial purposes such as sale or publication" (fonte: https://replicate.com/terms, Seção 5.1).
- Atribuição: não encontrada exigência.
- Uso comercial: sim, pela cláusula acima; ressalva de que cada modelo individual pode ter Third-Party Terms próprios que precisam ser respeitados (fonte: https://replicate.com/terms, Seção 2.7(c)(i)).
- Endpoint: `POST https://api.replicate.com/v1/predictions` (ou `.../v1/models/{owner}/{model}/predictions`); resposta JSON com `id`, `status` (starting/processing/succeeded/failed/canceled) e `output` (URL do arquivo gerado) (fonte: https://replicate.com/docs/reference/http).
- Filtro de conteúdo: não encontrado.
- ToS sobre o caso do app: não encontrada proibição de embutir a key ou chamar direto do device — só uma cláusula genérica de "proteção de credenciais" (fonte: https://replicate.com/terms, Seção 2.3(b)).
- Data: 21/09/2026.

**Hugging Face Inference (Inference Providers)**
- Chave: obrigatória, User Access Token via `Authorization: Bearer` (fonte: https://huggingface.co/docs/inference-providers/index).
- Limite grátis: conta "Free" recebe **US$0,10 de crédito por mês** ("subject to change"); PRO/Team/Enterprise recebem US$2,00/mês por assento. É recorrente mensal, mas US$0,10 é muito pouco — a própria doc dá de exemplo uma chamada de texto custando US$0,0012 por 10s de GPU (fonte: https://huggingface.co/docs/inference-providers/pricing).
- Direitos sobre a saída: não encontrado especificamente para saída de modelo de terceiro via Inference Providers — os Termos de Serviço confirmam "You own the Content you create" de forma genérica, sem detalhar este caso (fonte: https://huggingface.co/terms-of-service).
- Atribuição / uso comercial: não encontrado.
- Endpoint: via SDK, `client.text_to_image(prompt=..., model="black-forest-labs/FLUX.1-dev")` — resposta é imagem binária; a doc consultada não mostrou exemplo de HTTP cru especificamente para geração de imagem (fonte: https://huggingface.co/docs/inference-providers/index).
- Filtro de conteúdo: não encontrado.
- Data: 21/09/2026.

**Google AI Studio — Gemini API (geração de imagem / Imagen / "Nano Banana")**
- Chave: obrigatória, header `x-goog-api-key` (fonte: https://ai.google.dev/gemini-api/docs/image-generation).
- Limite grátis: **nenhum** — geração de imagem está marcada "Free Tier: Not available" para os três modelos listados (Gemini 2.5 Flash Image, Gemini 3.1 Flash Image, Gemini 3.1 Flash Lite Image); é preciso habilitar faturamento para gerar qualquer imagem. Preço de referência: Gemini 3.1 Flash Lite Image a US$0,25 (input) / US$30 por 1M tokens de imagem (fonte: https://ai.google.dev/gemini-api/docs/pricing).
- Direitos sobre a saída: "Google won't claim ownership over that content. You acknowledge that Google may generate the same or similar content for others" — nenhuma restrição de modificação encontrada; toda imagem carrega watermark **SynthID** obrigatório embutido nos pixels (fontes: https://ai.google.dev/gemini-api/terms, https://ai.google.dev/gemini-api/docs/image-generation).
- Atribuição: não exigida como texto visível — o watermark SynthID é técnico (embutido no arquivo), não uma exigência de crédito visível.
- Uso comercial: os termos descrevem o público como "developers building with Google AI models for professional or business purposes" — sugestivo, sem cláusula explícita de "permitido revender" localizada; e o ponto é discutível já que não existe free tier para imagem (item acima).
- Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/interactions`, corpo com `"model": "gemini-3.1-flash-image"` e `"input": [{"type": "text", "text": "..."}]`; resposta JSON com imagem em base64 em `interaction.output_image.data` (fonte: https://ai.google.dev/gemini-api/docs/image-generation).
- Filtro de conteúdo: sim, sujeito à "Prohibited Use Policy" do Google, mais o watermark SynthID obrigatório (fonte: mesma).
- ToS sobre o caso do app: não encontrada proibição específica sobre app Android; nota geral: na camada "Unpaid Services" (que não existe para imagem, mas existiria para texto), "Google uses the content you submit...to provide, improve, and develop Google products and services" e "human reviewers may read, annotate, and process your API input and output" (fonte: https://ai.google.dev/gemini-api/terms).
- Data: 21/09/2026.

**Segmind** (extra)
- Chave: não encontrado.
- Limite grátis: crédito único de **US$10** para novos usuários no plano "Flexible" — é crédito de trial, não recorrente; só planos pagos (Pro US$50/mês, Business US$99/mês) têm crédito mensal recorrente (fonte: https://www.segmind.com/pricing).
- Resto dos pontos: não encontrado — tentativas de acessar a doc de API (`docs.segmind.com/quickstart`) e os termos (`segmind.com/terms-of-service`) retornaram HTTP 404.
- Data: 21/09/2026.

**Stability AI** (extra)
- Chave: obrigatória — confirmado ao vivo, `GET https://api.stability.ai/v1/engines/list` sem autenticação devolveu `401 Unauthorized` (fonte: resposta ao vivo do endpoint, 21/09/2026).
- Resto dos pontos: não encontrado — `platform.stability.ai` é uma SPA renderizada em JavaScript; três tentativas de fetch (pricing, credits-and-billing, api-reference) só devolveram o título da página, sem conteúdo.
- Data: 21/09/2026.

**Descartados (verificados, sem free tier real em 2026)**
- **DeepAI**: chave obrigatória (`api-key`); "API access is included with every DeepAI Pro subscription" (US$9,99/mês) — nenhum uso gratuito sem assinatura. Endpoint: `https://api.deepai.org/api/text2img` (fonte: https://deepai.org/apis).
- **Prodia**: chave obrigatória; "You'll need a Pro subscription in order to generate a v2 token" — sem plano gratuito para o token v2 atual. Endpoint: `POST https://inference.prodia.com/v2/job` (fonte: https://docs.prodia.com/reference/getting-started).
- **OpenRouter**: chave obrigatória (`Authorization: Bearer`); nenhum modelo de geração de imagem com tag `:free` encontrado, cobrança por imagem (ex. US$0,04 no exemplo da doc). Endpoint: `POST https://openrouter.ai/api/v1/images` (fonte: https://openrouter.ai/docs/features/multimodal/image-generation).
- **Leonardo.ai**: página de preços bloqueou o fetch (HTTP 403) — não confirmado nesta pesquisa.

---

## Respostas objetivas

### (a) Qual usar — priorizando "sem chave" sobre "melhor qualidade"

**Busca:** só Openverse e Wikimedia Commons não pedem chave. Wikimedia Commons é a recomendação primária porque tudo que testei nela se confirmou (limites, endpoint, mecânica de licença por arquivo); Openverse também não pede chave, mas os números de limite e o formato de resposta não puderam ser confirmados aqui (bloqueio anti-bot) — vale testar direto do app Android antes de descartar, já que um cliente HTTP de app não necessariamente sofre o mesmo bloqueio que um fetch automatizado de pesquisa. Se nenhum dos dois tiver acervo bom o bastante para "praia"/"cidade à noite" (ambos são mais fracos em foto de banco de imagem "comercial" do que Pexels/Unsplash/Pixabay), o fallback com chave mais seguro é **Pixabay** (limite generoso, licença simples, nenhuma cláusula de risco encontrada). Evite Pexels para esta função específica — ver (b).

**Geração:** só Pollinations.ai não pede chave nenhuma, o que já a torna a escolha pela regra do enunciado — mas com uma ressalva real: não consegui confirmar a licença de uso da imagem gerada nem o ToS completo (página renderizada em JavaScript, não lida por nenhuma das 3 tentativas automatizadas). Abra `pollinations.ai/terms` num navegador antes de shippar. Se um plano com chave for aceitável, **Cloudflare Workers AI** é o segundo mais seguro: 10.000 neurons/dia recorrentes (~2.083 imagens grátis por dia no flux-1-schnell), e a Cloudflare afirma em termos próprios que o usuário retém direitos sobre a saída (a ressalva é que o modelo por trás, FLUX.1-schnell, é de um terceiro com termos não verificados aqui). **fal.ai está descartado** pela própria regra de arquitetura do app (ver b), e **Google AI Studio não tem free tier para imagem**, então nenhum dos dois serve.

### (b) Termos de uso que proíbem exatamente este caso

1. **fal.ai** — proíbe textualmente "expose any of the Services APIs directly to any End Users" (Seção 4(b)(ii) do ToS, fonte: https://fal.ai/legal/terms-of-service). Isso é exatamente a arquitetura do app (sem servidor, API chamada direto do device) — descarta o fal.ai por completo para este projeto, não é uma questão de risco, é proibição direta.
2. **Pexels** — proíbe "replicate core functionality of Pexels (including making Pexels content available as a wallpaper app)" (fonte: https://www.pexels.com/license/). A função de busca de fundo do app se aproxima perigosamente disso; recomendo evitar o Pexels para esta feature específica, mesmo com o resto da licença sendo favorável.
3. **Flickr** — uso comercial ("primary purpose... to derive revenue") exige solicitar key/permissão comercial separada (fonte: https://www.flickr.com/services/api/misc.api_keys.html); não bloqueia o uso pessoal de hoje, mas bloqueia a "virar produto pago" sem pedir upgrade de key antes.
4. **Unsplash** — proíbe compartilhar "Credentials" com terceiros (fonte: https://unsplash.com/api-terms); uma key embutida num APK público distribuído fora de loja é extraível por qualquer um que descompile o app, o que é uma zona cinzenta frente a essa cláusula, ainda que não seja "compartilhar" no sentido ativo tradicional.
5. **Nenhum dos 14 serviços pesquisados** tem uma cláusula que proíba literalmente "embutir a chave num APK Android distribuído fora de loja" — os itens 1 e 4 acima são os mais próximos disso que a pesquisa encontrou. Isso não significa ausência de risco real: é o risco estrutural que o próprio enunciado da tarefa já identificou (chave embutida = extraível); a maioria dos ToS não menciona esse cenário especificamente porque foi escrita pensando em backend servidor-a-servidor, não em app cliente puro.

### (c) Riscos

- **Serviço sumir:** Pollinations.ai é o mais frágil dos observados — projeto comunitário pequeno que já mudou a política uma vez, adicionando marca d'água ao tier grátis em 31/03/2025 (fonte: https://github.com/pollinations/pollinations/blob/master/APIDOCS.md). Cloudflare e Google têm menor risco de desaparecer, mas nenhum serviço pesquisado tem garantia contratual contra mudança de preço/limite do free tier.
- **Conteúdo impróprio numa busca:** confirmado com filtro de segurança — Openverse (exclui "mature" por padrão), Pixabay (`safesearch`), Flickr (`safe_search`, e chamada não-autenticada já é segura por padrão). Não encontrado/não confirmado filtro de NSFW documentado para Wikimedia Commons, Pexels e Unsplash — não é prova de que não exista, só que não achei a documentação. Na geração: Pollinations (`safe=true`), Together.ai (checker automático, bloqueia com HTTP 422) e Google (Prohibited Use Policy + SynthID) têm filtro confirmado; Cloudflare, fal.ai, Replicate e Hugging Face não têm filtro documentado encontrado.
- **Latência:** não encontrado nenhum número publicado em nenhum dos 14 serviços — nenhuma fonte trazia benchmark de tempo de resposta. Sem dado confiável, recomendo medir na prática antes de decidir; geração por IA tende a ser mais lenta que busca (que devolve arquivo já existente) só por rodar um modelo a cada chamada, mas isso é inferência minha, não algo que consegui sourcing.
- **Tamanho da imagem retornada:** confirmado — Pexels entrega vários tamanhos (`original`, `large2x`, `large`, `medium`, `small`, `portrait`, `landscape`, `tiny`); Pixabay tem 150px/640px/1.280px/FullHD; Together.ai gera 1.024×1.024 por padrão (paisagem 1.344×768, retrato 768×1.344, sempre múltiplo de 8). Demais serviços: resolução exata não encontrada nesta pesquisa — testar antes de assumir que a imagem cobre a tela cheia do celular sem esticar.

---

**Cobertura:** 6 serviços de busca + 9 de geração pesquisados em detalhe (mais 4 descartados por não terem free tier real) = 19 serviços avaliados.
