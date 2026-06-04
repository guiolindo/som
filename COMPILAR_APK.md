# Como compilar o APK sem instalar nada no PC

GitHub Actions compila pra você na nuvem em ~5 minutos, **grátis**.
Zero instalação local. Você só baixa o `.apk` pronto.

## Passo a passo (uma vez só)

### 1. Criar conta no GitHub
- Acesse <https://github.com/signup>
- Email + senha. Confirma email. Pronto.

### 2. Criar repositório
- Logado, clica no `+` no topo direito → **New repository**
- Nome: `pc-audio-stream` (qualquer nome)
- Pode deixar **Public** ou **Private** (Actions é grátis em ambos)
- **Sem** README/gitignore/license (já temos no projeto)
- Clica **Create repository**

### 3. Subir o projeto (a partir desta pasta)

Abre o `cmd.exe` aqui na pasta `Stream som` e cola:

```cmd
git init
git add .
git commit -m "Primeira versao"
git branch -M main
git remote add origin https://github.com/SEU-USUARIO/pc-audio-stream.git
git push -u origin main
```

Substitui `SEU-USUARIO` pelo seu nome de usuário do GitHub.

Se não tiver `git` instalado: baixa em <https://git-scm.com/download/win>
(é pequeno, ~50MB).

### 4. Esperar o build
- No GitHub, vai no seu repo → aba **Actions**
- Verá "Compilar APK do app Android" rodando
- Espera 4-7 minutos (primeira vez demora mais, próximas usam cache)
- Quando ficar verde ✅, clica nele

### 5. Baixar o APK
- Na página do build verde, lá no fim tem **Artifacts**
- Clica em **PCAudioStream-1** (ou o número que aparecer)
- Baixa um zip → dentro tem o `app-debug.apk`

### 6. Instalar no celular
- Manda o `.apk` pro celular (WhatsApp Web, e-mail, cabo USB, qualquer jeito)
- No celular, abre o arquivo
- Vai pedir pra permitir "instalar de fontes desconhecidas" — autoriza
- Instala. Abre o app.

### 7. Usar
- No app, digita o IP do PC (ex `192.168.100.149`)
- Porta fica 8765
- Iniciar reprodução

---

## Builds depois da primeira

Sempre que eu mandar alterações pro código:

```cmd
git add .
git commit -m "atualizacao"
git push
```

GitHub Actions compila sozinho. Você baixa o APK novo em 5 minutos.

---

## Alternativas se não quiser GitHub

- **Codemagic** <https://codemagic.io> — 500 min/mês grátis, interface visual
- **Bitrise** <https://bitrise.io> — 200 builds/mês grátis
- **AppCircle** <https://appcircle.io> — tem free tier

Os três aceitam upload direto do zip do projeto ou conexão com GitHub.

GitHub Actions é o mais rápido e simples. Recomendo começar por ele.
