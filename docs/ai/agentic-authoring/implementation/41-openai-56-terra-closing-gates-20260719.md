# Closing gates com OpenAI gpt-5.6-terra — 2026-07-19

## Escopo

Esta evidência fecha o gate de consistência estendido em três repetições e registra o primeiro gate browser `production-like` executado em seguida. O provider foi OpenAI e o modelo foi `gpt-5.6-terra`.

## Gate HTTP estendido x3

Artefatos locais: `artifacts/local-e2e/openai-gpt-5.6-terra-extended-x3-closing-20260718`.

- 48/48 turnos aprovados;
- acurácia obrigatória: 100%;
- acurácia estendida: 100%;
- provas transacionais: 3/3;
- mediana terminal: 28,973 s;
- p95 terminal: 53,489 s;
- média de 8.607 tokens por turno e máximo de 45.143;
- custo total estimado de USD 0,852613, média de USD 0,017763 por turno e máximo de USD 0,113895;
- nenhum timing de fase negativo.

A auditoria posterior encontrou mistura de idioma no evento público `intent.resolved` do cenário inglês: a resposta terminal estava correta, mas `userFacingUnderstanding` passava novamente pela sanitização portuguesa sem o `responseLocale` da requisição.

## Correção de locale

O `AgenticAuthoringTurnEngine` agora passa a requisição ao construir `userFacingUnderstanding`, reutilizando a apresentação locale-aware já usada pelo resultado terminal. O corpus inglês também proíbe explicitamente termos portugueses recorrentes, fazendo a regressão bloquear o gate.

Validações:

- `AgenticAuthoringPresentationTextTest`;
- `AgenticAuthoringTurnEngineTest`;
- `AgenticAuthoringAssistantConsistencyCorpusTest`;
- prova OpenAI real focal `platform-what-can-i-do-en`: 1/1, 13,144 s, 7.428 tokens, custo estimado de USD 0,005876.

Artefatos focais: `artifacts/local-e2e/openai-gpt-5.6-terra-en-locale-fix-proof-20260719`.

## Gate browser production-like

A auditoria de source passou antes do browser, incluindo o teste negativo que rejeita interceptação de endpoints críticos. O host Angular foi servido no origin oficial `http://localhost:4003`, com proxy explícito para o Quickstart local em `http://localhost:8088`.

Primeira execução completa:

- 12 cenários: 3 aprovados, 3 ignorados pela matriz e 6 falhos;
- os dois cenários de Project Knowledge falharam antes do authoring porque o processo Playwright não havia herdado as variáveis `CONFIG_DATASOURCE_*`;
- o Fluxo 0 falhou inicialmente por timeout do provider usando o default operacional `gpt-5-mini`;
- jornadas de dashboard, formulário e refinamento expuseram lacunas de contexto governado, reparo acionável e chegada à revisão.

Após corrigir somente o ambiente de execução e reiniciar o Quickstart com `gpt-5.6-terra`, o Fluxo 0 respondeu, ofereceu quick replies e avançou até a materialização. A prova focal então revelou a lacuna estrutural seguinte: pelo menos uma requisição posterior ao primeiro turno não carregou o `sessionId` canônico criado pelo backend.

## Decisão de fechamento

O gate HTTP está consistente, mas o corte ainda não está aprovado para release porque o gate browser production-like permanece vermelho. O próximo passo canônico é corrigir a preservação do `sessionId` na ponte `@praxisui/ai` / Page Builder, validar o lifecycle de Project Knowledge com o ambiente completo e repetir a matriz production-like.

