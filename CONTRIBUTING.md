# Contributing

Thanks for helping improve `praxis-config-starter`.

## Development Baseline

- Java 17+
- Maven
- PostgreSQL 14+ when testing database behavior beyond unit smoke tests
- `pgvector` when testing vector search or RAG behavior

Run the focused starter smoke before opening or merging changes:

```powershell
mvn -B -P ci-smoke-unit -T 1C clean verify
```

## Platform Boundaries

Keep changes in the canonical owner:

- backend resource semantics, `x-ui`, `/schemas/filtered`, discovery, and capabilities belong to `praxis-metadata-starter`;
- runtime configuration, AI registry, API metadata, stream auth, and governed authoring state belong here;
- Angular rendering, materializers, and host integration APIs belong to `praxis-ui-angular`;
- end-to-end operational proof belongs to `praxis-api-quickstart`.

Do not patch consumers to compensate for a contract that belongs in this starter, and do not redefine another starter's semantics here.

## Public Contract Changes

Changes to `/api/praxis/config/**`, headers, ETag behavior, AI contracts, stream auth, persistence shape, workflows, or release behavior should include:

- focused tests;
- README or docs updates when public behavior changes;
- downstream validation through `praxis-api-quickstart` when the change affects host integration;
- contract updates under `docs/ai/**` when AI request/response shape changes.

## Secrets

Never commit real credentials.

Keep local provider files ignored and local:

- `.env.openai.local.ps1`
- `.env.*.local.ps1`
- `.env.*.local.sh`

Use GitHub Secrets or host-owned secret management for CI and deployed environments.
