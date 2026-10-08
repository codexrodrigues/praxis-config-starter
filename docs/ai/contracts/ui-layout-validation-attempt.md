# Contexto e prazo de validação lifecycle — B2c2

> **Replanejamento vigente — 2026-10-05:** o usuário confirmou que as libs de UI
> não devem ser executadas no backend. B2d/executor e DEC-01..04 deixam de ser
> dependências deste fluxo. O texto abaixo conserva o checkpoint anterior;
> propostas de execução externa estão superadas. Requisitos independentes de
> integridade, autoridade e reconciliação precisam ser avaliados separadamente.
> Ver [decisão e próximo pacote](../../../../praxis-api-quickstart/docs/ui-layout-frontend-baseline-decision.md).

Continuidade: [inventário e aceite proposto B2d](ui-layout-execution-b2d-readiness.md).
O contexto cooperativo está implementado; provider/isolamento/protocolo ainda
dependem de DEC-01..04, sem admissão operacional por esta documentação.

Revisão 2026-10-05: a candidata rc.166 ainda aceita leitura histórica/diagnóstico
quando a última extensão altera autoridade/contexto depois da rechecagem anterior.
O check final de validation.require mede prazo e igualdade com a invocation
capturada; não consulta autoridade atual. Cinco probes determinísticos reproduziram
o problema, sem banco ou runtime owner. Correção no owner Config e regressões
focais são prioritárias antes de ativação; ver
[relatório da revisão](../../../../../../docs/agent-artifacts/praxis-json-upgrade-2026-09-30/review-b2c2-b2d1-2026-10-05/resultado.md).

Correção candidata rc.167: histórico reconsulta invocation/operação após o último
loop de acesso. Diagnose revalida histórico e acesso corrente após a validação
nativa final e reconsulta invocation/DIAGNOSE_EVOLUTION antes de aceitar o recibo.
Mesma tentativa, política única, sem recaptura de source ou CREATE_DRAFT adicional.
validation.require continua sendo check de prazo/snapshot, não consulta de grants.
Provas locais e limitações constam do
[resultado da correção](../../../../../../docs/agent-artifacts/praxis-json-upgrade-2026-09-30/final-read-authority-fix-2026-10-05/resultado.md).

Config cria uma única UiLayoutValidationAttempt por comando ou leitura externa,
com invocation confirmada, operação iniciadora, UUID de correlação e orçamento
monotônico não renovável. UiLayoutValidationContext transporta essa tentativa e
uma finalidade explícita pelos SPIs Java. Não há controle de prazo no HTTP,
bean default, propriedade de cliente ou executor novo.

## Política e migração Java

O host precisa fornecer UiLayoutValidationBudgetPolicy. Sua seleção recebe
operação/invocation e devolve Duration positiva representável em nanos, admitida
pelo servidor. Ausência impede auto-configuração de writer, reads, history e
evolution. Nulo, valor não positivo, overflow ou falha técnica resultam em
SOURCE_UNAVAILABLE sanitizado. DEC-03 segue sem orçamento operacional escolhido;
valores das fixtures são exclusivamente de teste.

A tentativa inicia após admissão inicial e seleção da política, antes de
transação/lock. A admissão inicial e esse lookup não estão dentro do orçamento;
o host deve mantê-los locais e limitados. Não renovar por target, extensão,
observação, leitura aninhada ou beforeCommit.

Migração beta limpa, sem overload antigo: UiLayoutLifecycleStructureValidator,
UiLayoutDraftWorkspaceSource, UiLayoutHistoricalEvidenceAccess e
UiLayoutCurrentEvolutionEvidenceAccess recebem contexto como último parâmetro.
Factories/construtores dos quatro serviços recebem política obrigatória.
Consumidores/implementadores precisam migrar juntos; candidata privada rc.166.
O pin Quickstart rc.163 permanece; provas usam override explícito.

## Finalidade e autoridade

| Finalidade | Uso |
| --- | --- |
| AUTHORING | Captura inicial e validações de autoria |
| CURRENT_WORKSPACE_READ | Projeção atual e leituras lifecycle |
| HISTORICAL_EVIDENCE_READ | Evidência frozen, incluindo targets removidos |
| EVOLUTION_CURRENT_READ | Captura/validação atual para diagnóstico consultivo |
| FROZEN_RELEASE | Validação completa antes de publish/rollback |

forPurpose cria uma vista da mesma tentativa. Não troca operação, correlação,
invocation ou deadline e não concede visibilidade. History direta cria tentativa;
as duas recuperações dentro de diagnose compartilham a externa e conservam os
grants READ_HISTORICAL_EVIDENCE e de conteúdo. Targets removidos não são validados
contra authoring atual. Captura consultiva lab exige DIAGNOSE_EVOLUTION, sem
CREATE_DRAFT. Publish/rollback preservam grants próprios, sem CREATE/READ_DRAFT.

## Aceite e transação

Config verifica antes/depois de callbacks, validações nativas, locks, metadata,
trabalho transacional e projeção. Invocation diferente falha com CONTEXT_STALE.
O default frozen verifica cada chamada antes de prosseguir. Resposta ou exceção
tardia não restabelece aceite; expiração usa SOURCE_UNAVAILABLE. No lookup,
indisponibilidade não demonstra criação falhada: reconciliar antes de recriar.

Writes exigem transação Config efetiva, gravável e com sincronização. Revalidam
prazo/autoridade no beforeCommit efetivo; afterCompletion fecha a tentativa.
Retorno de comando participante não fecha antes do commit externo. Falhas fecham
e marcam rollback pelo wrapper existente; ausência de sincronização fecha
localmente e impede persistência. Leituras fecham após aceite/falha e rechecagem
de invocation/autoridade.

## Limites e provas

System.nanoTime mede intervalo local; não transmitir o contador absoluto.
remainingBudget diminui, expira no limite exato e não reabre após close/expiração.
Wrap signed-long não troca para relógio civil. Prazo cooperativo impede aceite
tardio nos pontos verificados, mas não interrompe callback bloqueado, thread,
worker/filhos, JDBC ou lock. Não instala timeout efetivo da transação nem elimina
a janela entre último check e commit físico. Não equivale a SLA, isolamento ou
fencing IAM distribuído. Protocolo externo, limites e encerramento são B2d.

Provas focais cobrem orçamento único, finalidade, primitivas, callbacks tardios,
ausência de política e transações Spring participantes, sem sleeps. Quickstart
consome JAR privado real com providers/persistência simulados. Sem PostgreSQL,
owner backend, browser ou aceite integrado. Form permanece negado no adapter lab;
gateway continua sem implementação operacional.
