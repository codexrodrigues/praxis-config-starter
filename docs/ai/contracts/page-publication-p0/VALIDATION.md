# Evidência e revisão do P0

22/10/2026. **Aceito como formalização local de contrato; runtime/hosts não implementados.**

## Baseline e isolamento

- Config base: `60ff10a4b95538f540fc0002e7df91c03bc518e1`.
- Branch de trabalho: `codex/page-publication-p0-20261022`.
- Checkout: `.worktrees/config-page-publication-p0-20261022` no workspace Praxis.
- Fontes Angular: `cf27f5b89480a34a299fa6d3c591bfa9776a99b4`; libs do Hero `10.0.0-rc.37`.
- Checkouts principais tinham alterações anteriores; permaneceram preservadas. Coordenador é
  único escritor deste pacote. Nenhum processo concorrente usou target/Maven install/banco.
- Sem modificação de `src/main`, pom, migrations, public-api Angular ou aplicação host.
- No corte de validação original não houve commit/push/PR. A disponibilização posterior no GitHub usa branch/PR de planejamento; merge, Actions manuais, deploy e release não fazem parte desta entrega.

## Validação executada

```sh
mvn -B -Dtest=PagePublicationP0ContractTest test
```

Java 21 (Temurin), Maven 3.9.15. Resultado final em 22/10/2026 16:41:07 -03:00:
**BUILD SUCCESS — 63 testes, 0 falhas, 0 erros, 0 ignorados**. Tempo total Maven 9,492 s;
esses tempos não são benchmark de produto. Relatório bruto fica em
`target/surefire-reports/org.praxisplatform.config.service.PagePublicationP0ContractTest.txt`;
log original era efêmero. Para reprodução em outra máquina, executar o comando acima no SHA do PR; o resumo e os hashes abaixo são evidência histórica, não uma nova execução.

Cobertura:

- 41 exemplos de schema: documentos, criação, recibo, evidência, root de grupos, campos
  desconhecidos, versão canônica, injeção de identidade/autoridade, títulos e controles.
- 9 relações Form/Table contra os guards e compilador de patch existentes: troca de binding,
  columns materializadas, duplicatas e null; herança null dentro de array Form preservada.
- 8 exemplos relacionais de IDs e referências (checker de corpus, não validator de produção).
- 2 vetores de cópia dentro de um teste: remapeamento, literal igual a UUID preservado,
  colisão entre namespaces e hashes calculados pelo CanonicalJsonHashService real.
- Orçamento do documento inteiro em UTF-8/profundidade; parser real nega membros duplicados
  e JSON posterior; codecs novos explicitamente ainda não admitidos pela projeção atual.
- Unicidade de casos e status `not-executed` preservado para os 15 gates de integração.

A escolha focal é suficiente para arquivos de especificação e corpus; não há código de
runtime novo a empacotar ou host novo a executar. Compilação Maven emitiu avisos preexistentes
sobre APIs de teste deprecadas; somente a classe acima foi executada. Não iniciou Spring,
servidor, browser ou banco. Nenhum PostgreSQL local/embutido foi usado como prova.

## Revisão independente

Autor: coordenador. Revisor: `page_inventory`, read-only, distinto do autor; Sol/high foi
solicitado na configuração do agente, sem afirmar configuração efetiva não exposta.
Inspecionou fontes, perfil, contratos, testes e cenários; não executou builds ou banco.

Achados corrigidos:

1. `composition.version` passou a usar **1.0.0**, como Core, em vez de criar alias 1.
2. Remapeamento passou a usar **(kind,id)**; endpoint consulta o mapa de widgets. Corpus
   inclui mesmo UUID em widget e link, hashes reproduzidos e destino sem colisões.
3. Revisão de títulos/identificadores acrescentou negação de whitespace e controles nas
   extremidades. Um apontamento inicial de escape duplicado foi retirado após conferir bytes.
4. Inventário preveniu input `authoringDocument` fictício nos filhos e duplicação da autoria.
   Bindings fixos e seeds/capturas server-owned estão agora explícitos.

Parecer final do revisor: **apto para P0, sem novos impeditivos, condicionado à execução focal
final pelo coordenador**. A execução descrita acima satisfez essa condição. Isso não aprova
implementação de HTTP, banco, codecs, autorização, concorrência ou hosts.

## Rastreabilidade da árvore validada

SHA-256 dos arquivos de contrato/corpus/teste usados na última execução; não são atestados
de publicação corporativa:

| Arquivo | SHA-256 |
|---|---|
| `docs/ai/contracts/page-publication-p0/contracts.schema.json` | `6457c670df36543cc473644b5c1cfe50665532ab92603f72af291b0d6d31005e` |
| `docs/ai/contracts/page-publication-p0/corpus.json` | `b45364d03c2d256c815de4db2ce82cc821b63cf693410200698d3877f813fc93` |
| `docs/ai/contracts/page-publication-p0/scenarios.json` | `8ba887ce52f8a05a48f4ef4ee91f5d5da2bc1c144ac2445740554e021610a36c` |
| `src/test/java/org/praxisplatform/config/service/PagePublicationP0ContractTest.java` | `49f7be74991099476facf4c4527630dfe187996ba6b9ed724e3ed1ca6fa2888d` |

## Pendências e próximo passo

P1: codec page/groups; adapters nativos sem defaults; captura/admissão dos filhos; testes de
round-trip e reprodução. P2/P3: criação atômica, lifecycle, integridade referencial, descoberta,
resolução e navegação. P4/P5: editores, hosts e migrações. As provas futuras estão em
[scenarios.json](scenarios.json), com resultado esperado e invariante de falha.

Nenhuma garantia corporativa foi validada por ensaio sintético. Casos com banco exigem Neon
remoto em branch isolada atribuída, papéis/TLS/contexto comprovados e HTTP real no Quickstart.
E2E, acessibilidade visual e recarga do host pertencem aos incrementos seguintes.

Documentação derivada atualizada neste corte: índice de contratos Config e estado dos dois
documentos de pesquisa/plano do workspace. Landing, corpus HTTP operacional, manifests ativos,
recipes e skills não devem anunciar a capacidade antes da implementação. Avaliação das skills
canônicas consta do README; sem sincronização local necessária nesta entrega.
