# B2d — aceite de execução após a migração B2c2

> **Replanejamento vigente — 2026-10-05:** o usuário confirmou que as libs de UI
> não devem ser executadas no backend. B2d/executor e DEC-01..04 deixam de ser
> dependências deste fluxo. O texto abaixo conserva o checkpoint anterior;
> propostas de execução externa estão superadas. Requisitos independentes de
> integridade, autoridade e reconciliação precisam ser avaliados separadamente.
> Ver [decisão e próximo pacote](../../../../praxis-api-quickstart/docs/ui-layout-frontend-baseline-decision.md).

2026-10-05. **Desenho privado, sem provider admitido ou ativação.** Este adendo
reconcilia a [proposta 6s](ui-layout-execution-reconciliation-proposal.md) e sua
[revisão](ui-layout-execution-reconciliation-review.md) com o
[contexto implementado B2c2](ui-layout-validation-attempt.md). Não define DTO,
endpoint, estado persistido ou protocolo externo. DEC-01..04 seguem pendentes.

## Inventário atual e lacunas

| Item | Fato verificado na fonte | Lacuna de operação |
| --- | --- | --- |
| Config | Tentativa única, contexto/finalidade, política host obrigatória e verificação no beforeCommit efetivo | Enforcement cooperativo; sem timeout JDBC/lock, isolamento ou interrupção de filhos |
| Criação | Lock transacional e busca/replay precedem capture; source é chamado dentro da transação | Não há reserva externa nem preparação fora de locks; consulta prévia sozinha não elimina a corrida |
| Reconciliação | Lookup de criação por chave já existe; rechecagens atuais não recapturam | Ausência pontual não prova rollback; não é ledger genérico de todos os comandos |
| Table | Entry point authoring e implementação owner disponíveis no checkout | Falta admissão de publicação, closure transitiva, loader, runtime, glue e política de expressões |
| Form | Validação owner exportada pelo barrel; produtor de revisão interno e corpus finito | Produtor não exportado nem conectado ao Save; host continua negando Form |
| Core | Kernel de normalização contém avaliação de expressões por new Function | Não é parser inerte nem sandbox; admissão precisa anteceder a avaliação |
| Quickstart | NativeOwnerValidationGateway é interface privada, sem implementação operacional | Source pin compilado não atesta a cadeia JS; factory/teste não é composição em produção |
| Filas existentes | Streaming/RAG e Rule Lab têm concorrência em seus próprios domínios | Não demonstram isolamento de JS nem quotas admitidas; não importar limites/defaults desses mecanismos |

Classificação de aderência: **suportado-parcialmente** para contexto, autoridade,
captura e reconciliação de criação; **lacuna-real-de-contrato** para resultado
admitido e integração com provider isolado. Não abrir uma nova API para repetir
os dados que Config já conserva. A existência de provider externo é UNCONFIRMED,
nunca inferida como inexistente a partir desta busca focal.

## Aceite de resultado proposto

A interface definitiva depende de DEC-04. O dado faltante é uma evidência privada
imutável de execução, vinculada à tentativa e à publicação realmente admitidas.
Config governa consumo/commit; host governa origem, conteúdo e acesso; provider
governa execução, isolamento e término; owners governam semântica nativa.

1. Antes de qualquer cálculo, resolver replay autorizado ou reserva exclusiva
   da intenção. Preservar escopo/actor/chave e identidade exata. A chave de criação
   não é UUID de tentativa nem fingerprint completo. Duas reservas independentes
   não constituem exactly-once ou atomicidade distribuída.
2. Fixar todos os targets na ordem, descriptors, entradas textuais exatas,
   publicação/closure/entrypoint, loader/glue/serializer/defaults, runtime e
   revisões de policy/profile. Artefato local por hash é inventário, não admissão.
3. O pai transmite somente duração restante da tentativa original. O provider
   mede intervalo no próprio relógio e limita fila/cálculo pelo menor orçamento
   admitido. Transporte e fila consomem o prazo do pai; nenhum retry renova esse
   prazo. Nunca comparar contadores nanoTime absolutos entre processos.
4. Revalidar contexto e admissão após fila e antes de execução. Iniciar somente
   se todos os controles obrigatórios do perfil forem efetivamente aplicáveis.
   Recursos e credenciais do host/Config não são entregues ao worker.
5. Admitir retorno no pai pela tentativa original ainda ativa, invocation exata,
   finalidade e operação originais, identidade completa das entradas e artefatos,
   resultado integral e diagnostics originais. Rejeitar resposta parcial, tardia,
   trocada ou de policy revogada. Hash igual não dispensa acesso atual.
6. Na mutação Config, revalidar associação, ETag, contexto, operação e acesso de
   conteúdo antes de consumir a evidência. Preservar flush/append/reread atômicos
   e beforeCommit/afterCompletion da transação efetiva. Nunca conferir ao provider
   autoridade de salvar, publicar ou movimentar head.

O modelo futuro deve ser imutável também em profundidade: congelar/copiar árvores
JSON e coleções na fronteira para impedir troca depois da validação. Incluir
identidade do conjunto integral, não só do target raiz. Não serializar exceptions,
stacks, credenciais ou fontes corporativas em receipts públicos. Não criar agora
um record que deixe esses vínculos opcionais e seja confundido com prova admitida.

## Transação e liberação de recursos

O writer atual não será deslocado parcialmente. O desenho futuro precisa definir
reserva, preparação e consumo como um ciclo completo: replay/reserva → cálculo
fora de locks longos → transação curta com precondições revalidadas. Concorrência
entre consulta e reserva, crash após delivery e antes do vínculo Config, retenção
e conflito de chave são parte de DEC-04. Não mover pg_advisory_xact_lock para fora
da transação nem executar cálculo antecipado antes da decisão de replay/reserva.

Transação externa já iniciada não ganha timeout retroativo. DEC-04 deve escolher
o comportamento para participação em transação ambiente e provar a transação
Spring efetiva; nenhum novo propagation/default é selecionado neste documento.
Verificação beforeCommit não é fencing IAM distribuído nem controle do commit
físico. Essa janela permanece uma limitação explícita da implementação atual.

Expiração/cancelamento invalidam a entrega antes de solicitar encerramento.
Término precisa de evidência sobre worker e descendentes. Timeout do cliente ou
Future.cancel não é término comprovado. Não liberar uma vaga ocupada por worker
ainda vivo; ao mesmo tempo, não ocultar recursos órfãos numa fila sem recuperação.
O provider precisa definir contenção, recuperação e observabilidade desse caso.

## Decisões e provas de uso corporativo

| Decisão | Dado necessário | Responsável a identificar | Situação |
| --- | --- | --- | --- |
| DEC-01 | Serviço oficial, owner, runtime/deployment e suporte; ou confirmação de inexistência para planejar capacidade canônica | Plataforma/operação | PENDING |
| DEC-02 | Publicação real, closure completa, origem, expressões e admissão anterior ao Core | Owners/publicador e host | PENDING |
| DEC-03 | Perfil com unidades/escopo: CPU, memória total, tempo de fila/parede, saída/log, concorrência, fairness e término | Owner do provider e operação | PENDING |
| DEC-04 | Reserva durável, identidade, retorno, retenção, reconciliação e consumo transacional | Provider, host e Config | PENDING |

Nenhum selectedValue ou valor de fixture fecha essas decisões. Primeiro identificar
provider/owner admitidos; depois associar mecanismo e evidência a cada limite.
Um prazo Config não substitui limite externo, quota de tenant ou SLA corporativo.

| Gate futuro | Observação exigida |
| --- | --- |
| G01 — admissão ausente/alterada | Zero início de owner; nenhuma escrita/head |
| G02 — fila cheia/tenant saturado | Rejeição limitada, isolamento entre tenants e recursos liberados; sem lock Config longo |
| G03 — prazo esgotado na fila | Zero cálculo após prazo, tentativa não renovada |
| G04 — timeout/cancelamento em cálculo | Entrega invalidada, worker e filhos terminados/contidos, nenhuma aceitação tardia |
| G05 — resposta trocada/parcial/mutável | Sem consumo de resultado nem escrita/head |
| G06 — contexto/grant/policy revogados | Rechecagens após fila, entrega e antes de commit; declarar janela sem fencing |
| G07 — ETag/associação alterados durante cálculo | Precondição atual rejeitada, nenhuma sobrescrita |
| G08 — replay concorrente | Não executar duplicata cega; conflito de identidade não reaponta reserva |
| G09 — crash após delivery/commit e perda de resposta | Reconciliação autorizada, sem deduzir ausência nem recriar automaticamente |
| G10 — transação ambiente/rollback | Prova Spring e PostgreSQL reais do commit/rollback, sem timeout retroativo alegado |
| G11 — I/O/CPU/memória/saída proibidos | Contenção observada no ambiente admitido, sem secrets nem efeitos residuais |
| G12 — retention/restart/consulta negada | Resultado anterior continua incerto quando não consultável; acesso atual obrigatório |
| G13 — cadeia Table/Form | Owners reais, reprodução integral, políticas originais de null/presença/diagnostics |
| G14 — UX desktop/narrow | Estados separados de execução, draft salvo e publicação; foco/teclado e recuperação de conflito |

Esta matriz adapta os gates existentes ao B2c2, não os declara executados. Unit e
mocks podem verificar protocolo escolhido; prova de término/isolation exige
executor real admitido, atomicidade exige PostgreSQL, UX exige browser real.
Form continua negado até seus gates próprios. Falha de consulta não significa
“nada foi salvo”; usuário deve ver a incerteza e reconciliar antes de repetir.
