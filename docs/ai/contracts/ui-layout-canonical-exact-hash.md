# Hash exato de documentos governados

Correção 6q/R1. `CanonicalJsonHashService.sha256Exact` define identidade JSON para baselines, documentos de trabalho e patches/revisões. Não é atestado de origem, permissão, ordem de propriedades ou completude.

A árvore JsonNode é percorrida diretamente; null explícito permanece distinto de propriedade ausente. Chaves são ordenadas, arrays mantêm ordem e números finitos seguem os vetores canônicos compatíveis com o browser. Conversão/escaping usam configuração JSON privada fixa, sem defaults ou serializers do host. Valores não-JSON em JsonNode são recusados. Entradas Java precisam ser compatíveis com conversão JSON padrão; serializers de domínio do host não participam do hash exato.

`sha256` mantém sua semântica legada, inclusive omissão de nulls em objetos e conversão do host. A correção do hash exato não é migração automática do hash legado.

O chamador deve fornecer conteúdo íntegro: o hash não recupera nulls já descartados por parser externo, nem restabelece origem. Capture mantém parsing estrito e quotas próprios; o envelope workspace também precisa preservar a árvore no roundtrip. O isolamento de mapper deve ser testado com o MESMO mapper corporativo injetado no hash e no codec, não com hash construído separadamente sob defaults.

## Histórico afetado e uso corporativo

Hashes criados sob defaults divergentes podem não conferir após a correção. Sem inventário da configuração e dos dados reais, não afirmar quantidade de registros afetados. Readers devem rejeitar discrepâncias; não reescrever hashes/captures, completar inputs com metadata atual, usar hash legado como fallback ou criar flag de compatibilidade. Origem/histórico incompleto permanece indisponível e exige revisão operacional autorizada.

Se houver rejeição, a UX deve indicar evidência indisponível/incompatível, sem afirmar que edição/aplicação ocorreu e sem expor payload bruto. Esta correção não acrescenta UI ou prova de browser; os estados e a recuperação precisam do host integrado.

Gates focais: vetores canônicos existentes; null/ausência, Unicode em chaves/valores e nested/arrays; mappers READ/WRITE_NULL_PROPERTIES=false, escaping Unicode e serializer customizado; captura e workspace com mapper/hash compartilhados; histórico incompatível negado. Store/writer/resolução e Quickstart precisam passar no artefato candidato. PostgreSQL e runtime/browser integrado são provas separadas.

Nenhum endpoint, DTO, provider de execução, tag ou publicação nasce desta correção. A coordenada privada de teste não é confirmação de versão pública instalada.

## Identidade numérica e fronteiras textuais

No modo exato, inteiros e decimais de precisão arbitrária só são aceitos quando
seu valor decimal é igual ao token produzido pelo formatador canônico existente.
Assim, 9007199254740992 é representável, mas o inteiro adjacente
9007199254740993 é recusado antes da persistência. Não há limite global por
magnitude: 1e20 e 1e21 podem manter sua identidade. A comparação é decimal;
0.1 não precisa de representação binária exata para ser aceito.

DoubleNode/FloatNode já materializados denotam seu valor finito binary64/binary32.
Seus vetores browser e o hash legado continuam usando a formatação atual. Texto
JSON é preservado como decimal antes da validação: 0.10000000000000001,
underflow 1e-400, overflow 1e400 e texto 4.9e-324 são recusados quando não mantêm
identidade no token canônico; texto 5e-324 corresponde ao menor Double positivo.
-0 e notações equivalentes mantêm a equivalência numérica, sem promessa de
preservação lexical, escala ou precisão arbitrária do domínio.

Revision input, lifecycle, reader, captura e decode workspace usam fronteiras
JSON privadas para não perder precisão nem null explícito antes do hash. Quotas,
syntax fechado, diagnostics e escopo autorizado continuam obrigatórios.
Histórico com identidade incompatível falha fechado; não reparar hashes, usar
fallback legado ou reescrever documentos silenciosamente.

Provas propostas neste incremento ainda aguardam gate/execução: entrada HTTP
precisa negada antes de persistir; revisão/freeze/publicação/reader com o MESMO
mapper corporativo; captura e workspace negando texto alterado que antes colapsava
no hash anterior. MockMvc e repositories de teste não comprovam PostgreSQL,
browser ou aplicação remota. A publicação exige gates separados.

A origem nativa admite a árvore raw pelo mesmo hash exato antes de comparar valores numéricos com o baseline materializado. A comparação mantém ordem e proveniência, usando o token canônico existente para Float/Double: `5e-324` corresponde a `Double.MIN_VALUE`, enquanto raw `4.9e-324` e underflow são recusados tanto no seal quanto na leitura, mesmo com digest raw coerente. O teste de null compara a mesma patch com apenas o campo `title` removido. Essas novas provas permanecem sem execução até o gate da revisão independente.

#### Correção após a campanha V3

A campanha privada V3 executou 148 casos: três falhas, nenhum erro/skip. A publicação com mapper que remove null revelou mais um elo: WorkspaceSelectionVerifier.parsePatch, usado por FrozenWorkspaceResolver, herdava o mapper. O candidato V4 reutiliza o parser fechado de revisão nesse leitor e mantém INVALID_STATE e a verificação do hash. O core também aplicava por engano 256 KiB ao agregado inteiro do draft: V4 usa o parser fechado já existente do workspace para envelopes, mantendo os limites por documento e sem inventar quota para a composição. A expectativa numérica passa a exigir o contrato existente 422/INVALID_RELEASE, além de zero mutações; o status isolado não certifica o guard. V4 ainda não executado/aprovado.
