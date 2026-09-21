---
name: galeria-recorte-plano-os-dois
description: Plano em execução (20/09) para o recorte de pessoa da Galeria Estudo: ML Kit Subject Segmentation como padrão + ISNet (DIS) quantizado baixado do R2 como "alta qualidade"; estado do que já foi medido, o que roda em segundo plano e os passos exatos que faltam
metadata:
  type: project
---

**Decisão do dono (20/09, 12:45 Cuiabá): "os dois"** — ML Kit Subject Segmentation (peso zero) como padrão e ISNet
como alta qualidade opcional. Contexto: ele comparou o Fundo→Remover do 0.19 com o remove.bg; medi na bancada
(galeria/medicao/modelos_cmp.py, painel_modelos.jpg) e o isnet-general-use (DIS, Apache-2.0, 1024², 2,3 s em 2 núcleos,
178 MB fp32) dá recorte classe remove.bg; u2net_human_seg/silueta têm franjas; BiRefNet estoura 2 GB.

**Estado no ar:** Galeria 0.19 (R2 galeria-v0.19-build27). Fundo usa selfie_multiclass 256² + pós-processamento
validado (Fundo.Mascara.plena). Editor tem Luz/Cor/Recortar/Filtros/Detalhe/Fundo/Local/Corrigir.

**Rodando em segundo plano ao compactar:** `/tmp/claude-0/-opt-hs-tactical/b411c8f2-5312-4cb6-a058-243cb5ebe3b7/scratchpad/editor/quantiza_isnet.py` (heavy.sh, MEMCAP 2500M) → gera
`/tmp/claude-0/-opt-hs-tactical/b411c8f2-5312-4cb6-a058-243cb5ebe3b7/scratchpad/editor/modelos/isnet-general-use-dyn8.onnx` e `-fp16.onnx` e imprime tamanho/tempo/IoU vs fp32; saída em
/tmp/claude-0/-opt-hs-tactical/b411c8f2-5312-4cb6-a058-243cb5ebe3b7/tasks/bx4maxtsj.output. Modelo fp32 original em
/root/.rembg/models/isnet-general-use/isnet-general-use.onnx. venv com onnxruntime/rembg/ai-edge-litert em `/tmp/claude-0/-opt-hs-tactical/b411c8f2-5312-4cb6-a058-243cb5ebe3b7/scratchpad/editor/venv`.
Pré-processamento ISNet (rembg): resize 1024², /255, mean 0,5 std 1, NCHW; saída [0][0] normalizada min-max, redimensiona.

**Passos que faltam (ordem):**
1. Ler o resultado da quantização; escolher o menor arquivo com IoU ≥ 0,97 vs fp32 (se dyn8 não quantizar Conv, cair
   para fp16 ~89 MB; estático int8 só se precisar). Reaplicar na foto e olhar o recorte.
2. Publicar no R2: bucket omnigen-assets, prefixo galeria-estudo/modelos/, via aws CLI com endpoint R2 (credenciais
   R2 estão no Doppler cha-de-panela/prd — R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_ACCOUNT_ID — e nos secrets do CI).
   URL pública: https://pub-520120b0b03b4d3f8c94c5c9ba10d569.r2.dev/galeria-estudo/modelos/<arquivo>. Gravar sha256.
3. Android (build 0.20): deps `com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1` e
   `com.microsoft.onnxruntime:onnxruntime-android` (última 1.x), `ndk.abiFilters += "arm64-v8a"`.
   - Fundo.Mascara generalizar: pessoa (FloatArray, pw, ph em qualquer resolução) + pele (256² do multiclass, continua
     para Corrigir>Pele). plena() já amostra por pessoa(x,y).
   - MlKitAssunto.kt: SubjectSegmenterOptions.enableForegroundConfidenceMask(); Tasks.await no Default; máscara no
     tamanho do bitmap de trabalho (≤1024). Falha/indisponível (módulo do Play não baixado) → cai para multiclass + telemetria.
   - IsnetOnnx.kt: baixa do R2 sob demanda com progresso e sha256 (filesDir/modelos), OrtSession com XNNPACK/CPU 4 threads,
     entrada 1024² NCHW, pós min-max; cache da sessão. ~2–5 s no aparelho.
   - Fundo.Motor { Leve(multiclass), Padrao(ML Kit), Alta(ISNet) }, preferência em SharedPreferences; seletor
     "Qualidade: Padrão ▾" no painel Fundo (DropdownMenu com tamanho/tempo). Motor escolhido vale na prévia E na
     exportação (mesma máscara, resolução do trabalho reamostrada).
   - Segmentação sempre a partir do bitmap já com geometria (como hoje).
4. Testar no aparelho (adb normalmente offline; o dono atualiza pelo app): ML Kit não é medível na bancada; ISNet já foi.
5. Registrar resultado e atualizar [[galeria-estudo-app]] e [[mascara-de-pessoa-medida-na-bancada]].
