# Instruções para agentes (OpenCode, Claude Code)

- Projeto de estudo em Kotlin + Compose + CameraX. Português do Brasil nos textos e comentários; sem emoji.
- Não há SDK Android neste servidor: NÃO tente rodar `gradle` aqui. Quem compila é o GitHub Actions
  (`.github/workflows/build.yml`) a cada push em `main`; o APK sai no release "ultimo".
- Mantenha `minSdk 26`, `compileSdk 34`, versões dos plugins em `settings.gradle.kts`.
- Toda mudança: editar, `git commit`, `git push`, e conferir o Actions. Se o build falhar, ler o log e corrigir.
- Nada de chaves, senhas ou dados pessoais no código.
