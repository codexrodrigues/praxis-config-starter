# Recuperação operacional do Render — 2026-09-05

O override de `JAVA_OPTS` foi alinhado ao Dockerfile publicado após autorização explícita do usuário.
O serviço voltou a autenticar normalmente. Esta evidência sucede o diagnóstico de
`LIVE-JOURNEY-PREPARATION-2026-09-05.md`; o recibo anterior permanece histórico.

## Alteração aplicada

Serviço: `srv-d3qjqcemcj7s73bpluv0`, produção, Docker Standard.
Valor persistido e conferido pela UI:

```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=25.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8
```

Foi removido o teto separado `-XX:MaxMetaspaceSize=160m` e habilitado `ExitOnOutOfMemoryError`.
Chaves, modelo, plano de compute e políticas de segurança não foram alterados.

A opção **Save and deploy** reutilizou a imagem publicada, sem rebuild e sem publicação do corte local.
O [deploy corrigido](https://dashboard.render.com/web/srv-d3qjqcemcj7s73bpluv0/deploys/dep-dae8fkv40ujc73e8c5ig)
terminou como **Deploy succeeded | Live**. Commit preservado:
`0361e6cd698bda033a8a57816afe1200f2332634`, Quickstart `2.0.0-rc.46`.
A aplicação iniciou às **21:22:49 UTC**, em 55,807 segundos. O pre-deploy validou as migrações
operacionais e informou `executed=0`.

Houve uma primeira implantação (`dep-dae8efon74is73cq2060`) que manteve o valor antigo:
o carregamento assíncrono do campo mascarado substituiu a edição. A leitura após salvar detectou
isso. A edição foi refeita somente depois de carregar o valor remoto e o segundo salvamento foi
conferido como correto. Não foi necessária alteração de código ou rollback.

## Validação HTTP após o deploy

| Verificação | Resultado |
| --- | --- |
| `GET /actuator/health` | 200, `UP` |
| `GET /actuator/health/readiness` | 200, `UP` |
| `GET /actuator/info` | 200, versão `2.0.0-rc.46` |
| `POST /auth/login` | 204 |
| `GET /auth/session`, autenticado | 204 |
| `POST /auth/logout` | 204 |
| `GET /auth/session`, após logout | 401 |
| `GET /api/praxis/config/ai/status`, Origin `https://praxisui.dev` | 200, `openai`, `gpt-5-mini`, `hasApiKey=true`, `source=env`, `success=true` |
| Mesmo status, Origin `http://127.0.0.1:4301` | 403, `Config origin not allowed` |

As credenciais foram lidas apenas dos arquivos locais ignorados e enviadas por stdin ao curl.
Cookies ficaram em diretório temporário privado, removido ao finalizar. O recibo contém apenas
status e metadados sanitizados. Não houve inferência, escrita de registros de domínio ou apply.

A consulta de logs com filtro `ERROR`, entre **21:21:45 e 21:24:35 UTC**, retornou
**No matching logs**. Isso comprova a janela observada; não certifica estabilidade sob carga longa.
O aviso de migração Config existente (schema 61 versus última migração disponível 60) foi observado
na inicialização, sem novas migrações executadas; revisar a proveniência no próximo corte de release.

## Pendências da jornada real

1. Publicar o corte reconciliado pelo fluxo oficial, mediante pedido explícito para release.
2. Resolver a origem da lane: preflight CORS aceito não implica autorização do Config. A lane local
   preparada usa `127.0.0.1:4301`, atualmente recusado. A allowlist não foi ampliada nesta operação.
3. Definir autorização e teto USD, verificar o snapshot de preços e executar uma única jornada paga
   com os gates, escopo de dados descartáveis e cleanup já preparados.

Classificação dos arquivos desta operação: `docs-apenas`; a alteração externa é operacional no host.
Nenhum contrato público ou artefato derivado de runtime mudou. O runbook da lane foi atualizado com
a restrição de origem observada. Validação documental: leitura final, JSON válido e `git diff --check`.
