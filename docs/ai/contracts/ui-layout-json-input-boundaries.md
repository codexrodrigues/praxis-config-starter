# Fronteiras JSON lifecycle

2026-10-06. Correção source de UiLayoutLifecycleRequestDecoder: mapper privado
independente do host, campos desconhecidos/duplicados negados, um documento JSON
somente e nenhuma leniência JsonReadFeature. A assinatura e os DTOs permanecem.
Falha lexical retorna INVALID_REQUEST sanitizado antes de chamar o serviço.
Mapper global não é reconfigurado. Não chamar essa fronteira de schema nativo,
validação de negócio, renderer ou admissão de origem.

A candidata privada rc.167 congelada **não contém esta correção**. Não instalar
ou substituir seus bytes para apresentar a prova de source como prova de artefato.

A candidata privada rc.168 posterior contém os controles e passou a composição
downstream no Quickstart/Jackson 2.15.4. Não é publicação nem build default do
host (pin rc.163). MockMvc usou filtros reais e autoconfiguração; serviços/token/
sessão são fixtures. Config permitAll no host admite parsing limitado anônimo
quando Origin é permitida: principal/admissão são exigidos no serviço antes de
mutação. Não alegar autenticação obrigatória anterior ao advice nesse host.

## Matriz focal de limites

| Entrada | Controle encontrado | Momento / limite de evidência |
| --- | --- | --- |
| Body HTTP de revisão | RequestBodyAdvice MVC: UTF-8 estrito, 1 MiB de bytes reais; declaração maior também rejeitada | Após filtros de segurança do host e antes da conversão para String. Leitura limitada a quota + 1 byte; buffer limitado, não zero-copy. 413 sanitizado REVISION_BODY_TOO_LARGE, no-store |
| Envelope HTTP de revisão | Decoder fechado independente do host, 1 MiB e profundidade 65 | Após conversão limitada; propriedades/shape, duplicatas, trailing, Unicode e números até 256 caracteres verificados |
| authoringDocument de revisão e patch derivado | Decoder/parser Java limita C0; compilador limita sua saída: 256 KiB compactos, profundidade 64 por árvore | Texto Java já alocado: até 1 MiB UTF-8 antes do parse fechado. Patch de entrada é desconhecido; saída validada antes de escrita |
| Outros bodies HTTP lifecycle | Decoder fechado com propriedades/shape e duplicatas | A quota de revisão não foi generalizada para assignments, releases ou outros comandos |
| Workspace persistido | Codec privado, trailing/duplicates/unknown e integridade de refs/hashes; documentos B0/working 256 KiB | Valida envelope/documentos; não confundir limite por documento com quota total de workspace/composição |
| rawInputText de captura/assembly | Metadata codec: UTF-8 validado, 256 KiB por texto, parsing fechado, depth 64 e checks Unicode/nós | Limite de bytes antes de readTree desse texto, mas a String já existe. Não descreve limite do HTTP lifecycle |
| Composição de capturas | Metadata codec: soma raw e assembly até 1 MiB | Soma específica de textos de captura, não limite universal de request/workspace |
| createRevision Java direto | UiLayoutRevisionJsonInput fechado, checkpoints do contexto de validação e limites por documento | Mesmo controle de sintaxe/documento sem depender de MVC; baseline resolvida limitada como INVALID_STATE. Outros métodos e o envelope persistido não ganham uma quota universal |

## Continuidade

Os limites de revisão acima estão implementados em source. Content-Length menor
não oculta trailing: o converter recebe o tamanho real admitido, sem alterar os
headers originais. Erros de encoding, sintaxe e quota de documento retornam 400
INVALID_REQUEST; excesso de body HTTP retorna 413. O request recebe apenas candidato;
projeção/compilador estão ligados ao comando contra B0 original. Composição
downstream com a versão Jackson do host concluída localmente; host vivo e
política corporativa de autenticação antes de parsing continuam gates próprios.

Validação focal: advice, parser Java, autoconfiguração, decoder, comando, guards e round-trip MockMvc com
repositórios/persistência simulados. Sem prova de PostgreSQL ou host vivo. Sem
alteração de campos/endpoint/ETag. OpenAPI e documentação de autoria atualizados
para o novo 413 e quotas. O corpus inventariado só contém lookup GET; recipes,
registries e Landing pesquisados não exigem regeneração deste endpoint. UI não
foi alterada; seu tratamento HTTP genérico preserva o status, sem nova UX específica.
