# Vigência de autoridade nas escritas do lifecycle

O Config Starter verifica a invocation autenticada completa e a admissão da operação na entrada da transação, depois do trabalho e em beforeCommit. A comparação inclui ator, tenant, unidade administrativa, ambiente, versão do contexto e composição registrada. O fluxo cobre criação/replay de draft, revisão, assignment, freeze, submit, approve, publish, withdraw e rollback.

Mudança de contexto gera CONTEXT_STALE; revogação preserva o código DENIED; falha técnica do provider/admission gera SOURCE_UNAVAILABLE com mensagem sanitizada. Headers, DTOs, ETag e CAS existentes permanecem aplicáveis. A revalidação não autoriza a operação por si: os providers canônicos do host continuam necessários.

A execução exige uma transação Config real, gravável, com synchronization Spring. Sem isso, a operação falha antes do trabalho. Exceções na entrada ou após o trabalho marcam rollback. A verificação em beforeCommit lança a exceção para que o Spring reverta a transação efetiva. Não usar o TransactionStatus da chamada interna nesse callback: ele pode já estar concluído quando o método participa de uma transação externa.

Quando o chamador fornece uma transação externa, o método pode retornar antes do commit. Retorno de recibo nesse escopo não prova persistência: beforeCommit pode negar a operação e reverter a transação inteira. Se o chamador capturar uma falha após o trabalho e restaurar a autoridade, rollbackOnly deve impedir a persistência do trabalho rejeitado. Não enviar sucesso HTTP antes de concluir a transação externa.

## Limites corporativos

Os providers devem consultar o contexto e a autorização vigentes, sem cache inadequado. No Quickstart, autorização operacional dentro da transação Config usa o mesmo gerenciador Config e suspensão/restituição já canônica; não juntar unidades de persistência nem simular XA. A exceção em beforeCommit interrompe o commit Spring. Ela não cria fencing/lease distribuído, não garante atomicidade entre IAM e Config Store e não elimina a janela entre a consulta de autoridade e o commit físico. Se a política exigir linearização com a revogação, esse requisito precisa de um contrato canônico explícito de autoridade/fencing e prova operacional própria.

Testes com transações Spring reais e persistência simulada comprovam os pontos de admissão, o rollback solicitado e ausência de commit no modelo. Não substituem PostgreSQL/JPA real, rollback de triggers, isolamento entre tenants, operação do provider corporativo ou aceite integrado.

## Consumo e artefatos

A correção deve chegar ao host numa nova candidata privada identificada e verificada. A candidata congelada 0.1.0-rc.161 não contém esta alteração e não pode ser sobrescrita. Antes da ativação, validar o host contra o JAR exato da nova candidata, incluindo suspensão/restituição e revogação. Migrações V64–V68 permanecem imutáveis; o validate nativo de 66 migrações já aprovado não precisa ser repetido por esta mudança Java.

Não há endpoint, header, payload, enum ou formato JSON novo. Não é rebase B0/C0/B1 nem editor de versões. Registry, exemplos HTTP e manifests não precisam de regeneração estrutural para esta alteração; o guia operacional e a skill de persistência precisam refletir a nova garantia e seus limites.
