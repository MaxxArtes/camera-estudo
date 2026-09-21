---
name: galeria-recorte-plano-os-dois
description: Estado do recorte de pessoa da Galeria Estudo: 0.20 no ar com tres motores (Leve multiclasse, Padrao ML Kit, Alta ISNet baixado do R2); o que foi medido, o que falta medir no aparelho e a decisao pendente sobre o peso do ONNX Runtime
metadata:
  type: project
---

**Decisão do dono (20/09, 12:45 Cuiabá): "os dois"** — ML Kit Subject Segmentation como padrão e ISNet como alta
qualidade opcional. Contexto: ele comparou o Fundo→Remover do 0.19 com o remove.bg; medi na bancada
(galeria/medicao/modelos_cmp.py, painel_modelos.jpg) e o isnet-general-use (DIS, Apache-2.0, 1024², 178 MB fp32) dá
recorte classe remove.bg; u2net_human_seg/silueta têm franjas; BiRefNet estoura 2 GB.

**Passos 1 a 3 FEITOS (21/09).** No ar: **Galeria 0.20 (galeria-v0.20-build28)**.

1. Quantização dinâmica int8 (ConvInteger): **46 MB, IoU 0,998 vs fp32** na foto do grupo. fp16 não carrega na ORT.
2. Publicado no R2: `https://pub-520120b0b03b4d3f8c94c5c9ba10d569.r2.dev/galeria-estudo/modelos/isnet-general-use-dyn8.onnx`
   — 46.360.717 bytes, sha256 `f1b1c6f7656e532627697afc989d953be1e7ef8f55a718f3611e8c9fd50cdef7`, imutável por 1 ano.
   Cópia fora do /tmp em `/root/modelos-galeria/`. Credenciais R2: Doppler `cha-de-panela/prd` (R2_ACCESS_KEY_ID,
   R2_SECRET_ACCESS_KEY, R2_ACCOUNT_ID), mesmas dos secrets do CI.
3. Android 0.20: `Fundo.Motor { Leve, Padrao, Alta }`, `MlKitAssunto.kt`, `IsnetOnnx.kt`, painel Fundo em 3 linhas de
   48 dp (desenho do Astra, `resp_astra_qualidade`), motor na receita (desfazer/refazer e reedição) e preferência
   global só quando a escolha dá certo. Prévia e exportação usam o mesmo motor.

**Arquitetura do que ficou:**
- `Fundo.Mascara(pessoaMapa, pw, ph, peleBruta, fina, motor, pedido, motivo)` aceita máscara em qualquer resolução.
  `fina = true` (ML Kit, ISNet) pula o pós-processamento do Leve — binarizar/fechar/preencher/guiado foi medido para
  consertar a máscara mole de 256², e **estragaria** a borda que os modelos finos já entregam. Pele continua 256² do
  multiclasse (Corrigir>Pele), por isso o multiclasse roda sempre, mesmo nos outros motores.
- Falha do motor pedido (módulo do Play ausente, modelo não baixado) → cai para Leve com `motivo`, aviso na 3ª linha
  ("Recorte Alta indisponível. Usando Leve nesta foto.") e telemetria `editor_motor` com ok/ms.
- Receita antiga sem o campo `motor` reabre como **Leve**, que é o motor com que ela foi feita.

**Custo medido do ISNet no APK: `lib/arm64-v8a/libonnxruntime.so` = 33 MB.** O APK foi de 39,9 MB (0.19) para
73,1 MB (0.20), com arm64 já sendo o único ABI. Ou seja, o motor Alta custa 33 MB em toda instalação mesmo para quem
nunca o usar, além dos 46 MB que ele baixa. **Decisão pendente do dono**, depois de testar no aparelho: se o ML Kit
(peso zero) for bom o bastante nas fotos dele, tirar ONNX Runtime + ISNet volta o APK para ~40 MB.

**O que falta:**
4. **Testar no aparelho** (única coisa que a bancada não mede): ML Kit existe e funciona no aparelho dele? Quanto
   tempo cada motor leva numa foto? A qualidade do ML Kit chega perto do ISNet nas fotos de grupo e de cabelo?
   Pico de RAM do ISNet em 1024² (largeHeap ligado). O APK vem do R2 e não da Play Store: confirmar que o módulo
   `subject_segment` é mesmo baixado pelo Google Play services (o meta-data do manifesto pressupõe instalação normal;
   o Astra alertou que pode ser preciso pedir o módulo pelo ModuleInstallClient).
5. Conforme o resultado: manter os três motores ou remover o Alta; registrar em [[galeria-estudo-app]] e
   [[mascara-de-pessoa-medida-na-bancada]].
