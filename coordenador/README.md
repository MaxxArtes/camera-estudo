# Coordenador (app Android)

Casca Kotlin do painel do coordenador (https://coordenador.maxymus.dev.br). O app abre o painel numa Trusted Web
Activity do Chrome: tela cheia, ícone próprio, o mesmo login do Google do navegador e o painel inteiro, sem refazer
tela. Dentro de uma WebView o Google recusa o login, por isso não há WebView nem cópia da interface.

- **Build próprio:** `gradle -p coordenador assembleRelease` (settings.gradle.kts desta pasta). Mudar este app não
  recompila nem republica a câmera, a galeria ou o tradutor.
- **Publicação:** `.github/workflows/coordenador.yml` compila em todo PR e, a partir da `main`, publica
  `coordenador/coordenador.apk` e `coordenador/releases.json` no R2. Mesma chave de assinatura dos outros apps.
- **Atualização:** a cada abertura o app consulta o releases.json em segundo plano; na abertura seguinte, se houver
  versão nova, pergunta antes de abrir o painel. "Baixar" abre o APK no navegador; "Depois" cala a mesma versão por 24 h.
- **Testes:** `MEMCAP=1800M /opt/pocket-lm/heavy.sh coordenador/testes-jvm/roda.sh` (regra de atualização, na JVM).

## Tela cheia depende do site

O Chrome só tira a barra de endereço quando o site declara o app em `/.well-known/assetlinks.json`, com o pacote e a
impressão SHA-256 da assinatura (`assetlinks.json` desta pasta; a CI falha se a impressão da chave não estiver nele).
O Chrome busca esse arquivo sem cookie, então ele precisa sair antes do login. No Caddy do x86:

```
coordenador.maxymus.dev.br {
	encode gzip
	# Público de propósito: o Chrome confere o vínculo com o app sem cookie. Só pacote e impressão da assinatura.
	handle /.well-known/assetlinks.json {
		header Content-Type application/json
		respond `<conteúdo de coordenador/assetlinks.json>` 200
	}
	handle {
		reverse_proxy 127.0.0.1:4180 {
			flush_interval -1
		}
	}
}
```

Sem o arquivo no site o app funciona do mesmo jeito, com uma barra de endereço fina no topo.
