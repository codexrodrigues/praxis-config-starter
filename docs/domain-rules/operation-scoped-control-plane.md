# Identidade operacional do control plane de snapshots

Este contrato candidato torna `operationKey` uma coordenada obrigatória da identidade de versões, heads e rollouts. A família conserva `ruleSetKey`; não se codifica a operação em aliases dessa chave. O Engine continua proprietário de `RuleSetRef` e do conteúdo imutável; Config Contracts publica a porta neutra; Config Starter governa armazenamento, publicação, seleção e histórico.

## Identidade e concorrência

O escopo de um head é `(tenantId, environment, ruleSetKey, operationKey)`. Versão imutável e revisão de publicação pertencem a esse escopo. A operação A não substitui o head de B e ambas podem publicar a versão 1. ETag e revisão de ativação continuam pertencendo ao head mutável; rollback conserva o hash do conteúdo selecionado e gira a identidade de ativação conforme o protocolo existente.

O mutex PostgreSQL continua **familiar**, deliberadamente. Ele serializa a propriedade estável da família e as mudanças da política de rollout com publicações/ativações de qualquer operação. Consultas de heads e precondições são específicas da operação dentro desse mutex. Uma operação nova não pode mudar owner, domínio ou bounded context da família já publicada. O mutex não cria um head familiar nem fornece fallback de operação.

A política de rollout também continua familiar: sua autoria, aprovação, ativação e histórico governam todas as operações da família. Rollouts, probes e eventos de rollout passam a identificar a operação do candidato. Retirada de política continua verificando todos os rollouts abertos da família; criar um rollout de outra operação não contorna a política.

## Contrato HTTP

| Operação | Identidade exigida |
| --- | --- |
| `GET /domain-rules/snapshots/head` | Query `ruleSetKey` e `operationKey` |
| `GET /domain-rules/snapshots/head/status` | Query `ruleSetKey` e `operationKey` |
| `GET /domain-rules/snapshots` | Query `ruleSetKey`, `operationKey` e limite existente |
| `GET /domain-rules/snapshots/rollouts` | Query `ruleSetKey` e `operationKey` |
| `GET /domain-rules/snapshots/rollouts/pending` | Query `ruleSetKey` e `operationKey` |
| `GET /domain-rules/snapshots/head/host-status-summary` | Query `ruleSetKey` e `operationKey` |
| `POST /domain-rules/snapshots/host-status` | Body com `ruleSetKey` e `operationKey`, inclusive quando o host não carregou snapshot |
| Publicação e aprovação de composição | Operação de `ruleSet.ref.operationKey`, vinculada ao manifesto aprovado |
| Leitura, ativação ou rollback de `snapshotKey` | O servidor resolve a operação do conteúdo imutável no tenant/ambiente autorizado |
| Operações sobre `rolloutId` | O servidor resolve a operação do candidato e verifica as referências |
| Políticas familiares de rollout | Escopo familiar existente, sem operação inferida |

Todos os caminhos acima têm a base `/api/praxis/config`. Tenant, ambiente e ator continuam resolvidos pelo principal de governança e seus papéis existentes. A query seleciona uma identidade; não concede autorização. Pedidos sem operação suficiente são recusados; não se escolhe o primeiro head ou uma operação padrão. A porta Java é `new PublishedRuleSnapshotHeadScope(tenant, environment, ruleSetKey, operationKey)`.

Head status, catálogo de versões, heartbeat e respostas de rollout publicam a operação descrita. O cliente deve manter essa identidade na seleção, cache e atualização de estado. ETag de head não identifica a projeção viva de readiness; `head/status` mantém `no-store` e revalida a capacidade atual.

O recibo neutro `RuleSetCompositionPublication` descreve somente a publicação imutável: `snapshotKey`, `ruleSetKey`, `operationKey`, `ruleSetVersion` e `snapshotContentHash`. Publicar conteúdo não seleciona um head. ETag, revisão e tipo de ativação pertencem ao contrato de head e só podem ser obtidos da operação de ativação/leitura correspondente; não preencher esses campos com valores sintéticos no publisher.

## Migração V72

`V72__scope_rule_snapshot_operations` é uma migração **Java**, acompanhada de SQL no recurso `/db/operation-scope-v72.sql`. Não editar migrações históricas. Antes de qualquer coluna nova, a migração obtém locks das tabelas afetadas e usa o verificador histórico canônico para conferir cada envelope, hash, evidência de composição e propriedade da família. Ela não recompila snapshots contra o runtime atual para decidir se podem ser preservados.

A operação dos snapshots vem de seu payload verificado. Heads/eventos derivam do conteúdo referenciado; rollouts derivam do candidato; probes e eventos de rollout derivam do rollout. FKs compostas impedem cruzar tenant, ambiente, família ou operação. Um heartbeat sem conteúdo carregado só pode ser atribuído quando o histórico prova uma única operação da família. Ausência ou ambiguidade interrompe a migração, sem apagar o registro ou inventar operação.

Payloads, hashes, aprovações, IDs, revisões, ETags e vínculos históricos são preservados. A migração é transacional: falha de verificação ou backfill reverte todo o acréscimo. Preservação histórica não concede prontidão de execução nem aprovação nova a snapshots antigos.

Flyway com discovery no classpath inclui a migração Java. Instaladores que extraem apenas SQL devem adicionar ao mesmo Flyway as instâncias fornecidas por `ConfigMigrationInventory.fromSelectedArtifact(pathDoJar)`. O inventário recusa artefato ausente ou diferente daquele carregado; não usa uma versão implícita do classpath. O instalador Ergon foi preparado para consumir essa API. Startup HTTP continua sem instalar schema e com JPA `validate`.

## Validação e adoção

A prova de migração usa PostgreSQL Neon real em uma branch descartável filha direta da base Ergon atribuída, com expiração, banco próprio sintético e exclusão verificada. O teste `DomainRuleOperationScopeNeonIT` verifica histórico corrompido, backfill impossível, preservação de conteúdo/head, coexistência de versões e heads, unicidade por operação e referências cruzadas de rollouts/probes/eventos. Não usa Docker nem representa ativação operacional do Ergon.

O histórico sintético não mede o volume, a duração dos locks nem a janela de migração do banco operacional. O inventário e o ensaio do corte com cardinalidades reais continuam no gate G5, antes da promoção.

Esta é uma evolução pública transversal em linha beta. A lib não oferece overloads antigos sem operação ou uma trilha paralela de execução. Antes do corte, atualizar consumidores Java, Core Angular, Policy Studio, scripts e documentação para a identidade explícita, respeitando os gates H4–H6/G4/G5. Não executar cliente antigo contra um backend que já oferece múltiplos heads por família. A implantação coordenada e a prova downstream publicada continuam separadas dos testes locais deste candidato.

## Seleção consistente de várias operações (H4)

`GET /api/praxis/config/domain-rules/snapshots/head-set?ruleSetKey=...&operationKeys=create...&operationKeys=update...` exige `RULE_SNAPSHOT_READER` e devolve tenant, ambiente, família, mapa exato de heads e fingerprint. A seleção explícita é limitada a 64 operações. Respostas são `no-store`: 400 para seleção inválida, 404 para conjunto incompleto e 503 para autoridade inconsistente/indisponível, sem expor payload de erro interno. Autorização é resolvida antes da leitura.

`DomainRuleSnapshotHeadSetService` usa uma única query SQL com LEFT JOIN, preservando a detecção de heads cujo snapshot não existe, em transação Config nova, read-only, READ_COMMITTED e timeout de cinco segundos. O mapeamento desacoplado evita autoflush/cache JPA da transação chamadora. Cada envelope passa pelo verificador canônico de normalização, hash, composição, duas aprovações e compatibilidade. Não há nova migração além de V72.

O consumidor deve compilar o conjunto inteiro, reler a autoridade e comparar o fingerprint antes da troca atômica. Toda nova admissão reconsulta a autoridade e a vigência pelo relógio atual, mesmo com cache. Falha ou ausência não permite last-known-good como autorização. A fotografia SQL não cria atomicidade entre Config e a transação de domínio; esse fence e a retirada governada pertencem ao gate operacional H5.

A regressão `FuncaoConfiancaHeadSetNeonIT` usa somente fixtures sintéticas em filho Neon descartável: leitura de nove perfis, MVCC durante mudança administrativa concorrente, troca de identidade, runtime sem UPDATE, aprovação insuficiente e perda de um head. Não prova aprovação real, bootstrap de probes REQUIRED, retirada pelo protocolo, autorização dos nove fluxos HTTP ou persistência de domínio.
