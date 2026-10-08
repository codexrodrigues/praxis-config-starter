# Candidata privada de autoridade lifecycle

`0.1.0-rc.162` é a primeira candidata privada deste pacote que inclui a correção descrita em [vigência de autoridade](ui-layout-lifecycle-authority-freshness.md). A rc.161 congelada permanece intacta e sem essa correção. Não há publicação, tag, novo endpoint ou alteração de formato.

A fonte canônica da correção está no Config Starter. O Quickstart fixa a rc.162 e valida os adaptadores reais sobre o JAR, com gerenciadores Spring JPA reais e fontes de persistência/membership simuladas. Consultar o guia `docs/ui-layout-lab-authority-candidate.md` do Quickstart e o pacote de evidências `authority-candidate-downstream-2026-10-03`.

Este checkpoint não comprova persistência em PostgreSQL, execução IAM, browser ou fencing distribuído. Os arquivos V64–V68 aplicados continuam imutáveis. A ativação conjunta dos providers e a prova integrada precisam de admissão própria. Não tratar a coordenada instalada no cache privado como versão pública disponível a consumidores.
