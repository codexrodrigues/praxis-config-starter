AGENTS.md - Praxis Config Starter

Escopo e Heranca
- Escopo: aplica-se a `praxis-config-starter` e subpastas.
- Herda: segue o `AGENTS.md` da raiz do monorepo. Este arquivo adiciona regras locais.
- Foco deste guia: preservar a fronteira canonica de `/api/praxis/config/**`, contratos AI, release Maven Central e validacao downstream com `praxis-api-quickstart`.
- Nao editar por padrao: `target/`, `logs/`, artefatos em `artifacts/`, outputs de apidoc e arquivos gerados por build, salvo quando a tarefa for explicitamente sobre output gerado.

Premissa Local
- Neste starter, assumir como direcao preferencial que o Praxis e uma plataforma de decisoes semanticas authoradas por IA.
- Sempre que uma necessidade de authoring, explainability, preview, governance ou runtime decision surgir, preferir modelar o problema como decisao canônica governada em `/api/praxis/config/**`, e nao como patch solto, editor local ou mero artefato de componente.

Inventario Local de Aderencia Antes de Novo Contrato
- Antes de criar ou alterar contrato em `/api/praxis/config/**`, contratos AI, DTOs, eventos SSE, validators, compilers, manifests ou payloads, auditar primeiro o fluxo existente do starter e dos consumidores.
- A pergunta obrigatoria e: o que o `praxis-config-starter` ja sabe por `ai_registry`, `api_metadata`, templates, contexto, diagnostics, quick replies, previews, warnings, headers, ETag ou historico, mas ainda nao esta sendo bem materializado?
- Classificar cada melhoria como `ja-suportado-so-ux`, `ja-suportado-mal-nomeado-ou-mal-materializado`, `suportado-parcialmente` ou `lacuna-real-de-contrato`.
- So `lacuna-real-de-contrato` autoriza novo contrato. Nesse caso, explicitar dado faltante, fonte canonica, consumidores impactados, artefatos derivados e validacao minima antes de implementar.
- Nao criar uma camada paralela de authoring ou status apenas porque um consumidor ainda nao esta projetando corretamente a semantica que o starter ja publica.

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
- Quando houver disputa entre manter o foco em authoring de componente e promover authoring de decisao, a recomendacao local deve favorecer authoring de decisao canônica com materializacao derivada.
- O starter nao deve depender de semantica local do `praxis-api-quickstart` para definir contrato.
- O quickstart e o host operacional de prova downstream. Use-o para validar consumo real, nao para redefinir a semantica canonica do starter.
- O starter deve funcionar como repositorio isolado no GitHub Actions. Nao assuma que o checkout contem a raiz completa do monorepo.

Roteamento Semantico de Intencao
- `praxis-config-starter` e a fronteira canonica de backend para resolver intencao de authoring agentico, validar decisoes, compilar planos e materializar alteracoes governadas.
- `AiOrchestratorService`, manifests de authoring, validators, compilers, previews e controllers nao devem decidir a intencao primaria do usuario por palavras-chave, regex, `contains`, listas de termos, normalizacao textual ou fast paths locais.
- O fluxo correto e: contexto governado + catalogos semanticos + manifests de operacoes + LLM/tooling resolvem primeiro a intencao canonica; somente depois resolvers locais podem usar aliases, fuzzy search, matching aproximado ou normalizacao textual para ranquear campos, surfaces, filtros, actions ou candidatos.
- Se uma operacao necessaria ainda nao existir no contrato, modele a operacao/tool canonica apropriada em vez de criar um caminho deterministico por texto.
- Qualquer heuristica textual residual deve declarar explicitamente que nao e roteador primario de intencao, qual decisao semantica previa a habilita e como ela sera removida ou migrada para contrato canonico.
- Fast paths historicos que ainda existirem devem ser tratados como legado tecnico: mantenha-os estreitos, testados, inventariados e fora do caminho preferencial de novas capacidades.

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
- O input `config_artifact_source` torna a proveniencia explicita: `source-checkout` instala o starter do checkout para a prova pre-release; `maven-central` baixa e valida o SHA-512 da coordenada fixada pelo Quickstart para a prova pos-release. Nos dois modos, o gate empacota o host e exige identidade byte a byte entre o artefato de referencia e o JAR aninhado.
- `quickstart_ref`, `metadata_ref` e `ui_ref` devem ser SHAs imutaveis de 40 caracteres. Branches moveis, inclusive `main`, falham antes dos checkouts downstream.
- O input `paid_gate_lane` e exclusivo: use `none` para validacao deterministica, `http-sse` para a jornada HTTP paga, `page-builder` para o gate browser ou `llm-compliance` para o shadow de compliance. Nunca combine lanes pagas no mesmo corte.
- Toda lane diferente de `none` deve aguardar aprovacao no GitHub Environment protegido `ai-paid-gates`; o dispatch sozinho nao autoriza custo nem libera secrets ao job principal.
- Para mudancas que toquem fluxo agentic do page-builder, SSE browser, patch/apply ou contrato ponta a ponta com Angular, selecione `paid_gate_lane=page-builder` e nao execute outra lane paga por reflexo.
- Para release, manter `page_builder_e2e_mode=smoke`. Usar `page_builder_e2e_mode=full` apenas quando a investigacao exigir deliberadamente a matriz browser/LLM completa.
- O gate opcional faz checkout de `praxis-ui-angular`, sobe o quickstart em loopback na porta `8088`, Angular em loopback na porta `4003` e executa `praxis-page-builder-agentic-production-like.playwright.config.ts` contra PostgreSQL/pgvector, LLM e embeddings reais, com stream em modo `signed-url-token` e segredo efemero.
- A fonte unica de timeouts, retries e contagens esperadas e `tools/e2e/page-builder-agentic-gate-matrix.json`. Testes com mocks pertencem a lane/config `mocked` e nunca contam como evidencia production-like.
- Secrets do gate:
  - `PRAXIS_AI_OPENAI_API_KEY` para `provider=openai`;
  - `PRAXIS_AI_GEMINI_API_KEY` para `provider=gemini`;
- O workflow deve gerar e mascarar uma chave efemera por run para
  `PRAXIS_RESOURCE_VERSION_ETAG_SECRET`; nao adicionar segredo padrao ao
  runtime nem exigir configuracao manual desse valor para o smoke.
- `RELEASE_PAT` para releases com `create_tag=true` e quando o checkout do quickstart precisar de permissao adicional. O token de release deve permitir `contents:write`, pois pushes feitos com `GITHUB_TOKEN` nao disparam o workflow subsequente de publicacao por tag.
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
- Smoke local deterministico com quickstart:
  - `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -DomainRuleLifecycleOnly`
- Smoke local pago HTTP/SSE, somente depois de aprovacao deliberada do custo:
  - adicionar `-ConfirmPaidProviderRun` e nao combinar com `-DomainRuleLifecycleOnly`.
- E2E local do page-builder agentic:
  - smoke de release pago: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-PbAgenticFullE2E.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -UiRoot ..\praxis-ui-angular -ValidationMode smoke -ConfirmPaidProviderRun`
  - matriz completa deliberada: adicionar `-ValidationMode full`.
- Disparo local do workflow GitHub quando `gh` estiver autenticado:
  - deterministico: `gh workflow run agentic-authoring-smoke.yml --repo codexrodrigues/praxis-config-starter -f provider=openai -f paid_gate_lane=none`
  - prova publicada: adicionar `-f config_artifact_source=maven-central` somente depois que o Quickstart pinado consumir uma coordenada disponivel no Maven Central;
  - pago: substituir `none` por exatamente uma lane aprovada; nunca disparar uma sequencia de lanes para diagnostico exploratorio.

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
