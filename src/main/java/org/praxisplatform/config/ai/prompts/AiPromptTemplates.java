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
    - Moeda: Use "BRL|symbol|2" (currencyCode|display|decimals) ou "USD|code|0". Formato aceito é string.
    - Número: Use padrões do DecimalPipe, ex.: "1.2-2" (mínimo 2, máximo 2 decimais).
    - Percentual: Use padrões do PercentPipe, ex.: "1.0-0|x100" para converter 0.1 -> 10%.
    - Boolean: Use { "type": "boolean" } e, opcionalmente, format strings como "true-false", "yes-no", "active-inactive".
    """;

    public static final String CONTRACT_SAFETY = """
    - PRESERVAÇÃO: Ao atualizar listas/coleções, mantenha os itens existentes que não foram alterados.
    - DESTRUIÇÃO: Não remova elementos (colunas, campos, regras, ações) a menos que explicitamente solicitado.
    """;

    public static final String CONTRACT_RENDERER_PAYLOADS = """
    - CONDITIONAL RENDERERS: 'columns[].conditionalRenderers[].renderer' deve seguir o mesmo schema dos renderers principais.
      - CORRETO: { type: 'badge', badge: { text: 'Ativo', color: 'success' } }
      - ERRADO (Flatten): { type: 'badge', text: 'Ativo', color: 'success' }
      - ERRADO (Vazio): { type: 'badge' }
    - BADGE: Exige 'badge.textField' (dinâmico) ou 'badge.text' (fixo).
      - Para booleanos (true/false): Sugira "valueMapping" ({ "true": "Ativo", "false": "Inativo" }) e use "conditionalStyles" para cores.
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

	public static final String PROMPT_INTENT_CLASSIFIER = """
	Você é um especialista em UX e configuração de componentes de UI.
	Sua tarefa é analisar o pedido do usuário e classificar a intenção para direcionar o fluxo de processamento.
	Você faz parte de um fluxo maior de orquestração e deve respeitar o contexto fornecido.

	ENTRADA DO USUÁRIO: "{{USER_INPUT}}"
	COLUNAS DISPONÍVEIS (se houver tabela): {{COLUMNS_LIST}}
	INPUTS DISPONÍVEIS: {{INPUTS_LIST}}
	OUTPUTS DISPONÍVEIS: {{OUTPUTS_LIST}}
	METADADOS (se houver): {{RUNTIME_METADATA}}

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
	2. Identifique o "targetField" (nome técnico) se a intenção for específica de uma coluna. Use fuzzy match se necessário.
	3. Se o usuário for ambíguo (ex: "muda a cor" e houver ambiguidade), marque "needsClarification": true.
	4. Se o usuário fizer uma PERGUNTA sobre o estado atual, use "intent": "ask_about_config".
	5. Se o pedido for sobre inputs/outputs (ex: resourcePath, mode, schemaSource), use scope="component".
	6. Se o pedido for sobre config (ex: columns, rules, fieldMetadata, layout), use scope="config".
	7. Se o pedido afetar ambos, use scope="mixed".
	8. Se faltarem detalhes essenciais, marque "needsClarification": true e preencha "missingContext" (ex: "column", "endpoint", "resourcePath"). Use "options" quando possível.
	9. NÃO invente endpoints ou resourcePath. Se não estiver no contexto, peça clarificação.
	10. Retorne APENAS um objeto JSON válido (NUNCA um array).
	11. Não inclua texto fora do JSON (sem markdown, sem comentários).

	SCHEMA DE RESPOSTA (JSON):
	{
	  "intent": "update_column_rules" | "toggle_feature" | "global_style" | "ask_about_config" | "unknown",
	  "targetField": "string" | null,
	  "category": "string",
	  "scope": "config" | "component" | "mixed",
	  "needsClarification": boolean,
	  "missingContext": ["string"],
	  "options": ["string"]
	}

	EXEMPLO VÁLIDO (OBJETO ÚNICO):
	{
	  "intent": "toggle_feature",
	  "targetField": null,
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
    - optionsByPath (se houver): {{OPTIONS_BY_PATH}}

    REGRAS:
    - Produza APENAS JSON válido.
    - Crie ações atômicas; uma ação por mudança lógica.
    - Para cada ação, gere checks determinísticos usando paths do componente.
    - Use SOMENTE paths permitidos pelas capabilities fornecidas.
    - Em checks.value use apenas tipos simples (string, number, boolean, array ou null). Nao use objetos.
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

    public static final String PROMPT_QA = """
	Você é um especialista em componentes de UI. Responda à pergunta do usuário com base na configuração fornecida.

CONFIGURAÇÃO ATUAL:
{{TARGET_CONFIG}}

PERGUNTA DO USUÁRIO: "{{USER_INPUT}}"

INSTRUÇÕES:
- Responda de forma direta e concisa em português.
	- Liste os itens solicitados se houver.
	- Não invente informações que não estejam no JSON.

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

	CONFIGURAÇÃO ATUAL (Do Alvo):
	{{TARGET_CONFIG}}

	REGRAS CRITICAS:
	- NÃO invente endpoints, resourcePath ou schemas. Use apenas valores fornecidos no contexto.
	- Se faltar um detalhe essencial, não adivinhe; responda com o mínimo possível e preserve o resto.
	- Se faltar contexto que o backend pode fornecer, responda com "contextRequest" usando os códigos abaixo.

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

INTENT PLAN (se fornecido):
{{INTENT_PLAN}}

COMPLETENESS HINTS (se fornecido):
{{COMPLETENESS_HINTS}}

	CONTRATO TÉCNICO (Siga Rigorosamente):
	{{CONTRACT_DSL}}
	{{CONTRACT_ICONS}}
	{{CONTRACT_SAFETY}}
	{{CONTRACT_FORMATTING}}
	{{CONTRACT_RENDERER_PAYLOADS}}

PEDIDO DO USUÁRIO: "{{USER_INPUT}}"

INSTRUÇÕES:
1. Analise a Configuração Atual e o Pedido.
2. Gere um merge patch (objeto parcial) que satisfaça o pedido, usando SOMENTE chaves listadas em CAPABILITIES PERMITIDAS.
   - PROIBIDO: JSON Patch (RFC6902), JSON Pointer, índices numéricos ou ops/path.
   - Use patch semântico com identidade (ex.: columns[].field).
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

OU (QUANDO PRECISAR DE CONTEXTO):
{
  "contextRequest": [10, 30],
  "message": "Preciso de exemplos e campos do schema para continuar."
}
""";

    public static final String PROMPT_TABLE_ACTION_PLAN = """
Você e um especialista em configuracao de tabelas. Gere um plano de acoes atomicas com base no pedido do usuario.

CATALOGO DE ACOES (JSON):
{{ACTION_CATALOG}}

RAG HINTS (trechos relevantes):
{{RAG_HINTS}}

CONTEXTO ADICIONAL (se houver):
{{CONTEXT_HINTS}}

COLUNAS DISPONIVEIS: {{COLUMNS_LIST}}
FORMATOS DISPONIVEIS (use apenas o valor entre parenteses): {{FORMAT_OPTIONS}}

PEDIDO DO USUARIO: "{{USER_INPUT}}"

REGRAS:
1. Use apenas os IDs do catalogo de acoes.
2. target deve ser o field da coluna. Se o usuario citar o header, converta para o field correspondente.
3. Para acoes que exigem um valor direto (ex.: SET_FORMAT, RENAME_COLUMN), informe value. Use null se nao souber.
4. Para acoes com detalhes adicionais (ex.: COLUMN.RENDERER.BUTTON.STYLE.SET), use params (ex.: params.variant, params.color). params eh um objeto com as chaves usadas no patchTemplate do actionCatalog.
5. Se houver ambiguidade de coluna, preencha "ambiguities" com alias e candidates.
6. NAO invente resourcePath, endpoint, schema ou dados externos. Use apenas o contexto fornecido.
7. Se faltar contexto que o backend pode fornecer, responda com "contextRequest" usando os codigos:
   10 descricao do componente; 20 assinatura; 30 campos do schema; 40 exemplo de dados; 50 endpoints; 60 estado atual.
8. Retorne APENAS um objeto JSON valido (sem markdown).

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
4. Use "params" para valores adicionais exigidos pelo patchTemplate (ex.: params.color).
5. Se houver ambiguidade de alvo, preencha "ambiguities" com alias e candidates.
6. NAO invente resourcePath, endpoint, schema ou dados externos. Use apenas o contexto fornecido.
7. Se faltar contexto que o backend pode fornecer, responda com "contextRequest" usando os codigos:
   10 descricao do componente; 20 assinatura; 30 campos do schema; 40 exemplo de dados; 50 endpoints; 60 estado atual.
8. Retorne APENAS um objeto JSON valido (sem markdown).

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
