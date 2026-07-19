# OpenAI 5.6 Terra — evidência ampliada de consistência

Data: 2026-07-18

## Escopo executado

- provider real OpenAI, modelo `gpt-5.6-terra`;
- Quickstart local real em `http://localhost:8088`;
- perfil `extended`, 3 repetições, 48 turnos;
- perguntas básicas, criação de formulário/tabela/tela, variação em inglês, erro de digitação, jornada orientação → dashboard e seis refinamentos cumulativos de tabela;
- limites do corte x3: 40.000 tokens e USD 0,10 por turno; latência medida sem reprovação. As provas focais posteriores usaram 50.000 tokens e USD 0,15 para caracterizar os piores turnos observados.

## Resultado bruto e diagnóstico

O primeiro corte consolidou 15/48 porque os contextos históricos do corpus não materializavam o `agenticApplyTarget` que o Page Builder já conhece. A jornada nova, que já carregava esse alvo, chegou a uma prévia aplicável nas três repetições. Aderência: `ja-suportado-mal-nomeado-ou-mal-materializado`; não houve criação de contrato.

O ensaio bruto mediu mediana de 28,457 s, p95 de 49,728 s, 406.720 tokens e custo estimado total de USD 0,823869. O maior turno consumiu 45.197 tokens e USD 0,129530, acima dos limites inicialmente usados de 40.000 tokens e USD 0,10.

## Correções do gate comprovadas

- os contextos de página vazia e página existente agora publicam o alvo canônico de aplicação;
- cada unidade/repetição recebe `componentId` isolado, preservado do turno ao apply;
- uma página fornecida somente como contexto local usa `mode=create`; `mode=update` continua exigindo `baseEtag` real;
- a prova transacional respeita o contrato atual: create, readback exato, bloqueio de create duplicado, estado inalterado e cleanup.

Provas focais posteriores:

- formulário: 1/1, `canApply=true`, persistência 1/1, custo estimado USD 0,005329;
- jornada de seis refinamentos da tabela: 6/6 aplicáveis, preservando colunas, ordem, visibilidade, formatação e filtros.

## Lacunas reais remanescentes

1. `platform-what-can-i-do-en`: o runtime respeita a intenção, mas responde em português; o locale ainda não governa consistentemente a apresentação.
2. orientação → dashboard: uma execução posterior foi bloqueada por `semantic-preview-axis-stats-capability-verification-required`; o eixo foi reconhecido, mas a capacidade de estatística do recurso não ficou comprovada para aplicação.
3. observabilidade: a continuação com decisão ativa publicou `intentResolveLlm`/`intentResolution` negativos, indicando ordenação ou referência temporal incorreta.
4. eficiência: o Terra entrega melhor assertividade, mas os piores turnos exigem orçamento acima dos limites econômicos anteriores. Ele permanece indicado para assertividade/demonstração, não como padrão econômico de desenvolvimento.

## Próximo gate

Não executar novo `extended x3` até corrigir locale, grounding de stats e timestamps. Depois, repetir o mesmo corpus com meta de 100% must-pass, pelo menos 95% extended, zero duração negativa e limites de custo/tokens explicitamente aprovados.
