# IA online na câmera: decisões e medições (18/09/2026)

Pedido do dono: "coloque na câmera quando tiver internet, uma IA para melhoramento de imagem ou algo assim,
para ajudar ainda mais no que está sendo usado" e "desenvolva junto com o astra". Design e plano do Astra
(codex gpt-6-astra), crítica do agy, medições nas fotos reais do dono sem rosto (fones sobre madeira 1800x2400
e caneca esmaltada).

## Medições

| Caminho | Saída | Tempo | Custo/foto | Fidelidade |
|---|---|---|---|---|
| Gemini 2.5 flash image (OpenRouter), "melhore sem alterar" | 864x1184 (1 MP) | 8-9 s | US$ 0,039 | SSIM 0,96 caneca / 0,79 fones; nada inventado; perde resolução |
| Gemini 3.1 flash image | 896x1195 | 10 s | US$ 0,068 | INVENTOU: cor da madeira, veios, rachaduras e brilhos no fone |
| Gemini 3.1 flash image 2K | 1792x2390 | 19 s | US$ 0,101 | mesma invenção em resolução cheia |
| Gemini 2.5 como guia de tom (mapa de ganho na original) | original | 9 s + local | US$ 0,039 | detalhe intacto, mas Astra e agy apontaram halo, sombras instáveis e que tom/cor se faz local de graça |
| SnapEdit enhance 2x (entrada 900x1200) fones | 1800x2400 | 10,6 s | US$ 0,008 (4 créditos) | SSIM 0,996; nitidez 1,84->2,19; textura fina levemente "pintada" |
| SnapEdit enhance 2x caneca | 1800x2400 | 37 s | US$ 0,008 | SSIM 0,999; ruído p20 0,68->0,50; bordas intactas |

| iLoveIMG upscaleimage 2x (foto inteira 1800x2400) fones | 3600x4800 | 9,6 s | 20 créditos (2.500 grátis/mês) | SSIM 0,997; nitidez 1,84->2,01; ruído 3,48->3,18 |
| iLoveIMG upscaleimage 2x caneca | 3600x4800 | 11 s | 20 créditos | SSIM 0,999; nitidez 0,67->0,74; ruído 0,68->0,71 (não limpa) |

Limite do SnapEdit para 2x: recusa 1200x1600 (1,92 MP), aceita 900x1200 (1,08 MP). O servidor reduz a
entrada para até 1,1 MP. Zoom 4x (7 créditos) ainda não medido: fica para fotos maiores que 1800x2400 (v1.1).
Créditos grátis do SnapEdit: 5/dia automáticos + check-in diário (8 a 20), zeram às 00:00 UTC (20:00 Cuiabá).

## Decisões
- 0.68 / política v2: dois provedores no servidor, em ordem. SnapEdit primeiro (limpa ruído melhor), iLoveIMG
  (chaves ILOVEAPI_PUBLIC_KEY/SECRET_KEY no Doppler) quando o SnapEdit está sem crédito, fora do ar ou no prazo.
  A receita devolvida (snapedit-enhance-v1 | iloveimg-upscale-v1) vai para o EXIF da cópia. Capacidade grátis:
  SnapEdit 1 a 6 fotos/dia (check-in), iLoveIMG 125/mês. Fluxo iLove no servidor testado 18/09 16:2x UTC.
- v1 = "Melhorar (IA online)" com SnapEdit enhance; a imagem devolvida é o resultado (o app redimensiona
  para as dimensões exatas da original orientada). Sem Gemini, sem mapa de ganho.
- Original nunca é sobrescrita; cópia nova `<nome>_ia.jpg`, EXIF Software "(melhorado online, snapedit-enhance-v1)".
- Consentimento por foto (texto do Astra, provedor nomeado: SnapEdit/SilverAI, retenção conforme política
  publicada). Rosto detectado: aviso reforçado, sem bloqueio. Falha na detecção: aviso próprio.
  Isso é exceção deliberada à invariante "nunca envia imagem" da telemetria: só por toque do usuário,
  cópia reduzida sem metadados.
- Sem envio automático, sem fila offline, sem repetição automática. Botão só com rede validada e token.
- Cota 5/dia por app e teto US$ 10/mês no servidor. Token no APK não é segredo (Astra): serve para cota,
  não para identidade; plano pago exigiria credencial individual.
- "Extrair texto" (OCR local com ML Kit, sugestão do Astra) fica para a v2.
- Descartado: GetApps/Play não entram aqui; Gemini como editor final; bloqueio por rosto.

## Contrato v1 (servidor /opt/camera-ia/servidor.py, porta 8098, Caddy /camera/*)
Base https://pocketlm.maxymus.dev.br. Cabeçalhos: Authorization Bearer <MELHORAR_TOKEN>, Idempotency-Key = request_id.
- GET /camera/capacidades -> {api_version, privacy_version, privacy_url, features.melhorar{enabled, remaining, reset_at}}
- GET /camera/privacidade -> texto da política (pt-BR)
- POST /camera/melhorar {api_version:1, request_id, consent{privacy_version, accepted}, image{mime_type, width,
  height, base64}, operation:"enhance_v1", target{width,height}} -> {status:"ok", result{kind:"enhanced",
  image{...}, recipe_version}, usage{units_charged, remaining, reset_at}}
- Erros: 400 INVALID_REQUEST; 401 UNAUTHORIZED; 403 FEATURE_DISABLED; 409 PRIVACY_VERSION_CHANGED,
  REQUEST_IN_PROGRESS, REQUEST_ALREADY_FINISHED, IDEMPOTENCY_CONFLICT; 413 PAYLOAD_TOO_LARGE; 415 UNSUPPORTED_IMAGE;
  422 INVALID_IMAGE, CONTENT_REJECTED; 429 QUOTA_EXCEEDED, RATE_LIMITED; 502 PROVIDER_FAILURE;
  503 SERVICE_UNAVAILABLE (inclui crédito do provedor esgotado); 504 PROVIDER_TIMEOUT.
- Limites: JPEG <= 2 MiB, corpo <= 3 MiB, provedor 75 s, download 40 s. Servidor não grava imagem; guarda por
  24 h request_id, hash, estado, custo.
Teste de ponta a ponta 18/09 15:28 UTC: 401/400/409 corretos; chamada real 6,7 s, 1800x2400, 753 KB;
repetição do UUID -> REQUEST_ALREADY_FINISHED sem nova chamada; cota 5 -> 4.

## Antes de dar por pronto (critérios do Astra, reduzidos ao piloto de um usuário)
- Hash da original idêntico antes/depois; cópia com as dimensões da original orientada.
- Upload sem EXIF/GPS (conferir no servidor com um pedido real).
- Toque duplo/repetição: no máximo uma chamada ao provedor por UUID (provado no servidor).
- Modo avião durante o envio, 401/429/504 e cancelamento em cada etapa sem travar a galeria.
- Fotos do dono: retrato (com consentimento dele), documento, noite, céu; inspeção visual a 100%.
