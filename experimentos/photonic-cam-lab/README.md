# PhotonCam Lab — experimento de soma óptica

Protótipo web independente para estudar **aditividade de intensidade luminosa** usando a câmera de um celular.

## Executar

1. Abra `index.html` em um navegador moderno para utilizar o **simulador**.
2. Para acessar a câmera, sirva a página em **HTTPS** ou em `localhost` (o navegador pode bloquear a câmera ao abrir um arquivo local).
3. Se o navegador não permitir câmera ao vivo, use **Usar foto** para importar as quatro capturas.
4. Para publicar no GitHub Pages: em **Settings → Pages**, escolha **Deploy from a branch**, branch `main`, pasta `/(root)`. A URL, depois da publicação, será `https://maxxartes.github.io/camera-estudo/experimentos/photonic-cam-lab/` (não presumir ativação automática).

## Experimento

Aponte duas luzes fracas (A e B) para a mesma superfície branca fosca e mantenha a câmera fixa. Capture os quatro estados:

1. A e B apagadas — iluminação de fundo `I0`.
2. Somente A — `IA`.
3. Somente B — `IB`.
4. Ambas ligadas — `IAB`.

Compare `IAB - I0` com `(IA - I0) + (IB - I0)`. O aplicativo exibe o erro relativo e exporta CSV. Evite saturação e luz ambiente variável.

## Limitações

- A combinação luminosa acontece **antes** da câmera; o aplicativo apenas **mede e compara**. Não é uma CPU/GPU óptica.
- A câmera do celular pode aplicar exposição automática, HDR, balanço de branco e resposta de cor não linear. A conversão aproximada para linear não substitui captura RAW calibrada.
- A subtração do simulador é uma operação matemática **em JavaScript**; não demonstra subtração óptica.
- Nenhuma imagem é enviada a servidores pelo aplicativo; o processamento acontece no navegador.

## Estrutura

- `index.html` — página completa em HTML, CSS e JavaScript, sem dependências externas.

Projeto experimental adicionado ao repositório `camera-estudo` em uma pasta isolada, sem modificar o aplicativo Android original.
