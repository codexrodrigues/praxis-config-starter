# Corte de release de authoring livre — 2026-09-05

O corte foi reconciliado com a publicação Config `0.1.0-rc.148`, sem remover a persistência
canônica de `UiCompositionPlan` adicionada em `main`. Esta é evidência de preparação e validação;
não afirma publicação dos commits deste corte, aprovação do gate pago ou certificação live.

## Proveniência e impacto

Classificação: `transversal`. O inventário de aderência e os owners continuam os registrados em
`LIVE-JOURNEY-PREPARATION-2026-09-05.md`. Nenhum novo contrato de intenção, schema, endpoint,
export de lib ou protocolo de preço foi criado.

| Repositório | Base remota reconciliada | Alteração própria |
| --- | --- | --- |
| Config | `02bbc339a3e68cc30abbab66a857ef953729c535` | Continuidade semântica, lineage, admissão/replay, projeções COUNT/query-context |
| Angular | `87fd2f257df61f08c6edfc1d326b9ff442f724d2` | Required estrutural no normalizador do Core |
| Landing | `39215c767a7988eb91428c3a0c8f3e5d035b2641` | Status/modelo, readback/ETag, regressões e runner governado |
| Quickstart | `2b6b50fdc2a8f1c66862a66ac4df848d986bbab3` | Source sem alterações próprias; prova downstream do Config reconciliado |

O avanço da landing para pacotes `9.0.63` veio de `main`; nenhuma configuração de workspace ou
dependência declarada foi editada para resolver builds. Os três pacotes compilados locais
(Core, Dynamic Form e Page Builder) foram usados na instalação isolada da landing para a prova
do corte. Essa instalação não substitui a atualização do consumidor para artefatos publicados.

## Gates locais

- Config: **793 testes em 17 classes**, zero falhas/erros, incluindo os owners alterados e regressões
  de apply e resolução da fonte persistida trazidos pela `rc.148`.
- Quickstart: **10 testes em cinco classes**, zero falhas/erros. Incluem HTTP/PostgreSQL real de
  reabertura/refinamento, SSE, schemas de patch, templates governados e política de acesso.
  O provider e o domínio/RAG usam seams determinísticos conforme o teste canônico do host.
- Browser: **11 cenários aprovados**, com fixtures recompiladas pelo Java reconciliado, incluindo
  dois domínios, três turnos, apply, reload, edição, required, dashboards e foco responsivo.
- Runner: **9 testes aprovados**, TypeScript sem erros; canary em `https://praxisui.dev`
  permanece **skipped sem opt-in**, antes de navegação ou chamadas.
- Registry Angular: geração/ingestão, `validate:catalog`, governança e aceitação **20/20 PASS**.
  O catálogo semântico gerado é idêntico ao versionado; o diff apenas de timestamp foi descartado.
- Build development da landing e empacotamento Config/Quickstart aprovados. Os builds focais
  Core/Dynamic Form/Page Builder e os 23 testes Core do corte anterior continuam aplicáveis,
  pois seus fontes não mudaram nesta reconciliação.

O SHA-256 do Config reconciliado e do JAR aninhado no Quickstart é o mesmo:
`43578987066217e66fa7053305e2c059c2cc73751d9304b84e13f9c05672568d`.
A primeira tentativa de package leu o artefato anterior antes do install terminar; a comparação
detectou a divergência, e o package foi refeito após o install. Somente a comparação final idêntica
conta como evidência. O cache Maven usado é isolado. A versão local `rc.148` serve exclusivamente
para validação do source e não pode sobrescrever a coordenada já publicada.

Não foram executados `verify` integral do Quickstart, preflight completo de publicação npm,
workflow pago, inferência real, escrita de registros no Render ou deploy deste corte.
Os gates remotos e a attestation do artefato público permanecem necessários na promoção.

## Origem e preço da próxima jornada

A correção de Metaspace está concluída conforme `RENDER-JVM-RECOVERY-2026-09-05.md`.
`https://praxisui.dev` já passa pela restrição de origem do Config. O runner aceita essa origem
oficial e usa seu Origin real; não foi ampliada a allowlist nem desativada a proteção.
O caminho local em `127.0.0.1:4301` continua recusado pelo Config e não deve ser usado no canary
enquanto essa política estiver vigente. A revisão da landing publicada deve ser atestada pelo
deploy oficial antes do run; a URL isolada não comprova o código publicado.

O snapshot focal `provider-pricing-snapshot.free-authoring.2026-09-05.json` usa o contrato
canônico existente e a [página oficial do modelo](https://developers.openai.com/api/docs/models/gpt-5-mini).
Preços consultados nesta data, por milhão de tokens: input USD 0,25, cached input USD 0,025 e
output USD 2,00. Revalidar a data no momento do run. Isso é estimativa; não prova saldo, quota,
limite duro de cobrança ou custo de embeddings. O modelo do backend permanece `gpt-5-mini`.

## Sequência de promoção

1. Revisar os PRs do corte e manter as bases imutáveis nas evidências. O trabalho original sujo
   permanece fora dos branches de publicação.
2. Cumprir o gate canônico de authoring do Config antes da tag. Para authoring browser, a política
   local seleciona `paid_gate_lane=page-builder` e o environment `ai-paid-gates`; não substituir
   esse gate por testes mocked nem acioná-lo sem autorização/teto USD. O número de turnos do gate
   deve ser conciliado com o escopo autorizado, sem somar outra lane paga por conveniência.
3. Usar os workflows oficiais de release Maven/npm, calcular versões a partir dos registries no
   momento da promoção e atualizar os consumidores com as coordenadas realmente publicadas.
4. Atestar Quickstart e landing implantados, depois verificar o fluxo publicado no escopo da
   autorização. A jornada paga de três pedidos é opt-in e não foi disparada automaticamente.

O teto USD foi solicitado ao usuário e continua pendente. A preparação documental e técnica não
equivale a uma autorização paga. Nenhum workflow pago ou release foi disparado nesta preparação.
