# Contexto nativo de release congelado

Config reconstrói o workspace do release exato a partir de sourceDraftId; não resolve pelo head atual. UiLayoutFrozenWorkspaceResolver compartilha codec e selection verifier com a recuperação histórica. Confere identidade/escopo do release e draft RELEASED, vínculo source release, freeze command, targets/ordem, refs de revisão/assignment, hashes dos documentos/patch e selectors persistidos.

Publish/rollback usam a sua invocação e operação atuais, revalidadas na transação Config. O resolver interno não exige READ_HISTORICAL_EVIDENCE nem chama o endpoint histórico; o serviço histórico mantém seus gates de leitura e conteúdo. Resolver não concede acesso público ou execução. Ausência/inconsistência impede mover head.

UiLayoutLifecycleFrozenContribution agora exige NativeAuthoring como quarto componente: release/source draft/freeze refs, authoring e patch descriptors, baseline source/hash/document e candidate hash/document. JsonNode é copiado na entrada/saída. Esta é alteração incompatível do construtor Java, sem overload de compatibilidade na beta. O contrato HTTP não foi ampliado. Implementadores devem consumir o contexto resolvido pelo Config e não consultar repositories ou inputs do caller para fabricar documentos.

O default validateFrozenRelease chama validação de target, authoring baseline, revisão original/candidato/patch, patch e assignment. Um override continua responsável por executar as mesmas garantias e não pode presumir que checks de refs/hashes substituam validação owner ou admissão de conteúdo/execução. O Config verifica composição atual exata e reprodução compacta Table antes de entregar contribuições ao SPI.

Os 97 testes focais de fonte passaram, incluindo 11 novos casos com transação Spring e repositories simulados. Testes negativos cobrem falhas anteriores ao core.publish/core.rollback; eles não são rollback PostgreSQL real. Recuperação histórica continua aceitando targets removidos somente sob seu access gate; head mutation exige composição atual. As permissões e beforeCommit continuam locais, sem fencing IAM.

A fonte/candidata privada rc.163 inclui esta alteração; rc.162 congelada não a inclui. A publicação Table lab histórica materializada continua incompatível com revisão compacta (G1); não foi modificada. Provider oficial, metadata/source admission e DEC-01–04 continuam gates próprios. Sem execução de owners admitida, banco/browser ou publicação pública, não há aceite integrado.
