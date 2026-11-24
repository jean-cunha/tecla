# Tecla

Teclado Android com IA na barra: responde, traduz, corrige e reescreve o texto copiado, selecionado ou do campo, em qualquer aplicativo. Você usa a sua própria chave de API (OpenAI, OpenRouter, Gemini, Claude, Groq, DeepSeek, Ollama ou qualquer serviço compatível com o formato da OpenAI) e escolhe o modelo.

É um fork pessoal do [LeanType](https://github.com/LeanBitLab/LeanType), que por sua vez vem do HeliBoard/OpenBoard/AOSP. Toda a parte de digitação (layouts, dicionários, correção, gestos) é a deles; o Tecla acrescenta o painel de IA e a tela do app.

## Como usar

1. Instale o APK da última [Release](../../releases) (baixe pelo celular e abra o arquivo).
2. Abra o app **Tecla** e siga os três passos: ativar o teclado, escolher como teclado atual e colar a chave da API.
3. Em qualquer campo de texto, toque no **✦** da barra do teclado.

No painel:

- **Origem do texto**: o chip no topo mostra de onde vem o texto (seleção, copiado, texto do campo ou nenhum). Toque para trocar.
- **Ações rápidas**: Responder, Traduzir, Corrigir, Melhorar, Escrever resposta, Formal, Resumir. Dá para editar, reordenar e criar as suas no app.
- **Pedido livre**: digite qualquer coisa no campo do painel (o teclado continua embaixo) e envie.
- **Resposta**: aparece enquanto é gerada. Depois, *Substituir* (quando o texto veio do campo ou da seleção), *Inserir*, *Copiar* ou *Gerar de novo*.
- **Ajustes**: com a resposta na tela, toque em "Mais curto", "Outra versão" etc., ou escreva o ajuste no campo.
- **Histórico**: as últimas 40 respostas, guardadas só no celular.

A IA fica desligada em campos de senha. A chave da API é cifrada com o Android Keystore.

## Código

| Onde | O quê |
| --- | --- |
| `app/src/main/java/br/com/palora/tecla/core` | Configurações, chave cifrada, cliente de Chat Completions com streaming, prompts, histórico |
| `app/src/main/java/br/com/palora/tecla/panel` | O painel de IA dentro do teclado |
| `app/src/main/java/br/com/palora/tecla/app` | A tela do app (Compose) |
| `app/src/test/java/br/com/palora/tecla` | Testes e capturas de tela |

Ligações com o LeanType: `KeyCode.TECLA_AI`, `ToolbarKey.TECLA_AI`, o painel em `main_keyboard_frame.xml` e o roteamento das teclas em `KeyboardSwitcher` (o mesmo caminho da busca de GIFs).

## Build

A CI (`.github/workflows/tecla.yml`) roda os testes do Tecla, publica as capturas de tela na branch `capturas` e gera o APK assinado numa Release a cada push na `main`.

Localmente: `./gradlew :app:assembleStandardRelease` (JDK 17 e Android SDK 36).

## Licença

GPL-3.0, como o LeanType e o HeliBoard (ver `LICENSE`). Fontes Inter e Space Grotesk sob a SIL Open Font License 1.1 (`app/src/main/assets/tecla`).
