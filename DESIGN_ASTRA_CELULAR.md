# Acesso ao celular do dono: possibilidades, regras e prioridades (Astra, 18/09/2026)

Contexto: adb sobre Tailscale no POCO X8 Pro Max (sem root). Brief e mapa do que funciona em
/root/.claude/projects/-opt-hs-tactical/memory/acesso-celular-adb-tailscale.md. Resposta integral do Astra abaixo.

Eu faria **A primeiro, B em etapas e C como tentativa de reconexão, sem prometer recuperação autônoma total**. Já é possível coletar evidências úteis; interação e recuperação da depuração ainda têm bloqueios independentes.

Li o código local. Nenhuma alteração foi feita.

**Prioridade — no máximo oito frentes**

| # | Trabalho | Esforço | Valor |
|---|---|---|---|
| 1 | **A: acessibilidade** dos três controles, papel de botão e estados corretos | P | Alto: melhora uso e automação |
| 2 | Executor com regras de horário, interrupção, limpeza e relatório de falha | M | Muito alto: protege o celular de uso diário |
| 3 | **B: teste básico local** de câmera e agenda, com verificações reais | M | Muito alto: detecta regressões após releases |
| 4 | Agenda: lembretes com tela bloqueada, processo encerrado e Doze | M | Muito alto: valida a função mais sensível |
| 5 | Regressão visual e acessibilidade: telas estáveis, fontes grandes e TalkBack | M | Alto |
| 6 | Medições de abertura, captura, IA, memória e aquecimento | M | Alto |
| 7 | **C + instalação pós-CI**: reconexão, fila de APKs e verificação da versão instalada | M/G | Alto, condicionado ao Xiaomi |
| 8 | Inventário e experimentos com Extensions, lentes e sensores | M/G | Exploratório |

**Possibilidades e limites**

- **B pode começar agora:** abrir apps, confirmar versão, coletar árvore, screenshot, tempo de abertura e erros. Marcar interação como “bloqueada”, nunca como teste aprovado. Quando liberar toque, selecionar elementos por semântica; coordenadas calculadas pela árvore são recurso secundário.
- **Câmera:** conferir criação de uma nova URI no MediaStore, arquivo decodificável, dimensões, orientação/EXIF e ausência de duplicatas. A quantidade total de fotos não é uma boa referência, porque o dono apaga os testes. Registrar exatamente as URIs criadas pelo executor.
- **Agenda:** criar um bloco sintético, mover, reabrir e verificar persistência; depois validar entrega do lembrete. Tela bloqueada, morte de processo e `force-stop` são cenários diferentes. Doze merece teste próprio e restauração ao terminar; não equivale a simplesmente apagar a tela. [Documentação de Doze](https://developer.android.com/training/monitoring-device-state/doze-standby).
- **Visual:** comparar apenas regiões estáveis, mascarando relógio, notificações, preview e miniaturas. Fixar orientação, tamanho de fonte e estado do app. Diferença de pixels deve gerar revisão, não reprovação automática de qualquer mudança.
- **Desempenho:** separar abertura fria/quente, primeira imagem utilizável da câmera, disparo→arquivo salvo e IA→resultado exibido. Os 261 ms são uma amostra, não uma referência consolidada. Medir etapas com relógio monotônico no aparelho evita confundir latência do app com DERP.
- **IA:** usar imagem sintética conhecida; testar consentimento recusado, sucesso, erro, cancelamento e “Salvar cópia” preservando o original. Rodar o teste pago/online separadamente, com orçamento e autorização para envio da imagem.
- **Falhas:** guardar logs limitados ao app e ao período do teste, versão/commit, etapa e screenshot pertinente. Incluir retorno do segundo plano, rotação e troca repetida de lentes/modos.
- **Bateria/térmica:** acompanhar `meminfo`, bateria e estado térmico em sessões curtas de rajada/HDR. Temperatura da bateria não representa diretamente o SoC; dados térmicos variam conforme o fabricante. “120 MB livres” isoladamente não prova pressão de memória. [Limitações da API térmica](https://developer.android.com/games/optimize/adpf/thermal).
- **TalkBack:** a árvore é uma primeira verificação; não comprova ordem de foco nem experiência falada. Não ligaria TalkBack remotamente enquanto toque/teclas estiverem bloqueados. Depois, testar em janela reservada, preservando os serviços de acessibilidade existentes e restaurando o estado anterior.

**Sobre C e a instalação automática**

O script pode procurar o serviço **localmente no Termux**, reconectar com pareamento já autorizado e tentar restabelecer o transporte. Mas descobrir a porta não liga um serviço de depuração desligado. Termux:Boot também não garante execução contínua sob as restrições do HyperOS. O ADB documenta dependências de pareamento, rede e descoberta mDNS; não presuma que a descoberta atravessará a Tailscale. [ADB sem fio](https://developer.android.com/tools/adb#wireless).

Eu usaria um executor no servidor que instala somente quando o aparelho estiver acessível e na janela combinada: CI aprovado, pacote/assinatura esperados, versão confirmada após `install -r`. Celular offline significa **adiar**, sem tentativas incessantes. Evitar dois atualizadores concorrendo com o Obtainium.

**HAL e sensores**

O app **já consulta CameraX Extensions** em [CameraScreen.kt:295](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/CameraScreen.kt:295), incluindo disponibilidade por seletor de câmera. Primeiro, coletaria esse resultado no POCO.

Tags `superResolution.*`, `mfnr` e `insensorzoom` não demonstram acesso público utilizável. CameraX Extensions depende da implementação do fabricante e não oferece um modo público genérico “superResolution”. Não escreveria vendor tags por tentativa. [Extensões oficiais](https://developer.android.com/media/camera/camera-extensions).

Com sensores, começaria por **nível de horizonte** e indicação de movimento durante captura longa. Ambos têm benefício claro; exigem validação de orientação e suspensão dos sensores fora da tela de câmera.

**Regras operacionais curtas**

- Executar apenas em janela combinada; interromper se o dono retomar o uso. Tela apagada não significa autorização para testar.
- Não desbloquear, ler notificações, explorar galerias pessoais nem capturar áudio/ambiente por padrão.
- Usar dados sintéticos; apagar somente arquivos e blocos identificados como criados pelo teste. Nunca `pm clear` no app pessoal.
- Como política inicial: bateria ≥40%, sessões ≤5 minutos e parada ao estado térmico `MODERATE` ou superior.
- Sem dados móveis ou IA paga por padrão; limitar transferências, especialmente em DERP.
- Restaurar configurações alteradas mesmo em falhas; evitar mudanças de rede que derrubem o próprio acesso.
- Manter ADB autenticado, sem exposição pública; restringir acesso na Tailscale e verificar também a exposição da porta 5555 na rede local.
- Guardar evidências pelo menor tempo necessário, sem fotos pessoais, tokens ou conteúdo da agenda.

**Item A: textos exatos e localização**

Aplicaria a semântica no **contêiner clicável**, com `Role.Button`.

| Controle/local | `contentDescription` |
|---|---|
| [Obturador, linha 948](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/CameraScreen.kt:948) — foto | `"Tirar foto"` |
| Mesmo controle — vídeo parado | `"Iniciar gravação de vídeo"` |
| Mesmo controle — gravando | `"Parar gravação de vídeo"` |
| [Miniatura, linha 944](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/CameraScreen.kt:944) | `"Abrir galeria"` |
| [Temporizador, linha 693](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/CameraScreen.kt:693) | `"Alterar temporizador"` |

Para o temporizador, separar o estado em `stateDescription`: **`"Desativado"`**, **`"3 segundos"`** ou **`"10 segundos"`**. Evitar anunciar novamente o número desenhado dentro do ícone. Essa separação entre nome, papel e estado segue a [semântica do Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics).

Uma correção no achado: a miniatura já tem `"Última"` no `AsyncImage`, mas **somente quando existe imagem**. A descrição deve ficar no `Box`, inclusive vazio; a imagem interna passa a decorativa (`null`).

No obturador, também revisaria o estado desabilitado durante processamento/contagem, preservando a ação de parar vídeo. Para automação durável, acrescentaria identificadores de teste estáveis, sem transformar os textos falados pelo TalkBack em identificadores técnicos.
