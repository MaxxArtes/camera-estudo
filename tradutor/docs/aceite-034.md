# Tradutor 0.34 — aceite no aparelho

Leitor principal, sessão manual de tela inteira e acessibilidade opcional com prioridade.
Sem assistente nesta versão. `versionCode = 34`, `minSdk = 26`, `compileSdk = 34`, `targetSdk = 34`.

## Compilação e harness pelo responsável

- Compilar somente `:tradutor:assembleDebug` ou `:tradutor:assembleRelease` no ambiente com SDK.
- Executar `tradutor/testes-jvm/roda034.sh` no ambiente do harness. Ele chama também `roda033.sh`,
  preservando os testes de junção por altura da linha, cache da 0.31, contrato antigo e OCR em faixas.
- `Teste034.kt` compila contra `RegrasSessao.kt` real, sem classes Android. `SRC`, `HARNESS` e
  `KOTLINC` permitem apontar para os fontes e ferramentas já existentes.
- Nesta execução foram feitas apenas revisão dos fontes, validação do XML, sintaxe do shell e
  conferência de diff. Não houve compilação Android nem execução dos harnesses.

## Plano de aceite do dono, sem adb

1. Desligar acessibilidade: abrir dois capítulos conhecidos por compartilhamento; traduzir, ouvir
   e reabrir sem pedir sua ativação. O leitor deve constar sempre pronto.
2. Autorizar sobreposição e iniciar sessão. Fazer 20 capturas entre dois apps, incluindo páginas
   completamente paradas. Não pode aparecer bolha incorporada, imagem anterior nem novo consentimento
   dentro da mesma sessão. Conferir pintura alinhada, voz, botão Parar e Voltar à página.
3. Girar a tela antes, durante e depois de uma captura: a congelada anterior deve fechar; um novo
   toque captura na nova geometria. Negar consentimento, testar tela protegida/preta e ausência de
   quadro: erro explícito, sem travamento e sem tradução anterior. Verificar também uma página
   escura legítima com balões claros.
4. Encerrar pela tela do app e pela notificação. Parar pelo indicador do sistema, bloquear/desbloquear,
   iniciar outro gravador e forçar parada/reabrir. A sessão não pode voltar sozinha; o estado deve
   orientar um novo consentimento. Retirar sobreposição também deve encerrar a sessão.
5. Usar por 30 minutos no HyperOS habitual e registrar interrupções, mortes inesperadas e incômodo
   dos consentimentos. A sobrevivência no HyperOS depende desta validação.
6. Ligar acessibilidade durante uma sessão: a projeção, notificação e bolha manual devem desaparecer
   antes da bolha avançada. Revalidar congelada, voz, contínuo, preparo por rolagem e gestos da 0.33.
   Desligar a acessibilidade não reinicia projeção: exige iniciar sessão e novo consentimento.

## Implementação a conferir

Permissões novas: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE` e
`FOREGROUND_SERVICE_MEDIA_PROJECTION`. Serviço não exportado com tipo `mediaProjection`.
Um `VirtualDisplay` por sessão; rotação usa `resize` e troca de superfície. Cada toque esconde
a bolha, espera 90 ms, drena/fecha o ImageReader anterior e conecta um novo ao mesmo display,
para obter quadro fresco inclusive sem rolagem. Os pixels não são gravados nem enviados ao servidor.
O teste de preto é conservador (conteúdo pelo menos 99,9% quase preto); a mensagem não afirma
que houve proteção. Sem quadro novo em 3 segundos, a captura falha explicitamente.
