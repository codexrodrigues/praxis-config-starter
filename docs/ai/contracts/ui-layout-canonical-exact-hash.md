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
