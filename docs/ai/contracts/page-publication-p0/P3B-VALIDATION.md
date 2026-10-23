# P3-B — validação atribuída

Estado: gates focais Config concluídos e revistos nas fontes abaixo. Produção R2 é `0eb159862ba9fcd8fbcecb1ad851f6ba30fe5218`; o último addendum de testes é `bfec7fe744cd43f7e94f49dcf74516d780095a98`. A reconciliação de main em `4ec36ce44f7e49a76c44628d2ea45ae45db60498` altera somente o snapshot AI registry e seu teste. Não houve nova execução Neon após a fusão, nem se atribuem os ensaios à árvore fundida.

[Implementação e limites](P3B-IMPLEMENTATION.md), [manifest portátil](evidence/p3b/manifest.json), [revisão pós-R2](evidence/p3b/POST-R2-REVIEW-MATRIX.md) e [parecer S2](evidence/p3b/S2-REVIEW.md) identificam o objeto de cada aceite. Não somar rodadas sobrepostas como uma suite única da fonte final.

## Recurso Neon e identidade

Projeto `ancient-leaf-72055027`, branch exclusiva `br-weathered-mountain-a48o0map`, endpoint direto `ep-floral-voice-a40bufyv.us-east-1.aws.neon.tech`; schema `p3b_runtime_512_20261023`, runtime `p3b_runtime_512_runtime`. PG 17.11, V64–68/V70 atuais; nenhum DDL novo em R2, nenhum uso de banco local como prova.

API confirmou ADMIN/PG17; SQL confirmou owner `neondb_owner` sem SUPERUSER. Runtime sem SUPERUSER/BYPASSRLS/CREATEDB/CREATEROLE/schema CREATE/evidence UPDATE/catalog DELETE; authoring limitado às tabelas/colunas existentes. [Preparo](evidence/p3b/neon-preparation.json) fixa migrations, hashes e privilégios. A fixture verifica identidade, schema, versão e SSLSocket/peer JDBC TLS1.3 antes de Hibernate validate.

O primeiro preparo abortou antes de DDL ao exigir `pg_stat_ssl.ssl=true`: esse campo descrevia o backend, enquanto libpq verify-full comprovava TLS do cliente. A segunda preparação preserva ambos; [handshake independente](evidence/p3b/tls-peer.json) valida hostname/CA. O log de cada rodada comprova o socket JDBC real. Segredos ficam fora do export. A branch foi preservada com autosuspend 300, sem atribuição nova a P3-C; nenhuma conexão foi aberta neste fechamento.

## Resultados e limites

Cada diretório abaixo está em [evidence/p3b](evidence/p3b/README.md), com run-start/final, seleção exata, árvore, hashes de fontes/brutos, exit e XML. Todos os resultados verdes têm zero falhas, erros e skips. Os tempos de XML incluem setup e não são latência runtime.

| Rodada | Fonte | Resultado e limite |
| --- | --- | --- |
| `neon-a-2b0` | `2b0db3a5` | 3 casos, 367,076 s. Criação/publicação P2 reais, mesmo head no catálogo/URL/menu, phantom e ABA em transações independentes, own-writes REQUIRED e rollback fresh. Histórica anterior a R2. |
| `neon-b-ad62` | `ad62bd9a` | 2 casos, 475,341 s. Cursor/ordem/identidade, app/tenant/env/namespace/contexto A→B→A, selectors e autoridade final antes de 304. |
| `neon-c-ad62` | `ad62bd9a` | 1 caso, 102,453 s. ACK perdido sintético após COMMIT JDBC real, evento/head fresh e novo owner reconciliando GET/304. Não prova crash de processo ou rejeição de COMMIT. |
| `neon-density32-97ed`, `neon-density129-97ed` | `97edb0e5` | Diagnósticos: D32 aceito em 23,856 s; D129 indisponível após 40,389 s, somente 1 vetor/lote e 128 workspaces. XML diagnóstico verde não aprova capacidade 129/1000 nem causa exclusiva de deadline. |
| `prefetch-unit-initial-529b` | `529b7484` | 86 casos focais; readset/RawRow, prefixo/replacement/guard/savepoint e stores. Sem banco. |
| `prefetch-gates-0eb` | `0eb15986` | 72 casos focais de stores/owner/bridge. Sem banco. |
| `prefetch-loop-final` | `9119837f` | 1 caso focal de replacement/refill; sem banco. |
| `unit-deadline-97ed` | `97edb0e5` | 1 caso: extensão structure esgota budget e retorna sucesso; owner/MockMvc nega 503/no-store sem documento/receipt/ETag/304. I/O simulado e budget unitário 2 s; sem alteração dos defaults ou prova de cancelamento. |
| `neon-density32-prefetch-1e133455` | `1e133455` | 32 IDs completos, leitura 6,126 s/40 s, 2 vetores/1 lote, 17 SQL concluídos/6 getters; callbacks iguais ao baseline D32. |
| `neon-density129-prefetch-e6c75aec` | `e6c75aec` | 129 IDs completos, leitura 9,764 s/40 s, 2 vetores/2 lotes, managed starts [0,0], peak 768. |
| `neon-density1000-prefetch-e6c75aec` | `e6c75aec` | Gate aceito em 35,476 s/40 s; 1.000 IDs carregados uma vez e observados por structure, 2 vetores/8 lotes, oito starts managed zero, peak 768, heads/audit fresh iguais. |
| `neon-private129-prefetch-e6c75aec` | `e6c75aec` | 1 caso, 95,312 s. 127 privadas/2 visíveis, 4 lotes em dois GETs limit1, ordem global e continuação sem receipts privados; heads/audit fresh iguais. Não é corrupção privada. |
| `neon-workspace-capture-prefetch-e6c75aec` | `e6c75aec` | 2 casos, 115,471 s. Workspace prefix de 7 IDs em 2/1/1/1/2, 3+5 MiB, primeiro oversize e missing; captura pública otherwise-correlated corrompida nega 503 sem parcial/304. Não é prefixo RawRow. |
| `neon-runtime-regressions-prefetch-e6c75aec` | `e6c75aec` | 4 casos, 807,618 s. Published copy P2 com head próprio após retirada da origem/grants ancestrais negados; own-writes REQUIRED; cursor/ABA/escopos; selector e autoridade final. Fonte da origem e release destino conferidas fresh. |
| `neon-raw-evidence-bfec7fe7` | `bfec7fe7` | 4 casos, 659,143 s. SQL ledger/metadata no envelope exato, escaping/nulls, prefixos 128+1, 8 MiB/missing, isolamento de RawPageKey no mesmo owner/transação. Primeiro RawRow oversize usa SELECT sintético com parâmetro grande, sem INSERT na tabela e sem alegar publicação admitida. |
| `neon-private-corruption-bfec7fe7` | `bfec7fe7` | 1 caso, 164,035 s. Contrafactuais SQL de READ_HEAD/selector, controle saudável Bob, 503 ao admitir mesma fonte corrompida, nenhuma parcial/304; projeção pública e heads/audit fresh preservados. [Revisão independente](evidence/p3b/S2-REVIEW.md). |

D1000: 108 statements preparados concluídos (20,186 s), 20 getters (2,877 s), CPU real do thread atual 10.789.284.000 ns. Callbacks: 16.000 native, 48.000 metadata, 17.000 structure, com 1.000 raízes semeadas e outra raiz; identidades são provadas separadamente das contagens. Retenção amostrada: 128 entradas por owner, 936.960 bytes ledger e 365.824 metadata; máximo simultâneo observado 1.302.784 bytes/256 entradas. Não é heap ou limite global. SQL falho não entra no tempo concluído; não inferir CPU por subtração.

Os seeds densos são registros sintéticos correlacionados com codecs/FKs/review/approval/quatro eventos e triggers, não 1.000 jornadas authoring/HTTP ou cadeias Published. As policies da fixture não fazem I/O remoto externo. RawIT calibra envelopes por SELECT com DriverManagerDataSource sem pool; setup/calibração abre conexões TLS e não mede a latência runtime. O deadline é cooperativo e não cancela I/O bloqueado.

## Evidência anterior e fechamento

Os logs focais iniciais estão preservados: `focal-initial.log` tem 34 casos e oito erros de fixture (não verde); `focal-corrections-1.log` preserva maker inline/assertion HTTP incorretos; `focal-corrections-2.log` passa 12 casos; `focal-boundary.log` passa 14; host-root/node-boundary/provider-wiring têm um caso cada. São rodadas sobrepostas, sem soma. O histórico Git conserva o desenho/checkpoints completos.

Todos os hashes do export foram confrontados com os brutos e as fontes Git. Propriedades XML e caminhos privados foram sanitizados; o manifest distingue hash original/exportado. Dependências coletadas agora a partir do classpath histórico têm essa limitação explícita. Runners retidos não são apresentados como versões históricas exatas quando seus hashes diferem.

Antes do push foram revistos os gatilhos reais: release somente tag `v*` ou dispatch; demais workflows manuais/schedule. Nenhuma tag, dispatch ou release é necessária neste incremento. A validação adicional da reconciliação passou no teste existente `AiRegistrySnapshotContractTest`: 11 casos, zero falhas/erros/skips, 1.928 s ([registro](evidence/p3b/registry-merge/run.json)). Fonte/teste/POM idênticos a `4ec36ce4`, somente docs de fechamento ainda sem commit nessa execução. O pacote privado recompila a fonte final sem testes de banco.

P3-C host HTTP, renderer/rotas/menu browser P4, E2E visual e push invalidation não foram executados. Compile privado Quickstart e wire/build Core são provas separadas do coordenador; publicação Maven, adoção pública e integração de guidance não são inferidas dos testes Config.
