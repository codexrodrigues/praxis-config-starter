# Runbook do Orquestrador

Este arquivo guia o chat principal que coordena a execucao das fases.

## Papel deste chat

Este chat nao deve tentar implementar todas as fases em sequencia longa. Ele deve:

- manter o plano coerente;
- revisar entregas de chats delegados;
- decidir se uma fase esta pronta;
- identificar drift entre fases;
- impedir duplicacao de pipelines;
- atualizar o estado do programa quando uma fase fechar.

## Fluxo padrao

1. Escolher uma fase.
2. Abrir o prompt correspondente em `prompts/`.
3. Delegar a fase para um novo chat.
4. Receber o resumo final do chat delegado.
5. Revisar contra `REVIEW-CHECKLIST.md`.
6. Pedir ajustes ou aceitar a fase.
7. Atualizar `PLAN.md` e o arquivo da fase quando necessario.
8. Somente depois liberar a proxima fase.

## Regras de revisao

Antes de aceitar qualquer fase, confirmar:

- a fonte canonica foi respeitada;
- nao foi criado RAG paralelo;
- nao foi criada nova tabela sem necessidade;
- `praxis-ui-angular` nao virou fonte primaria de decisao;
- `@praxisui/ai` permaneceu cliente/UX;
- `vector_store` interno continuou sendo o indice canonico;
- artefatos derivados foram considerados;
- validacao local foi proporcional ao escopo;
- o handoff foi atualizado.

## Quando bloquear uma fase

Bloquear se o chat delegado:

- criar novo pipeline concorrente ao `tools/ai-registry`;
- propor provider externo como fonte de verdade;
- alterar contrato publico sem mapa de impacto;
- remover contrato legado ainda consumido sem migracao;
- tratar RAG como regra executavel;
- aplicar patch local quando a correcao correta for canonica;
- deixar arquivo de fase sem estado/handoff.

## Formato de revisao esperado

Ao revisar uma fase, responder com:

```md
Status da revisao: aprovado | ajustes obrigatorios | bloqueado

Achados:
- ...

Riscos residuais:
- ...

Validacao conferida:
- ...

Proxima acao:
- ...
```

