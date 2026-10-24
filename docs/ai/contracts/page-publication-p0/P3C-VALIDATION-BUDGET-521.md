# P3-C / #521 — uma tentativa dona por operação

Classificação: transversal/arquitetural interna. Baseline Config074cc0e8 (rc168),
Quickstart0d1c1956 (PR463), pin público168 integrado em466. Fonte canônica Config.

Inventário: UiLayoutValidationAttempt já define ticker, duração, saldo monotônico,
correlação, operação iniciadora e fechamento. Context.forPurpose retém tentativa;
Chain e NativeSnapshotReader reconstruíam tentativas com tempo restante. Comando
reservado, receipt read e verify de adapters abriam budgets novos. Admission inicial
rodava antes de beginValidation. Aderência: suportado-parcialmente/mal-materializado,
sem lacuna real de DTO, endpoint ou novo conceito de autorização.

Plano antes de editar:
1. Conservar o estado da tentativa dona em vistas internas correlacionadas. Permitir
   mudar composição, nunca ator/tenant/unidade/ambiente/contextVersion. Fechar vista
   não fecha pai; fechar dono invalida todas; não reconstruir now+remaining.
2. Propagar contexto explicitamente em overloads package-private de reserved draft
   e receipt. Escopo léxico interno somente para atravessar callbacks sem validation;
   guarda apenas tentativa, nunca principal/membership/grant/resultado. Restaurar por
   try/finally, sem herança para workers e sem reter ThreadLocal após retorno participante.
3. Colocar admission inicial dentro do budget; preservar callbacks de entrada/retorno/
   commit e reinstalar contexto capturado nos callbacks. Não renovar policy filha.
4. Host: fundir apenas a dupla admissão dentro de require; separar estrutura pura de
   autoridade, mantendo escopo/composição/attempt/contratos. Metadata/child/native/
   mapping/lifecycle continuam readmitidos no presente. Não aumentar40s ou cachear grant.
5. Testes determinísticos de relógio/fechamento/correlação, troca de autoridade,
   reentrância/exceção/thread, nestedcommand/read/admission/commit e rollback. Focais
   unit sem banco; todo banco real só Neon atribuído root. Depois candidato privado
   isolado, HTTP focal de criação/publicação e falha beforeCommit observada por outra
   conexão ordinária antes do cleanup; preservar negativos e parar para diagnóstico.
6. Revisão independente antes de merge/release Config, adoção pública e PR463. Prova
   privada não certifica pacote público. Se a prova exigir release compatível, usar
   workflow oficial autorizado pela jornada, sem retag/upload duplicado; conferir bytes.

Mapa de impacto: Config Attempt/Context e serviços de criação/lifecycle/bridge/native;
Quickstart providers/harness/pin/docs; skill canônica runtime-persistence e operational-
proof842 devem refletir a correção e provas. Hero/Angular/landing/corpus sem payload
novo; reavaliar derivados se surgir contrato público. Risco: expiração agora alcança
trabalho antes não contado e pode revelar custo restante. Não prometer40s end-to-end
só por compilação; não confundir budget cooperativo com cancelamento de SQL bloqueado.

Coordenação: root único escritor Config/harness/POM/docs/release/Maven/Neon. Agente
p3c_page_providers escreve só Host/Configuration e testes atribuídos; revisão independente
p3c_security_review somente leitura. Modelo/esforço herdados nos followups (sem override),
adequados a autorização/transação; decisões críticas com root. Instruções raiz/local e
/Users/rodrigo/Dev/pessoal/praxis-plataform/docs/engineering/agentes-e-modelos.md aplicadas.
Neon br-wandering-frog-adu4l0bu root exclusivo, c1 encerrada; nova atribuição/atestações
antes de conectar. Reusar P3-A/B/b1/b2 inalterados com fonte/limites; não repetir por reflexo.


Pré-ensaio:246 testes focais em13 classes PASS/0skip,21.467s, sem banco, após
corrigir fixtures UUID e contagens das travessias; resultados negativos preservados.
Revisão independente p3c_security_review aprovou desenho e diff para candidato
privado/ensaio, sem aceite funcional. Readmissão de metadata raiz/filho e fonte
filha agora é cercada por entry/return, inclusive após deadline expirar; estrutura
usa wrapper canônico. Lookup por chave preserva normalização anterior de erros.
Candidato terá GAV novo0.0.0-p3c.521-SNAPSHOT em cache isolado, nunca bytes alterados
sob168 pública. Host69 testes focais PASS; diff revisto estaticamente pelo peer.


## Resultado negativo c2 e limite de aceite

O ensaio privado c2 de 24/10/2026 usou Quickstart `78b473cd` e Config `d948c4d3`,
GAV `0.0.0-p3c.521-SNAPSHOT`, com JAR SHA256
`15b450bbb50717df052afb7a2ae7b0f04d27a77308a10f8ad981844e6f3763ed`.
A criação retornou 503 `SOURCE_UNAVAILABLE` em 41.275 ms. A deadline dona não foi
renovada, mas nenhuma criação 201, publicação ou observação independente de rollback
foi alcançada. A limpeza confirmou dois bancos e dois papéis removidos às 03:18:55 UTC.
O recibo negativo está preservado no Quickstart, em
`internal-planning/page-runtime/evidence/p3c-budget-521-20261024/c2-negative.json`.

A prova local não aceita a funcionalidade. O próximo passo é executar o gate focal
em runner remoto com os mesmos 40 segundos e verificações de autorização, atribuição
dedicada, SHA e JAR atestados e revisão independente antes do dispatch. O runner
Ubuntu não comprova localização ou proximidade ao banco. Sucesso privado ainda
exige adoção pública. A negativa não aprova release ou integração.

O preflight adicional do perfil oficial `ci-smoke-unit` passou com 3.692 testes,
sem falhas, erros ou casos ignorados, em 2min41s, sem banco. A fonte de produção
`d948c4d3` permaneceu inalterada. A execução antecipa regressões das entradas
lifecycle fora das 246 verificações focais; não substitui a prova HTTP/Neon
nem o build e a assinatura oficiais de release.
