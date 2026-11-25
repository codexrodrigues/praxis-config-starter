# Segurança, Soberania de Provedores, Proveniência Epistêmica e Observabilidade do Agente Operacional

Este guia documenta a arquitetura de endurecimento de runtime do loop operacional do `praxis-config-starter` (`PraxisOperationalAgentLoop`), cobrindo:

1. Guarda determinística de segurança em 4 estágios (`AgentSecurityGuard` e `TurnSecurityState`);
2. Soberania de provedores e escalação no meio do turno (`ProfileSovereigntyAiProviderEligibilityPolicy`, `AiCallConfig.baseUrl` e isolamento de `apiKey`);
3. Composição modular de instruções e cache de prefixo (`AgentSystemInstructionContributor`, `SectionPlacement`, `AiThinkingProfile`);
4. Proveniência analítica, envelope epistêmico e projeção determinística de blocos (`AgentAnalyticalProvenance`, `AgentEpistemicEnvelope`, `StatisticalIntelligenceBlockContributor`);
5. Telemetria OpenTelemetry GenAI via Micrometer (`MicrometerGenAiAgentTelemetryRecorder`) e suítes de trajetória.

---

## 1. Segurança Determinística em 4 Estágios e Gate de Contaminação (*Taint Gate*)

O `PraxisOperationalAgentLoop` executa por padrão um `DefaultDeterministicAgentSecurityGuard` (ou uma cadeia combinada via `CompositeAgentSecurityGuard`, controlada por `praxis.ai.agent.security.mode=ENFORCE`). O estado de segurança é rastreado por turno em `TurnSecurityState`.

| Estágio | Método em `AgentSecurityGuard` | Comportamento canônico |
| --- | --- | --- |
| **Entrada e histórico** | `guardInboundUserInput(...)` e `guardHistoryEntry(...)` | Extrai identificadores grounded de entradas limpas; isola ou bloqueia padrões de override de sistema (`prompt-injection-user-message`, `prompt-injection-in-selection-hint`, `prompt-injection-in-history`), marcando o turno como `tainted`. |
| **Pré-ferramenta** | `guardBeforeToolExecution(...)` | Aplica o gate sobre ferramentas da classe `AgentToolSafetyClass.PROPOSAL_MUTATION`: se o turno estiver contaminado (`tainted-turn-mutation-blocked`), bloqueia propostas/mutações (*fail-closed*); também bloqueia alvos não confirmados (`ungrounded-mutation-target`) e escopos `QUERY` em lote sem filtros ou leitura prévia (`ungrounded-bulk-query-scope`). |
| **Pós-ferramenta** | `guardToolOutput(...)` | Coleta IDs retornados pelas ferramentas de leitura elegíveis (`query_resource_records`, `search_and_resolve_entities`, `get_entity_360_dossier`) para grounding e envolve campos textuais livres em tags `<untrusted_context_data field="...">...</untrusted_context_data>`, marcando o turno como `tainted` em caso de instrução suspeita embutida (`indirect-prompt-injection-in-tool-output`). |
| **Saída final** | `guardOutboundResponse(...)` | Remove delimitadores internos residuais (`<untrusted_context_data>`) antes de devolver a narrativa final ao usuário (`fencing-tag-stripped`). |

### Isolamento de Contexto para o Modelo (`modelSafeContext`) e Cofre de PII

- Antes de enviar metadados ao provedor LLM, o loop deriva um `modelSafeContext` via mascaramento estrutural Mod-11 (CPF/CNPJ) e máscara de e-mails brutos (`TurnScopedPiiVault` + `OperationalAssistantBlockComposer`), preservando o `effectiveContext` integral apenas para chamadas internas autorizadas no servidor.
- Todas as dicas dinâmicas da UI do host (`activeSelectionHints`) são sanitizadas contra quebras de tag (`</untrusted_context_data>`) e encapsuladas em `<untrusted_context_data type="active_selection_hints">` no final do prompt pelo `HostContextSystemInstructionContributor`.

---

## 2. Soberania de Provedores, Isolamento de `apiKey` e `baseUrl` por Chamada

`ProfileSovereigntyAiProviderEligibilityPolicy` implementa `AiProviderEligibilityPolicy` em modo *fail-closed* para impedir que dados ou rotas restritas trafeguem para provedores externos não autorizados.

### Triggers avaliados pela política de soberania

Uma requisição ou turno é marcado como restrito quando qualquer uma das condições abaixo é verdadeira:
- `propagatedClaims.sovereignOnly == true`;
- claims `executionProfile`, `dataClassification`, `sovereigntyProfile` ou `domainSensitivity` correspondem a `praxis.ai.agent.sovereignty.restricted-profiles`;
- `activeScreenResourcePath` ou `propagatedClaims.accessedResourcePaths` casa com `praxis.ai.agent.sovereignty.restricted-resource-prefixes` ou `restricted-path-segments`;
- `activeTopicKey` ou `propagatedClaims.accessedTopicKeys` casa com `praxis.ai.agent.sovereignty.restricted-topics`.

### Escalação no meio do turno e propriedades

- Durante a execução de ferramentas no loop ReAct, sempre que argumentos ou saídas de ferramenta contêm `resourcePath`, `topicKey`/`activeTopicKey` ou `sovereignOnly=true`, o loop registra o acesso em `TurnSecurityState` e chama `TurnSecurityState.escalateToSovereign()` quando aplicável. Nas rodadas seguintes de LLM e na síntese final (`enrichModelContextForTurn`), o contexto enriquecido restringe o roteador aos provedores listados em `praxis.ai.agent.sovereignty.sovereign-providers`.
- **`AiCallConfig.baseUrl`:** permite direcionar chamadas compatíveis ao endpoint soberano/dedicado configurado na chamada (observado por gateways compatíveis), sem alterar configuração global.
- **Isolamento de credencial em failover (`RoutingAiToolCallingProvider` / `AiProviderManagementService`):** quando ocorre failover para um provedor alternativo de outro vendor, o roteador nunca reaproveita a `apiKey` explícita do provedor primário; limpa a credencial anterior e resolve apenas a credencial pertencente ao provedor candidato de fallback.

| Propriedade | Padrão | Descrição |
| --- | --- | --- |
| `praxis.ai.agent.security.mode` | `ENFORCE` | Modo da guarda determinística em 4 estágios (`AgentSecurityMode`). |
| `praxis.ai.agent.sovereignty.restricted-profiles` | `""` | Lista de perfis/classificações que exigem provedor soberano. |
| `praxis.ai.agent.sovereignty.restricted-resource-prefixes` | `""` | Prefixos de caminho operacional que disparam restrição soberana. |
| `praxis.ai.agent.sovereignty.restricted-topics` | `""` | Tópicos que disparam restrição soberana. |
| `praxis.ai.agent.sovereignty.restricted-path-segments` | `""` | Segmentos de path sensíveis avaliados em `activeScreenResourcePath` e acessos no turno. |
| `praxis.ai.agent.sovereignty.sovereign-providers` | `""` | Provedores elegíveis sob contexto soberano (`strict-fail-closed=true` nega tudo se vazio). |
| `praxis.ai.agent.sovereignty.strict-fail-closed` | `true` | Nega execução caso o contexto seja restrito e não haja provedor soberano elegível. |

---

## 3. Instruções Modulares (`AgentSystemInstructionContributor`), Cache de Prefixo e `AiThinkingProfile`

O prompt de sistema do agente é composto pelo `AgentSystemInstructionComposer` em três compartimentos ordenados de `SectionPlacement` para maximizar acertos de *prompt caching* e isolar contexto dinâmico:

1. `STABLE_POLICY_PREFIX`: políticas estáticas e universais ordenadas primeiro (`CoreOperationalRulesInstructionContributor`, `SecurityBoundaryInstructionContributor`, `AnalyticalRecipeAndEpistemicInstructionContributor`);
2. `TENANT_CATALOG_SECTION`: resumo escopado de tópicos e catálogo de domínio (`ResourceDiscoverySummaryInstructionContributor`);
3. `VOLATILE_TURN_CONTEXT`: foco atual da tela do host e bloco `<untrusted_context_data>` sempre ao final (`HostContextSystemInstructionContributor`).

Para otimização de latência e custo:
- **Cache de prompt:** `OperationalAgentTurnStreamService` preenche `AiCallConfig.promptCacheKey` (`praxis-op-agent:<tenant>:<env>`) e os gateways aplicam `promptCacheKey` (OpenAI Responses via `OpenAiResponsesAgentToolGateway`) ou `cachedContentName` (Google GenAI via `GoogleGenAiAgentToolGateway`).
- **`AiThinkingProfile`:** perfis neutros de esforço/orçamento de raciocínio aplicados pelos gateways de modelo: `OFF`, `FAST_LOOKUP`, `OPERATIONAL_AUDIT` (padrão no stream operacional) e `DEEP_ANALYSIS`, além do campo opcional `thinkingBudgetTokens` em `AiCallConfig`.

---

## 4. Proveniência Analítica, Envelope Epistêmico e Blocos Determinísticos de UI

Resultados quantitativos carregam metadados estruturados separados da narrativa da LLM:

- **Proveniência de agregações e receitas verificadas (`AgentAnalyticalProvenance`):**
  - `VERIFIED_RECIPE`: produzida por `execute_verified_analytical_recipe`, inclui `recipeId`, `recipeTitle`, `recipeVersion`, `governanceTier`, `provenanceDigest`, `lockedFilters` impostos pelo servidor e `appliedParameters`.
  - `EXPLORATORY_UNVERIFIED`: produzida por `aggregate_resource_stats`, registra os parâmetros aplicados em `appliedParameters` e `lockedFilters` vazio.
  - Política configurável via `praxis.ai.agent.analytics.policy` (`ALLOW_EXPLORATORY`, `PREFER_VERIFIED_WHEN_AVAILABLE`, `STRICT_VERIFIED_ONLY`) ou `praxis.ai.agent.analytics.require-verified-recipes`.
- **Envelope epistêmico (`AgentEpistemicEnvelope`):** vincula descobertas estatísticas aos tipos epistêmicos canônicos (`DETERMINISTIC_LAW`, `CALIBRATED_PROBABILITY`, `STOCHASTIC_SIMULATION`, `CAUSAL_HYPOTHESIS`, `OPTIMIZATION_RESULT`) e estados de disponibilidade fail-closed (`AVAILABLE`, `INSUFFICIENT_SAMPLE`, `K_ANONYMITY_SUPPRESSED`, `ENGINE_UNAVAILABLE`, `DEGRADED`), junto com `statisticallySignificant`, `qValueFdr`, `sampleSize`, `suppressedCellCount`, `provenanceHash`, `modelCardId` e `datasetKey`.
- **Blocos emitidos pelo `StatisticalIntelligenceBlockContributor`:**
  - `epistemic-badge`: selo epistêmico com métricas de significância, exposição, conformidade Nigrini-Benford, `suppressedCellCount`, `failClosedNote`, `errorCode` e `provenanceHash`.
  - `widget-block` (`praxis-chart`): gráfico `boxplot` em escala robusta (`boxplot.scaleMethod = "mad"`, com campos `min`, `q1`, `median`, `q3`, `max`, `outliers`, `sampleSize` e `epistemicMeta`) e gráfico `combo` forense Nigrini-Benford (frequência observada vs. esperada para dígitos 1–9), omitindo estritamente qualquer grupo cuja `suppressionStatus != EXACT_RELEASE`.

---

## 5. Telemetria OTel GenAI e Trajetórias Douradas (*Golden Trajectories*)

`MicrometerGenAiAgentTelemetryRecorder` registra observações compatíveis com OpenTelemetry GenAI sob disciplina estrita de baixa cardinalidade (Zero-PII):

- Spans publicados: `gen_ai.agent.turn`, `gen_ai.chat`, `gen_ai.execute_tool` e `praxis.agent.security.finding`.
- Correlação de artefatos no span do turno via `recordArtifactCorrelation(...)`: `praxis.epistemic.kind`, `praxis.intelligence.provenance_hash` e `praxis.ai.proposal_id`, além de uso de tokens (`gen_ai.usage.input_tokens`, `output_tokens`, `cache_read_input_tokens`, `reasoning_tokens`) e estado `praxis.agent.security.tainted`.
- Validação automatizada de regressão comportamental:
  - Offline/determinística (executa no build padrão): `OperationalAgentGoldenTrajectoryTest` (`@Tag("unit")`);
  - Opt-in ao vivo: `OperationalAgentLiveTrajectoryTest` (`@Tag("external")`, `@Tag("ai-trajectory-live")`, habilitada apenas com `PRAXIS_AI_LIVE_TRAJECTORY=true`).
