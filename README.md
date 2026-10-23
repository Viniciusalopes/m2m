# m2m - Machine To Machine

Aplicação web para **backup, restauração, sincronização e transferência de arquivos entre máquinas** (LOCAL, SFTP, FTP e FTPS), com fila de jobs, verificação de igualdade, criptografia opcional dos backups e segundo fator no login.

Desenvolvido para manter os arquivos de trabalho do notebook **NOTEBOOK-REMOTO** protegidos e sincronizados com o **NOTEBOOK** (onde o m2m roda), e pronto para ser publicado numa VPS.

- **Backend:** Java 25, Spring Boot 4, Apache MINA SSHD, Apache Commons Net, Tink, PostgreSQL 18, RabbitMQ 4.
- **Frontend:** Angular 22 e Angular Material 3, tudo em português do Brasil.
- **Entrega:** Docker Compose (banco, fila, migrações, back e front com HTTPS).

> **Segurança em primeiro lugar.** O m2m pode apagar e sobrescrever arquivos em várias máquinas. Leia a seção [Primeiro uso com segurança](#primeiro-uso-com-segurança) antes da primeira restauração ou sincronização numa máquina real.

---

## Sumário

1. [Pré-requisitos](#pré-requisitos)
2. [Configuração (`.env`)](#configuração-env)
3. [Subir e derrubar (`m2m-up` / `m2m-down`)](#subir-e-derrubar-m2m-up--m2m-down)
   - [Certificado HTTPS](#certificado-https)
   - [Logs no Grafana](#logs-no-grafana)
4. [Primeiro acesso e chave mestra](#primeiro-acesso-e-chave-mestra)
5. [E-mail (SMTP) pela tela](#e-mail-smtp-pela-tela)
6. [Cadastro de máquinas](#cadastro-de-máquinas)
7. [Matriz de fidelidade](#matriz-de-fidelidade)
8. [Máquinas atrás de NAT](#máquinas-atrás-de-nat)
9. [Perfis, backups e retenção](#perfis-backups-e-retenção)
10. [Primeiro uso com segurança](#primeiro-uso-com-segurança)
11. [Extrair um backup manualmente](#extrair-um-backup-manualmente)
12. [Backups criptografados e o comando `decrypt`](#backups-criptografados-e-o-comando-decrypt)
13. [Recuperação do próprio m2m](#recuperação-do-próprio-m2m)
14. [Segundo fator (TOTP)](#segundo-fator-totp)
15. [Desenvolvimento e testes](#desenvolvimento-e-testes)
16. [Créditos e licenças](#créditos-e-licenças)

---

## Pré-requisitos

- **Docker** com **Docker Compose v2** (o usuário precisa pertencer ao grupo `docker`; o m2m não usa `sudo`).
- Portas **80** e **443** livres no host (configuráveis por `HTTP_PORT` e `HTTPS_PORT`).
- Para desenvolver: JDK 25, Node 24 e npm (o Maven vem no repositório, `./mvnw`).

## Configuração (`.env`)

Copie o modelo e preencha. **O `.env` nunca vai para o git.**

```bash
cp .env.example .env
```

| Variável | Para que serve |
|---|---|
| `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `ADMIN_EMAIL` | Administrador criado no primeiro start. |
| `APP_SECRET_KEY` | **Chave mestra**: cifra senhas de máquinas, chaves privadas, SMTP e as chaves de criptografia dos backups. Gere com `openssl rand -base64 32`. |
| `M2M_LOCAL_ENABLED`, `HOST_HOME_BIND`, `HOST_ROOTS` | Máquina **LOCAL** (o próprio host). |
| `PUBLIC_URL`, `HTTP_PORT`, `HTTPS_PORT`, `TLS_CERT_PATH`, `TLS_KEY_PATH` | Endereço e certificado. O `m2m-up` preenche `TLS_CERT_PATH`/`TLS_KEY_PATH` com o certificado gerado por `scripts/gerar-certificado-tls.sh` (veja [Certificado HTTPS](#certificado-https)). Em branco, o m2m gera um certificado autoassinado no primeiro start. |
| `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_PORT`, `GRAFANA_BIND`, `LOKI_RETENTION` | Logs no Grafana (veja [Logs no Grafana](#logs-no-grafana)). A senha é obrigatória; porta padrão 3000, `GRAFANA_BIND` padrão `127.0.0.1` e retenção padrão `168h` (7 dias). |
| `TRANSFER_CONCURRENCY` | Transferências simultâneas na tela de transferência (padrão 2). |
| `POSTGRES_*`, `RABBITMQ_*` | Banco e fila (rede interna do Compose). |

### NOTEBOOK (máquina com acesso ao próprio disco)

```dotenv
M2M_LOCAL_ENABLED=true
HOST_HOME_BIND=/home
HOST_ROOTS=/home
```

O m2m enxerga `/home` do host como a máquina **LOCAL**, e os backups vão por padrão para `/home/backup`.

### VPS (sem acesso ao disco do host)

```dotenv
M2M_LOCAL_ENABLED=false
HOST_HOME_BIND=
```

Nada do host é montado. Armazene os backups em outra máquina cadastrada (SFTP/FTP/FTPS) e use certificado válido em `TLS_CERT_PATH` / `TLS_KEY_PATH`.

## Subir e derrubar (`m2m-up` / `m2m-down`)

No NOTEBOOK existem dois atalhos no shell (bloco `# m2m` do arquivo de aliases):

```bash
alias m2m-up="$M2M_HOME/scripts/m2m-up.sh"
alias m2m-down="docker compose -f $M2M_HOME/docker-compose.yml --env-file $M2M_HOME/.env down"
```

- `m2m-up` sobe `m2m-db`, `m2m-queue`, `m2m-db-migrate`, `m2m-back` e `m2m-front`.
- `m2m-down` remove containers e rede e **preserva os volumes** (banco e fila).
- Sem os aliases, rode os mesmos comandos `docker compose` dentro do diretório do projeto.
- Os containers têm nome fixo: `m2m-db-postgresql`, `m2m-queue-rabbitmq`, `m2m-db-pg-migrate`, `m2m-back-java` e `m2m-front-angular`. Os comandos `docker compose` continuam usando o nome do serviço (`m2m-db`, `m2m-back` etc.).
- Ao atualizar de uma versão anterior, o `m2m-up` recria os containers com os nomes novos e mantém os volumes; não use `down -v`. Scripts com `docker exec m2m-m2m-*` devem usar os nomes novos.

A interface fica em **https://localhost** (ou no `PUBLIC_URL`). Com o certificado gerado pelo `m2m-up`, o navegador mostra o site como seguro. A documentação da API está em `/api/docs` (exige login).

## Certificado HTTPS

O `m2m-up` (`scripts/m2m-up.sh`) chama `scripts/gerar-certificado-tls.sh` antes de subir os serviços. Na primeira execução ele:

1. cria uma **CA local** em `~/.config/m2m/tls/` (a chave dela nunca vai para os containers e a CA só vale para `localhost`, loopback, redes privadas e `.local`/`.lan`);
2. emite o certificado do servidor, válido por 397 dias, com SAN para `localhost`, `127.0.0.1`, `::1` e o host do `PUBLIC_URL`;
3. grava `TLS_CERT_PATH` e `TLS_KEY_PATH` no `.env`;
4. instala a CA no sistema (`sudo`, uma única vez) e no Chrome/Chromium (precisa de `libnss3-tools`: `sudo apt install libnss3-tools`). Feche e reabra o navegador depois.

Nas execuções seguintes nada é regerado nem pede `sudo`, exceto se o certificado vencer em menos de 30 dias ou se o host do `PUBLIC_URL` mudar (só o certificado é reemitido). Se a instalação falhar, o m2m sobe mesmo assim e o script avisa o que ficou pendente.

- **Firefox e outros dispositivos** (celular, outro PC): importe `~/.config/m2m/tls/ca.crt` como autoridade confiável.
- **Só gerar os arquivos**, sem instalar a CA: `scripts/gerar-certificado-tls.sh --sem-sistema`.
- **VPS ou domínio público**: use um certificado válido (ex.: Let's Encrypt) em `TLS_CERT_PATH` / `TLS_KEY_PATH` e não rode o gerador.

## Logs no Grafana

O stack sobe junto com um **Grafana**, um **Loki** (armazenamento) e um **Alloy** (coletor), que reúnem os logs de back, front, fila, banco e migração em um só lugar. A configuração fica em `logs/` e é versionada com o projeto.

- **Acesso:** `https://localhost:3000` (porta em `GRAFANA_PORT`), com usuário `admin` e a senha de `GRAFANA_ADMIN_PASSWORD` do `.env`. Na página **Auditoria** há o botão **Abrir logs no Grafana**, que abre o mesmo endereço em uma nova aba. O dashboard inicial, **Logs m2m**, filtra por serviço e por texto.
- **HTTPS:** o Grafana usa o mesmo `TLS_CERT_PATH`/`TLS_KEY_PATH` do front (veja [Certificado HTTPS](#certificado-https)), então o navegador o reconhece como seguro. Sem certificado configurado, ele gera um autoassinado. Quando o certificado for renovado ou reemitido, recrie o Grafana: `docker compose up -d --force-recreate m2m-grafana` (o `m2m-up` já recria o que mudou).
- **Rede:** por padrão o Grafana só escuta em `127.0.0.1`. Com o m2m aberto em outro dispositivo, o botão da Auditoria não alcança o Grafana; para isso defina `GRAFANA_BIND=0.0.0.0`, sabendo que ele passa a ficar exposto na rede. O Loki e o Alloy não publicam portas.
- **Retenção:** os logs ficam `LOKI_RETENTION` (padrão 7 dias) no volume `m2m-loki-data`; os mais antigos são apagados sozinhos.
- **O que entra:** só containers do projeto `m2m`. Containers de teste (`m2m-test-*`) e a própria stack de logs (`m2m-logs-*`) ficam de fora; por isso todo container de teste novo deve se chamar `m2m-test-*`.
- **Independência:** nada depende da stack de logs. Se ela parar, o m2m segue funcionando e `docker logs` continua valendo.
- **Segurança:** o Alloy lê os logs pelo socket do Docker (`/var/run/docker.sock`, montado somente leitura). Mesmo assim, quem controla o Alloy enxerga o daemon, por isso a porta dele não é publicada. Em uma máquina de uso único o risco é aceito.

## Primeiro acesso e chave mestra

1. Entre com o `ADMIN_USERNAME` e a `ADMIN_PASSWORD` do `.env`.
2. Uma faixa no topo pede que você **guarde o `.env` (ou a chave `APP_SECRET_KEY`) num cofre de senhas**. Ela some quando você clica em **Já guardei a chave**.
3. **Sem a chave mestra**, as credenciais salvas e os backups criptografados **não podem ser recuperados**: o m2m não guarda cópia dela e a interface nunca a mostra.
4. Troque a senha em **Alterar senha / e-mail** e, de preferência, ative o [segundo fator](#segundo-fator-totp).

## E-mail (SMTP) pela tela

Cada usuário cadastra o próprio servidor em **Configurações → E-mail (SMTP)**: servidor, porta, segurança (nenhuma, STARTTLS ou SSL/TLS), usuário, senha (guardada cifrada, nunca exibida de novo) e remetente. Há um botão de **e-mail de teste**. Com o envio ativo, as notificações importantes (falha de job agendado, backup suspeito ou corrompido, sincronização aguardando confirmação, identidade de servidor alterada, pouco espaço) chegam também por e-mail, além do painel.

## Cadastro de máquinas

Em **Máquinas → Nova máquina**. O **SFTP é o padrão** e o único que preserva tudo.

### SFTP (SSH)

- Autenticação por **chave** (recomendada) ou senha.
- Por chave: o formulário mostra a **chave pública do m2m** com o comando pronto para copiar. No servidor, como o usuário escolhido:

  ```bash
  mkdir -p ~/.ssh && chmod 700 ~/.ssh
  echo 'ssh-ed25519 AAAA... m2m' >> ~/.ssh/authorized_keys
  chmod 600 ~/.ssh/authorized_keys
  ```

- A chave SSH é única por usuário do m2m e fica cifrada pela chave mestra. Uma cópia dela entra na exportação de configuração.

### FTP e FTPS

- **FTP** trafega usuário, senha e dados **em claro**: o formulário e a lista mostram um alerta. Use só em rede confiável.
- **FTPS** explícito (`AUTH TLS`) ou implícito; modo passivo.
- O servidor pode limitar conexões por IP: ajuste **Conexões simultâneas** da máquina (padrão 4).

### Identidade do servidor (impressão digital)

Na primeira conexão com um servidor SFTP ou FTPS, o m2m **recusa** e mostra a impressão digital (chave SSH ou certificado). Confira-a **por outro canal** (por exemplo, no próprio servidor: `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`) e só então clique em **Confirmar esta identidade**. Se a impressão mudar depois, a conexão é recusada e você é avisado, até confirmar de novo.

> Um servidor SSH tem uma chave de host por algoritmo (ED25519, ECDSA, RSA). O m2m confirma a que o servidor apresentar; confira a correspondente.

### Teste de conexão

O botão **Testar conexão** verifica alcance, autenticação, identidade, leitura e escrita (um único arquivo exclusivo, criado e removido) e detecta recursos do servidor. Se a máquina só permitir leitura, ela serve como origem, nunca como destino.

## Matriz de fidelidade

O que cada protocolo preserva. Onde não preserva, o m2m **avisa antes de confirmar** e registra o aviso no job.

| Protocolo | Conteúdo | Datas de modificação | Permissões Unix | Links simbólicos |
|---|---|---|---|---|
| LOCAL | sim | sim | sim | sim |
| SFTP | sim | sim | sim | sim |
| FTP / FTPS | sim | quando o servidor aceita `MFMT` | não | não (são pulados, com aviso) |

Backups com origem FTP/FTPS ficam marcados com fidelidade parcial. Em restaurações e sincronizações para FTP/FTPS, links são pulados com aviso e a verificação final ignora o que o protocolo não guarda.

## Máquinas atrás de NAT

O m2m (container) precisa **alcançar** cada máquina. Se o m2m está numa rede e a máquina em outra (ex.: m2m na VPS, notebook em casa):

- use uma **VPN** entre as redes (recomendado), ou
- faça **redirecionamento de porta** no roteador da máquina (SSH ou FTP) para um endereço público.

Cadastrar um endereço de rede privada (`192.168.x.x`, `10.x.x.x`, `172.16–31.x.x`) que o container não alcança resulta no erro "rede privada inalcançável"; o texto da interface explica as opções acima.

## Perfis, backups e retenção

- Um **perfil** define a máquina de origem, os diretórios, o armazenamento (máquina + diretório) e, se quiser, o agendamento diário e a criptografia.
- O arquivo é um **`.tar.zst`** (tar PAX + zstd) com um manifesto lateral (`.manifest`) e outro interno. É gravado em `.partial`, **validado por releitura** e só então renomeado.
- **Retenção:** os 2 backups aceitos mais recentes de cada perfil (os fixados ficam de fora). Backups de segurança (`PRE_RESTORE`, `PRE_SYNC`) têm a própria retenção.
- Um backup com muito menos arquivos que o anterior fica **suspeito** (você aceita ou exclui). A **verificação periódica** relê os backups e marca `corrompido` ou `ausente`.
- O **botão de emergência** (INTERROMPER AGORA) para um job na hora; o que ficou parcial é removido e os backups anteriores são mantidos.

## Primeiro uso com segurança

O m2m pode **sobrescrever e excluir** arquivos ao restaurar em espelho ou sincronizar. Para não ter surpresas:

1. **Só faça a primeira restauração ou sincronização real numa máquina depois de existir um backup validado dela** (um backup com origem na própria máquina, estado `Concluído`).
2. Antes de qualquer alteração, o m2m grava um **backup de segurança do destino** (`PRE_RESTORE`/`PRE_SYNC`) e oferece **Desfazer**.
3. Confira a **prévia** (quantos itens serão criados, sobrescritos e excluídos) e leia os alertas: origem vazia, trabalho feito no destino, fidelidade parcial.
4. Sincronizações **agendadas** só são ativadas depois de uma sincronização manual concluída para o mesmo destino, e param para sua confirmação se houver exclusões demais.
5. Teste primeiro num diretório pequeno e descartável.

## Extrair um backup manualmente

Um backup não criptografado é um `.tar.zst` comum. Sem o m2m:

```bash
mkdir restaurado
tar --zstd -xpf Perfil_NOTEBOOK_20261022-131227.tar.zst -C restaurado
```

O diretório de origem fica em `dir-1/`, `dir-2/`... (na ordem do perfil); o arquivo `.m2m-manifest.json` traz os caminhos originais, tamanhos, datas, permissões e SHA-256 de cada arquivo.

## Backups criptografados e o comando `decrypt`

Com **Criptografar os backups deste perfil** ligado, o arquivo é gravado como `.tar.zst.enc` (criptografia autenticada AES-256-GCM em streaming, via Google Tink), com o manifesto lateral também cifrado. A chave do perfil fica no banco, cifrada pela chave mestra. Os backups de segurança do perfil também são cifrados.

### Exportar a chave

No formulário do perfil, **Exportar a chave de criptografia** baixa um JSON. A exportação é registrada na auditoria. **Quem tem esse arquivo decifra os backups do perfil**: guarde-o num cofre de senhas.

### Decifrar fora do m2m

A imagem `m2m-back` traz o comando `decrypt` (não precisa de banco nem de `.env`):

```bash
docker run --rm -v "$PWD:/dados" m2m-back:dev decrypt \
  --key /dados/chave-do-perfil.json \
  --in  /dados/Perfil_NOTEBOOK_20261022-131227.tar.zst.enc \
  --out /dados/Perfil_NOTEBOOK_20261022-131227.tar.zst
```

Em seguida, extraia com `tar --zstd -xpf`, como acima. Chave errada ou arquivo adulterado terminam com erro e **nada é gravado pela metade**.

## Recuperação do próprio m2m

Quatro peças permitem reconstruir o m2m do zero:

1. **Chave mestra:** o `.env` (`APP_SECRET_KEY`) guardado no cofre. Sem ela, credenciais e backups criptografados são irrecuperáveis.
2. **Backup diário do banco:** o job `DB_BACKUP` roda todo dia (padrão 01:30, em **Configurações → Manutenção**) e grava `m2m-db_AAAAMMDD-HHMMSS.sql.zst` (pg_dump compactado) no armazenamento configurado (padrão `/home/backup/m2m-db/`), mantendo os 7 mais recentes. Para restaurar num banco novo:

   ```bash
   zstd -dc m2m-db_20261022-013000.sql.zst | docker exec -i m2m-db-postgresql psql -U m2m -d m2m
   ```

3. **Exportar/importar configuração:** em **Configurações → Exportar e importar configuração**, um arquivo cifrado por senha (Argon2id + AES-256-GCM) com máquinas (e credenciais), identidades de servidor, chave SSH, perfis, mapeamentos, agendamentos e SMTP. A importação mostra uma **prévia** (o que será criado e substituído) antes de aplicar. Máquinas e perfis são casados pelo nome.
4. **Reindexar:** **Configurações → Reindexar backups** varre um diretório de qualquer máquina e recria o registro dos `.tar.zst`/`.tar.zst.enc` que o banco não conhece (lendo o manifesto), mesmo com o banco perdido. Backups cifrados exigem que a chave do perfil exista no m2m (importe a configuração antes).

Ordem sugerida num desastre: subir o m2m com o mesmo `.env` → restaurar o dump do banco **ou** importar a configuração → reindexar o diretório de backups.

## Segundo fator (TOTP)

Em **Configurações → Segundo fator (TOTP)**: escaneie o QR code com um aplicativo autenticador, confirme o primeiro código e guarde os **10 códigos de recuperação** (aparecem uma única vez; cada um vale uma vez). Com o segundo fator ativo, o login pede o código depois da senha; códigos errados contam para o bloqueio (5 falhas bloqueiam por 5 minutos). Para desativar é preciso senha e um código.

Se perder o aplicativo e os códigos de recuperação, remova o segundo fator direto no banco (acesso ao servidor):

```bash
docker exec m2m-db-postgresql psql -U m2m -d m2m -c "delete from user_totp where user_id = (select id from app_user where username = 'admin');"
```

## Desenvolvimento e testes

```bash
# backend (Testcontainers: PostgreSQL, RabbitMQ, OpenSSH, pure-ftpd)
cd back && ./mvnw verify

# frontend
cd front && npm ci && npm run lint && npx ng test --watch=false && npx ng build

# e2e (Playwright): o stack principal precisa estar no ar; com E2E_SERVERS=1 o próprio e2e sobe os
# servidores de teste (SFTP, OpenSSH, FTP, FTPS e SMTP em container) e os derruba ao terminar
docker compose up -d --build
cd e2e && npm ci && E2E_SERVERS=1 npx playwright test
```

Regras do projeto:

- **Nenhum teste que grave ou exclua arquivos roda em máquinas reais.** Só servidores SFTP/FTP/FTPS em container e o diretório sandbox `/home/usuario/m2m-sandbox/`. Máquinas reais só são lidas (teste de conexão, navegação, backup com origem nelas, verificação).
- O pipeline (`.gitlab-ci.yml`) roda lint, testes do back e do front, verificação de licenças, build das imagens e, manualmente, o e2e.
- `./scripts/gerar-third-party-notices.sh` gera `THIRD-PARTY-NOTICES.md` e falha se alguma dependência tiver licença fora da lista permitida.
- O esquema do banco vive em `db/migrations/` (Flyway, aplicado pelo serviço `m2m-db-migrate`).

## Créditos e licenças

- A **tela de transferência** é **inspirada no FileZilla Client** (Tim Kosse e colaboradores, GPL-3.0-or-later; <https://filezilla-project.org>). O m2m usou o FileZilla apenas como referência funcional e de experiência de uso: **não contém código do FileZilla**. "FileZilla" é marca registrada de seus titulares e não é usada como nome, logotipo ou funcionalidade do m2m.
- Protocolos: Apache MINA SSHD (SFTP) e Apache Commons Net (FTP/FTPS), ambos Apache 2.0. Criptografia de backups: Google Tink. Compressão: zstd.
- As licenças das dependências estão em [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) e na tela **Sobre**.
- O m2m é software proprietário da Vovolinux (ver [`LICENSE`](LICENSE)).
