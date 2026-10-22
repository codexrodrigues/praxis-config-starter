# Evidência P1-A

Fonte de implementação final: `ce166fed180361801872aea392d69bd45fae2132`, árvore
`c2eef28b8f9a8368665e2eac4dee99f10cee32d8`. O commit seguinte adiciona somente este relatório.
Java Temurin 21.0.10, Maven 3.9.15, macOS arm64. [Manifesto](manifest.json) fixa hashes dos logs brutos.

| Classe | Log | Resultado e alcance |
|---|---|---|
| Unitário/modelado + Neon | [final-tests](final-tests.txt) | 273 executados, uma falha de stubbing Mockito, zero skips; 2 cenários Neon passaram (104.9s). Não é uma suite verde. |
| Store/admissão + snapshot | [store-snapshot-tests](store-snapshot-tests.txt) | 58 passaram; corrige o stubbing e prova conexão JPA exata, rejeição de holder avulso/outro DataSource/autocommit e snapshot. |
| Lifecycle/limite + Neon | [final-rollback-reason-tests](final-rollback-reason-tests.txt) | 14 unitários + 1 Neon passaram; reason 1025 rejeitado antes de writes e rollback de draft/metadata juntos. |
| Assignment + Neon | [assignment-tests](assignment-tests.txt) | 2 unitários + 1 Neon passaram após parametrização do assignment; confirma identidade/TLS e rollback de criação. |
| Integridade histórica | [historical-boundary-tests](historical-boundary-tests.txt) | 44 passaram após ce166fed; corruption JSON/binding por projector HTTP e frozen resolver retorna INVALID_STATE/409/no-store, sem mutação. |
| Setup remoto | [neon-prepare](neon-prepare.txt) | Hashes dos cinco DDLs e schema; não conta como teste do lifecycle ou Flyway integral. |
| Build Config | [install-final](install-final.txt) | Instalado exclusivamente no repositório Maven isolado, testes já executados separadamente. |
| Downstream | [quickstart-final](quickstart-final.txt) | 28 passaram e package executável; JAR Config aninhado byte a byte igual ao candidato. |

Os cenários Neon cobrem criação, JSONB/JPA, remove/restaura, inclusão com nova fonte, revisão,
assignment, freeze, submit, approve, publish, leitura histórica, replay/restauração negados por
revogação e beforeCommit sem troca de invocation. Duas novas conexões observam rollback de revisão/
workspace e rollback conjunto de draft/metadata. Source/policy são sintéticos. Nenhum banco local,
HTTP real do host, E2E browser ou pacote de grupos/catálogo foi usado como prova.

Comandos (na worktree Config, exceto downstream):

```sh
# Execução ampla original, com resultado falho preservado:
mvn -B -Dtest=PagePublicationP0ContractTest,UiLayoutPageRevisionReproductionTest,UiLayoutPageChildCapturesTest,UiLayoutLifecycleHttpRoundTripTest,UiLayoutLifecycleCommandServiceTest,UiLayoutLifecycleServiceTest,UiLayoutNativeStructureChecksTest,UiLayoutFormRevisionReproductionTest,UiLayoutTableRevisionReproductionTest,UiLayoutAuthoringProjectionTest,UiLayoutDraftWorkspaceCodecTest,UiLayoutRevisionJsonInputTest,UiLayoutRevisionRequestBodyAdviceTest,JdbcUiLayoutMetadataCaptureStoreTest,UiLayoutPageLifecyclePostgresIT -Dpraxis.page.neon.it=true test
mvn -B -Dtest=JdbcUiLayoutMetadataCaptureStoreTest,AiRegistrySnapshotContractTest test
mvn -B '-Dtest=UiLayoutLifecycleServiceTest,UiLayoutPageLifecyclePostgresIT#finalAdmissionRevocationRollsBackDraftAndMetadataTogether' -Dpraxis.page.neon.it=true test
mvn -B '-Dtest=UiLayoutPageNeonAssignmentTest,UiLayoutPageLifecyclePostgresIT#finalAdmissionRevocationRollsBackDraftAndMetadataTogether' -Dpraxis.page.neon.it=true test
mvn -B -Dtest=UiLayoutLifecycleHttpRoundTripTest,UiLayoutPageChildCapturesTest,UiLayoutDraftWorkspaceCodecTest,JpaUiLayoutCompositionReleaseReaderTest test
mvn -B -Dmaven.repo.local=/tmp/praxis-page-p1-508-m2 -DskipTests -Dmaven.javadoc.skip=true install
# Quickstart 64080d77986f567f2d9bc6b27b7c6e218dbd7834, árvore limpa 05b2421bad2ee4b848e1849f8fded057279546c6:
mvn -B -Dmaven.repo.local=/tmp/praxis-page-p1-508-m2 -Dpraxis.config.version=0.1.0-rc.166 -Dtest=UiLayoutFormPublicationDownstreamTest,UiLayoutCompactPublicationDownstreamTest,UiLayoutLifecycleAutoActivationDownstreamIT package
```

Comandos Neon receberam `PRAXIS_PAGE_P1_NEON_RUNTIME_FILE` apontando ao assignment privado autorizado.
O repositório Maven isolado foi iniciado com cópia por clone APFS do cache local, depois recebeu somente
o artefato Config da worktree. Não equivale a resolução limpa de Maven Central. Dependências aninhadas:

| Artefato | SHA-256 |
|---|---|
| Config 0.1.0-rc.166 | `e516116460df28fdb2dfb3544fc42eb81e0af7738e4a401768e3f22703f40b9f` |
| Metadata 8.0.0-rc.178 | `24a592b0723efcd29c1b45d409d90003e2889e6ce426b1073213fcd455945321` |
| Config contracts 0.1.0-beta.5 | `b50971ae80f47fd5ddcb69ed775a422b35b58a546bffd71c5f7993ab2ff44057` |
| Rules engine 0.1.0-beta.17 | `2ca78cc7fe1abcf63b36df0259ccf41491acf6056eb4fd837ea334229c61ea52` |

Não somar execuções repetidas como casos novos. Não contar XMLs antigos como frescos. Logs mostram
identidades das suites, saídas Maven e skips; alguns testes reutilizados antecedem o ajuste exclusivo
de classificação histórica, cuja regressão focal está separada. Alterações apenas em docs não mudam
o artefato validado. Revisão independente, paridade Angular e skills canônicas têm gates próprios.
