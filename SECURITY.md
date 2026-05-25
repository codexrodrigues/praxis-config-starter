# Security Policy

`praxis-config-starter` handles runtime configuration, AI provider credentials, signed stream access, and governed authoring state. Treat reports involving these surfaces as security-sensitive.

## Reporting A Vulnerability

Do not open a public issue that contains secrets, credentials, private URLs, customer data, request payloads with sensitive data, or exploit details.

Report vulnerabilities privately to the repository maintainer through GitHub Security Advisories or another private channel agreed with the maintainer.

Include:

- affected version or commit;
- affected endpoint, workflow, or configuration key;
- reproduction steps without real secrets;
- expected impact;
- whether a provider key, stream token, tenant/user header, or persisted configuration record is involved.

## Secret Handling

- Never commit real provider keys, stream token secrets, database credentials, Maven Central credentials, GPG material, or GitHub tokens.
- Local files such as `.env.openai.local.ps1` and `.env.*.local.*` are ignored by Git and must remain local.
- Rotate any key that was copied into logs, chat, screenshots, test artifacts, browser captures, or an issue.
- Use GitHub Secrets, environment variables, or host-owned secret management for CI and deployed environments.

## Supported Security Posture

The starter expects production hosts to provide:

- authenticated principal resolution;
- tenant/user/environment boundaries;
- HTTPS;
- stable secret management;
- appropriate CORS and cookie policy;
- monitored storage for `ui_user_config`, `ai_registry`, `api_metadata`, and AI turn records.

Local defaults are for development and smoke testing only.
