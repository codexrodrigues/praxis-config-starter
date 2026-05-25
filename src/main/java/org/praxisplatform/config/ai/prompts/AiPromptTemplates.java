package org.praxisplatform.config.ai.prompts;

import java.util.Map;

public final class AiPromptTemplates {

    private AiPromptTemplates() {}

    public static final String CONTRACT_DSL = """
    - SINTAXE OBRIGATÓRIA: Use DSL específica, NÃO use JavaScript puro.
    - ERRADO: "row.status === 'Active'", "value.includes('A')", "item.price > 10"
    - CERTO: "status == 'Active'", "contains(name, 'A')", "price > 10"
    - Operadores: ==, !=, >, <, >=, <=, in, contains, startsWith, endsWith.
    - Contexto: O campo é implícito. Não use prefixos como "row." ou "this.".
    """;

    public static final String CONTRACT_ICONS = """
    - Use apenas nomes de ícones do Google Material Symbols (ex: check_circle, warning, edit, delete, add).
    - Não use URLs ou classes CSS para ícones.
    """;

    public static final String CONTRACT_FORMATTING = """
    - Datas: Use { "type": "date", "format": "dd/MM/yyyy" } (ou similar).
    - Moeda: Use "BRL|symbol|2" (currencyCode|display|decimals) ou "USD|code|0". Opcional: sufixo "|nosep".
    - Número: Use padrões do DecimalPipe, ex.: "1.2-2" (mínimo 2, máximo 2 decimais).
    - Percentual: Use padrões do PercentPipe, ex.: "1.0-0|x100" para converter 0.1 -> 10%.
    - Boolean: Use { "type": "boolean" } e, opcionalmente, format strings como "true-false", "yes-no", "active-inactive" ou "custom|Sim|Nao".
    - String: Use "uppercase|truncate|50|..." quando precisar truncar (transformação + limite + sufixo).
    """;

    public static final String CONTRACT_SAFETY = """
    - PRESERVAÇÃO: Ao atualizar listas/coleções, mantenha os itens existentes que não foram alterados.
    - DESTRUIÇÃO: Não remova elementos (colunas, campos, regras, ações) a menos que explicitamente solicitado.
    """;

    public static final String CONTRACT_API_LISTING = """
    - PADRÃO PRAXIS (listagem): Para recursos CRUD, use POST {resource}/filter para filtros, paginação e listagem.
    - NÃO invente endpoints como /list ou /all. Se o endpoint não estiver no contexto, peça confirmação.
    """;

    public static final String CONTRACT_RENDERER_PAYLOADS = """
    - CONDITIONAL RENDERERS: 'columns[].conditionalRenderers[].renderer' deve seguir o mesmo schema dos renderers principais.
      - CORRETO: { type: 'badge', badge: { text: 'Ativo', color: 'success' } }
      - ERRADO (Flatten): { type: 'badge', text: 'Ativo', color: 'success' }
      - ERRADO (Vazio): { type: 'badge' }
    - BADGE/CHIP: Exige payload tipado completo.
      - BADGE: use 'badge.textField' ou 'badge.text'; CHIP: use 'chip.textField' ou 'chip.text'.
      - Para booleanos/códigos discretos: use "valueMapping" quando o pedido for apenas trocar rótulos.
      - Para rótulos com aparência visual diferente por valor (ex.: Sim verde, Não cinza), use "columns[].conditionalRenderers[]" com uma regra por valor, preservando os rótulos existentes quando o usuário só pedir cor, variante, ícone ou tooltip.
      - Não repita um renderer genérico sem a propriedade visual solicitada. Se o usuário pedir "verde", "cinza", "suave", "discreto", "com ícone" ou "tooltip", inclua isso no payload tipado do badge/chip.
      - Default variant: 'filled'. Default color: 'primary'.
    - TOGGLE: Exige 'toggle.stateExpr' e 'toggle.action.id' quando interativo.
      - Use DSL em disabledCondition (ex.: ativo == false).
      - Prefer ariaLabel explícito para acessibilidade.
    - PROGRESS: Exige 'progress.valueExpr' (0-100). Cor opcional. 'showLabel' habilita o número.
      - Evite deixar apenas type; sempre informe a origem do valor.
    - RATING: Preferir modo somente leitura em tabela. Exige 'rating.valueExpr'; 'max' padrão 5.
      - Inclua 'color' e, se usado, 'outlineColor' e 'size'; ariaLabel é recomendado.
    - PREENCHIMENTO MÍNIMO: Não responda apenas com "renderer.type". Preencha as subpropriedades necessárias (ex: text, valueExpr, src).
    - Evite objetos vazios: Se usar renderer.badge, preencha suas propriedades.
    """;

    public static final String CONTRACT_VISUAL_DESIGN = """
    - VISUAL SURPREENDENTE: Sempre que possível, utilize propriedades de estilo para criar interfaces ricas e modernas.
    - TABLE/LIST:
      - 'appearance.density': Use "comfortable" ou "compact" conforme a quantidade de dados.
      - 'appearance.elevation': Use nível 1 ou 2 para destaque.
      - 'skin': Para listas, prefira "glass" ou "gradient-tile" quando apropriado para dashboard.
      - 'toolbar.visible': true para permitir ações globais.
    - DYNAMIC PAGE (Master-Detail / Dashboard):
      - LAYOUT: Use 'layout.columns' (ex: 3 ou 4) para criar grids flexíveis.
      - SPANS: Use 'className': 'col-span-2' (ou 3, 4, full) nos widgets para proporções assimétricas.
        - Exemplo Master-Detail (2:1): Tabela com 'col-span-2', Form com 'col-span-1' (layout.columns=3).
        - Exemplo Dashboard: KPIs no topo ('col-span-1'), Gráfico principal ('col-span-full').
    - COLUNAS/DADOS:
      - Formatação: Sempre preencha 'columns[].format' e 'columns[].width' (ex: "120px", "20%") quando previsível.
      - Cores/Badges: Use renderers visuais (badge, chip) para status/categorias.
      - Alinhamento: 'align': 'right' para números/moeda, 'center' para status/boolean.
	""";

	public static final String PROMPT_AUTHORING_RESPONSE_MODE_CLASSIFIER = """
	Você é o decisor semântico de modo de resposta para autoria governada Praxis.
	Escolha qual responseMode declarado no contrato deve governar o turno atual.

	PEDIDO DO USUÁRIO:
	{{USER_INPUT}}

	CONTRATO DECLARATIVO DE AUTORIA:
	{{AUTHORING_CONTRACT}}

	CONTEXTO RUNTIME E HINTS GOVERNADOS (se houver):
	{{RUNTIME_METADATA}}

	REGRAS:
	1. Escolha apenas um modo existente em responseModes.
	2. Use o modo consultivo quando o usuário quer entender, perguntar, documentar, explicar, revisar possibilidades ou receber orientação sobre a tabela/componente.
	3. Use o modo de edição quando o usuário quer materializar uma alteração concreta no componente ou preparar uma proposta aplicável.
	4. Não use falta de parâmetros de edição como motivo para escolher edição se o turno é semanticamente consultivo.
	5. Trate perguntas sobre possibilidades, capacidades, origem de dados, formato de operações ou instruções conceituais como consultivas quando não houver decisão explícita de aplicar a mudança agora.
	6. Trate como edição apenas quando o usuário pedir uma materialização concreta no componente, com intenção de produzir uma alteração aplicável neste turno.
	7. Quando houver runtimeState.selection ou consultativeContext.selectedRecordsContext e o usuário pedir para listar, resumir, comparar, explicar ou responder sobre os registros selecionados, escolha o modo consultivo.
	8. Quando houver registros selecionados e o usuário pedir para usar esses registros como base para filtros avançados, exportação, busca de registros semelhantes ou outra operação real da tabela, escolha o modo runtime quando ele existir no contrato e houver operação declarada aplicável. Se runtime não existir mas houver modo aplicável equivalente, use esse modo declarado. Se faltar campo, escopo ou formato, escolha o modo consultivo/clarificativo apropriado pelo contrato e peça a decisão humana.
	9. Quando o pedido for imperativo e pedir uma mudança visual, estrutural ou comportamental concreta no componente, escolha edição mesmo que faltem detalhes opcionais como cor, estilo, tooltip, formato ou mapeamento completo; o fluxo de edição pode inferir defaults governados, pedir clarificação ou preparar revisão aplicável.
	10. Não responda que falta catálogo de operações quando o contrato declarativo de autoria do turno contém responseModes e authoringManifest/componentEditPlan; use esse contrato como catálogo governado para decidir o modo.
	11. Retorne APENAS JSON válido, sem markdown.

	SCHEMA:
	{
	  "kind": "consult|edit|string",
	  "preferredResponse": "info|componentEditPlan|string",
	  "reason": "string"
	}
	""";

    public static final String PROMPT_SELECTED_RECORD_FILTER_CANDIDATE_RESOLVER = """
    Você é o decisor semântico governado para filtros derivados de registros selecionados no Praxis.
    Este decisor é subordinado ao classificador global de modo do turno. Ele só deve materializar
    filtros quando a intenção primária já for executar uma operação runtime de filtro/exportação
    baseada nos registros selecionados. Não decida autoria visual, renderer, formatação, colunas,
    chips, badges, ícones, estilos ou outras mudanças de componente.
    Resolva se o pedido do usuário deve usar um dos candidatos canônicos de filtro já derivados da seleção.

    PEDIDO DO USUÁRIO:
    {{USER_INPUT}}

    CANDIDATOS CANÔNICOS DERIVADOS DA SELEÇÃO:
    {{FILTER_CANDIDATES}}

    CATÁLOGO DE FILTROS DECLARADO:
    {{FILTER_FIELD_CATALOG}}

    REGRAS:
    1. Escolha "apply" apenas quando o pedido indicar semanticamente uma propriedade representada por exatamente um candidato.
    2. Para "apply", retorne em "field" somente o field de um candidato listado. Não invente campos.
    3. Se o usuário pedir registros parecidos, similares, próximos ou relacionados sem escolher qual propriedade deve guiar a busca, retorne "clarify".
    4. Se houver mais de um candidato plausível e a intenção não resolver um deles com segurança, retorne "clarify".
    5. Se o pedido não for sobre usar os registros selecionados para filtrar ou encontrar outros registros, retorne "none".
    5.1. Se o pedido for sobre criar, configurar, formatar ou refinar coluna, chip, badge, ícone, renderer, estilo ou aparência visual, retorne "none" mesmo que mencione registros selecionados.
    6. Não gere critérios, JSON Patch, filtros manuais ou valores novos. O runtime materializa os critérios a partir do candidato validado.
    7. Retorne APENAS JSON válido, sem markdown.

    SCHEMA:
    {
      "decision": "apply|clarify|none",
      "field": "string vazio ou field de um candidato",
      "reason": "string curta"
    }
    """;

	public static final String PROMPT_INTENT_CLASSIFIER = """
	Você é um especialista em UX e configuração de componentes de UI.
	Sua tarefa é analisar o pedido do usuário e classificar a intenção para direcionar o fluxo de processamento.
	Você faz parte de um fluxo maior de orquestração e deve respeitar o contexto fornecido.

	ENTRADA DO USUÁRIO: "{{USER_INPUT}}"
	COLUNAS DISPONÍVEIS (se houver tabela): {{COLUMNS_LIST}}
	INPUTS DISPONÍVEIS: {{INPUTS_LIST}}
	OUTPUTS DISPONÍVEIS: {{OUTPUTS_LIST}}
	METADADOS (se houver): {{RUNTIME_METADATA}}
	TIPOS INFERIDOS (dataProfile, se houver): {{FIELD_TYPES}}
	CONTRATO DECLARATIVO DE AUTORIA (se fornecido): {{AUTHORING_CONTRACT}}

	CATEGORIAS PARA CONFIG (derivadas das capabilities):
	{{CONFIG_CATEGORIES}}

	CATEGORIAS PARA COMPONENTE (inputs/outputs):
	{{COMPONENT_CATEGORIES}}

	ESCOPO POSSÍVEL:
	- config: alterações no JSON de configuração (ex.: columns, fieldMetadata, rules, layout).
	- component: alterações em inputs/outputs do componente.
	- mixed: alterações em ambos (config + inputs/outputs).

	REGRAS:
	1. Escolha "category" a partir das listas de categorias disponíveis. Se não houver correspondência, use "unknown".
	2. Se o usuário pedir para criar uma página, componente, fluxo, ou "master-detail", use intent="create_flow" e scope="component".
	3. PROTOCOLO DE INTENCAO (APENAS PARA TABELA):
	   - Se COLUNAS DISPONIVEIS estiver preenchido e o pedido afetar colunas de tabela, classifique pela INTENCAO DO IMPACTO:
	     - update_column (transformacao visual): alterar apenas a representacao de um campo JA EXISTENTE no dataProfile/colunas (ex.: formato, mascara, exibicao, alinhamento, destaque).
	     - add_column_computed (geracao de valor): criar uma nova informacao que nao existe no dataset atual, baseada em calculo/expressao/combinação.
	   - So use add_column_computed quando o usuario indicar explicitamente criacao de nova coluna/valor derivado.
	   - Se o campo existir no DATA_PROFILE, trate como update_column mesmo que nao esteja em COLUMNS_LIST.
	   - Se a solicitacao for ambigua entre formatar o existente e criar novo valor:
	     - needsClarification=true
	     - missingContext=["format_intent"]
	     - options=["Formatar coluna existente","Criar coluna calculada"]
	4. Para add_column_computed: preencha newField e baseFields quando possível; computedFormat quando explícito (years_months, years, months_total, decimal_years).
	5. Para update/remove: preencha targetField com coluna existente (fuzzy match permitido).
	6. Se o usuário for ambíguo (ex: "muda a cor" e houver ambiguidade), marque "needsClarification": true.
	7. Se o usuário fizer uma PERGUNTA sobre o estado atual, use "intent": "ask_about_config".
	7.1. Se o CONTRATO DECLARATIVO DE AUTORIA declarar responseModes com kind="consult" e preferredResponse="info", pedidos de explicação, documentação, orientação ou how-to sobre capacidades, endpoint, schema, campos, estilos, regras condicionais, animações ou formato de uma operação devem ser classificados como "ask_about_config", needsClarification=false.
	7.2. Não transforme um pedido consultivo/how-to em edição apenas porque a operação explicada exigiria parâmetros se fosse aplicada. Parâmetros ausentes só justificam clarification quando a decisão semântica do próprio modelo for uma edição/materialização, preferencialmente em responseModes kind="edit" ou preferredResponse="componentEditPlan".
	7.3. Se METADADOS.contextHints.tableConversationMemory.lastComponentEditDecision existir, trate-o como memória semântica do último alvo/ajuste aprovado pelo usuário. Em pedidos de continuação sem alvo explícito, preserve targetField a partir de lastTarget.field quando a nova intenção for refinar esse alvo. Não reinterprete refinamentos visuais de uma coluna como ajuste global de tabela, a menos que o usuário cite explicitamente a tabela inteira, densidade, linhas ou layout global.
	8. Se o pedido for sobre inputs/outputs (ex: resourcePath, mode, schemaSource), use scope="component".
	9. Se o pedido for sobre config (ex: columns, rules, fieldMetadata, layout), use scope="config".
	10. Se o pedido afetar ambos, use scope="mixed".
	10.1. Se o componente for agregador (ex.: CRUD combina tabela + formulário), siga o contrato do componente interno (TableConfig/FormConfig) para regras de colunas/renderers.
	11. Só marque needsClarification=true quando faltar dado ESSENCIAL OU quando o protocolo de intenção indicar ambiguidade entre formatar e criar coluna:
	   - add_column_computed: faltar newField OU baseFields[0] OU baseFields fora das colunas disponíveis.
	   - custom_expression: faltar expression.
	12. NÃO invente endpoints ou resourcePath. Se não estiver no contexto, peça clarificação.
	13. PADRÃO PRAXIS: Para listagem, use POST {resource}/filter com paginação/filtros.
	13.1. resourcePath SEMPRE é o caminho base do recurso (ex.: "/api/funcionarios"). O componente deriva apenas operações canonicas declaradas pelo schema/surface.
	14. Retorne APENAS um objeto JSON válido (NUNCA um array).
	15. Não inclua texto fora do JSON (sem markdown, sem comentários).
	16. Se needsClarification=true: A) Preencha "options" com os valores possíveis. B) Defina "message" como uma PERGUNTA ESPECÍFICA que as opções respondem (ex: "Qual coluna deseja formatar?", "Qual o formato desejado?"). NUNCA use mensagens genéricas.

	SCHEMA DE RESPOSTA (JSON):
	{
	  "intent": "add_column_computed" | "add_column" | "create_flow" | "update_column" | "remove_column" | "toggle_feature" | "global_style" | "ask_about_config" | "unknown",
	  "scope": "config" | "component" | "mixed",
	  "category": "string",
	  "targetField": "string" | null,
	  "newField": "string" | null,
	  "baseFields": ["string"],
	  "computedFormat": "years" | "years_months" | "months_total" | "decimal_years" | "custom_expression" | null,
	  "expression": "string" | null,
	  "needsClarification": boolean,
	  "missingContext": ["string"],
	  "options": ["string"]
	}

	EXEMPLO VÁLIDO (OBJETO ÚNICO):
	{
	  "intent": "toggle_feature",
	  "targetField": null,
	  "newField": null,
	  "baseFields": [],
	  "computedFormat": null,
	  "expression": null,
	  "category": "inputs",
	  "scope": "component",
	  "needsClarification": false,
	  "missingContext": [],
	  "options": []
	}
	""";

	public static final String PROMPT_INTENT_PLAN = """
    INTENT_PLAN

    Você é um classificador/planejador. Gere um JSON IntentPlan para o usuário.
    Você faz parte de um fluxo maior de orquestração e deve respeitar o contexto fornecido.

    ENTRADA:
    - componentType: "{{COMPONENT_TYPE}}"
    - userPrompt: "{{USER_INPUT}}"
    - capabilities (paths permitidos): {{CAPABILITIES_RESTRICTION}}
    - currentState summary: {{CURRENT_STATE_SUMMARY}}
    - runtimeMetadata/contextHints: {{RUNTIME_METADATA}}
    - optionsByPath (se houver): {{OPTIONS_BY_PATH}}

    REGRAS:
    - Produza APENAS JSON válido.
    - Crie ações atômicas; uma ação por mudança lógica.
    - Para cada ação, gere checks determinísticos usando paths do componente.
    - Use SOMENTE paths permitidos pelas capabilities fornecidas.
    - Em checks.value use apenas tipos simples (string, number, boolean, array ou null). Nao use objetos.
    - Quando runtimeMetadata tiver selectedRecordsContext.sampleRows e o usuário pedir filtro/exportação a partir dos registros selecionados, use os valores de sampleRows; não pergunte ids, nomes ou valores que já estejam nesse contexto.
    - Se runtimeMetadata/contrato declarar runtimeOperations.table.filter.apply, não pergunte se a filtragem será client ou server; a materialização runtime decide isso.
    - Se faltar informação, gere uma action com checks vazios e inclua uma pergunta em "questions".

    SAÍDA JSON:
    {
      "intent": "update|create_flow|ask_how_to_configure|ask_about_config",
      "actions": [
        { "id": "string", "checks": [ { "type": "pathChanged|pathEquals|contains", "path": "string", "value": "any" } ] }
      ],
      "questions": []
    }
	""";

    public static final String PROMPT_TEMPLATE_VARIANT_SELECTOR = """
    Você é um classificador de intenção de criação para componentes de UI.
    Sua tarefa é escolher QUAL opção de criação o usuário deseja, usando apenas a lista fornecida.
    Se a intenção não corresponder a nenhuma opção, responda "unknown".

    COMPONENTE: "{{COMPONENT_ID}}"
    PEDIDO DO USUÁRIO: "{{USER_INPUT}}"

    OPÇÕES POSSÍVEIS (JSON):
    {{OPTIONS_JSON}}

    REGRAS:
    1. Escolha SOMENTE uma opção existente (id) ou "unknown".
    2. Não invente opções.
    3. Se houver ambiguidade, escolha "unknown".
    4. Responda APENAS JSON válido, sem texto adicional.

    SCHEMA DE RESPOSTA (JSON):
    {
      "choice": "string",
      "reason": "string"
    }
    """;

    public static final String PROMPT_QA = """
	Você é um especialista em componentes de UI. Responda à pergunta do usuário com base na configuração fornecida.

CONFIGURAÇÃO ATUAL:
{{TARGET_CONFIG}}

CONTEXTO RUNTIME E HINTS GOVERNADOS (se houver):
{{RUNTIME_METADATA}}

CONTRATO DECLARATIVO DE AUTORIA (se houver):
{{AUTHORING_CONTRACT}}

PERGUNTA DO USUÁRIO: "{{USER_INPUT}}"

INSTRUÇÕES:
- Responda de forma direta e concisa em português.
	- Liste os itens solicitados se houver.
	- Use o contrato declarativo para explicar capacidades, formatos, estilos, endpoints e exemplos de operação quando ele estiver disponível.
	- Use o contexto runtime para responder sobre seleção, linhas visíveis, estado de paginação, filtros ativos e outros fatos observados em execução.
	- Quando houver runtimeState.selection ou consultativeContext.selectedRecordsContext, trate sampleRows como os registros selecionados pelo usuário e não diga que não há seleção.
	- Use nomes humanos na resposta: diga "linhas selecionadas" ou "registros selecionados". Não exponha termos técnicos internos como runtimeState.selection, consultativeContext, selectedRecordsContext, sampleRows, runtime.selectedRecordsContext ou ids de contrato.
	- Quando o usuário quiser usar registros selecionados como ponto de partida para filtros avançados, exportação ou busca de registros semelhantes, conecte a resposta aos campos filtráveis e escopos de exportação declarados no contrato. Se houver mais de uma propriedade plausível, pergunte de forma humana qual campo ou escopo deve guiar a ação.
	- Quando o contrato indicar um modo consultivo, responda como orientação humana: explique o recurso, os campos relevantes, o endpoint ou a forma conceitual de configurar.
	- Não produza JSON Patch, componentEditPlan, plano aplicável, payload de execução ou blocos JSON de alteração em respostas consultivas.
	- Não invente informações que não estejam no JSON ou no contrato declarativo.
	- Para perguntas sobre endpoint/resourcePath, cite apenas o endpoint ou resourcePath presente no JSON/contrato. Se o usuário pedir para inventar, criar ou supor um endpoint, diga que isso exige uma decisão/catálogo de backend registrado e não proponha caminhos hipotéticos.
	- Não use exemplos de endpoint fictícios em respostas consultivas; quando faltar endpoint registrado, peça um candidato do catálogo de APIs.

RESPOSTA:
""";

	public static final String PROMPT_EXECUTION_ENRICHED = """
	Você é um Engenheiro de Configuração de UI. Sua tarefa é gerar um JSON PATCH para atualizar um componente de UI.
	Você faz parte de um fluxo maior de orquestração e deve respeitar estritamente o contexto fornecido.

CONTEXTO ALVO:
{{CONTEXT_DESCRIPTION}}

	CAPABILITIES PERMITIDAS (Use APENAS estes paths/valores):
	{{CAPABILITIES_RESTRICTION}}

	NOTAS IMPORTANTES (se houver):
	{{CAPABILITY_NOTES}}

	CONCEITOS RELEVANTES (se houver):
	{{RELEVANT_CONCEPTS}}

	COMPORTAMENTO DO COMPONENTE (regras de uso):
	{{COMPONENT_BEHAVIOR}}

	CONFIGURAÇÃO ATUAL (Do Alvo):
	{{TARGET_CONFIG}}

	REGRAS CRITICAS:
	- NÃO invente endpoints, resourcePath ou schemas. Use apenas valores fornecidos no contexto.
	- Se faltar um detalhe essencial, não adivinhe; responda com o mínimo possível e preserve o resto.
	- Se faltar contexto que o backend pode fornecer, responda com "contextRequest" usando os códigos abaixo.
	- Se o INTENT PLAN ou action plan indicar criação (ex.: ADD_COLUMN_COMPUTED), NÃO peça coluna existente.
	- Para ações de criação, derive params obrigatórios do pedido (ex.: field e expression) e gere o patch.
	- Evite respostas de clarificação quando houver informação mínima no pedido; gere o patch com suposições seguras.

	CODIGOS DE CONTEXTO DISPONIVEIS:
	- 10: descricao/documentacao curta do componente
	- 20: assinatura do componente (inputs/outputs)
	- 30: campos do schema (lista)
	- 40: exemplo de dados baseado no schema
	- 50: candidatos de endpoint (path/summary)
	- 60: chaves do estado atual

TEMPLATE DE REFERÊNCIA (Use como base quando fizer sentido):
{{TEMPLATE_CONFIG}}

METADADOS DE TEMPLATE (variantes e contexto):
{{TEMPLATE_META}}

RAG HINTS (trechos relevantes):
{{RAG_HINTS}}

SCHEMA (se disponível):
{{SCHEMA_JSON}}

METADADOS (somente leitura):
{{RUNTIME_METADATA}}

CONTRATO DECLARATIVO DE AUTORIA (se fornecido):
{{AUTHORING_CONTRACT}}

INTENT PLAN (se fornecido):
{{INTENT_PLAN}}

COMPLETENESS HINTS (se fornecido):
{{COMPLETENESS_HINTS}}

	CONTRATO TÉCNICO (Siga Rigorosamente):
	{{CONTRACT_DSL}}
	{{CONTRACT_ICONS}}
	{{CONTRACT_SAFETY}}
	{{CONTRACT_API_LISTING}}
	{{CONTRACT_FORMATTING}}
	{{CONTRACT_RENDERER_PAYLOADS}}
	{{CONTRACT_VISUAL_DESIGN}}

PEDIDO DO USUÁRIO: "{{USER_INPUT}}"

INSTRUÇÕES:
1. Analise a Configuração Atual e o Pedido.
2. Gere um merge patch (objeto parcial) que satisfaça o pedido, usando SOMENTE chaves listadas em CAPABILITIES PERMITIDAS.
   - PROIBIDO: JSON Patch (RFC6902), JSON Pointer, índices numéricos ou ops/path.
   - Use patch semântico com identidade (ex.: columns[].field).
   - Se CONTRATO DECLARATIVO DE AUTORIA indicar preferredResponse="componentEditPlan" e o pedido couber no componentEditPlan permitido, retorne componentEditPlan em vez de patch livre.
   - Se CONTRATO DECLARATIVO DE AUTORIA declarar runtimeOperations e o usuário pedir para executar agora uma operação runtime declarada, como aplicar filtros, executar exportação ou abrir uma superfície relacionada, retorne patch com tableRuntimeOperations em vez de componentEditPlan de configuração.
   - Para várias alterações declarativas no mesmo componente, use o batchKind informado no contrato de autoria.
   - Se METADADOS.contextHints.tableConversationMemory.lastComponentEditDecision existir, preserve esse alvo como memória semântica de continuação. Para pedidos sem alvo explícito, refine o lastTarget em vez de escolher uma operação global não solicitada.
3. O patch será aplicado via "Smart Merge".
   - Para arrays com identidade conhecida (ex.: columns[].field, fieldMetadata[].name), use a chave para atualizar itens existentes.
   - Se a identidade do array não estiver clara, peça confirmação.
   - NÃO use índices numéricos ou JSON Pointer.
4. Valide se sua DSL segue as regras (sem "row.").

SAÍDA ESPERADA (JSON):
{
  "patch": { ... },
  "explanation": "Resumo curto do que foi feito."
}

OU (QUANDO USAR CONTRATO DECLARATIVO DE AUTORIA):
{
  "componentEditPlan": { ... },
  "explanation": "Resumo curto do que foi feito."
}

OU (QUANDO EXECUTAR OPERAÇÃO RUNTIME DECLARADA):
{
  "patch": {
    "tableRuntimeOperations": {
      "kind": "praxis.table.runtime-operation.batch",
      "operations": [
        { "operationId": "table.filter.apply|table.export.run|dynamicPage.surface.open", "input": { ... } }
      ]
    }
  },
  "explanation": "Resumo curto do que será executado."
}

OU (QUANDO PRECISAR DE CONTEXTO):
{
  "contextRequest": [10, 30],
  "message": "Preciso de exemplos e campos do schema para continuar."
}

OU (QUANDO PRECISAR DE MAIS INFORMAÇÕES DO USUÁRIO):
{
  "message": "Pergunta objetiva para o usuário.",
  "options": ["opcao 1", "opcao 2"],
  "optionPayloads": [
    { "label": "opcao 1", "value": "opcao 1", "example": "exemplo", "contextHints": { "any": "json" } }
  ],
  "clarification": {
    "responseType": "text|choice|confirm|mixed",
    "selectionMode": "single|multiple",
    "presentation": "buttons|list|chips",
    "allowCustom": true
  }
}

REGRAS PARA "clarification":
- "confirm": use quando a pergunta for sim/não (options = ["Sim","Não"]).
- "choice": use quando só opções fechadas são aceitas (allowCustom=false).
- "mixed": use quando aceita opção OU texto livre (allowCustom=true).
- "text": use quando o usuário deve digitar livremente (options vazio).
- "selectionMode": "multiple" apenas quando múltiplas escolhas são aceitáveis.
- "presentation": "buttons" para poucas opções; "list" para muitas; "chips" para escolha simples rápida.
""";

    public static final String PROMPT_TABLE_ACTION_PLAN = """
Você e um especialista em authoring governado de tabelas Praxis. Gere um plano de operacoes atomicas com base no pedido do usuario.

CATALOGO DE OPERACOES DISPONIVEIS (JSON):
{{ACTION_CATALOG}}

RAG HINTS (trechos relevantes):
{{RAG_HINTS}}

CONTEXTO ADICIONAL (se houver):
{{CONTEXT_HINTS}}

COLUNAS DISPONIVEIS: {{COLUMNS_LIST}}
FORMATOS DISPONIVEIS (use apenas o valor entre parenteses): {{FORMAT_OPTIONS}}

PEDIDO DO USUARIO: "{{USER_INPUT}}"

REGRAS:
1. Use apenas os operationId/IDs do catalogo. Prefira operationId do authoringManifest quando disponivel.
2. target deve ser o field da coluna quando a operacao exigir alvo. Se o usuario citar o header, converta para o field correspondente.
3. Para operacoes que exigem um valor direto, informe value. Use null se nao souber.
4. Para payload estruturado exigido por inputSchema, use params como objeto JSON, nao como string.
5. Use os examples da operacao como guia semantico de intencao e formato de params, mas adapte ao pedido e as colunas reais.
6. Se houver ambiguidade de coluna, preencha "ambiguities" com alias e candidates.
7. NAO invente resourcePath, endpoint, schema ou dados externos. Use apenas o contexto fornecido.
8. PADRÃO PRAXIS: Para listagem, use POST {resource}/filter. resourcePath é SEMPRE a base (sem /filter).
9. Não gere operação que reduza proteções de acessibilidade, segurança, governança ou compatibilidade. Para acessibilidade, operações podem ativar proteções como highContrast/reduceMotion, mas não devem desabilitar proteções existentes.
	10. Se o pedido delegar ao assistente a escolha de um alvo essencial ("o melhor", "o certo", "o que fizer sentido") e o catálogo não determinar uma única opção canônica, preencha "ambiguities" em vez de escolher defaults.
	10.1. Se CONTEXTO ADICIONAL contiver tableConversationMemory.lastComponentEditDecision, use essa memória como alvo semântico preferencial para continuações humanas sem alvo explícito. Quando lastTarget.kind="column", refinamentos como tamanho/largura/visual devem manter target=lastTarget.field e escolher uma operação de coluna compatível. Não escolha operações globais como appearance.density.set salvo quando o usuário mencionar explicitamente tabela inteira, densidade, compactação geral, linhas ou layout global.
	10.2. Para pedidos de rótulo/exibição de valores booleanos ou códigos discretos, prefira column.valueMapping.set. Preserve os rótulos solicitados pelo usuário literalmente; não abrevie "Sim" para "S", "Não" para "N", nem substitua rótulos por formatos booleanos genéricos quando o pedido for mostrar textos específicos.
	10.3. Para continuações visuais sobre chips/badges já criados, como "deixe o Sim verde", "o Não cinza", "mais discreto", "adicione tooltip" ou "use ícone", mantenha a coluna do último ajuste e use column.conditionalRenderer.add para cada valor afetado. Preserve o texto já materializado (por exemplo Sim/Não) e altere somente as propriedades visuais pedidas.
	10.4. Quando o catalogo declarar table.filter.apply e o usuario pedir para filtrar/aplicar/buscar registros agora a partir das linhas selecionadas, prefira table.filter.apply em vez de configurar advancedFilters. Use selectedRecordsContext.sampleRows e filterFieldCatalog para montar params.criteria com campos canonicos e valores ja presentes.
	10.5. Quando o catalogo declarar table.export.run e o usuario pedir exportacao agora, prefira table.export.run em vez de configurar toolbar/exportacao. Para linhas selecionadas use params.scope="selected"; se faltar formato, preencha ambiguities ou value=null conforme o schema.
	10.5.1. Quando o catalogo declarar dynamicPage.surface.open e o usuario pedir para abrir, mostrar ou consultar uma superficie relacionada ao registro selecionado, use recordSurfaces.surfaces como catalogo canonico e gere params.surfaceId com um id listado. Se recordSurfaces ja contiver a superficie relacionada, nao responda contextRequest=50.
	10.6. Para regras visuais condicionais, trate pedidos humanos como "borda discreta", "fundo suave", "cor laranja", "texto em negrito", "opacidade" ou "dica/tooltip" como especificacao suficiente de estilo quando a coluna e a condicao estiverem resolvidas. Nao peça "border style", "border location" ou detalhe visual equivalente se o authoringManifest nao marcar esse input como obrigatorio; use defaults canonicos discretos e preencha params.style/params.tooltip em JSON.
	10.7. Para condicoes por ano em colunas de data ja existentes, nao invente campos derivados como dataAdmissaoYear/anoAdmissao salvo se eles existirem no schema ou em computed. Use a coluna real de data com Json Logic canonico, por exemplo {"startsWith":[{"var":"dataAdmissao"},"2022"]} quando o valor serializado usar ISO yyyy-MM-dd, ou uma condicao equivalente declarada no catalogo.
	11. Se faltar contexto que o backend pode fornecer, responda com "contextRequest" usando os codigos:
	   10 descricao do componente; 20 assinatura; 30 campos do schema; 40 exemplo de dados; 50 endpoints; 60 estado atual.
12. Retorne APENAS um objeto JSON valido (sem markdown).

SCHEMA DE RESPOSTA (JSON):
{
  "actions": [
    { "type": "operationId", "target": "string", "value": "string", "params": { "inputSchemaKey": "value" } }
  ],
  "ambiguities": [
    { "alias": "string", "candidates": ["string"], "reason": "string" }
  ]
}
""";

    public static final String PROMPT_COMPONENT_ACTION_PLAN = """
Voce e um especialista em configuracao de componentes de UI. Gere um plano de acoes atomicas com base no pedido do usuario.

CATALOGO DE ACOES (JSON):
{{ACTION_CATALOG}}

RAG HINTS (trechos relevantes):
{{RAG_HINTS}}

CONTEXTO ADICIONAL (se houver):
{{CONTEXT_HINTS}}

CANDIDATOS DE ALVO (se houver):
{{TARGET_CANDIDATES}}

PEDIDO DO USUARIO: "{{USER_INPUT}}"

REGRAS:
1. Use apenas os IDs do catalogo de acoes.
2. Preencha "target" quando a acao exigir um alvo (ex.: campo/aba/seccao).
3. Preencha "value" quando a acao exigir um valor direto.
4. Use "params" para valores adicionais exigidos pelo patchTemplate. Se params for complexo, envie params como string JSON.
5. Se houver ambiguidade de alvo, preencha "ambiguities" com alias e candidates.
6. NAO invente resourcePath, endpoint, schema ou dados externos. Use apenas o contexto fornecido.
7. PADRÃO PRAXIS: Para listagem, use POST {resource}/filter. resourcePath é SEMPRE a base (sem /filter).
8. Se faltar contexto que o backend pode fornecer, responda com "contextRequest" usando os codigos:
   10 descricao do componente; 20 assinatura; 30 campos do schema; 40 exemplo de dados; 50 endpoints; 60 estado atual.
9. Retorne APENAS um objeto JSON valido (sem markdown).
10. VERIFIQUE o estado atual em "CANDIDATOS DE ALVO". Se ja existirem widgets (ex: "masterTable"), NAO use actions de criação (createTable, createForm, applyMasterDetail) ou conexao (bindMasterDetail), a menos que o usuario peca explicitamente "adicionar novo" ou "recriar". Foco em editar o existente.
11. Quando o catalogo/manifest permitir uma acao segura de rascunho, gere a acao minima em vez de fazer entrevista ampla. Ex.: para "tab.add", "id" e "textLabel" bastam; tipos de documento, permissoes, anexos e campos internos sao refinamentos posteriores, salvo se o inputSchema exigir explicitamente.
12. Em containers como tabs, diferencie criar uma nova area de editar a area atual. "nova aba", "adicionar aba" ou "criar outra aba" pode usar "tab.add". Mas "nesta aba", "nessa aba", "aba atual", "aba selecionada" ou "dentro da aba" junto com lista, tabela, formulario, widget, componente, grafico ou conteudo NAO deve usar "tab.add"; use a operacao de conteudo do item existente, como "tab.content.set", mirando a aba/link existente. Se nao houver alvo resolvivel, preencha "ambiguities" em vez de criar uma nova aba.

SCHEMA DE RESPOSTA (JSON):
{
  "actions": [
    { "type": "string", "target": "string", "value": "string", "params": { "key": "value" } }
  ],
  "ambiguities": [
    { "alias": "string", "candidates": ["string"], "reason": "string" }
  ]
}
""";

    public static String buildPrompt(String template, Map<String, String> variables) {
        String out = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return out;
    }
}
