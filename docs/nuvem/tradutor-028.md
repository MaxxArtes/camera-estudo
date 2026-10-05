# Instrução para sessão na nuvem: Tradutor 0.28, a leitura em voz alta no leitor de capítulo

Você é o executor. O orquestrador (outra sessão, no servidor do dono) já decidiu o desenho. Trabalhe SÓ no módulo
`tradutor` (pacote br.maxymus.tradutor). Não mexa em `app`, `galeria` nem `.github/`: outra sessão pode estar
mexendo em `app` agora.

Regras:
- O clone da sessão pode estar velho. Rode `git fetch origin` e crie o ramo `tradutor-028` a partir de `origin/main`,
  que já tem a 0.27 com Voz.kt. Commits pequenos nesse ramo e, no fim, UM pull request para `main`. NUNCA faça push para
  `main`: o CI publica no celular do dono.
- Português do Brasil, sem emoji, comentário só onde o porquê não é óbvio, no estilo dos arquivos.
- Telemetria só com números e códigos fixos: nada de texto, URL, nome de app nem hash de conteúdo.
- Reaproveite o Voz.kt da 0.27 sem enfraquecer nada dele: voz pt-BR offline obrigatória, setVoice conferido, geração
  contra callbacks velhos, foco de áudio, fone, tela desligada e shutdown.

## O que fazer (desenho aprovado pelo revisor Astra)

1. **Dados de leitura por quadro.** Hoje o leitor guarda a imagem pintada (`<i>t_<destino>.jpg`), mas não a lista de
   falas. Guarde junto, em arquivo no mesmo diretório do capítulo, as falas TRADUZIDAS de cada quadro: texto, posição
   (retângulo na imagem), ordem de leitura (a mesma regra da 0.27: faixas de cima para baixo, tolerância de 0,6 x a
   altura mediana das linhas, e dentro da faixa da esquerda para a direita), idioma de destino e a versão da tradução.
   Não faça OCR da imagem pintada para reconstruir isso. Esses metadados seguem a limpeza de capítulos que já existe e
   ficam fora de log e de backup.
2. **Barra de baixo do leitor.** Ela passa a ter "Ver original", "Ouvir" (que vira "Parar" durante a leitura) e
   "Quadro X de N". Com fonte grande, quebra em duas linhas. Junto aos controles, a opção "Avançar automaticamente",
   DESLIGADA no início, com a explicação "Ao terminar um quadro, passa para o próximo.". A escolha vale durante aquela
   abertura do leitor. "Voltar" continua na barra de cima.
3. **Comportamento.**
   - "Ouvir" começa pelo quadro com MAIOR área visível (no empate, o primeiro). O indicador "Quadro X de N" mostra o
     mesmo quadro que vai ser ouvido.
   - Sem "Avançar automaticamente": lê as falas desse quadro e para ("fim").
   - Com "Avançar automaticamente": ao terminar, rola um pouco até o próximo quadro e continua. Em quadro alto, antes
     de uma fala fora da área visível, rola só o necessário para mostrá-la. Essa rolagem do próprio app NÃO interrompe
     a leitura.
   - Arrasto manual, rolagem por acessibilidade ou comando de navegação interrompem a voz e o avanço
     (motivo=rolagem).
   - As barras ficam VISÍVEIS durante a preparação e a leitura (hoje o leitor as esconde em qualquer rolagem, e isso
     esconderia o "Parar").
   - Quadro ainda em preparo: mostra "Preparando próximo quadro…" e mantém o "Parar". Se a preparação falhar, para com
     "Não foi possível preparar o próximo quadro." e mantém o "Tentar tradução" que já existe.
   - Quadro confirmado sem fala traduzida é pulado; quadro com erro não é. Evite uma sequência rápida de animações em
     páginas sem texto.
   - No fim do capítulo: "Fim do capítulo.", sem repetir.
   - "Ver original" interrompe a voz (motivo=original). No original, o "Ouvir" fica indisponível com
     "Selecione 'Ver traduzido' para ouvir.".
   - Sair do leitor, bloquear a tela ou desligar a tela para a leitura.
4. **Áudio.** Para capítulo contínuo, peça foco de duração longa (AUDIOFOCUS_GAIN). Qualquer perda de foco para a
   leitura sem retomar. Fone desconectado para. Nunca mexa em volume nem em saída.
5. **Acessibilidade.** Alvos de pelo menos 48 dp. Não mude o foco de acessibilidade automaticamente durante o avanço.
   Não leia cada fala ao mesmo tempo pelo TalkBack e pelo TTS.
6. **Telemetria.** ouvir_iniciou com onde=leitor, só no onStart da primeira fala. ouvir_terminou uma vez por sessão,
   com motivo (fim, fim_capitulo, parar, rolagem, original, foco_audio, saida_audio, tela_fechada, tela_desligada,
   erro_quadro, erro) e lidas.
7. **Versão.** tradutor/build.gradle.kts: versionCode 28, versionName "0.28".

## Como provar
- Escreva as regras puras (escolha do quadro inicial, ordem das falas, pular ou não pular quadro, quando a rolagem
  interrompe) em funções sem Android, para o orquestrador testar.
- Tente compilar o módulo (`./gradlew :tradutor:compileReleaseKotlin`, instalando o Android SDK de linha de comando se
  precisar). Se não der, diga isso no PR.
- No PR: o que mudou, arquivo por arquivo; o que só dá para validar no aparelho; e os riscos.
