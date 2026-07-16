# Evidência 32 — consistência estendida e refinamento semântico compacto

Data: 2026-07-16

## Resultado

As seis jornadas bloqueantes passaram três vezes no mesmo pacote e o perfil
estendido passou de 9/12 para 12/12 depois da correção da variância observada
no refinamento progressivo de Table.

O ajuste não introduz roteamento por palavras-chave nem um novo contrato de
UI. Quando existe um componente selecionado, o resolvedor usa uma chamada LLM
compacta para decidir semanticamente se o novo pedido ainda pertence ao alvo e
qual capability declarada representa a mudança. O contexto contém apenas:

- alvo selecionado e recurso governado;
- decisão semântica ativa;
- duas mensagens recentes da conversa;
- `changeKind`, efeitos semânticos, exemplos e restrições já publicados pelo
  catálogo de capabilities do componente.

O schema estruturado restringe `changeKind` às capabilities declaradas para o
componente atual. A posição da capability, aliases, termos e regexes não
decidem a intenção. Se a LLM classificar o pedido como novo artefato, outro
componente, orientação geral ou regra compartilhada, o resolvedor completo
retoma o turno. Se a chamada compacta falhar no provider, o fluxo falha fechado
e não multiplica latência e tokens com um fallback genérico grande.

## Inventário de aderência

| Necessidade | Classificação | Decisão |
| --- | --- | --- |
| Saber qual widget, componente e recurso estão selecionados | `ja-suportado-so-ux` | Reutilizar `AgenticAuthoringTarget` e `selectedWidgetKey`. |
| Preservar objetivo, recurso e continuidade do turno | `ja-suportado-mal-nomeado-ou-mal-materializado` | Reutilizar decisão ativa, thread e conversa já existentes. |
| Distinguir `column.add` de reordenação, formato ou visibilidade | `ja-suportado-mal-nomeado-ou-mal-materializado` | Projetar efeitos, exemplos e restrições do catálogo governado de Table. |
| Limitar custo e variância da decisão contextual | `suportado-parcialmente` | Adicionar uma resolução LLM interna e compacta com schema dinâmico. |
| Criar endpoint, DTO, evento SSE ou ontologia paralela | não aplicável | Nenhuma lacuna real de contrato público foi encontrada. |

A plataforma já conhecia tudo que a correção precisava. A lacuna estava na
materialização excessivamente genérica desse contexto para a LLM, não na
ausência de metadata ou de um novo contrato canônico.

## Evidência real com OpenAI

### Gate bloqueante `must-pass`

As seis jornadas foram executadas três vezes com quickstart real, Neon,
OpenAI `gpt-4.1-mini` e stream SSE:

| Indicador | Resultado |
| --- | ---: |
| Execuções aprovadas | 18/18 |
| Acurácia | 100% |
| P50 terminal | 15,264 s |
| P95 terminal | 23,698 s |
| Tokens totais | 24.987 |
| Máximo de tokens por execução | 1.753 |
| Custo estimado total | 11.474 micros de USD |
| Provas transacionais | 3/3 |

Evidência local:
`artifacts/local-e2e/assistant-consistency-20260716-111854`.

### Baseline negativo do perfil estendido

O primeiro perfil estendido preservou a saída funcional correta, mas reprovou
três turnos de refinamento por eficiência:

- `add-email` da primeira repetição: 49,018 s;
- `add-salary` da primeira repetição: 68,512 s;
- `add-salary` da terceira repetição: 45,113 s e 12.068 tokens.

A telemetria mostrou que o fluxo usava `intent_fast` com o schema genérico
completo. Na última falha, essa fase expirou e abriu `intent_full`, consumindo
11.414 tokens adicionais no fallback. O resultado foi 9/12, P95 de 68,512 s,
66.098 tokens e 31.718 micros de USD. Evidência local:
`artifacts/local-e2e/assistant-consistency-20260716-112537`.

### Prova focal depois da correção

A jornada progressiva de colunas passou em seis turnos consecutivos:

| Indicador | Resultado |
| --- | ---: |
| Execuções aprovadas | 6/6 |
| P50 terminal | 36,149 s |
| P95 terminal | 40,955 s |
| Máximo de tokens por execução | 4.007 |
| Máximo de custo por execução | 2.081 micros de USD |
| `canApply=true` | 6/6 |

`email` foi acrescentado e preservado antes de `salario`, sem duplicatas, sem
clarificação indevida e sem fallback para `intent_full`. Evidência local:
`artifacts/local-e2e/assistant-consistency-20260716-114729`.

### Perfil estendido final

O fechamento repetiu orientação em inglês, formulário com erro de digitação e
a jornada progressiva de Table três vezes:

| Indicador | Antes | Depois |
| --- | ---: | ---: |
| Execuções aprovadas | 9/12 | 12/12 |
| Acurácia estendida | 75% | 100% |
| P50 terminal | 32,508 s | 26,584 s |
| P95 terminal | 68,512 s | 39,481 s |
| Tokens totais | 66.098 | 30.437 |
| Máximo de tokens por execução | 12.068 | 4.019 |
| Custo estimado total | 31.718 | 13.721 micros de USD |
| Estimativa de custo completa | 12/12 | 12/12 |

Isso representa redução de aproximadamente 42% no P95, 54% nos tokens totais
e 57% no custo estimado total, além da recuperação de 25 pontos percentuais de
assertividade. Evidência local:
`artifacts/local-e2e/assistant-consistency-20260716-115149`.

## Mapa de impacto

- fonte canônica afetada: `praxis-config-starter`, exclusivamente no
  orquestrador interno de intenção;
- consumidores impactados: refinamentos de componentes já selecionados no
  Page Builder e futuras shells que reutilizem o mesmo turn engine;
- host de referência: `praxis-api-quickstart` foi apenas reempacotado contra o
  starter local para a prova, sem alteração de fonte;
- docs públicas, Angular, landing page, recipes e corpus HTTP: sem atualização
  derivada necessária, pois endpoint, DTO, SSE, OpenAPI e public API não
  mudaram;
- risco de breaking change: baixo; pedidos fora do escopo compacto retornam ao
  resolvedor completo e falhas do provider permanecem fail-closed.

## Validações

- 26 testes focais do resolvedor LLM, incluindo schema dinâmico, contexto
  limitado, falha fechada e retorno ao resolvedor completo;
- 405 testes focais de corpus, intent resolver e turn engine;
- gate integral `ci-smoke-unit`: 1.975 testes, sem falhas ou erros, além de
  JAR, sources e javadocs;
- starter instalado localmente e quickstart reempacotado, com SHA da classe
  validada dentro do fat JAR;
- gate `must-pass`: 18/18 com três provas transacionais;
- gate focal da jornada progressiva: 6/6;
- gate estendido final: 12/12;
- quickstart e conexões encerrados depois das provas.

## Próximos passos recomendados

1. Certificar a mesma shell e o contexto mínimo assistível nos hosts de Table
   e Dynamic Form, incluindo prova browser de UX e acessibilidade.
2. Ampliar Table com reordenação, visibilidade, formato, filtros e recuperação
   após schema temporariamente indisponível, mantendo cada capability
   semanticamente distinta.
3. Abrir um slice isolado para a versão compatível mais atual de Spring AI,
   OpenAI SDK e política de modelos; comparar assertividade, P50/P95, tokens e
   custo com estes baselines antes de promover qualquer migração.
4. Manter Spring AI 2.0 + Boot 4 como spike arquitetural separado até existir
   evidência operacional superior ao caminho compatível.
