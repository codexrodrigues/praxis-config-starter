# Seleção de campos e diagnóstico por turno

Classificação: `transversal`. Corte local de continuação da revisão humana de 2026-09-05.

## Inventário e plano executado

| Necessidade | Aderência | Fonte e consumidores |
| --- | --- | --- |
| Classificar a primeira falha | `ja-suportado-mal-nomeado-ou-mal-materializado` | Config já possui `AiProviderCallException.Kind`; o resolver perdia a categoria ao desembrulhar a causa |
| Preservar custo/diagnóstico por turno | `ja-suportado-mal-nomeado-ou-mal-materializado` | `decisionDiagnostics.providerTelemetry` já existe; Angular captura, Config exporta campos permitidos antes de validar o recibo |
| Formulário somente com campos escolhidos | `ja-suportado-mal-nomeado-ou-mal-materializado` | `MinimalFormPlan.fields` já exprime seleção; Config faz grounding/seleção semântica e compila `FormConfig.sections`/items existentes |
| Tipos, obrigatoriedade e opções | `ja-suportado-so-ux` | Metadata publica schema; Dynamic Form reconcilia e mantém sua autoridade |

Nenhum DTO, endpoint, input público ou DSL novo. Metadata, API Quickstart e bibliotecas públicas
não mudam neste corte. Landing consome a saída real do compilador em teste; sua prova reutiliza o
build com libs públicas 9.0.64. HTTP corpus, OpenAPI, manifests e barrels não precisam regeneração.
As evidências históricas de abril permanecem históricas; os novos fixtures são gerados em
`target/free-authoring` pelo teste do owner. Docs operacionais e guidance da skill foram atualizados.

## Comportamento implementado

- O erro classificado pelo provider prevalece sobre a causa de transporte aninhada. Timeout sem
  mensagem continua timeout; quota não vira timeout devido ao texto da causa.
- Cada turno observado guarda a telemetria canônica em attachment privado. O exportador publica
  somente campos permitidos, mesmo se apply/runtime ou o gate first-pass falhar depois.
- O schema fornece o catálogo de campos editáveis; a LLM seleciona campos usando o pedido e esse
  catálogo. Nomes exatos reconciliam a seleção já resolvida, sem roteamento textual de intenção.
- Controles, options, required e defaults são recuperados da fonte canônica. A LLM não pode
  inventá-los. Campo desconhecido/duplicado ou obrigatório omitido invalida o plano; o compilador
  não roda. O plano preserva o pedido de esclarecimento existente.
- O compilador materializa ordem/seleção em layout authorado. Não usa `sections: []` como atalho de
  inicialização. Hydration continua schema-driven, inclusive para validações e option sources.
- A seleção é de apresentação, não um mecanismo para alterar a política de submit do backend.
  Campos opcionais sem valor e não alterados são omitidos pelo pipeline existente. Defaults ou
  valores de contexto publicados pelo schema continuam sujeitos à política canônica de submit.

## Validação local

- 73 testes Java: resolver, serialização de telemetria e adapter OpenAI; dois negativos primeiro
  reproduziram a perda de categoria antes da correção.
- 339 testes Java: plano, preview, compilador, forward em dois domínios, turn engine e política de
  materialização. Seleção exata, metadados canônicos, campo inexistente e required omitido cobertos.
- 19 testes Node: exportador sanitizado e integração estática do runner/workflow.
- 17 testes Node de auditoria de source Angular e TypeScript focal de ambos os testes browser.
- 4 testes Chromium, sem retries: formulários e dashboards nos domínios staff/shipments. Teclado,
  required, option source remoto, apply/reload, somente name/groupId no layout e ausência de notes/group
  no POST do formulário; dashboard/cross-filter/modal preservados. Desktop e narrow inspecionados.

A semântica/provider, metadata, HTTP de domínio e persistência desses quatro casos são controlados;
os componentes e o compilador são reais. Isso não certifica a interpretação livre com LLM real.
PowerShell não está instalado no host macOS: o runner foi validado por wiring estático e exportador
Node executável, não por execução local do script Windows.

O modelo configurado permanece gpt-5-mini. O limite da conta é declarado pelo usuário, não verificado
independentemente. Não há reconstrução de custo desconhecido dos runs antigos.

## Fechamento do esclarecimento no runtime

A revisão posterior ao primeiro merge reproduziu mais dois problemas de consumo do estado existente:
`clarificationNeed.needed=true` não chegava a `assistantMessage`, e o classificador de reparo
considerava o preview inválido reparável. A correção publica a pergunta canônica e classifica o estado
como `user_clarification_required`, impedindo o reparo automático do preview.

Os dois testes de regressão falharam antes da correção; depois passaram as suites focais de preview,
classificação e turn engine: 303 testes, incluindo revalidação de casos já contabilizados acima.
O gate live `34003732255` foi iniciado no commit `e00035f1` e não contém este ajuste posterior;
a sua evidência deve ser atribuída ao SHA efetivamente executado, sem estendê-la a uma jornada de
seleção de campos que ele não executa. Config #457 e Angular #510 já passaram seus CIs e foram
incorporados. O primeiro dispatch `34003661988` foi recusado pela política de branch do ambiente,
antes do job com provider; nenhuma alteração na proteção foi feita.
