# Adendo crítico — fronteira e reconciliação 6s

> **Replanejamento vigente — 2026-10-05:** o usuário confirmou que as libs de UI
> não devem ser executadas no backend. B2d/executor e DEC-01..04 deixam de ser
> dependências deste fluxo. O texto abaixo conserva o checkpoint anterior;
> propostas de execução externa estão superadas. Requisitos independentes de
> integridade, autoridade e reconciliação precisam ser avaliados separadamente.
> Ver [decisão e próximo pacote](../../../../praxis-api-quickstart/docs/ui-layout-frontend-baseline-decision.md).

2026-10-03. Status **PROPOSTA EM REVISÃO**, não decisão/implementação/aceite. Complementa a [proposta 6s](ui-layout-execution-reconciliation-proposal.md), preservada como checkpoint. Classe editorial docs-apenas; ajustes arquiteturais futuros devem ter mapa próprio antes de código.

## 1. Independência precisa ser limitada ao desenho Config

A recomendação “avançar a frente Config independentemente” é válida para inventário de reuso, desenho da correlação/consulta, escopo de autorização e plano de migração/gates. Não autoriza implementar a integração de cálculo remoto antes de definir protocolo, resultado admissível, publicação, profile e provider/owner. Não separar primeiro o SPI transacional e depois preencher sua semântica por conveniência no Quickstart.

Dividir DEC-04 em desenho da correlação Config (análise independente) e integração com reserva/resultado do provider (depende de DEC-01/02/03 e protocolo decidido). Correlação Config tem utilidade para os comandos atuais, mesmo sem executor JS; não deve virar uma fachada de job provider nem exigir infraestrutura nova. Implementação exige fechar seu contrato focal e auditar reuso antes de tabela/endpoint/DTO novo.

## 2. Replay e reserva devem preceder cálculo novo

O código atual adquire JdbcUiLayoutDraftCreationLock e procura draft pela chave dentro da transação Config; só chama capture na criação sem replay. O lock é pg_advisory_xact_lock do datasource Config e não existe fora daquela transação. Ele não protege uma execução antecipada no provider.

O diagrama 6s descreve preparação antes do commit, mas não explicita suficientemente a decisão de replay antes do cálculo. Se o novo fluxo calcular primeiro e descobrir depois um draft existente, desperdiça cálculo/quota e pode duplicar execução após resposta perdida. Mover o lock antigo para fora da transação, manter uma transação longa para protegê-lo ou confiar apenas em consulta prévia são soluções inadequadas: a consulta pode ficar obsoleta antes da reserva.

Critério proposto: resolver replay autorizado existente ou reserva exclusiva da intenção antes de nova execução; a reserva deve vincular de forma inequívoca chave/escopo/actor/intent e identidade admitida do resultado. Resolver concorrência entre consulta, reserva, início de cálculo e vinculação ao comando, mantendo a ligação durável e revalidada. Detalhar ownership/atomicidade de cada passagem, inclusive crash entre entrega e vínculo Config. Não assumir que dois stores independentes oferecem uma reserva global ou exactly-once. Um fingerprint alterado não pode silenciosamente reapontar a mesma reserva para outro resultado.

## 3. Revalidação não equivale a garantia atômica de revogação

O writer realiza verificações repetidas, inclusive uma final dentro do callback antes de TransactionTemplate concluir o commit. Isso é evidência estática de chamadas, não prova de ordenação atômica com revogação externa. Existe intervalo conceitual entre última leitura de permissão/contexto e commit; leitura repetida sozinha não fecha esse intervalo.

A decisão deve declarar o ponto de ordenação: qual revisão/geração de autoridade é vinculada à tentativa e à mutação, onde é verificada e qual mecanismo faz a verificação efetiva no commit. Fencing/versionamento, fonte de autoridade compatível ou garantia de revogação limitada precisam de avaliação explícita; nenhum mecanismo é escolhido ou alegado implementado aqui. Se não houver garantia de impedir commit após revogação, documentar o limite real; não anunciar “qualquer revogação antes do commit sempre impede a escrita”.

Testar a corrida de revogação depois da verificação final e antes do commit, além de revogação na fila/antes de delivery. Leitura futura de resultado exige permissão atual mesmo quando a mutação anterior ocorreu validamente. Acesso revogado não desfaz o commit nem permite revelar seu resultado.

## 4. Correlação, delivery e notificações não são intercambiáveis

Ledger é hipótese para identidade/resultado de comandos; outbox é hipótese para entrega confiável de eventos. Um outbox sem identidade/payload/precondição/resultado não resolve a correlação. Auditoria existente pode oferecer reuso após avaliação, mas actor/reason/timestamp/eventType não são chaves inequívocas. Evitar desenhar todos os mecanismos de uma vez por prevenção.

Se for escolhido recibo durável, sua confirmação de commit deve acompanhar atomicamente a mutação Config, e a leitura deve retornar apenas resultado autorizado. Falha antes do commit, commit confirmado, commit incerto e estado desconhecido não podem ser deduzidos de ausência de registro: uma consulta pode ocorrer enquanto a transação ainda está em andamento, após limpeza, em escopo negado ou com store indisponível. O registro “pendente” em outra transação não prova sucesso ou rollback da mutação.

Retenção de payload, retenção de correlação e retenção de captura são políticas distintas. Definir janela de deduplicação, conflito/reuso de chave após expiração e recuperação sem inventar conclusão. Limpar resultado não pode permitir uma repetição automática enquanto a identidade anterior ainda tiver efeito incerto. A proposta atual não resolve isso com TTL sozinho.

## 5. Negação da consulta conserva incerteza anterior

Separar permissão para consultar de resultado histórico do comando. Se uma leitura de reconciliação retorna DENIED/403, a mensagem deve ser “Não é possível consultar esta operação no contexto atual; o resultado anterior continua sem confirmação”, quando essa incerteza existia. Não substituir por “Alteração negada”, “Nada foi salvo” ou “Operação desfeita”.

Uma negação explícita recebida na própria resposta do comando tem outro contexto. Core deve preservar a origem/fase do fato apresentado pelo consumidor; estado UI local não substitui receipt canônico. As leituras de seleção/freeze comprovam apenas correlação projetada e validada naquela observação; mismatch/supersession não comprova falha anterior. Esta é orientação futura de UX, sem componente implementado ou validação visual nesta revisão.

## 6. A rota nativa genuína precisa continuar explícita

UiLayoutBaselineMetadataSeed distingue OperationSource/SchemaProjection e NativeDocumentSource/NativeIdentity. O diagrama de provider refere-se à reprodução executável da projeção; não obriga toda origem nativa a executar JS. NativeIdentity exige documento nativo genuíno e input estrito correspondente a B0, com origem/conteúdo/política atuais admitidos. Não autoriza disfarçar schema derivado como origem nativa nem acrescentar transformação nativa ao contrato existente.

O desenho deve discriminar a rota por variante canônica e evidência real, não selector, label, presença de hash ou flag do caller. Identidade/captura/replay/commit e correlação permanecem governados pelo Config em ambas as rotas. Provider de cálculo pendente não é motivo, por si só, para remodelar NativeIdentity; tampouco prova que a rota nativa esteja operacional no host.

## Sequência recomendada corrigida

1. Pacote focal Config: auditar correlação/auditoria/reserva existentes e definir consulta autorizada não mutante da criação quando o locator se perdeu; classificar reuso versus lacuna antes de endpoint/tabela. Fechar escopo de actor/tenant/ambiente/root, vínculo da chave com intenção/resultado, comportamento pendente/desconhecido/expirado e ponto de commit. Produzir mapa de impacto e critérios de saída para decisão; nenhuma superfície concreta escolhida neste adendo.
2. Em paralelo, obter provider oficial/owner/documentação ou confirmação da necessidade de nova capacidade. O inventário 6n não localizou fronteira compatível no escopo consultado, mas não prova inexistência global.
3. Fechar contrato focal Config para implementação e DEC-01–DEC-03/protocolo de integração para executor. A correlação dos comandos atuais pode ter fatia própria; separação/integração remota permanece dependente do provider e de suas garantias.
4. Implementar fatias decididas nos owners, sincronizar consumidores/derivados afetados e validar testes focais. Depois provar mecanismos reais de PostgreSQL, provider/gateway e browser no ambiente autorizado. Sem criar infraestrutura ou contornar OP1r.

Não recomendar outro ciclo documental amplo sem critério de encerramento: o próximo pacote Config deve entregar inventário de reuso, decisão focal de consulta/correlação, mapa de impacto e plano de testes executável. Se a decisão de plataforma não estiver fechada, registrar precisamente qual alternativa/política requer decisão; repetir a especificação geral não resolve o bloqueio.

## Cenários adicionais futuros

| ID | Caso | Critério |
| --- | --- | --- |
| RV-AT-01 | Replay existente antes da preparação remota | Zero cálculo novo; acesso atual e captura exata |
| RV-AT-02 | Dois callers concorrentes após consulta sem draft | Reserva exclusiva/vínculo demonstrados; nenhum cálculo duplicado por consulta obsoleta |
| RV-AT-03 | Revogação após último recheck, antes do commit | Garantia de ordenação/fencing demonstrada ou limite explicitamente registrado |
| RV-AT-04 | Leitura negada após escrita com ack perdido | Sem divulgação; incerteza anterior preservada |
| RV-AT-05 | Correlação/payload expirados e nova chamada com mesma chave | Política explícita de dedup/conflito; ausência não autoriza nova execução automática |
| RV-AT-06 | Registro pendente independente e mutação rollback/commit incerto | Nunca representar pendente/outbox como commit confirmado |
| RV-AT-07 | NativeIdentity genuíno versus schema derivado disfarçado | Variante/evidência admitidas; não exigir engine indevido nem aceitar origem falsa |

Nenhum cenário foi executado. Esta revisão valida documentação/âncoras/checkpoints, não prova defeito em runtime ativado, isolamento, revogação atômica ou commit real. Correções 6r e evidências 6s anteriores permanecem históricas e não são recontadas.
