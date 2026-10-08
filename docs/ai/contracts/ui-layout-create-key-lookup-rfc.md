# RFC focal — recuperar draft por chave de criação sem mutação

2026-10-03. **DESENHO SELECIONADO para a próxima fatia; não implementado/ativado.** Classe atual docs-apenas; implementação futura contrato-publico/transversal, aditiva. Escopo: recuperar locator de createDraft após perda de resposta. Não resolve todos os comandos lifecycle nem implementa provider/reserva/cálculo remoto.

## Inventário e decisão

| Mecanismo existente | Reuso | Limite |
| --- | --- | --- |
| UiLayoutDraft.creationIdempotencyKey | Vincula a criação à chave em coluna existente | Não contém fingerprint de inputs/job/intenção externa |
| UiLayoutDraftRepository.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey | Busca exata já usada no replay | Falta projeção pública de leitura |
| V65 índice único com os seis campos | Impede duplicidade da chave no mesmo escopo enquanto a linha existe | Não fornece fila/pendência/TTL/ledger universal; gate DB real ainda necessário |
| JdbcUiLayoutDraftCreationLock | Preservar no writer antes de replay/capture | Não usar na consulta nem como lock de provider fora da transação |
| READ_DRAFT + invocation host | Autorização e escopo atuais | Não exigir CREATE_DRAFT para simples consulta |
| UiLayoutDraftReceipt | Projeção pequena de identidade/estado/validator | Não é workspace íntegro ou receipt de job/upgrade/apply |
| UiLayoutReleaseEvent | Manter auditoria em seu escopo | Não tem creation key, draftRef ou correlation fingerprint; não resolve lookup de criação |

Classificação: suportado-parcialmente no armazenamento/replay; lacuna-real-de-contrato para lookup autorizado não mutante com key e locator perdido. Escolha técnica: reutilizar entidade, repositório, operação de leitura e receipt existentes. **Sem ledger, outbox, tabela, migration, provider ou novo enum/DTO** nesta fatia.

## Contrato HTTP escolhido para implementação

`GET /api/praxis/config/ui-layouts/drafts/by-creation-key`

- Query obrigatória: rootComponentType/rootComponentId, como nas leituras atuais.
- Headers obrigatórios: X-Praxis-Context-Version e Idempotency-Key. Reutilizar a chave enviada na criação; não colocar key em URL/query/body nem incluí-la em Location/erro/log. Sanitização de headers em logging da implantação continua responsabilidade do host; header sozinho não garante redação.
- Principal/tenant/ambiente/actor vêm da invocation autenticada. Não aceitar user/tenant/actor no request para ampliar a busca. Root deve estar registrado/autorizado.
- Normalização da chave idêntica ao writer: trim, não vazia, até 180 caracteres após trim, case-sensitive. Sem hash/truncamento/alias alternativo; chave é referência de correlação, não credencial ou grant.
- Exigir READ_DRAFT antes da busca, revalidar a invocation completa e a admissão antes de devolver o locator. Mudança detectada: CONTEXT_STALE; DENIED e SOURCE_UNAVAILABLE sanitizados. Rechecks não prometem revogação atomicamente ordenada com commit externo.
- 200: UiLayoutDraftReceipt existente, Location da leitura canônica `/api/praxis/config/ui-layouts/drafts/{draftRef}`, ETag forte do draft e Cache-Control: no-store. Location não elimina root/contexto exigidos ao ler o workspace.
- 404: NOT_FOUND sanitizado no escopo autorizado. Significa **nenhum draft observado nessa leitura**, não “comando falhou/nunca executou/pode recriar”. Não comparar com outros actors/tenants para detalhar ausência.
- 400: key/target/entrada inválidos; 401/403/409/503 seguem categorias existentes. Não adicionar estado PENDING/EXPIRED/FAILED ou contador de retry: o armazenamento atual não sustenta essas conclusões.
- Leitura responde 200 ou erro autorizado; sem 304, sem processamento de If-None-Match e sem chave ecoada. Não interpretar If-Match como precondição de uma mutação inexistente. Documentar a semântica, sem herdar comportamento condicional do GET workspace por conveniência.
- A rota estática deve vencer `/drafts/{draftId}` no Spring mapping; testar dispatch real com MockMvc para evitar conversão UUID indevida.

Esta é uma decisão de desenho local revisável, não ADR de provider aprovado. A rota pertence ao Config; Quickstart apenas hospeda a superfície protegida. A API não deve aparecer no corpus/site como disponível antes da implementação validada.

## Semântica transacional e de evidência

Não salvar, flushar, bloquear, chamar producer/capture/metadata store, inserir audit event, invocar criação ou adquirir lock de escrita nesta consulta. Executar a leitura sobre estado persistido visível ao request independente, usando transaction manager Config/readOnly quando necessário. Não invocar a consulta dentro da transação de criação para apresentar dados ainda não commitados como observação pública; o host deve respeitar essa separação.

Um 200 estabelece a associação atualmente observada key/actor/scope → draftRef. Não estabelece resultado exato do job, captura íntegra, estado original do workspace, resultado de submit/publish ou materialização browser. O receipt pode refletir estado atualizado/RELEASED; não deve ser reescrito para simular resposta histórica de criação. Antes de apresentar documento editável ou escrever, consumidor lê o workspace pela rota canônica e usa contexto/validator atual, preservando integrity/admission existentes.

Um draft ainda não visível pode estar em criação/in-flight, ter rollback, nunca existir, ter sido removido ou não ser consultável no contexto. Sem ledger não distinguir essas hipóteses por ausência. Não esperar lock, converter timeout em FAILED ou chamar POST para descobrir. Erro/negação na consulta conserva incerteza anterior da escrita.

O vínculo reside na mesma linha persistida pelo fluxo de criação; a consulta não introduz segunda escrita para “confirmar commit”. Atomicidade real de draft+capture exige gate PostgreSQL já previsto, e esta RFC não declara esse gate passado. A hipótese de um store autoritativo/read consistente deve constar da implantação: leitura atrasada/replica não comprova ausência; não inventar configuração de roteamento para corrigir isso localmente.

## Identidade e retenção fechadas para esta fatia

Identidade de lookup = tenant + environment + rootComponentType + rootComponentId + actor autenticado + creation key normalizada, exatamente o índice/repositório existentes. Context version autoriza a leitura atual; não é acrescentada à chave persistida como mudança disfarçada de idempotência. A consulta não modifica a observação histórica nem repõe dados atuais nos captures.

A intenção recuperável é somente a associação de criação existente. Não afirmar equivalência de inputText/publicação/profile/execução: essas identidades continuam no protocolo futuro. Novo fingerprint e reserva para provider são fora do escopo.

Nenhum TTL/purge é criado e a fatia não exige migrar linhas com creation key nula. Retenção do mapping acompanha a linha existente; após remoção eventual, 404 continua desconhecido e não prova expiração. A consulta não resolve política global de reuso da key após remoção: manter a recomendação de não recriar automaticamente após resultado incerto. Dedup/retenção durável para executor e demais comandos continua decisão separada.

## Consumidor Core e UX

Adicionar cliente tipado focal no Core usando receipt existente, contexto/header admission e sanitização atuais. A chamada é leitura: 0/502/504 resultam em erro HTTP de consulta, não novo resultado incerto de escrita. A incerteza do comando anterior permanece no estado do consumidor; não fazer um erro da leitura apagar esse fato.

Nenhum retry/polling ou POST automático. 200 permite obter locator e efetuar readDraft autorizado; não apresenta “customização aplicada”. 403: consulta não permitida no contexto atual; resultado anterior continua não confirmado. 404/503: não foi possível confirmar a criação; continuar revisão autorizada. Não criar componentes/copy/i18n sem pacote funcional/UX próprio; testes do cliente não equivalem à prova visual.

## Mapa de impacto

- Config: read service, controller/OpenAPI, docs, testes focais. Reutilizar DTO/repository/READ_DRAFT; sem alteração writer/V65/capture/enum/DI público preventiva.
- Core: método público de leitura e specs de transporte, docs do lifecycle. Build focal Core e consumidor direto; signatures/models existentes preservados.
- Quickstart: HTTP proof com principal/Origin/contexto atuais e candidato privado exato. Não modificar políticas/origins/portas para aprovar o teste.
- Corpus HTTP: acrescentar exemplo/registros/verificador/LLM_SURFACE da nova leitura quando implementada, conforme governança local. Não expor raw schema/seed/key de produção. Docs oficiais atualizadas só para superfície efetiva; revisar landing se tiver documentação lifecycle correspondente.
- Table/Form: nenhum comportamento automático de recuperação introduzido. Teste consumidor crítico avalia transporte Core e ausência de alteração do producer; integração visual posterior tem escopo próprio.
- Breaking risk: adição de endpoint/método, sem remoção de contrato, flags/aliases/v2/major bump. Header de key passa a ser consumido por GET; proteção/caching/logging precisam de revisão no host.

## Matriz focal de testes a implementar

| ID | Gate | Resultado requerido |
| --- | --- | --- |
| LK-01 | Config service | Busca somente escopo/actor/key autenticados e normalização igual writer |
| LK-02 | Config service | READ_DRAFT admitido sem exigir CREATE_DRAFT; denial antes de query |
| LK-03 | Config service | 200 para associação existente; nunca save/flush/lock/producer/capture/event |
| LK-04 | Config service | Ausência devolve NOT_FOUND; nenhuma segunda busca global nem criação |
| LK-05 | Config service | Key nula/vazia/longa inválida; não ecoar texto em erro |
| LK-06 | Config service | Revogação/contexto mudado entre lookup/retorno rejeita resultado sem dados privados |
| LK-07 | Config service | Erro repositório/provider é SOURCE_UNAVAILABLE redigido, sem cause/raw key |
| LK-08 | Config service | Draft atualizado/RELEASED mantém locator e receipt atual; não simula criação histórica |
| LK-09 | Controller MockMvc | Rota estática é despachada, não parseada como UUID; headers/Root/context requeridos |
| LK-10 | Controller | 200 Location/ETag/no-store, 404 seguro; sem 304 nem payload schema/capture/key |
| LK-11 | Core HTTP mock | GET/header/context corretos; não herdar key/conditional headers indevidos; response tipada |
| LK-12 | Core HTTP mock | 403/404/503/0/502/504 em consulta: categoria de leitura, sem POST/retry ou sucesso falso |
| LK-13 | Quickstart/corpus | Endpoint protegido/Origin/context + contrato em artefato Config privado exato; manifesto/smoke focal |
| LK-14 | PostgreSQL admitido | Query concorrente com criação ainda não commitada não vê sucesso parcial; após commit recupera locator, após rollback continua sem confirmação |

Todos os casos são PLANNED, zero execução por 6t. LK-14 depende de ambiente exclusivo já autorizado; não criar schema/DB/container nem usar mock como prova DB. Suites reais selecionadas na implementação devem existir no checkout; não alegar suíte inexistente.

## Encerramento do desenho e próxima execução

Inventário, escolha de reuso, semântica, mapa e matriz entregues. Não há decisão de produto do executor necessária para implementar esta consulta de associação existente. Próximo pacote: plano contrato-publico e implementação focal Config/Core/host/corpus deste contrato, com testes mínimos e candidato privado novo preservando rc.159. Gates reais ainda dependentes de ambiente são relatados como pendentes. Não reabrir desenho geral do provider para adicionar a leitura nem promover a consulta a reserva/ledger.
