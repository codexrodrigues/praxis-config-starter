# Composição Swagger — candidato privado

Estado em 02/10/2026: **não validado, não publicado e não adotado pelo host**. A versão `0.1.0-boot35-validation-SNAPSHOT` identifica apenas esta prova isolada. Não há mudança de endpoint, DTO, wire, política, prazo, IAM ou persistência.

## Causa e dono

Classificação: `transversal`; aderência: `suportado-parcialmente`. O Config precisa da dependência direta `swagger-annotations-jakarta` para suas anotações `@Schema`, `@Operation` e afins. O POM público `0.1.0-rc.157` declara a versão 2.2.28. No host candidato, esse caminho mais curto vence a versão 2.2.47 que acompanha Springdoc 2.8.17 no Metadata. O Swagger Core 2.2.47 chama `Schema.$dynamicRef()`, ausente da interface 2.2.28; a geração OpenAPI falha com `NoSuchMethodError`. Separadamente, `Config → openai-java:4.43.0 → openai-java-core` traz `swagger-annotations` não Jakarta 2.2.31, outro JAR que define o mesmo FQCN `io.swagger.v3.oas.annotations.media.Schema`. A árvore efetiva do host e `javap` das duas versões Jakarta confirmam essas arestas e o método ausente. Isso diagnostica a composição privada Boot 3.5; não declara quebrada a coordenada pública atual em seu baseline anterior.

O dono da dependência direta e da aresta OpenAI é o Config. Este candidato eleva somente `swagger-annotations-jakarta` para 2.2.47 e exclui `swagger-annotations` não Jakarta somente de `openai-java`. Mantém a dependência Jakarta direta, necessária para compilar as anotações públicas do Config. Não há exclusão ou override no Quickstart e não se cria uma segunda camada de resolução Swagger. O Metadata é dono do Springdoc/OpenAPI canônico; o host prova a composição com os dois starters.

## Limites e validação necessária

- Inspecionar a árvore resolvida do Config e do host candidato: uma única implementação de `io.swagger.v3.oas.annotations.media.Schema`, Jakarta 2.2.47; nenhum `swagger-annotations` não Jakarta, inclusive no JAR empacotado. Confirmar também Swagger Core 2.2.47 e a identidade dos artefatos privados usados na prova.
- Compilar Config e executar focais dos DTOs/controllers anotados e do contrato OpenAPI. A exclusão de uma dependência transitiva do cliente OpenAI requer ao menos compilação e prova focal do consumidor SDK; não presumir que a árvore sozinha certifica esse cliente.
- Validar Config com Metadata candidato sob Boot 3.5 e executar no host os seletores HTTP que encontraram o `NoSuchMethodError`, preservando oráculos de schema/hash, domínio e segurança. Depois repetir as provas PostgreSQL de política e timeout com as coordenadas privadas exatas. As falhas restantes do focal host têm de ser diagnosticadas separadamente; a colisão não as explica por si só.

Nenhuma dessas provas foi executada por este autor neste worktree. A mudança no POM não autoriza instalar sobre coordenada pública, publicar release, alterar pin oficial do host nem alegar correção causal até passar pelos gates. Não há artefato de UI, corpus HTTP ou documentação pública a sincronizar antes de observar eventual diferença no contrato servido. A revisão independente deve confrontar POM, árvore efetiva, JAR e testes com a mesma fonte congelada.
