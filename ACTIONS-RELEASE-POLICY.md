# Actions no fechamento de versões


O padrão durante desenvolvimento é zero execuções remotas. Valide localmente o escopo alterado; commits, PRs, documentação interna e conclusão de tarefas não são motivos para iniciar Actions. Use os workflows manuais somente no fechamento autorizado de uma versão/publicação ou na prova necessária do host já implantado. Não use `[skip ci]` como mecanismo principal nem desabilite checks/proteções para economizar.

Antes de push, tag ou dispatch, confira os gatilhos reais de `.github/workflows/`. Tags de release publicam artefatos: não criá-las para testar a automação. Diagnostique localmente antes de repetir um job; conserve a evidência da revisão e dos artefatos usados. Monitores operacionais explicitamente mantidos são independentes do CI de commits. Consulte [ACTIONS-RELEASE-POLICY.md](ACTIONS-RELEASE-POLICY.md) para os pontos de entrada e recuperação.

## Fluxo deste repositório

`release.yml` é iniciado por dispatch em main com `create_tag=true`. Persiste POM/tag atomicamente e a tag `v*` publica após conferir ancestralidade e versão. Uma única sessão Maven executa `clean verify` com `release,ci-smoke-unit` (unit/smoke, sem integration/external/e2e), assina e só depois publica. Não há build automático de main ou PR.

Os gates manuais de migração e authoring continuam disponíveis quando necessários ao contrato do corte. Prefira os equivalentes locais; gates pagos exigem a aprovação já definida no environment e não devem ser repetidos por falhas de exportação de evidência. O monitor operacional semanal permanece separado. Consulte RELEASING.md.
