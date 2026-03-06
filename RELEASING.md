# Releasing - praxis-config-starter

Este documento descreve como publicar Release Candidates (RC) e versoes finais no Maven Central usando o workflow deste repositorio.

## Pre-requisitos
- GitHub Secrets no repositorio:
  - `CENTRAL_TOKEN_USER` e `CENTRAL_TOKEN_PASS` (tokens do Sonatype Central Portal)
  - `GPG_PRIVATE_KEY` (chave privada ASCII-armored ou base64, sem CRLF)
  - `GPG_PASSPHRASE` (passphrase da chave)
  - `GPG_KEY_ID` (opcional; se ausente, o workflow resolve automaticamente)
- Java 17 + Maven instalados localmente (validacao local opcional).

## Fluxo (Release Candidate)
1) Opcional - validar localmente (sem assinatura):
```bash
mvn -B -DskipTests -T 1C clean verify
mvn -B javadoc:javadoc && test -d target/site/apidocs
```

2) Criar a tag do RC e enviar:
```bash
git tag v0.1.0-rc.1
git push origin v0.1.0-rc.1
```

3) Acompanhar o workflow `Release Java Starter (praxis-config-starter)`:
- O workflow resolve a versao a partir da tag (`v` e removido).
- Passos: importar GPG -> `versions:set` -> `clean verify` com perfil `release` (assina) -> publicar via Central Plugin.

4) Verificar artefatos assinados no job:
- `target/praxis-config-starter-<versao>.jar(.asc)`
- `target/praxis-config-starter-<versao>-sources.jar(.asc)`
- `target/praxis-config-starter-<versao>-javadoc.jar(.asc)`

5) Acompanhar aprovacao no Sonatype Central Portal.

## Fluxo (Versao Final)
Mesmo processo, usando tag sem sufixo RC:
```bash
git tag v0.1.0
git push origin v0.1.0
```

## Observacoes
- O `pom.xml` no repositorio pode ficar em `-SNAPSHOT`; o workflow usa `versions:set` apenas durante a execucao do CI.
- O perfil `release` assina os artefatos, gera POM flatten compativel com Central e publica usando `central-publishing-maven-plugin`.

## Troubleshooting
- GPG key id nao resolvido:
  - Confirme que `GPG_PRIVATE_KEY` nao tem BOM/CRLF. O workflow sanitiza, mas vale validar o secret bruto.
- Falha de publicacao (`publish`):
  - Verifique `CENTRAL_TOKEN_USER/PASS` e se o server `central` foi injetado pelo `actions/setup-java`.
- Assinaturas ausentes:
  - Confirme que o build roda com `-P release -Dgpg.skip=false`.
