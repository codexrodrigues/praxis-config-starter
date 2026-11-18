# Zero-Boilerplate Governed Rules Host Onboarding & Contextual Decision Inspection

## Objetivo

Este guia documenta a arquitetura canônica da **Praxis Platform** para adoção de regras de negócio determinísticas em projetos host (Java/Spring + Angular) com **zero boilerplate de infraestrutura**:

1. **Registro Declarativo de Famílias (`GovernedRuleFamilyDescriptor`):** um único `@Bean` por família (tipada com `RuleFactContract` ou declarativa via `ofSingleRuleSet(...)`).
2. **Runtime Governado Thread-Safe (`GovernedHostRuleBundleRuntime`):** resolução atômica de *heads* publicados por `(tenantId, environment)`, verificação contra o baseline de produto (`RuleSetProductAdmissionVerifier`), cache por escopo e barreira transacional `CommitFence` (`beforeCommit`).
3. **Semeação Genesis Idempotente (`ProductBaselineGenesisInitializer`):** bootstrap automático do snapshot `GENESIS` por escopo no startup ou no provisionamento dinâmico de novos tenants SaaS.
4. **Superfície Canônica de Catálogo e Composição (`GovernedRulesStudioAutoConfiguration`):** projeção automática em `/schemas/domain` e endpoints REST `/api/praxis/rules-studio/**` sem controladores ou provedores manuais no host.
5. **Inspeção Contextual In-App e X-Ray `422`/`409` (`@praxisui/core` + `@praxisui/visual-builder` + `@praxisui/dynamic-form` + `@praxisui/crud`):** explicabilidade imediata para o operador e *deep-link* determinístico para governança em 4 olhos no **`praxis-policy-studio`**.

---

## 1. Onboarding Backend no Host (`praxis-config-starter`)

### 1.1. Modo A — Família Declarativa Rápida (`ofSingleRuleSet`)

Ideal para recursos corporativos cujo `RuleSetDefinition` já declara suas raízes ontológicas (`availableRoots`), sem necessidade de escrever classes `*Runtime`, `*Runtimes` ou JSON Schemas manuais de `RuleFactContract`:

```java
@Bean
GovernedRuleFamilyDescriptor<SingleRuleOperation> setorRuleFamily(
        PublishedRuleSnapshotHeadSetReader headSetReader,
        Clock clock) {
    return GovernedRuleFamilyDescriptor.ofSingleRuleSet(
                    "meu-servico",
                    "host/1.0",
                    SetorRuleSet.definition(),
                    headSetReader,
                    clock,
                    ZoneId.of("America/Sao_Paulo"),
                    RuleBindingExecutorRegistry.empty(),
                    SetorRuleErrorCatalog.INSTANCE)
            .withMetadata(
                    "organizacao.setores",
                    "Regras de Setores Organizacionais",
                    "Invariantes cadastrais e hierárquicas de setores.",
                    Map.of());
}
```

Em tempo de execução transacional, o `Service` consome o mesmo `GovernedHostRuleBundleRuntime` com `CommitFence` ativo:

```java
var scope = new TenantEnvironmentScope(tenantId, environment);
var admissionScope = setorRuleFamily.runtime().require(scope, null, true);
admissionScope.runtime().enforceAdmission(
        SingleRuleOperation.DEFAULT,
        factsJson,
        admissionScope.nowUtc(),
        admissionScope.zone());
```

### 1.2. Modo B — Família Multi-Operação Tipada (`RuleFactContract`)

Ideal para agregados críticos multi-operação (`CREATE`, `UPDATE`, `CLOSE`, `PAY`) que exigem validação estrutural estrita do JSON de fatos contra `RuleFactSchema` e digest criptográfico do contrato (`factContractDigest`):

- Construir `GovernedHostRuleBundleRuntime<MyOperation>` com `List<OperationSpec<MyOperation>>` contendo os `RuleFactContract`s de cada operação;
- Expor `@Bean GovernedRuleFamilyDescriptor<MyOperation>` com `.withMetadata(resourceKey, title, description, conceptRootSpecs)`.

---

## 2. Ciclo de Vida Multi-Tenant e Escala Corporativa SaaS

### 2.1. Bootstrap Genesis vs. Onboarding Dinâmico de Tenants

`ProductBaselineGenesisInitializer` suporta dois modos complementares:

1. **Tenants conhecidos no boot (ambientes single-tenant ou grupos econômicos fixos):**
   ```yaml
   praxis:
     config:
       domain-rules:
         genesis:
           enabled: true
           environment: prod
           tenant-ids:
             - "1"
             - "2"
   ```
2. **Onboarding elástico em SaaS corporativo (centenas ou milhares de tenants):**
   - Não listar milhares de tenants estaticamente no YAML de boot (evitando latência linear $O(T \times F)$ na inicialização do pod).
   - Manter `tenant-ids` restrito apenas a tenants canônicos de sistema/homologação e invocar programaticamente `productBaselineGenesisInitializer.initializeScope(newTenantId, environment)` no *workflow* transacional de provisionamento do novo tenant. O método é **idempotente** e cria apenas *heads* ausentes.

### 2.2. Isolamento, `CommitFence` e Fail-Closed (`withdraw`)

- **Isolamento de Escopo:** `PreparedBundle.admit(...)` valida `TenantScopeResolver.verifyFactsContextScope(facts.path("context"), scope.tenantId())` tanto em famílias tipadas quanto em `ofSingleRuleSet`, bloqueando qualquer divergência entre o tenant autenticado e o payload de fatos (`empCodigo` / `tenantId`).
- **Concorrência (`CommitFence`):** Em toda transação gravável (`requireSyncTransaction = true`), um `TransactionSynchronization.beforeCommit` revalida o `fingerprint` do `PublishedRuleSnapshotHeadSet` e a janela temporal (`validFromUtc` / `validUntilUtc`). Se uma política for rotacionada ou revogada durante a transação, o commit aborta *fail-closed* com `503 SERVICE_UNAVAILABLE`.
- **Emergência Operacional (`withdraw`):** Retirar um snapshot ativo sem substituto bloqueia imediatamente novas mutações daquela operação no tenant afetado (*fail-closed* deliberado para contenção de risco regulatório/financeiro). Para reverter ao padrão de fábrica sem indisponibilidade, publique uma nova revisão restaurando os *bindings* de produto ou reexecute a reconciliação Genesis controlada.

---

## 3. Auto-Descoberta Semântica e Endpoints Canônicos

Quando `GovernedRuleFamilyRegistry` está ativo no host:

- **Ponte `/schemas/domain` (`MetadataDomainRuleSetCatalogBridgeSupport`):** projeta automaticamente os `RuleSet`s efetivos do tenant (ou o baseline de produto quando não há customização publicada) no catálogo semântico do recurso, filtrando famílias elegíveis antes de consultar o `PublishedRuleSnapshotHeadSetReader` para evitar queries desnecessárias ao banco.
- **API REST para o Rules Studio (`GovernedRulesStudioCompositionController`):** expõe automaticamente:
  - `GET /api/praxis/rules-studio/families`
  - `POST /api/praxis/rules-studio/compositions/preview`
  - `POST /api/praxis/rules-studio/compositions/publish`
  - `POST /api/praxis/rules-studio/compositions/reset`

---

## 4. Integração Frontend Zero-Code no Host Angular

### 4.1. Ativação Global em 1 Linha (`app.config.ts`)

```ts
import { providePraxisDecisionInspector } from '@praxisui/visual-builder';

export const appConfig: ApplicationConfig = {
  providers: [
    providePraxisDecisionInspector({
      rulesStudioUrl: 'https://rules-studio.sua-empresa.com',
    }),
  ],
};
```

### 4.2. Experiência Corporativa Entregue Automaticamente

1. **Botão Contextual "Regras de Negócio" (`<praxis-resource-page>`):** aparece automaticamente apenas nos recursos que publicam `domain_rule_set` em `/schemas/domain`, invalidando o cache ao alternar tenant/ambiente no `EnterpriseRuntimeContextService`.
2. **Drawer Frameless de Inspeção (`<praxis-decision-inspector-surface>` + `<praxis-rule-decision-stages>`):** exibe os **5 estágios canônicos** (`PROTECTED_GUARD`, `DOMAIN_DECISION`, `POST_DECISION`, `TRANSFORMATION_INTENT`, `EFFECT_INTENT`), políticas reais de extensão (`FORBIDDEN`, `RESTRICT_ONLY`, `PARAMETERIZABLE`, `REPLACEABLE`), autoridades (`SECURITY`, `PRODUCT`, `CUSTOMER`, `GENERATED_INFRASTRUCTURE`), raízes ontológicas e `@ResourceFact`s, com navegação completa por teclado (WCAG 2.1 AA).
3. **X-Ray Explicável `422`/`409` (`<praxis-dynamic-form>` / `<praxis-form-actions>`):** extrai todas as violações de `RestApiResponse` / `ProblemDetail` sem duplicar resumos por campo, exibindo código normativo, estágio, autoridade, linhagem e inspeção individual com 1 clique focada diretamente no `slotKey` acionado.
4. **Deep-Link Seguro para Governança (`buildRulesStudioDeepLink`):** valida estritamente esquemas `http:`/`https:` ou rotas relativas seguras (`/studio`), rejeitando esquemas executáveis (*fail-closed*) e transferindo `resourceKey`, `ruleSetKey`, `operationKey`, `slotKey`, `tenantId` e `environment` para a rota `/catalog` do **`praxis-policy-studio`**.
