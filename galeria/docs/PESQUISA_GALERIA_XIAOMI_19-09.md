# Galeria da Xiaomi (HyperOS 3) como alvo de engenharia reversa — pesquisa de 19/09/2026

Pesquisa web por agente (130 consultas, todas com URL). Serve de alvo para os próximos recursos da Galeria Estudo.
A parte no aparelho (dumpsys/uiautomator dos pacotes) ainda não foi feita: precisa do celular carregado.

## Arquitetura do alvo
- Dois pacotes: visualizador `com.miui.gallery` (4.3.1.6) e editor `com.miui.mediaeditor` ("Gallery Editor", 2.4.0.4.3),
  que a Xiaomi chama de MiMediaEditor. https://www.mi.com/global/support/faq/details/KA-529353/
- **A IA generativa do editor roda na NUVEM, por documento oficial** (política "Gallery Editor AI features", vigente
  desde 01/10/2024): Image enhancement, AI Expansion e AI Erase Pro "processam a imagem via internet" e sobem a
  imagem ao servidor para revisão. https://privacy.mi.com/gallery_editor_gl/pt_BR/
  GSMArena confirmou (AI Eraser 2.0 exige conexão). https://www.gsmarena.com/ai_object_removal_which_phone_is_best-review-2819.php
- Não encontrado: se o agrupamento "Pessoas" roda no aparelho; se Cutout, Sky, Bokeh e Beautify rodam no aparelho.

## Organização e busca (o que existe lá)
Linha do tempo por data; álbuns automáticos (pessoas, lugares, pets); busca por texto/cena (HyperAI: "só no aparelho"
por política, mas rodapé "internet required"); OCR; favoritos; lixeira 30 dias; álbum oculto; álbum privado com senha;
compartilhamento seguro (tira metadados e o vídeo do motion photo). Fontes: KA-367225, KA-507815, KA-492674 (mi.com).

## Edição (o que existe lá)
Ajustes (exposição, realces, sombras, HSL), copiar/colar edições em lote, filtros (Leica/film, Art), recorte/rotação,
AI Erase (nuvem), AI Expand (nuvem), AI Enhance/Ultra Clear (nuvem), remoção de reflexo, AI Cutout (sujeito/fundo),
Sky (4 céus), Bokeh posterior 0-100, Beautify (pele, forma, também em vídeo), doodle/texto/mosaico/adesivo/moldura,
marca d'água (inclusive animada), imagem→vídeo (nuvem), edição de vídeo com templates.

## Formatos
- Ultra HDR: "Pro HDR display" só dentro da Galeria; API 34 tem Bitmap.hasGainmap/getGainmap/setGainmap e
  rotate/crop/scale preservam o gain map. https://developer.android.com/media/grow/ultra-hdr/edit
- Motion Photo: Xiaomi usa XMP privado (diferente do GCamera:MotionPhoto); rotação descuidada apaga o vídeo (bug digiKam).
- RAW (.dng no modo Pro) e HEIF (toggle na câmera; Galeria converte para JPEG ao enviar).

## Prova de que rosto e busca semântica 100% offline existem em Android
Ente Photos (AGPL-3.0): YOLO5Face-small + MobileFaceNet (o MESMO modelo que a câmera e a galeria usam) via ONNX Runtime,
e MobileCLIP para busca semântica, tudo no aparelho. https://ente.com/ml/

## O que é viável OFFLINE com modelo aberto (licença verificada)
| Recurso | Modelo | Licença | URL |
|---|---|---|---|
| Recorte de pessoa/fundo | MediaPipe SelfieMulticlass (a câmera JÁ tem o .tflite, 16 MB) | Apache-2.0 | https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter |
| Recorte por toque | MediaPipe Interactive Segmenter (MagicTouch) | Apache-2.0 | https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter |
| Recorte de objeto | MobileSAM · BiRefNet · U-2-Net | Apache/MIT/Apache | https://github.com/ChaoningZhang/MobileSAM |
| Borracha mágica (inpainting) | MI-GAN (Picsart) · LaMa (ONNX da Carve) | MIT · Apache-2.0 | https://github.com/Picsart-AI-Research/MI-GAN · https://huggingface.co/Carve/LaMa-ONNX |
| Busca semântica | SigLIP / SigLIP 2 | Apache-2.0 | https://huggingface.co/google/siglip2-base-patch16-224 |
| Super-resolução / melhorar | Real-ESRGAN · QuickSRNet (Qualcomm) | BSD-3 | https://github.com/xinntao/Real-ESRGAN |
| Restaurar rosto | GFPGAN | Apache-2.0 | https://github.com/TencentARC/GFPGAN |
| Bokeh por profundidade | Depth Anything V2 **Small** (Base+ são CC-BY-NC) · MiDaS | Apache-2.0 · MIT | https://github.com/DepthAnything/Depth-Anything-V2 |
| Máscara de céu | FFNet (Cityscapes, classe sky) | BSD-3 | https://huggingface.co/qualcomm/FFNet-40S |
| OCR | ML Kit Text Recognition v2 · Tesseract · PaddleOCR | proprietário grátis · Apache | https://developers.google.com/ml-kit/vision/text-recognition/v2 |
| Vídeo (cortar, transcodificar, efeitos) | Media3 Transformer | Apache-2.0 | https://developer.android.com/media/media3/transformer |
| Expandir imagem (outpainting) | pouco viável offline (só difusão; MediaPipe Image Generator sem manutenção) | — | — |

**Evitar:** RMBG-2.0 (comercial só com contrato), YOLO-seg da Ultralytics (AGPL, a câmera aceitou só para ela),
MobileCLIP (pesos só pesquisa), InsightFace (modelos não comerciais), CodeFormer (não comercial), SkyAR (CC BY-NC-SA).

## No aparelho do dono (dumpsys package, 20/09 01:40, só leitura)
- **Galeria** `com.miui.gallery` 4.3.1.18-global (minSdk 31, targetSdk 35), 111 atividades, 74 permissões. Componentes
  que mostram recurso: `PeoplePageActivity`, `PickFaceAlbumActivity`, `PickPeopleActivity`, **`IgnorePeoplePageActivity`**
  (ocultar pessoas, igual ao nosso "Ocultar"), `SearchActivity`/`SearchProvider`, `TrashActivity`, `SecretAlbumDetailActivity`,
  `ShareAlbumDetailActivity` + convites (álbum compartilhado), `CloudSettings`/`GallerySyncService` (Xiaomi Cloud),
  widgets 2x2/2x3/4x2/4x4 (`CustomWidgetProvider*`, `RecommendWidgetProvider*` = "memórias"), `AiAddToAlbumPageActivity`,
  `GalleryProviderForAiToolbox` + `AIActionProvider` (integração com o AI Toolbox do HyperOS; permissões
  `aiservice.permission.AI_ABILITY`, `hyperos.permission.READ_AIACTION`), `GalleryProviderForMediaEditor` (ponte para o editor).
- **Editor** `com.miui.mediaeditor` 2.4.0.5.2-global (minSdk 32), 45 atividades, 7 filtros de `ACTION_EDIT`
  (`ExternalPhotoEditor` é a porta de entrada; a nossa galeria pode chamar `ACTION_EDIT` e cair nele). Componentes:
  `AiClawService`, `AiActionProvider`, `AIResultExportActivity` (IA generativa: pedido→serviço→exportar resultado),
  **`DocPhotoPostProcessingActivity`** e **`IDCardPhotoPostProcessingActivity`** (pós-processo de documento e de foto
  3x4/identidade: recurso que o nosso scanner já cobre em parte), `MotionPhotoActivity`, `MovieActivity`,
  `VideoEditorActivity`, `VlogAutoGenerateActivity`/`VlogTemplate*` (vlog automático por modelo),
  **`ReEditServiceActivity`** (reedição = histórico não destrutivo), `PhotoEditorTipsActivity`, `MlKitInitProvider`
  (o editor usa ML Kit do Google), `AvifInitProvider` (AVIF), Firebase Messaging, WorkManager com restrições
  BatteryCharging/BatteryNotLow/NetworkState/StorageNotLow (trabalho pesado só carregando e com rede: mesma política que o
  Ente usa e que a nossa indexação deve adotar).
- Não é possível ler o índice de rostos deles nem os modelos (dados privados do app; sem root). O que vale copiar de
  desenho: pessoas com "ignorar", reedição não destrutiva, pós-processo de documento/3x4, trabalho pesado só carregando.
