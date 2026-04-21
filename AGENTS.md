AGENTS.md - Praxis Config Starter

Escopo e Heranca
- Escopo: aplica-se a `praxis-config-starter` e subpastas.
- Herda: segue o `AGENTS.md` da raiz do monorepo. Este arquivo adiciona regras locais.
- Foco deste guia: preservar a fronteira canonica de `/api/praxis/config/**`, contratos AI, release Maven Central e validacao downstream com `praxis-api-quickstart`.
- Nao editar por padrao: `target/`, `logs/`, artefatos em `artifacts/`, outputs de apidoc e arquivos gerados por build, salvo quando a tarefa for explicitamente sobre output gerado.

Classificacao Padrao da Mudanca
- `docs-apenas`: mudancas restritas a `AGENTS.md`, `README.md`, `RELEASING.md`, `docs/**` ou comentarios/Javadoc sem efeito em contrato ou comportamento.
- `local-pequena`: mudanca confinada ao starter, sem alterar endpoints, headers, ETag, entidades persistidas, contratos AI ou artefatos de release.
- `transversal`: mudanca que exige sincronizar starter, quickstart, Angular, docs publicas, workflow, scripts ou consumidores.
- `contrato-publico`: mudanca em `/api/praxis/config/**`, headers, ETag, modelos de request/response, contratos AI, `ai_registry`, `ui_user_config`, templates, publicacao Maven ou workflow de release.
- `arquitetural`: mudanca que altera fronteiras entre config starter, metadata starter, quickstart, runtime Angular, RAG, tools backend ou modelo canonico de authoring.

Fronteira Canonica Local
- `praxis-config-starter` e a fonte canonica de persistencia e semantica de:
  - `ui_user_config`;
  - `ai_registry`;
  - `api_metadata`;
  - templates;
  - headers de tenant/usuario/ambiente;
  - ETag de configuracao;
  - endpoints sob `/api/praxis/config/**`;
  - contratos AI, authoring manifests, validacao, compilacao de patch e streaming SSE.
- O starter nao deve depender de semantica local do `praxis-api-quickstart` para definir contrato.
- O quickstart e o host operacional de prova downstream. Use-o para validar consumo real, nao para redefinir a semantica canonica do starter.
- O starter deve funcionar como repositorio isolado no GitHub Actions. Nao assuma que o checkout contem a raiz completa do monorepo.

Areas de Alto Risco Local
- `src/main/java/org/praxisplatform/config/**`
- `src/main/resources/**`
- `docs/ai/**`
- `tools/**`
- `.github/workflows/**`
- `pom.xml`
- `README.md`
- `RELEASING.md`
- contratos JSON em `docs/ai/agentic-authoring/contracts/**`

Regras Locais Obrigatorias
- Nao improvisar endpoints, headers, aliases ou payloads fora de `/api/praxis/config/**` sem revisar a fonte canonica.
- Mudancas em contratos AI devem atualizar tipos, validadores, docs, scripts de smoke e artefatos derivados relevantes no mesmo ciclo.
- Se um script em `tools/` precisar rodar no GitHub Actions, ele deve resolver paths relativos ao proprio repo quando possivel e nao depender da raiz do monorepo.
- Nao imprimir secrets ou chaves de API em logs. Scripts devem usar env vars, GitHub secrets ou arquivos locais ignorados.
- Para authoring/SSE, manter cobertura de `start`, `probe`, stream, evento terminal, replay e cancelamento quando o escopo tocar essa superficie.
- Para authoring executavel, manifests e validators devem ser tratados como contrato publico, nao como hints documentais.
- Se a tarefa revelar drift entre docs, workflow e comportamento real, atualize `README.md`, `RELEASING.md` ou `docs/ai/**` no mesmo ciclo.

Release e Gate de Authoring
- Antes de criar tag/publicar no Maven Central, o gate recomendado e `Agentic Authoring HTTP Smoke`.
- Workflow: `.github/workflows/agentic-authoring-smoke.yml`.
- O workflow instala o starter do checkout no Maven local do runner, empacota `praxis-api-quickstart` contra essa versao local e roda o smoke HTTP/SSE completo.
- O `quickstart_ref` padrao deve apontar para um ref conhecido com dependencias publicadas no Maven Central; nao usar `main` como default enquanto ele puder depender de releases ainda nao publicadas.
- Para mudancas que toquem fluxo agentic do page-builder, SSE browser, patch/apply ou contrato ponta a ponta com Angular, habilitar tambem o input `run_page_builder_full_e2e=true` nesse mesmo workflow.
- O full gate opcional faz checkout de `praxis-ui-angular`, sobe o quickstart em `8088`, Angular em `4003` e executa `praxis-page-builder-agentic-validation.playwright.config.ts` contra LLM real e stream em modo `signed-url-token`, com retry controlado para absorver variacao nao deterministica do provedor.
- Secrets do gate:
  - `PRAXIS_AI_OPENAI_API_KEY` para `provider=openai`;
  - `PRAXIS_AI_GEMINI_API_KEY` para `provider=gemini`;
  - `RELEASE_PAT` quando o checkout do quickstart precisar de permissao adicional.
- Se o gate falhar em GitHub Actions, trate a causa real do log antes de publicar. Nao contorne o gate com publicacao manual.

Comandos de Validacao Local
- No Windows, use Maven instalado quando `mvn` nao estiver no PATH:
  - `D:\Developer\maven\apache-maven-3.9.6\bin\mvn.cmd`
- Mudancas docs-only:
  - leitura final do arquivo alterado;
  - `git diff --check`.
- Mudancas unit/smoke do starter:
  - `mvn -B -P ci-smoke-unit -T 1C clean verify`
- Mudancas localizadas em authoring/registry:
  - preferir testes focais de `src/test/java/org/praxisplatform/config/ai/**` antes de suite ampla.
- Smoke local completo com quickstart:
  - `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart`
- Full E2E local do page-builder agentic:
  - `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-PbAgenticFullE2E.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -UiRoot ..\praxis-ui-angular -StreamProcessingTimeoutSeconds 180`
- Disparo local do workflow GitHub quando `gh` estiver autenticado:
  - `gh workflow run agentic-authoring-smoke.yml --repo codexrodrigues/praxis-config-starter -f provider=openai`

Validacao Downstream
- Quando a mudanca tocar contrato publico, release, authoring, AI tools, streaming ou integracao real de host, validar com `praxis-api-quickstart`.
- Para validar versao publicada pelo Maven Central:
  - confirmar `praxis-api-quickstart/pom.xml` com a versao desejada;
  - executar `mvn -B verify` no quickstart;
  - quando necessario, rodar o smoke HTTP/SSE contra o jar empacotado.
- Para validar versao ainda nao publicada:
  - instalar o starter localmente;
  - empacotar o quickstart contra a versao local;
  - rodar o smoke HTTP/SSE.

Artefatos Derivados e Sincronizacao
- Alteracoes em `/api/praxis/config/**`, contratos AI ou release devem revisar:
  - `README.md`;
  - `RELEASING.md`;
  - `docs/ai/**`;
  - scripts em `tools/**`;
  - workflow em `.github/workflows/**`;
  - `praxis-api-quickstart/AGENTS.md` ou docs do quickstart quando a validacao downstream mudar.
- Se nao houver artefatos derivados a atualizar, declare isso explicitamente na resposta final.

Referencias Uteis
- `README.md`
- `RELEASING.md`
- `docs/ai/contracts/README.md`
- `docs/ai/agentic-authoring-streaming.md`
- `tools/Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1`
- `tools/Invoke-QuickstartAiPatchStreamHttpE2E.ps1`
- `.github/workflows/agentic-authoring-smoke.yml`
- `../praxis-api-quickstart/AGENTS.md`

Regra de Pronto
- A tarefa so termina quando ficar claro:
  - se a mudanca pertence ao starter ou a um consumidor;
  - qual validacao minima foi executada;
  - se o quickstart precisou ser usado como prova downstream;
  - quais docs, scripts, workflows ou contratos derivados foram revisados.
