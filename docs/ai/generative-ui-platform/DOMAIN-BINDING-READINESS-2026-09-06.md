# Domain Knowledge operacional em demo/landing — 2026-09-06

A jornada rc.49 anterior parou em `operational-grounding-binding-required`. O inventário somente leitura confirmou catálogos dos três recursos de RH em `demo/landing`, porém nenhum conceito ou binding nesse escopo. As projeções existentes estavam exclusivamente em `desenv/local`. O painel Render não tinha `PRAXIS_DOMAIN_KNOWLEDGE_PROJECTION_ENABLED` configurada.

## Aderência e impacto

Classificação `transversal`; aderência `ja-suportado-mal-nomeado-ou-mal-materializado`. Config já possui projeção idempotente e operações governadas de aprovação. Não existe necessidade de contrato novo, binding sintético, mudança de modelo, relaxamento de governança ou publicação de biblioteca.

Fonte canônica: `DomainCatalogIngestionService`, `DomainKnowledgeProjectionService`, `DomainKnowledgeChangeSetService`, `AgenticAuthoringDomainBindingService`. Consumidores: host Quickstart/Render e authoring do Landing. Artefatos derivados: este recibo e guidance operacional. Não houve alteração de API, runtime Angular, exemplos de componentes ou docs públicas de contratos; sem breaking change.

## Correção e verificação anterior à LLM

- Habilitada a propriedade existente no Render; opção Save and deploy, sem rebuild. Deploy `dep-daenp3fqj5pc73a7n000` confirmado Live. API rc.49/Config rc.151 preservadas.
- Reingestão dos snapshots publicados por `/schemas/domain`, via `/api/praxis/config/domain-catalog/ingest`, com Origin `https://praxisui.dev` e escopo exato. Três HTTP 202; nenhuma escrita SQL.
- A projeção gerou 136 conceitos em `demo/landing`. Os bindings examinados nasceram `generated`, com conceitos `candidate/generated`, versões coerentes e evidências ativas. A ingestão não os aprovou.
- Revisadas nove superfícies existentes: list/detail/edit de funcionários, cargos e departamentos. Identidade e operação foram confrontadas com `/schemas/surfaces?resource=...`, e cada `/schemas/filtered` retornou HTTP 200. Capabilities retornaram HTTP 200 para cada recurso. Nenhum registro de negócio foi usado nessa revisão.
- Três change sets `proposed` passaram por validate, status approved, apply e readback applied; apenas `approve_concept` e `approve_binding`, com proveniência extraída das fontes publicadas. Não foram criados conceitos ou bindings alternativos.
- Consulta somente leitura reproduzindo os guards de tenant/environment, aprovação, lifecycle, visibility, release e evidência ativa encontrou três bindings elegíveis por recurso.
- 22 testes locais do helper do canary passaram. A equivalência de metadados e a elegibilidade não certificam a experiência do usuário; a jornada real é registrada separadamente.

## Limites

A skill instalada `praxis-config-domain-decisions` ainda afirma que apply só suporta add/revert evidence. O código e a documentação canônica já suportam oito operações, incluindo approve_concept/approve_binding. A cópia correspondente não existe em `codex-skills/` deste workspace; registrar o drift e corrigir guidance disponível sem inventar uma segunda fonte canônica.

Não foram alterados modelos. O teto OpenAI permanece uma declaração do usuário sobre sua conta; custos ausentes continuam desconhecidos. Nenhuma aprovação de catálogo prova suporte universal a componentes ou a futuras jornadas.

## Jornada publicada

Run `free-00b850b8-2ac2-4a29-87c4-70499e4d31b6`, Landing source `8606f7c`, site publicado, API rc.49. Um turno humano, sem retry do browser nem clarificação. `retrievalSource=domain_binding`, decisão válida, sem keyword fallback, preview válido e `canApply=true`, sem preview failure codes. `page-apply` retornou sucesso e payload igual ao preview.

A jornada falhou em readback: authoring resolve `AiPrincipalContext` com precedência do principal autenticado (`admin`), enquanto `UserConfigController` passa `X-User-ID` diretamente ao serviço (`free-...`). A leitura do teste retornou HTTP 404. Uma consulta somente leitura comprovou persistência no usuário admin, versão 1 e igualdade JSONB exata com a página do evento terminal do stream `c58cc443-e48d-4d40-ab8d-328853b826a9`. Não houve evidência de divergência de materialização; houve divergência de identidade entre APIs.

O erro de cleanup mascarou a exceção funcional original. O helper foi ajustado para preservá-la, registrar sua etapa e usar o ETag HTTP recebido, não o UUID sem aspas do corpo. TypeScript focal e diff-check passaram; 22 testes do helper passaram antes desse ajuste pequeno. Não foi executada uma segunda jornada paga para testar o cleanup.

Os funcionários sintéticos 280/281/282 foram removidos pelo próprio teste. A página foi removida separadamente somente após igualdade estrutural com o preview terminal e confirmação de versão/ETag; DELETE 204, readback 404. O recibo original mantém cleanupComplete=false/journeyPassed=false; o recibo complementar prova recuperação concluída, sem reescrever o histórico como verde.

Seis invocações: planejamento Luna; general authoring mini; intent_fast teve timeout, intent_full completou; refinamento Luna. Esse fallback interno já existia. Tokens totais/custo desconhecidos devido à chamada sem usage e cache-write não informado. Sem Astra, sem alteração de parâmetros de modelo.

## Próximo corte necessário

Classificação prevista `contrato-publico`: alinhar leitura/escrita/delete de configuração de usuário com a identidade canônica autenticada usada no authoring, preservando escopos tenant/global e integrações legítimas. Antes de editar, auditar authorizers e consumidores; não solucionar com spoofing de header ou preferindo um id de browser sobre o principal. Provar com testes autenticado-versus-header, isolamento e roundtrip apply/read/delete, depois atualizar o preflight do canary para inspecionar o escopo efetivo antes de qualquer fixture/apply. Só então repetir a jornada de três turnos.

Não foram certificados runtime de linhas, reload, edição do registro nem dois refinamentos nessa execução. O bloqueio operacional de bindings está resolvido; a vertical livre completa continua aberta.

A referência `codex-skills/praxis-generative-ui-authoring/references/pilot-scenarios.md` foi atualizada e espelhada diretamente na cópia instalada. Scripts sync/bootstrap não existem neste workspace, e esse diretório de skills não está sob Git nesta raiz.
