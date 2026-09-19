# Reconhecimento facial offline em Android (Kotlin) — 19/09/2026

Método: 5 frentes de pesquisa web + verificação direta (li protobufs dos modelos, fiz HEAD nos arquivos, li o código do Ente, ReFra, Immich, DeepFace). Números vêm só da fonte citada; "não encontrei" onde não há.

## 1. Detecção (achar o rosto + alinhar)

### 1.1 Google ML Kit Face Detection (on-device, proprietário)

| Item | Valor | Fonte |
|---|---|---|
| Artefatos atuais | `com.google.mlkit:face-detection:16.1.7` (bundled, 07/08/2024) e `com.google.android.gms:play-services-mlkit-face-detection:17.1.0` (unbundled, 16/08/2022); release notes de 21/07/2026 marcam ambos "Updated? NO" | https://developers.google.com/ml-kit/release-notes ; https://dl.google.com/dl/android/maven2/com/google/mlkit/face-detection/maven-metadata.xml |
| Tamanho no APK | bundled "About 6.9 MB"; unbundled "About 800 KB" | https://developers.google.com/ml-kit/vision/face-detection/android |
| Landmarks | LEFT_EYE, RIGHT_EYE, NOSE_BASE ("midpoint between the subject's nostrils", não é ponta do nariz), MOUTH_LEFT, MOUTH_RIGHT, MOUTH_BOTTOM, LEFT/RIGHT_CHEEK, LEFT/RIGHT_EAR; lado é do SUJEITO ("not the eye that is on the left when viewing the image") | https://developers.google.com/android/reference/com/google/mlkit/vision/face/FaceLandmark |
| Contornos | 133 pontos (oval 36, olhos 16 cada, lábios 11/9/9/9, sobrancelhas 5, nariz 2+3, bochechas 1) | https://developers.google.com/ml-kit/vision/face-detection/face-detection-concepts |
| Ângulos | Euler X, Y, Z; landmarks só saem completos com Euler Y entre -12° e 12° | mesma página |
| Reconhecimento | Literal: "Note that the API _detects faces_, it does not _recognize people_." | https://developers.google.com/ml-kit/vision/face-detection |
| Latência | doc do Face Detection não dá número; a página do Face Mesh cita "~60ms on Pixel 3 when fast mode is ON" para a Face Detection API | https://developers.google.com/ml-kit/vision/face-mesh-detection |
| Termos (14/05/2025) | proíbe extrair o modelo ("you may not reverse engineer or attempt to extract the source code or any related software"; "machine learning models will be considered related software"); dados de entrada ficam no aparelho, mas "ML Kit APIs also send metrics about the performance and utilization of the APIs in your app to Google". Nenhuma cláusula sobre dados faciais/biométricos | https://developers.google.com/ml-kit/terms |
| Face Mesh (beta) | 468 pontos 3D, ~6,4 MB, só Android, "not subject to any SLA or deprecation policy"; ~14 ms Pixel 3 | https://developers.google.com/ml-kit/vision/face-mesh-detection |

### 1.2 MediaPipe (Google AI Edge, Apache 2.0)

| Item | Valor | Fonte |
|---|---|---|
| `com.google.mediapipe:tasks-vision` | 1.0.0 (Google Maven 27/07/2026; GitHub v1.0.0 28/07/2026); POM Apache 2.0 | https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/maven-metadata.xml ; https://github.com/google-ai-edge/mediapipe/releases |
| Face Detector (BlazeFace short-range) | 6 keypoints: olho esq, olho dir, ponta do nariz, boca (centro), tragion esq/dir; 128x128; Pixel 6 CPU 2,94 ms / GPU 7,41 ms | https://developers.google.com/edge/mediapipe/solutions/vision/face_detector |
| Model card | "224KB in size", "~275FPS on Pixel 2 single-core CPU with XNNPACK", Apache 2.0; "Any form of surveillance or identity recognition is explicitly out of scope"; arquivo real: 229.746 bytes | https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf ; https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite |
| Face Landmarker | 478 landmarks 3D + 52 blendshapes + matriz de transformação; bundle 3.758.596 bytes; Apache 2.0 | https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker ; https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf |
| Convenção de lado | "Left eye (from the observer's point of view)" — OPOSTA à do ML Kit | model card acima |

### 1.3 Confirmação: nem ML Kit nem MediaPipe (nem outra API do Google) dão embedding de identidade on-device

| Onde | O que existe | Fonte |
|---|---|---|
| ML Kit Vision | barcode, face detection, face mesh, text, image labeling, object detection, digital ink, pose, segmentation, document scanner — nada de reconhecimento | https://developers.google.com/ml-kit |
| ML Kit GenAI | summarization, proofreading, rewriting, image description, speech, prompt | https://developers.google.com/ml-kit/genai |
| MediaPipe Tasks (visão) | face detection, face landmark, gesture, hand, holistic, image classification, image embedding, image/interactive segmentation, object detection, pose, image generation — sem face recognition | https://developers.google.com/edge/mediapipe/solutions/guide |
| MediaPipe Image Embedder | embedding genérico de MobileNetV3 "trained using ImageNet data" — conteúdo, não identidade | https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder |
| Face Mesh model card | "Predicted face landmarks do not provide facial recognition or identification and do not store any unique face representation." | https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf |
| Android plataforma | `android.media.FaceDetector` só localiza; biometria só via `BiometricPrompt` (app não recebe template) | https://developer.android.com/reference/android/media/FaceDetector ; https://source.android.com/docs/security/features/biometric/face-authentication |
| Google Cloud Vision | "Specific individual Facial Recognition is not supported." | https://docs.cloud.google.com/vision/docs/detecting-faces |

### 1.4 Alinhamento (o que os modelos de 112x112 esperam)

- Template ArcFace de 5 pontos em 112x112: `[[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]]`, transformação de similaridade (`SimilarityTransform` + `warpAffine`) — https://github.com/deepinsight/insightface/blob/master/python-package/insightface/utils/face_align.py
- O Ente usa exatamente esse template em Rust (`MOBILEFACENET_IDEAL_5_LANDMARKS = [38.2946/112, 51.6963/112], ...`) — https://github.com/ente-io/ente/blob/main/rust/crates/ml/src/face/align.rs
- Com ML Kit: 4 dos 5 pontos batem com o template (RIGHT_EYE, LEFT_EYE, MOUTH_RIGHT, MOUTH_LEFT; ordem posicional na imagem: índice 0 = olho na esquerda da IMAGEM = RIGHT_EYE do sujeito). O quinto NÃO bate: NOSE_BASE é a base entre as narinas, não a ponta do nariz. Recomendação (minha, revisada pelo agy): estimar a transformação de similaridade (Umeyama, 4 graus de liberdade: escala uniforme, rotação, translação — não deforma) só com os 4 pontos que batem e deixar o nariz de fora; ou alinhar só pelos dois olhos. Não alimentar NOSE_BASE no lugar da ponta — https://developers.google.com/android/reference/com/google/mlkit/vision/face/FaceLandmark
- Não precisa de OpenCV: a similaridade cabe em ~40 linhas de Kotlin e o warp é `Canvas.drawBitmap(bitmap, Matrix, paint)` com `android.graphics.Matrix` — https://developer.android.com/reference/android/graphics/Matrix
- Com MediaPipe Face Detector NÃO fecha: dá o centro da boca, não os cantos — precisa do Face Landmarker (478 pontos; tem ponta do nariz e cantos da boca). Não encontrei mapeamento oficial dos índices para os 5 pontos ArcFace; os índices se tiram do modelo canônico (468 vértices no .obj + imagem UV numerada no repo) — https://developers.google.com/edge/mediapipe/solutions/vision/face_detector ; https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/modules/face_geometry/data/canonical_face_model_uv_visualization.png ; https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/modules/face_geometry/data/canonical_face_model.obj
- FaceNet (Sandberg, 160x160) não usa esse warp: é recorte da caixa do MTCNN com margem e resize para 160 — https://github.com/davidsandberg/facenet

### 1.5 Detectores open source alternativos (Android)

| Detector | Licença | Landmarks | Nota | Fonte |
|---|---|---|---|---|
| SCRFD (InsightFace) | código Apache 2.0; modelos prontos "non-commercial research only" | 5 | det_500m 2,5 MB / det_10g 16,9 MB (dentro dos packs buffalo) | https://github.com/deepinsight/insightface/tree/master/detection/scrfd ; https://github.com/deepinsight/insightface/blob/master/model_zoo/README.md |
| YOLOv5-Face | GPL-3.0 | 5 | é o detector do Ente (`yolov5s_face_640_640_static_b1.onnx`, 32,4 MB) | https://github.com/deepcam-cn/yolov5-face ; https://models.ente.com/yolov5s_face_640_640_static_b1.onnx |
| UltraFace (1MB) | MIT | não (só caixas) | RFB-320 no ONNX Model Zoo; é o detector do ReFra | https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB ; https://github.com/onnx/models/tree/main/validated/vision/body_analysis/ultraface |
| BlazeFace .tflite avulso | Apache 2.0 | 6 | mesmo arquivo do MediaPipe; decodificar âncoras + NMS por conta própria | model card acima |

## 2. Embedding (identidade)

### 2.1 Tabela dos modelos (tamanhos medidos por mim ou lidos na fonte; LFW = declarado pela fonte)

| Modelo / distribuição | Dim | Tamanho | LFW | Outras | Licença código | Licença pesos / dataset | Formato | URL |
|---|---|---|---|---|---|---|---|---|
| MobileFaceNet — Qualcomm AI Hub (pesos foamliu) | 128 | tflite 3.988.392 B | 99,48 | não informado | Apache-2.0 | Apache-2.0 declarado pela Qualcomm e pelo foamliu; treino Refined MS-Celeb-1M | TFLite, ONNX, QNN prontos | https://huggingface.co/qualcomm/MobileFaceNet ; https://github.com/foamliu/MobileFaceNet |
| MobileFaceNet — sirius-ai/MobileFaceNet_TF | 128 | .pb 5.956.310 B | "99.4+" | Val@1e-3 98,4+ | Apache-2.0 | mesmo repo; treino MS1M-refine-v2 (commit "train by MS1M-V2 dataset") | TF .pb/ckpt; sem tflite no repo | https://github.com/sirius-ai/MobileFaceNet_TF |
| MobileFaceNet 192-D `.tflite`/`.onnx` que circula (estebanuri, syaringan357, Ente) | 192 | 5,2-5,3 MB | não declarada | — | nenhuma / MIT (app) / AGPL (app) | origem dos pesos não documentada; NÃO é o `.pb` do sirius-ai (que é 128-D, ver 2.4) | TFLite; ONNX (Ente) | https://github.com/estebanuri/face_recognition ; https://models.ente.com/mobilefacenet_portable_static_b1.onnx |
| InsightFace w600k_mbf (buffalo_s / buffalo_sc) | 512 | onnx 13.616.099 B; 450 MFLOPs | 99,70 | CFP-FP 98,00; AgeDB 96,58; IJB-C 95,02 | MIT | "non-commercial research purposes only"; WebFace600K | ONNX | https://github.com/deepinsight/insightface/releases/tag/model-zoo ; https://github.com/deepinsight/insightface/blob/master/model_zoo/README.md |
| InsightFace w600k_r50 (buffalo_l) | 512 | onnx 174.383.860 B | 99,83 | 99,33; 98,23; 97,25 | MIT | non-commercial; WebFace600K | ONNX | idem |
| FaceNet 512 — Sandberg 20180402-114759 → deepface → TFLite | 512 | facenet_512.tflite 24.394.880 B (fp); int8 NXP 24.538.968 B | 0,9965 (Sandberg); NXP remediu 97,6% em 1.000 pares | não informado | MIT (Sandberg, deepface) | Sandberg não declara licença dos pesos; dataset VGGFace2 CC BY-SA 4.0 "commercial/research" (página de 2019; downloads hoje removidos) | TFLite 160x160 | https://github.com/davidsandberg/facenet ; https://github.com/serengil/deepface#licence ; https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android/tree/master/app/src/main/assets ; https://huggingface.co/nxp/facenet512-imx ; https://web.archive.org/web/20190623094708/http://www.robots.ox.ac.uk/~vgg/data/vgg_face2/ ; https://www.robots.ox.ac.uk/~vgg/data/vgg_face2/ |
| FaceNet 128 — `facenet.tflite` (keras-facenet = Sandberg 20170512-110547) | 128 | 23.705.216 B | 0,992 | — | Apache-2.0 (app) / sem licença (keras-facenet) | treino MS-Celeb-1M | TFLite | https://github.com/nyoki-mtl/keras-facenet ; https://github.com/davidsandberg/facenet/blob/529c3b0b5fc8da4e0f48d2818906120f2e5687e6/README.md |
| facenet-pytorch (timesler) | 512 | .pt 111.898.327 B (vggface2) | 0,9965 / 0,9905 | — | MIT | portado do Sandberg, sem licença própria | PyTorch; ONNX/TFLite não documentado (issues #200/#213) | https://github.com/timesler/facenet-pytorch |
| EdgeFace-XS (γ=0,6) — Idiap | 512 | 1,77M params, 154 MFLOPs; .pt 7.170.425 B; onnx 7.277.264 B | 99,73 | CFP-FP 94,71; AgeDB 96,08; IJB-C 94,8 | BSD-3-Clause (GitHub/GitLab) | HF: "EdgeFace is released under CC BY-NC-SA 4.0"; treino WebFace4M/12M (não comercial) | PyTorch (torch.hub/HF); ONNX comunitário MIT; TFLite não encontrei | https://github.com/otroshi/edgeface ; https://huggingface.co/Idiap/EdgeFace-XS-GAMMA ; https://github.com/yakhyo/edgeface-onnx/releases/tag/weights |
| EdgeFace-XXS | 512 | 1,24M, 94,7 MFLOPs; .pt 5.032.125 B | 99,57 | AgeDB 94,92 | BSD-3 | CC BY-NC-SA 4.0 (HF) | idem | https://huggingface.co/Idiap/EdgeFace-XXS |
| GhostFaceNet V1-1.3-1 (MS1MV3) | 512 | .h5 17.271.840 B; 215,7 MFLOPs; TFLite comunitário 8,11 MB | 99,73 | CFP-FP 96,83; AgeDB 98; IJB-C 94,94 | MIT | sem cláusula própria; treino MS1MV3 (InsightFace, non-commercial) | Keras .h5; TFLite só por conversão | https://github.com/HamadYA/GhostFaceNets ; https://github.com/HamadYA/GhostFaceNets/releases ; https://github.com/albiistafa/ghostfacenet-ptq-experiments |
| MixFaceNet-XS | 512 | 1,04M, 161,9 MFLOPs | 99,60 | AgeDB 95,85; IJB-C 90,73 | MIT | MIT sem cláusula própria; treino MS1MV2 | PyTorch (Dropbox) | https://github.com/fdbtrs/mixfacenets |
| PocketNet S-128 | 128 | 0,92M, 587 MFLOPs | 99,58 | IJB-C 91,62 | CC BY-NC-SA 4.0 | CC BY-NC-SA 4.0; MS1MV2 | PyTorch | https://github.com/fdbtrs/PocketNet |
| AdaFace IR18 WebFace4M | 512 | safetensors 96.158.576 B; onnx 92 MB | 0,9953 | IJB-C 94,99 | MIT | WebFace4M "cannot be used for any commercial purposes" | PyTorch; ONNX comunitário | https://github.com/mk-minchul/AdaFace ; https://github.com/yakhyo/adaface-onnx/releases/tag/weights ; https://www.face-benchmark.org/download.html |
| MagFace iResNet100 MS1MV2 | 512 | .pth "270M"; 65,2M params | 99,83 | IJB-C 95,81 | Apache-2.0 | não declarada; MS1MV2 | PyTorch — não é para celular | https://github.com/IrvingMeng/MagFace |
| Qualcomm CavaFace IR-SE-100 | 512 | 249,96 MB, 65,5M params | não informado | — | MIT | MIT (card) | TFLite/ONNX/QNN — pesado demais | https://huggingface.co/qualcomm/CavaFace |

Trechos de licença que pesam:
- InsightFace: "The code of InsightFace is released under the MIT License. There is no limitation for both academic and commercial usage." / "The training data containing the annotation (and the models trained with these data) are available for non-commercial research purposes only." / "Both manual-downloading models from our github repo and auto-downloading models with our python-library follow the above license policy"; update 24/11/2025: "For open-sourced face recognition models (e.g., buffalo_l package), please contact recognition-oss-pack@insightface.ai for licensing." — https://github.com/deepinsight/insightface/blob/master/README.md#license
- Model zoo: "ALL models are available for non-commercial research purposes only." — https://github.com/deepinsight/insightface/blob/master/model_zoo/README.md
- WebFace4M/12M (EdgeFace, AdaFace): "cannot be used for any commercial purposes" — https://github.com/leondgarse/Keras_insightface ; https://www.face-benchmark.org/download.html
- EdgeFace: contradição real entre BSD-3 no GitHub/GitLab e CC BY-NC-SA nos cards do HF — tratar como não comercial — https://raw.githubusercontent.com/otroshi/edgeface/main/LICENSE ; https://huggingface.co/Idiap/EdgeFace-XS-GAMMA
- VGGFace2 (FaceNet 512): "available to download for commercial/research purposes under a Creative Commons Attribution-ShareAlike 4.0 International License" (snapshot 23/06/2019); hoje "The download links for the VGGFace2 dataset are no longer available from this website" — URLs na tabela
- Quase todo modelo leve foi treinado em MS1M/MS1MV2/MS1MV3 (Qualcomm/foamliu, sirius-ai, GhostFaceNets, MixFaceNets, PocketNet) ou WebFace (InsightFace, EdgeFace, AdaFace). Só a cadeia FaceNet/VGGFace2 tem dataset com licença que menciona uso comercial.

### 2.2 Repos Android open source que já empacotam detecção + embedding

| Repo | Licença | Stack | Estado | Fonte |
|---|---|---|---|---|
| **IacobIonut01/ReFra** (galeria Kotlin/Compose) | Apache-2.0 | ONNX Runtime Android 1.29; UltraFace RFB-320 + `arc.onnx` (ArcFace 112→512-D); agrupamento por pessoa on-device via `FaceIndexerWorker` (WorkManager) + Room; centróide incremental, cosseno ≥ 0,45 | release 5.1.5 em 14/09/2026; pessoas desde commit c8db97a4 (14/07/2026) | https://github.com/IacobIonut01/ReFra ; https://github.com/IacobIonut01/ReFra/blob/main/app/src/main/kotlin/com/dot/gallery/core/ml/FaceHelper.kt ; https://github.com/IacobIonut01/ReFra/blob/main/app/src/main/kotlin/com/dot/gallery/core/workers/FaceIndexerWorker.kt |
| ReFra — ponto fraco | — | `arc.onnx` vem de https://huggingface.co/garavv/arcface-onnx sem licença nem origem dos pesos | — | https://github.com/IacobIonut01/ReFra/blob/main/app/src/main/kotlin/com/dot/gallery/core/workers/ModelDownloadWorker.kt |
| shubham0204/OnDevice-Face-Recognition-Android | Apache-2.0 | ML Kit `face-detection:16.1.7` + FaceNet via **ExecuTorch** (`model.pte`, HF `shubhxm0204/facenet-executorch`, MIT) + ObjectBox HNSW 512-D cosseno; limiar `distance > 0.3`; README ainda fala de TFLite/MediaPipe (diverge do código) | push 08/09/2026 | https://github.com/shubham0204/OnDevice-Face-Recognition-Android ; https://github.com/shubham0204/OnDevice-Face-Recognition-Android/blob/main/app/src/main/java/com/ml/shubham0204/facenet_android/domain/embeddings/FaceNet.kt ; https://github.com/shubham0204/OnDevice-Face-Recognition-Android/blob/main/app/src/main/java/com/ml/shubham0204/facenet_android/data/DataModels.kt |
| shubham0204/FaceRecognition_With_FaceNet_Android | Apache-2.0 | ML Kit FAST + `facenet.tflite` (128-D) / `facenet_512.tflite` (512-D), 160 px; limiares `ModelInfo`: cos 0,4 / L2 10 (128) e cos 0,3 / L2 23,56 (512); GPU delegate se suportado, senão 4 threads; XNNPACK opcional | push 27/07/2024 | https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android ; https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android/blob/master/app/src/main/java/com/ml/quaterion/facenetdetection/model/Models.kt ; https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android/blob/master/app/src/main/java/com/ml/quaterion/facenetdetection/model/FaceNetModel.kt |
| pillarpond/face-recognizer-android | Apache-2.0 | BlazeFace (`face_detection_front.tflite`) + FaceNet Sandberg 20180402-114759 (`facenet.tflite` 93,9 MB, 512-D) + LibSVM; NÃO é MTCNN+MobileFaceNet | push 31/10/2022 | https://github.com/pillarpond/face-recognizer-android ; https://github.com/pillarpond/face-recognizer-android/blob/master/app/src/main/java/pp/facerecognizer/Recognizer.java |
| estebanuri/face_recognition | SEM licença | ML Kit + `mobile_face_net.tflite` 192-D (112 px, mean/std 128); vizinho mais próximo L2 < 1,0 | push 24/03/2023 | https://github.com/estebanuri/face_recognition ; https://github.com/estebanuri/face_recognition/blob/master/android/app/src/main/java/org/tensorflow/lite/examples/detection/tflite/TFLiteObjectDetectionAPIModel.java |
| Ente (Flutter + Rust) | AGPL-3.0 | ver 2.4; o antigo `OnnxDartPlugin.kt` (Kotlin, `ai.onnxruntime`) é o pedaço reaproveitável | ativo | https://github.com/ente-io/ente/blob/0e235751/mobile/apps/photos/plugins/onnx_dart/android/src/main/kotlin/io/ente/photos/onnx_dart/OnnxDartPlugin.kt |
| Immich mobile | AGPL-3.0 | NÃO faz nada on-device: "Face detection sends the generated preview image to the machine learning service"; `mobile/` não tem .onnx/.tflite | — | https://docs.immich.app/features/facial-recognition ; https://github.com/immich-app/immich/tree/main/mobile |
| kby-ai, FaceOnLive, MiniAiLive, Faceplugin, recognito-vision | comerciais (licença por contato) | SDK fechado | — | https://github.com/kby-ai/FaceRecognition-Android#sdk-license ; https://github.com/FaceOnLive/Face-Recognition-SDK-Android |
| HyperInspire/InspireFace (equipe InsightFace) | modelos "solely for academic purposes and explicitly prohibiting commercial applications" | SDK C++ com Android | — | https://github.com/HyperInspire/InspireFace#license |

Galerias/servidores: LibrePhotos (servidor; SCRFD+ArcFace 512, HDBSCAN) https://docs.librephotos.com/docs/user-guide/face-recognition/ ; PhotoPrism (servidor; YuNet + sface 128-D, DBSCAN) https://docs.photoprism.app/developer-guide/vision/face-recognition/ ; Nextcloud Recognize (servidor; face-api.js) https://github.com/nextcloud/recognize ; Aves, Fossify Gallery, Stingle, Photok: não fazem (issues abertas/wontfix) https://github.com/deckerst/aves/issues/69 ; https://github.com/stingle/stingle-photos-android/issues/121 ; https://github.com/leonlatsch/Photok/issues/124

### 2.3 Ente Photos — o que usa (lido no código, commit main de 18/09/2026)

| Etapa | Fato | Fonte |
|---|---|---|
| Runtime | ONNX Runtime (crate `ort` 2.0.0-rc.13; Android com features `xnnpack`, `webgpu`; iOS `coreml`); "we have grown to favor ONNX Runtime for its reliability across many devices" após testar TFLite, PyTorch Mobile e GGML | https://github.com/ente-io/ente/blob/main/rust/crates/ml/Cargo.toml ; https://ente.com/ml/ |
| Detecção | `yolov5s_face_640_640_static_b1.onnx` (32.355.091 B), score ≥ 0,5, NMS IoU 0,4, 5 keypoints, máx. 100 faces | https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/ml_model_assets.dart ; https://github.com/ente-io/ente/blob/main/rust/crates/ml/src/face/detect.rs |
| Alinhamento | template ArcFace 5 pontos, 112x112, normalização 1/127.5, blur por Laplaciano | https://github.com/ente-io/ente/blob/main/rust/crates/ml/src/face/align.rs |
| Embedding | `mobilefacenet_portable_static_b1.onnx` (5.278.803 B): entrada `img_inputs` [1,112,112,3] NHWC, saída `embeddings` [1,192], L2-normalizada; produzido por tf2onnx 1.16.1 "converted from mobilefacenet_unq_TF211.tflite" (lido no protobuf) | https://models.ente.com/mobilefacenet_portable_static_b1.onnx ; https://github.com/ente-io/ente/blob/main/rust/crates/ml/src/face/embed.rs |
| Download | `kModelBucketEndpoint => "https://models.ente.com/"` + SHA-256 | https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/ml_model.dart |
| Licença dos modelos | nenhuma declarada; a página só diz "we are somewhat dependent on public data and pre-trained models"; YOLOv5-Face upstream é GPL-3.0 | https://ente.com/ml/ ; https://github.com/deepcam-cn/yolov5-face |
| Origem do 192-D | os nomes `img_inputs`/`embeddings` são do grafo do sirius-ai (`tf.placeholder(name='img_inputs')`, `l2_normalize(..., name='embeddings')`), mas o `.pb` pré-treinado do sirius-ai tem `Logits/LinearConv1x1/weights` [1,1,512,128] = 128-D (li o protobuf). Logo os pesos de 192-D foram treinados por alguém com `--embedding_size 192`; quem e com que dado, não encontrei | https://github.com/sirius-ai/MobileFaceNet_TF/blob/master/train_nets.py ; https://github.com/sirius-ai/MobileFaceNet_TF/tree/master/arch/pretrained_model |
| O `.tflite` do seu `:galeria` | `galeria/src/main/assets/mobilefacenet.tflite` (5.233.552 B, SHA-256 `be4bc7cf…3854`) é byte a byte o `assets/mobilefacenet.tflite` do MCarlomagno/FaceRecognitionAuth. O "BSD-3" é a licença desse app Flutter (Marcos Carlomagno, 2020); o README dele não diz de onde vêm os pesos. Ou seja: mesma linhagem 192-D sem proveniência | https://github.com/MCarlomagno/FaceRecognitionAuth/blob/master/assets/mobilefacenet.tflite ; https://github.com/MCarlomagno/FaceRecognitionAuth/blob/master/LICENSE |
| Saúde do aparelho | `minimumBatteryLevel = 20`, `maximumAndroidBatteryTemperature = 42`; indexa só em rede "stable and unmetered" | https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/device_health_policy.dart ; https://ente.com/ml/ |

## 3. Agrupamento sem cadastro

### 3.1 Como cada um faz

| Sistema | Algoritmo | Métrica e limiar | Fonte |
|---|---|---|---|
| Ente (mobile) | "linear incremental clustering": faces em ordem cronológica; cada face nova compara com TODAS as anteriores ("This loop makes clustering O(n²); keep its body cheap"), herda o cluster da mais próxima se `distance < threshold`, senão abre cluster; passo separado funde centróides com distância < `mergeThreshold` | `distance = 1 - dot` (cosseno, vetores unitários); `kRecommendedDistanceThreshold = 0.24`; `kConservativeDistanceThreshold = 0.16` para face ruim (score < 0,80, blur < 50, ou blur < 200 com score < 0,85, ou de lado); `mergeThreshold = 0.30`; cluster só aparece na busca com ≥ 10 faces | https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/face_ml/face_clustering/face_clustering_service.dart ; https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/face_ml/face_filtering/face_filtering_constants.dart |
| Ente (web) | mesma regra em similaridade | `threshold = fj.isBadFace ? 0.84 : 0.76` | https://github.com/ente-io/ente/blob/main/web/packages/new/photos/services/ml/cluster.ts |
| Ente (sugestão de mesclar pessoas) | distâncias mediana/média entre clusters | `maxMedianDistance = 0.62`, `goodMedianDistance = 0.55`, `maxMeanDistance = 0.65`, `goodMeanDistance = 0.45` | https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/face_ml/feedback/cluster_feedback.dart |
| Immich (servidor, buffalo_l) | "derived from DBSCAN": busca k vizinhos no pgvector; face é "core" se tem ≥ `minFaces` vizinhos dentro de `maxDistance`; não-core fica adiada e roda de novo à noite | distância cosseno (`<=>`); defaults `minScore 0.7`, `maxDistance 0.5`, `minFaces 3`; doc recomenda distância entre 0,3 e 0,7 | https://docs.immich.app/features/facial-recognition ; https://github.com/immich-app/immich/blob/main/server/src/dtos/config.dto.ts ; https://github.com/immich-app/immich/blob/main/server/src/services/person.service.ts ; https://github.com/immich-app/immich/blob/main/server/src/repositories/search.repository.ts |
| ReFra (Android, ArcFace 512) | atribui ao centróide mais parecido; centróide = média corrida renormalizada | similaridade de cosseno ≥ `CLUSTER_THRESHOLD = 0.45f` | https://github.com/IacobIonut01/ReFra/blob/main/app/src/main/kotlin/com/dot/gallery/core/workers/FaceIndexerWorker.kt |
| Google Photos | página de ajuda: "face models that numerically represent the images of faces... estimate whether different images represent the same face"; agrupa também por proximidade temporal e roupa; não diz onde roda. Paper/blog sobre agrupamento on-device: não encontrei | — | https://support.google.com/photos/answer/6128838?hl=en&co=GENIE.Platform%3DAndroid |
| FaceNet (paper) | "clustering can be achieved using off-the-shelf techniques such as k-means or agglomerative clustering"; Fig. 7 "generated using agglomerative clustering" | limiar L2² ótimo em LFW 1,242 (o 1,1 é só ilustração da Fig. 1) | https://arxiv.org/pdf/1503.03832 |
| Escala (referência) | Otto/Wang/Jain 2017: k-NN aproximado (FLANN, 200 vizinhos) + rank-order, O(n) após vizinhos; Yang 2019: grafo k-NN por cosseno + GCN | — | https://arxiv.org/abs/1604.00989 ; https://arxiv.org/abs/1904.02749 |
| PhotoPrism (servidor, sface 128-D) | DBSCAN | cluster distance 0,72, radius 0,70, match 0,25, core mínimo 5 | https://docs.photoprism.app/developer-guide/vision/face-recognition/ |
| LibrePhotos (servidor) | `from hdbscan import HDBSCAN` | — | https://github.com/LibrePhotos/librephotos/blob/dev/apps/backend/api/face_classify.py |

### 3.2 Limiares por modelo (com fonte)

Convenções: similaridade s = a·b/(‖a‖‖b‖); distância cosseno = 1 − s (Ente, pgvector `<=>`); com vetores L2-normalizados, L2² = 2 − 2s. O limiar é do modelo/treino, não do algoritmo: o guia do InsightFace manda "never carry over a threshold across model versions" — https://www.insightface.ai/guides/choose-face-recognition-model-and-evaluate

| Modelo | Métrica | Limiar (mesma pessoa se…) | Fonte |
|---|---|---|---|
| MobileFaceNet 192-D (Ente) | dist. cosseno | < 0,24 (0,16 face ruim); merge < 0,30 | face_clustering_service.dart acima |
| MobileFaceNet 192-D (estebanuri) | euclidiana | < 1,0 (app) / 0,89 (VerifyMFN) | https://github.com/estebanuri/face_recognition/blob/master/android/app/src/main/java/org/tensorflow/lite/examples/detection/DetectorActivity.java ; https://github.com/estebanuri/face_recognition/blob/master/VerifyMFN/app/src/main/java/org/tensorflow/lite/tflite/FaceEmbedder.java |
| MobileFaceNet 128-D (Qualcomm/foamliu) | — | não encontrei limiar publicado; calibrar | https://huggingface.co/qualcomm/MobileFaceNet |
| Facenet 128 (DeepFace) | cos / euclid / euclid_l2 | 0,40 / 10 / 0,80 | https://github.com/serengil/deepface/blob/master/deepface/config/threshold.py |
| Facenet512 (DeepFace) | cos / euclid / euclid_l2 | 0,30 / 23,56 / 1,04 | idem |
| ArcFace (DeepFace) | cos / euclid / euclid_l2 | 0,68 / 4,15 / 1,13 | idem |
| GhostFaceNet (DeepFace) | cos / euclid / euclid_l2 | 0,65 / 35,71 / 1,10 | idem |
| Buffalo_L (DeepFace) | cos / euclid / euclid_l2 | 0,55 / 0,6 / 1,1 | idem |
| InsightFace packs (guia oficial) | similaridade cosseno | 1:1 "0.30-0.45 cosine range at FMR = 1e-4 to 1e-5"; exemplo 1:N `threshold = 0.40` | https://www.insightface.ai/guides/choose-face-recognition-model-and-evaluate |
| InsightFace (issue, mantenedor) | L2 normalizada | "Maybe 0.8~1.4 is suitable" | https://github.com/deepinsight/insightface/issues/168 |
| buffalo_l (Immich) | dist. cosseno | ≤ 0,5 padrão (faixa 0,3-0,7) | https://docs.immich.app/features/facial-recognition |
| ArcFace 512 (ReFra) | similaridade | ≥ 0,45 | FaceIndexerWorker.kt acima |
| FaceNet 128 (paper) | L2² | < 1,242 (≈ s 0,38) | https://arxiv.org/pdf/1503.03832 |
| dlib 128-D | euclidiana | < 0,6 (99,38% LFW); Chinese Whispers com 0,5 | https://github.com/davisking/dlib/blob/master/python_examples/face_recognition.py ; https://github.com/davisking/dlib/blob/master/python_examples/face_clustering.py |
| EdgeFace, AdaFace | — | README não sugere limiar: não encontrei | https://github.com/otroshi/edgeface ; https://github.com/mk-minchul/AdaFace |

### 3.3 Ferramentas para Kotlin

| Opção | Estado | Fonte |
|---|---|---|
| Copiar o incremental do Ente (sem biblioteca) | 5.000 fotos × poucas faces = ~10-20k vetores; O(n²) em produtos escalares de 128-512 floats cabe em segundos; é o único algoritmo on-device com constantes públicas e testadas em produção. Copiar o ALGORITMO, não os limiares: 0,24/0,16/0,30 valem para o modelo 192-D do Ente; para outro modelo o limiar tem que ser calibrado (abaixo) | face_clustering_service.dart acima |
| Calibração do limiar (obrigatória ao trocar de modelo) | rodar o modelo escolhido no PC sobre pares rotulados (LFW e AgeDB-30, os mesmos que os papers usam), traçar distância intra-pessoa vs inter-pessoa, escolher o ponto de FAR baixo (não o EER), e depois ajustar fino na galeria com feedback do usuário; o guia do InsightFace diz "always recompute" | https://www.insightface.ai/guides/choose-face-recognition-model-and-evaluate ; https://github.com/davidsandberg/facenet/blob/master/src/lfw.py |
| Desfazer/separar cluster e drift do centróide | guardar o embedding de CADA rosto (não só o centróide) e o cluster id por rosto; o Ente guarda `rejectedClusterIds` por rosto e tem serviço de feedback (merge/de-merge/ignorar); rosto "ruim" entra com limiar conservador (0,16) para não puxar o centróide; o ReFra usa média corrida renormalizada (drift possível se rosto ruim passar no filtro) | https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/face_ml/feedback/cluster_feedback.dart ; https://github.com/IacobIonut01/ReFra/blob/main/app/src/main/kotlin/com/dot/gallery/core/workers/FaceIndexerWorker.kt |
| ObjectBox (HNSW on-device) | "Vector Search is currently available for Python, C, C++, Dart/Flutter, Java/Kotlin and Swift"; `@HnswIndex(dimensions, distanceType = COSINE)`; usado pelo shubham0204 | https://docs.objectbox.io/on-device-vector-search |
| sqlite-vec | sem binding Android oficial (issues #68 e #102 abertas); release traz `loadable-android-aarch64` para carregar por conta própria | https://github.com/asg017/sqlite-vec/issues/68 ; https://github.com/asg017/sqlite-vec/releases |
| Apache Commons Math `DBSCANClusterer(eps, minPts, DistanceMeasure)` | roda em Android | https://commons.apache.org/proper/commons-math/javadocs/api-3.6.1/org/apache/commons/math3/ml/clustering/DBSCANClusterer.html |
| Smile (DBSCAN, hierárquico) | v4+ exige Java 21 — mantenedor: "I don't think that Android supports Java 21 yet" | https://github.com/haifengl/smile/issues/799 |
| ELKI (DBSCAN, HDBSCAN*) | Java, AGPL-3.0 | https://elki-project.github.io/algorithms/ |

## 4. Custo no aparelho

### 4.1 Latências medidas (só as publicadas com aparelho/condição)

| Etapa / modelo | Aparelho e condição | Tempo | Fonte |
|---|---|---|---|
| BlazeFace short-range (detecção) | Pixel 6, CPU / GPU | 2,94 ms / 7,41 ms | https://developers.google.com/edge/mediapipe/solutions/vision/face_detector |
| BlazeFace (paper, GPU fp16) | Pixel 3 / Galaxy S9+ / Huawei P20 | 3,4 / 3,7 / 5,8 ms | https://arxiv.org/abs/1907.05047 |
| MediaPipe Face Detection (port Qualcomm, 256x256) | Snapdragon 8 Gen 3 NPU, TFLite float / ONNX w8a8 | 0,396 / 0,188 ms | https://huggingface.co/qualcomm/MediaPipe-Face-Detection |
| ML Kit Face Detection | Pixel 3, fast mode | "~60ms" | https://developers.google.com/ml-kit/vision/face-mesh-detection |
| MobileFaceNet (paper) | Snapdragon 820 CPU, 4 threads, NCNN | 24 ms (112x112); 18 ms (96x96) | https://arxiv.org/pdf/1804.07573 |
| MobileFaceNet TFLite (Keras_insightface, emb 512, GDC) | Snapdragon 630, TFLite float16 + XNNPACK, 4 threads | 20,4 ms (1,84 MB); se_mobilefacenet 18,7 ms; mobilenet_v3_small 4,2 ms; ghostnet s2 11,1 ms; EfficientNetB0 22,1 ms; mobilenet_v2 float16 xnn 1 thread 29,9 ms / 4 threads 8,7 ms | https://github.com/leondgarse/Keras_insightface#tflite-model-inference-time-test-on-arm64 |
| MobileFaceNet (Qualcomm AI Hub) | Snapdragon 8 Elite Gen 5 NPU, TFLite float | 0,504 ms | https://huggingface.co/qualcomm/MobileFaceNet |
| MobileFaceNet_TF (sirius-ai, TF, não tflite) | MSM8976 (Snapdragon 652) CPU | "260-" ms | https://github.com/sirius-ai/MobileFaceNet_TF#performance |
| FaceNet Inception-ResNet-v1 TFLite | Pixel 3 (2020, artigo) | "around 3.5 seconds" | https://medium.com/@estebanuri/real-time-face-recognition-with-android-tensorflow-lite-14e9c6cc53a5 |
| FaceNet TFLite (shubham0204) | — | app mostra ms na tela, mas nenhum número publicado: não encontrei | https://github.com/shubham0204/OnDevice-Face-Recognition-Android |
| EdgeFace / GhostFaceNet em celular | — | não encontrei (papers dão só MFLOPs) | https://arxiv.org/abs/2307.01838 |
| w600k_mbf em ONNX Runtime Android | — | não encontrei | https://onnxruntime.ai/docs/tutorials/mobile/ |
| Ente, todos os modelos (YOLOv5s-face + MobileFaceNet + CLIP) | "recent iPhone", GPU (CoreML) | "~40ms per photo, 10x faster than before"; Android: não publicado | https://ente.com/blog/ml-on-gpu/ |

### 4.2 Estimativa para 5.000 fotos (derivada; premissas explícitas)

Componentes por foto: ler do MediaStore + decodificar e reduzir o JPEG (`inSampleSize`) + EXIF/rotação + converter para tensor + detecção + N rostos × (alinhar + embedding) + clustering (desprezível: ~10-20k vetores, O(n²) em produtos escalares).

Piso, só inferência, com os números medidos acima em CPU média (Snapdragon 630/820, 4 threads, float16 + XNNPACK): detecção BlazeFace ~3-8 ms ou ML Kit ~60 ms (Pixel 3), embedding ~20-25 ms por rosto; premissa minha de 2 rostos por foto → ~50-110 ms por foto → 5.000 fotos ≈ 4-9 min de CPU.

O que esse piso NÃO inclui (apontado na verificação cruzada, sem medição em fonte — medir no aparelho-alvo):
- decodificação + EXIF + rotação + conversão para FloatArray de um JPEG de 12 MP em Kotlin, com pressão de GC (estimativa do revisor: 30-100 ms/foto; não encontrei medição publicada; o Ente só reporta "a few milliseconds per photo" de pré/pós em Rust, iOS);
- distribuição real de rostos: muitas fotos com 0 rostos (recibo, paisagem, tela) e algumas com 10-15 (festa) — o "2 por foto" é chute;
- throttling térmico após minutos de CPU/NPU contínua e pausas do sistema no worker;
- memória: Bitmap sem reciclar/`inBitmap` derruba o processo antes da CPU virar gargalo.
Ordem de grandeza realista segundo o revisor: 3-5x o piso, 15-30 min de worker intermitente. Com FaceNet-512 (Inception-ResNet-v1) o único dado é 3,5 s no Pixel 3 em 2020 — provavelmente inviável para 5.000 fotos; medir antes de considerar.

### 4.3 Runtime, aceleradores e background

| Item | Fato | Fonte |
|---|---|---|
| NNAPI | "NNAPI is deprecated... deprecated in Android 15"; recomendação: CPU ou "TF Lite GPU runtime" | https://developer.android.com/ndk/guides/neuralnetworks |
| LiteRT GPU delegate | fp16/fp32; ops não suportadas caem para CPU e "often results in slower performance than when the whole network is run on the CPU alone" | https://developers.google.com/edge/litert/performance/gpu ; https://developers.google.com/edge/litert/android/gpu |
| XNNPACK | delegate de CPU; nos binários Android "disabled by default. Use the setUseXNNPACK method" (README); 1 thread por padrão | https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/delegates/xnnpack/README.md |
| LiteRT Next (`CompiledModel`, NPU) | `com.google.ai.edge.litert:litert` 2.2.0 (13/08/2026); `CompiledModel.Options(Accelerator.NPU, Accelerator.GPU)` com fallback; libs de NPU exigem minSdk 31 e "Google Play for On-device AI" | https://developers.google.com/edge/litert/next/overview ; https://developers.google.com/edge/litert/next/npu ; https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml |
| ONNX Runtime Android | `com.microsoft.onnxruntime:onnxruntime-android` 1.30.0 (14/09/2026); guia: quantizado → CPU EP; float → XNNPACK; só depois NNAPI | https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml ; https://onnxruntime.ai/docs/tutorials/mobile/ |
| WorkManager longo | workers longos "can run longer than 10 minutes" com `setForeground(ForegroundInfo)`; Android 14+ exige `foregroundServiceType`; "Starting with Android 16, long running workers (which use foreground services) can exhaust your app's job quota"; caso de uso citado: "crunching on an ML model locally" | https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running |
| Expedited | `setExpedited()` (WorkManager 2.7+), `OutOfQuotaPolicy` | https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work |
| Galeria | Android 14 "Selected Photos Access"; `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED`; `MediaStore` funciona igual com acesso parcial | https://developer.android.com/about/versions/14/changes/partial-photo-video-access |
| Referência de política | Ente: bateria ≥ 20%, temperatura ≤ 42 °C, rede não tarifada | https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/services/machine_learning/device_health_policy.dart |

## 5. Recomendação ranqueada (licença limpa + tflite pronto + acurácia + leve)

Antes: nenhum modelo leve tem cadeia 100% limpa (código + pesos + dataset). O que muda o ranking é se o app é comercial.

**1º — MobileFaceNet do Qualcomm AI Hub (pesos foamliu) + ML Kit para detecção/landmarks**
- Por quê: único TFLite pronto com licença Apache-2.0 declarada pelo distribuidor e pelo autor dos pesos; 3,99 MB, 128-D, LFW 99,48%, ~1M params; 0,5 ms no NPU (8 Elite Gen 5) e classe de ~20 ms em CPU média (Keras_insightface, SDM630) — https://huggingface.co/qualcomm/MobileFaceNet ; https://huggingface.co/qualcomm/MobileFaceNet/blob/main/LICENSE ; https://github.com/foamliu/MobileFaceNet
- Ressalvas: (a) o export do AI Hub tem DUAS entradas `img1`/`img2` [1,3,112,112] float 0..1 (NCHW, normalização ImageNet embutida) e saída `embeddings [2,128]` — serve como lote de 2 rostos por chamada, ou exporte você mesmo o `.pt` do foamliu (Apache-2.0) com entrada única — https://github.com/qualcomm/ai-hub-models/blob/v0.62.2/src/qai_hub_models/models/mobile_facenet/model.py ; (b) treinado em Refined MS-Celeb-1M (dataset retirado pela Microsoft em 2019; a versão "refined" vem do InsightFace, que declara non-commercial). O selo Apache-2.0 da Qualcomm cobre o export, não limpa a origem dos dados: é o melhor tecnicamente, com respaldo corporativo, mas NÃO é "juridicamente blindado" — risco cinzento comum a quase todos os modelos leves; (c) sem IJB-C publicado e sem limiar publicado: NÃO transportar o 0,24 do Ente (modelo diferente, espaço latente diferente); calibrar por ROC em pares rotulados no PC (seção 3.3) e só então afinar na galeria; (d) LFW não representa galeria de família: medir falso merge entre irmãos, crianças e a mesma pessoa com anos de diferença antes de fixar o limiar.
- Detecção: ML Kit (5 landmarks → alinhamento ArcFace 112x112; unbundled +800 KB) é o caminho mais curto; se quiser 100% open source, MediaPipe Face Landmarker (Apache-2.0) ou UltraFace (MIT, sem landmarks — o ReFra alinha só pela caixa).

**2º — FaceNet-512 (Sandberg 20180402-114759 via DeepFace → TFLite)**
- Por quê: cadeia de licença mais defensável — código MIT (Sandberg, DeepFace), dataset VGGFace2 publicado como CC BY-SA 4.0 "commercial/research"; TFLite pronto (`facenet_512.tflite` 24,4 MB fp; int8 24,5 MB da NXP sob MIT); limiares publicados (DeepFace cos 0,30 / euclid_l2 1,04); apps Kotlin prontos (shubham0204, Apache-2.0) — https://github.com/davidsandberg/facenet ; https://github.com/serengil/deepface#licence ; https://huggingface.co/nxp/facenet512-imx ; https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android
- Ressalvas: falha no critério "leve" — 6x maior e Inception-ResNet-v1 (único tempo publicado em Android: 3,5 s no Pixel 3 em 2020, provavelmente sem XNNPACK); para 5.000 fotos em background isso tende a ser inviável por bateria/térmica — só entra se a licença for o critério dominante E a medição no aparelho-alvo aprovar; LFW 0,9965 declarado, mas a NXP remediu 97,6% nos seus 1.000 pares; sem IJB-C; entrada 160x160 por recorte com margem, não pelo warp de 5 pontos; VGGFace2 saiu do ar e o efeito do ShareAlike sobre pesos não está resolvido juridicamente.

**3º — EdgeFace-XS (γ=0,6) do Idiap via ONNX Runtime — se o app NÃO for comercial**
- Por quê: melhor acurácia por tamanho entre os leves (1,77M params, 154 MFLOPs, LFW 99,73, IJB-C 94,8; ONNX 7,3 MB; vencedor do EFaR 2023 na faixa < 2M) — https://github.com/otroshi/edgeface ; https://huggingface.co/Idiap/EdgeFace-XS-GAMMA ; https://github.com/yakhyo/edgeface-onnx/releases/tag/weights
- Ressalvas: pesos CC BY-NC-SA 4.0 no HF (BSD-3 no GitHub — contraditório, tratar como NC), WebFace4M/12M; só PyTorch/ONNX (TFLite não encontrei); sem limiar publicado; sem tempo em celular publicado.
- Se for comercial e a acurácia InsightFace importar: `buffalo_sc` (w600k_mbf, 13 MB, IJB-C 95,02) com licença negociada em recognition-oss-pack@insightface.ai — https://github.com/deepinsight/insightface/blob/master/README.md#license

Implementações para copiar: clustering e filtros de qualidade do Ente (algoritmo e estrutura, seção 3; limiares recalibrados), worker + Room + centróide do ReFra (Apache-2.0), integração ML Kit + TFLite + ObjectBox do shubham0204 (Apache-2.0).

Não recomendo como ativo licenciado: o MobileFaceNet 192-D do Ente/estebanuri/MCarlomagno (pesos de origem desconhecida — é o que está hoje no `:galeria`), o `arc.onnx` do ReFra (sem licença), e qualquer `.tflite` de ArcFace copiado sem proveniência.

Sobre o `:galeria` como está: além da proveniência, o limiar `LIMIAR_CASA = 0.65` (similaridade; = distância 0,35) e `LIMIAR_CONSOLIDA = 0.62` são bem mais frouxos que os do Ente para essa mesma família de 192-D (similaridade 0,76 normal / 0,84 rosto ruim; merge 0,70) — risco de juntar pessoas diferentes. Antes de mexer: medir pureza dos grupos na galeria real e a curva em pares rotulados (seção 3.3); se trocar para o modelo 128-D da Qualcomm, recalibrar do zero — https://github.com/ente-io/ente/blob/main/web/packages/new/photos/services/ml/cluster.ts

Tamanho no APK (estimativa, sem medição): runtime (LiteRT ou ORT AAR) + modelo de 4 MB + ML Kit unbundled 800 KB; sem OpenCV (o warp é `android.graphics.Matrix`). Medir com `bundletool`/App Bundle antes de decidir entre LiteRT e ORT.

## 6. Jurídico: offline não dispensa consentimento

| Fato | Fonte |
|---|---|
| LGPD, Art. 5º, II: "dado pessoal sensível: dado pessoal sobre origem racial ou étnica, convicção religiosa, opinião política, filiação a sindicato ou a organização de caráter religioso, filosófico ou político, dado referente à saúde ou à vida sexual, dado genético ou biométrico, quando vinculado a uma pessoa natural" — o embedding do rosto é dado biométrico, mesmo sem sair do aparelho | https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm |
| LGPD, Art. 11, I: tratamento de dado sensível "quando o titular ou seu responsável legal consentir, de forma específica e destacada, para finalidades específicas" — tela de opt-in própria antes de indexar rostos, não parágrafo em política de privacidade | mesma URL |
| Google Play, User Data policy: "You must provide an in-app disclosure of your data access, collection, use, and sharing"; a divulgação não pode ficar "only... in a privacy policy or terms of service"; consentimento "Must require affirmative user action (for example, tap to accept, tick a check-box)". A política não nomeia dado biométrico/facial explicitamente | https://support.google.com/googleplay/android-developer/answer/10144311 |
| BIPA (Illinois, 740 ILCS 14) — relevante só se distribuir nos EUA; não consegui acessar o texto (ilga.gov não respondeu): não verificado | — |
| Referência de produto: o Ente liga o reconhecimento facial por opt-in e explica o processamento local | https://ente.com/help/photos/features/search-and-discovery/machine-learning |

## 7. Verificação cruzada (agy, 19/09)

Manteve o 1º lugar (MobileFaceNet Qualcomm) por viabilidade técnica e derrubou: (1) tratar o Apache-2.0 da Qualcomm como blindagem sobre pesos de MS-Celeb-1M — corrigido na ressalva (b); (2) transportar o limiar 0,24 do Ente para outro modelo — corrigido: calibrar por ROC; (3) a estimativa de custo só com inferência — corrigido na 4.2 (decode/EXIF/rotação, distribuição de rostos, térmica, memória); (4) NOSE_BASE no template ArcFace — corrigido na 1.4 (4 pontos ou olhos); (5) ausência de LGPD/consentimento — seção 6 nova; (6) fraquezas não nomeadas: desfazer/separar cluster, drift de centróide, tamanho do APK — adicionadas em 3.3 e 5. Discordei de um ponto dele: o warp não exige OpenCV (Matrix + Canvas resolvem).
