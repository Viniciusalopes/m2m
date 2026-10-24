# m2m - Machine To Machine

[![CI](https://github.com/viniciusalopes/m2m/actions/workflows/ci.yml/badge.svg)](https://github.com/viniciusalopes/m2m/actions/workflows/ci.yml) [![Release](https://img.shields.io/github/v/release/viniciusalopes/m2m)](https://github.com/viniciusalopes/m2m/releases/latest) [![Licença: MIT](https://img.shields.io/badge/licen%C3%A7a-MIT-blue)](LICENSE)

Aplicação web para **backup, restauração, sincronização e transferência de arquivos entre máquinas** (LOCAL, SFTP, FTP e FTPS), com fila de jobs, verificação de igualdade, criptografia opcional dos backups e segundo fator no login.

O m2m roda no seu próprio computador ou servidor, em containers Docker, e é aberto no navegador. Ele enxerga os arquivos da própria máquina (a máquina **LOCAL**) e os de outras máquinas por SFTP, FTP ou FTPS.

- **Backend:** Java 25, Spring Boot 4, Apache MINA SSHD, Apache Commons Net, Tink, PostgreSQL 18, RabbitMQ 4.
- **Frontend:** Angular 22 e Angular Material 3, tudo em português do Brasil.
- **Entrega:** Docker Compose (banco, fila, migrações, back e front com HTTPS, logs no Grafana), com um instalador que configura a primeira instalação por um quiz.
- **Licença:** [MIT](LICENSE). Código aberto, feito por Vovolinux.

> **Segurança em primeiro lugar.** O m2m pode apagar e sobrescrever arquivos em várias máquinas. Leia a seção [Primeiro uso com segurança](#primeiro-uso-com-segurança) antes da primeira restauração ou sincronização numa máquina real.

---

## Sumário

1. [Instalação limpa (Ubuntu e derivados)](#instalação-limpa-ubuntu-e-derivados)
   - [Caminho A: executável pronto](#caminho-a-executável-pronto)
   - [Caminho B: a partir do clone](#caminho-b-a-partir-do-clone)
   - [O que o quiz pergunta](#o-que-o-quiz-pergunta)
   - [Depois da instalação](#depois-da-instalação)
   - [Atualizar](#atualizar)
   - [Desinstalar](#desinstalar)
2. [Instalar com o Claude](#instalar-com-o-claude)
3. [Configuração (`.env`)](#configuração-env)
4. [Subir e derrubar (`m2m-up` / `m2m-down`)](#subir-e-derrubar-m2m-up--m2m-down)
   - [Certificado HTTPS](#certificado-https)
   - [Logs no Grafana](#logs-no-grafana)
5. [Primeiro acesso e chave mestra](#primeiro-acesso-e-chave-mestra)
6. [E-mail (SMTP) pela tela](#e-mail-smtp-pela-tela)
7. [Cadastro de máquinas](#cadastro-de-máquinas)
8. [Matriz de fidelidade](#matriz-de-fidelidade)
9. [Máquinas atrás de NAT](#máquinas-atrás-de-nat)
10. [Perfis, backups e retenção](#perfis-backups-e-retenção)
11. [Primeiro uso com segurança](#primeiro-uso-com-segurança)
12. [Extrair um backup manualmente](#extrair-um-backup-manualmente)
13. [Backups criptografados e o comando `decrypt`](#backups-criptografados-e-o-comando-decrypt)
14. [Recuperação do próprio m2m](#recuperação-do-próprio-m2m)
15. [Segundo fator (TOTP)](#segundo-fator-totp)
16. [Desenvolvimento e testes](#desenvolvimento-e-testes)
17. [Apoie o projeto](#apoie-o-projeto)
18. [Créditos e licenças](#créditos-e-licenças)

---

## Instalação limpa (Ubuntu e derivados)

A primeira instalação é feita por um **quiz no navegador**: você responde algumas perguntas e o instalador gera a configuração, prepara o certificado HTTPS, constrói e sobe o m2m. **Não é preciso editar nenhum arquivo.**

**Requisitos da máquina:**

- **Ubuntu 22.04, 24.04 ou 26.04**, ou um derivado deles (Linux Mint, Pop!_OS, Zorin OS etc.), em **amd64** (x86_64).
- **4 GiB de memória** e **16 GiB livres em disco**: o build das imagens usa cerca de 1,6 GiB de RAM no pico e a instalação ocupa uns 5 GiB.
- **Internet** durante a instalação (pacotes, imagens-base, Maven e npm).
- Portas **80** e **443** livres (dá para trocar no quiz).

Há dois caminhos até o quiz. Os dois chegam ao mesmo resultado.

| | Caminho A: executável pronto | Caminho B: a partir do clone |
|---|---|---|
| Precisa de Docker antes? | Não: o quiz mostra os comandos para instalar | Sim |
| Precisa de `git`? | Não | Sim |
| Onde o m2m fica | No diretório escolhido no quiz (padrão `~/m2m`) | No próprio clone |
| Para quem | Quem só quer usar o m2m | Quem quer acompanhar o código ou contribuir |

Prefere que o Claude conduza a instalação? Veja [Instalar com o Claude](#instalar-com-o-claude).

### Caminho A: executável pronto

1. **Baixe o instalador e o `SHA256SUMS`** da [última release](https://github.com/viniciusalopes/m2m/releases/latest). O instalador é um executável único, sem dependências: ele leva dentro o código da versão e o quiz.

2. **Confira o download e dê permissão de execução:**

   ```bash
   sha256sum -c SHA256SUMS --ignore-missing
   chmod +x m2m-instalador-*-linux-amd64
   ```

   O `sha256sum` precisa responder `OK`: assim você sabe que o arquivo não foi corrompido nem trocado.

3. **Execute como o seu usuário, sem `sudo`:**

   ```bash
   ./m2m-instalador-*-linux-amd64
   ```

   Com interface gráfica, o navegador abre no quiz. Numa sessão SSH (servidor sem tela), o terminal mostra o comando do túnel: rode-o **no seu computador** e abra no seu navegador o endereço mostrado.

   ```bash
   ssh -L 8765:127.0.0.1:8765 usuario@servidor
   ```

   O quiz só escuta em `127.0.0.1`, e o endereço tem um token de uso único: não o compartilhe. Opções: `--porta` troca a porta do quiz e `--sem-navegador` não abre o navegador.

4. **Responda o quiz** (veja [O que o quiz pergunta](#o-que-o-quiz-pergunta)). O instalador nunca roda `sudo` nem pede a senha do sistema: o que exige administrador (instalar o Docker, incluir o seu usuário no grupo `docker`, confiar no certificado do m2m) aparece como um **comando para colar no terminal**, com o que ele faz e para que serve. Depois de rodar o comando, clique em **Verificar de novo**.

   Ao entrar no grupo `docker`, o quiz pede para abrir o instalador de novo numa sessão com o grupo valendo. O comando aparece pronto, no formato `sg docker -c ./m2m-instalador-...`.

5. **Clique em Iniciar m2m** na Revisão e acompanhe o progresso. Na primeira vez, o build leva alguns minutos. Se um passo falhar (por exemplo, por falta de internet), **Tentar de novo** retoma a partir dele.

### Caminho B: a partir do clone

1. **Instale o Docker Engine e o Compose v2** pelo repositório oficial do Docker. O comando abaixo vale para o Ubuntu e os derivados (usa o codinome do Ubuntu de base):

   ```bash
   sudo apt-get update
   sudo apt-get install -y ca-certificates curl git
   sudo install -m 0755 -d /etc/apt/keyrings
   sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
   sudo chmod a+r /etc/apt/keyrings/docker.asc
   . /etc/os-release
   echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${UBUNTU_CODENAME:-$VERSION_CODENAME} stable" \
     | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
   sudo apt-get update
   sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
   ```

2. **Permita que o seu usuário use o Docker** e abra uma sessão nova (ou saia e entre de novo):

   ```bash
   sudo usermod -aG docker "$USER"
   newgrp docker
   ```

   Pertencer ao grupo `docker` equivale a ter poder de administrador na máquina; é o que o m2m exige para subir os containers sem `sudo`.

3. **Clone o repositório e rode o instalador:**

   ```bash
   git clone https://github.com/viniciusalopes/m2m.git
   cd m2m
   ./instalar.sh
   ```

   O `instalar.sh` confere o Docker, compila o instalador em containers (não precisa de Go nem de Node na máquina) e abre o quiz **em modo clone**: o m2m é instalado no próprio clone, que recebe o `.env`. Na primeira vez ele baixa as imagens `node` e `golang`, o que leva alguns minutos. Ele aceita as mesmas opções do executável, como `./instalar.sh --sem-navegador`.

4. **Responda o quiz** e clique em **Iniciar m2m**, como no caminho A.

### O que o quiz pergunta

As etapas pedem só o essencial para a primeira execução; e-mail, segundo fator, máquinas remotas e perfis ficam para a interface do m2m. As etapas com padrões seguros podem ser puladas (**Pular** usa os padrões); Pré-requisitos e Usuários, não.

| Etapa | O que pergunta | Padrão |
|---|---|---|
| **Início** | Instalar ou desinstalar; se há um arquivo `.json` de outra instalação para importar | Instalar, sem arquivo |
| **Pré-requisitos** | Nada: confere sistema, Docker, Compose, Buildx, `openssl`, `certutil`, internet, portas, memória e disco, e mostra o comando para cada pendência | — |
| **Containers** | Diretório de instalação, portas HTTP e HTTPS, fuso horário | `~/m2m` (no modo clone, o clone), 80, 443, fuso do sistema |
| **Segurança** | Chave mestra (gerada, ou a sua para ler backups cifrados de outra instalação); confirmação de que você a guardou | Gerada |
| **Usuários** | Usuário, e-mail e senha do administrador (mínimo de 12 caracteres) | `admin` |
| **Rede** | Endereço pelo qual o m2m será aberto e o certificado: CA local do m2m, certificado próprio ou autoassinado | IP da máquina, CA local |
| **Máquinas** | Acesso LOCAL ligado ou não, nome da máquina LOCAL, diretório do host montado, raízes permitidas | Ligado, hostname, `/home`, `/home` |
| **Logs** | Porta do Grafana, acesso só local ou pela rede, retenção | 3000, só local, 7 dias |
| **Revisão** | Resumo de tudo, com os segredos mascarados; **Iniciar m2m** | — |

Senhas internas (banco, fila e Grafana) são geradas pelo instalador.

### Depois da instalação

A tela final mostra:

- o **endereço** do m2m e o usuário administrador;
- o **comando para o sistema confiar no certificado** do m2m (o Chrome já confia, se o `certutil` estiver instalado);
- a opção de criar os **atalhos `m2m-up` e `m2m-down`** no `~/.bashrc` e no `~/.zshrc`;
- o download do **arquivo da instalação** (`.json`): as respostas vão legíveis e a chave mestra e as senhas vão cifradas (Argon2id + AES-256-GCM) com uma senha de no mínimo 12 caracteres. Com ele, uma reinstalação abre o quiz já preenchido, inclusive com a mesma chave mestra.

**Guarde a chave mestra** (a etapa Segurança permite copiá-la e baixá-la) e o arquivo da instalação num cofre de senhas. Veja [Primeiro acesso e chave mestra](#primeiro-acesso-e-chave-mestra).

O registro da instalação fica em `~/.config/m2m/instalacao.json`, sem segredos. O instalador só faz instalação limpa: se já houver m2m na máquina, ele mostra o que encontrou e oferece a desinstalação.

### Atualizar

- **Instalação pelo clone (recomendado para quem quer atualizar com frequência):** `git pull` dentro do clone e `m2m-up`. As imagens são reconstruídas e os dados (volumes) ficam.
- **Instalação pelo executável:** o instalador só faz instalação limpa, então a atualização é uma reinstalação com o **arquivo de reinstalação**:
  1. com o m2m no ar, rode o executável da nova versão, escolha **Desinstalar o m2m**, baixe o arquivo de reinstalação e desinstale com todos os itens marcados. Os destinos de backup nunca são apagados;
  2. rode-o de novo, escolha **Instalar** e importe o arquivo: as respostas e a **mesma chave mestra** voltam preenchidas;
  3. ao fim da instalação, o instalador restaura a configuração do m2m (máquinas, credenciais, perfis com as chaves de criptografia, agendamentos e e-mail) e reindexa os diretórios de backup: tudo volta como estava, com os backups na lista.

### Desinstalar

Rode o instalador (o executável ou `./instalar.sh`) e escolha **Desinstalar o m2m**. O quiz:

- localiza a instalação pelo registro, pelo diretório padrão (`~/m2m`) ou pelos containers do projeto Compose `m2m`;
- lista os **destinos de backup** dos perfis, consultados no banco só para leitura. Eles **nunca são removidos**;
- deixa escolher o que remover: containers e rede (sempre), imagens `m2m-*:dev`, volumes de dados, diretório de instalação, certificados e CA local e atalhos de shell. Numa instalação pelo clone, o item do diretório vira dois: o `.env` gerado (marcado) e **apagar o clone** (desmarcado, porque apaga também alterações locais no código). Ao marcar os volumes, lembra que o banco tem o catálogo dos backups e as chaves dos perfis, guardados no arquivo de reinstalação. Volumes mantidos impedem uma nova instalação até serem removidos;
- oferece o download do **arquivo de reinstalação** antes de remover: as respostas do quiz, a chave mestra e as senhas e, com o m2m no ar, a configuração do m2m do administrador (máquinas e credenciais, identidades de servidor, chave SSH, perfis com as chaves de criptografia, agendamentos e e-mail), tudo cifrado com a senha que você escolher. Importado numa nova instalação, ele restaura a configuração e reindexa os backups que ficaram no disco. Com o m2m fora do ar, o arquivo leva só a parte da instalação, e o quiz avisa;
- exige digitar `DESINSTALAR` para confirmar e mostra o resultado de cada item. A falha de um item não impede os demais.

Só são removidos recursos do projeto Compose `m2m`; outros projetos Docker não são tocados. O Docker e os pacotes do sistema ficam. O comando para tirar a CA da confiança do sistema e os comandos opcionais (imagens públicas, cache de build e o próprio Docker) aparecem no fim.

## Instalar com o Claude

Se você usa o [Claude Code](https://docs.anthropic.com/en/docs/claude-code/overview) (o Claude no terminal), ele pode conduzir a instalação por você: confere a máquina, obtém o instalador, abre o quiz e acompanha até o m2m ficar no ar. **Basta copiar o prompt abaixo e colá-lo numa janela do Claude Code aberta na máquina onde o m2m vai rodar.**

**Requisitos:** o Claude Code instalado e logado nessa máquina, Ubuntu ou derivado em amd64, e internet.

**Prompt para copiar:**

```text
Instale o m2m nesta máquina. Baixe o roteiro de instalação com
curl -fsSL https://raw.githubusercontent.com/viniciusalopes/m2m/main/docs/instalacao-com-claude.md
leia-o inteiro e siga-o do início ao fim, respeitando todas as regras dele. Fale comigo em português.
```

**O que o Claude faz**, seguindo o roteiro [`docs/instalacao-com-claude.md`](docs/instalacao-com-claude.md):

1. Confere se a máquina é Ubuntu ou derivado em amd64 e se já existe um m2m instalado. Se não for compatível, ou se já houver instalação, ele avisa e não altera nada.
2. Obtém o instalador: o executável da última release, com o checksum conferido, ou, se você abriu o Claude dentro de um clone do repositório com o Docker funcionando, o `./instalar.sh`.
3. Inicia o instalador e mostra o endereço do quiz (ou o comando do túnel SSH, numa sessão sem tela).
4. **Você responde o quiz no navegador.** As escolhas são suas; o Claude não preenche nada por você.
5. Quando o quiz mostrar um comando com `sudo`, o Claude pede que **você** o rode **em outro terminal**: ele nunca executa `sudo` nem pede a sua senha. Se o quiz pedir para reabrir o instalador com o grupo `docker`, ele faz isso por você.
6. Acompanha a instalação até o m2m responder e resume o endereço e os próximos passos.

O Claude não lê nem mostra o `.env`, não edita arquivos de configuração e não envia nada da instalação para fora da máquina. Como sempre no Claude Code, cada comando que ele quiser rodar passa pela sua aprovação.

**Já clonou o repositório?** Abra o Claude Code dentro do clone e use o comando do projeto `/instalar-m2m`, que segue o mesmo roteiro a partir do clone.

## Configuração (`.env`)

O quiz gera o `.env` no diretório de instalação, com modo `0600` (legível só pelo dono), a partir do modelo [`.env.example`](.env.example). **Não é preciso editá-lo para instalar.** A tabela abaixo serve de referência para ajustes depois: edite o `.env` e rode `m2m-up`. **O `.env` nunca vai para o git.**

| Variável | Para que serve |
|---|---|
| `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `ADMIN_EMAIL` | Administrador criado no primeiro start. |
| `APP_SECRET_KEY` | **Chave mestra**: cifra senhas de máquinas, chaves privadas, SMTP e as chaves de criptografia dos backups. Gere com `openssl rand -base64 32`. |
| `M2M_LOCAL_ENABLED`, `HOST_HOME_BIND`, `HOST_ROOTS` | Máquina **LOCAL** (o próprio host). |
| `M2M_LOCAL_NAME` | Nome da máquina LOCAL criada no primeiro start, que é a única máquina inicial. Em branco, ela se chama `LOCAL`. |
| `PUBLIC_URL`, `HTTP_PORT`, `HTTPS_PORT`, `TLS_CERT_PATH`, `TLS_KEY_PATH` | Endereço e certificado. O `m2m-up` preenche `TLS_CERT_PATH`/`TLS_KEY_PATH` com o certificado gerado por `scripts/gerar-certificado-tls.sh` (veja [Certificado HTTPS](#certificado-https)). Em branco, o m2m gera um certificado autoassinado no primeiro start. |
| `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_PORT`, `GRAFANA_BIND`, `LOKI_RETENTION` | Logs no Grafana (veja [Logs no Grafana](#logs-no-grafana)). A senha é obrigatória; porta padrão 3000, `GRAFANA_BIND` padrão `127.0.0.1` e retenção padrão `168h` (7 dias). |
| `TRANSFER_CONCURRENCY` | Transferências simultâneas na tela de transferência (padrão 2). |
| `POSTGRES_*`, `RABBITMQ_*` | Banco e fila (rede interna do Compose). |

### Com acesso LOCAL (o m2m enxerga o disco da própria máquina)

```dotenv
M2M_LOCAL_ENABLED=true
HOST_HOME_BIND=/home
HOST_ROOTS=/home
```

O m2m enxerga `/home` do host como a máquina **LOCAL**, e os backups vão por padrão para `/home/backup`.

### Sem acesso LOCAL (VPS)

```dotenv
M2M_LOCAL_ENABLED=false
HOST_HOME_BIND=
```

Nada do host é montado. Armazene os backups em outra máquina cadastrada (SFTP/FTP/FTPS) e use certificado válido em `TLS_CERT_PATH` / `TLS_KEY_PATH`. No quiz, isso corresponde a desligar o acesso LOCAL na etapa Máquinas.

## Subir e derrubar (`m2m-up` / `m2m-down`)

O quiz oferece criar dois atalhos no `~/.bashrc` e no `~/.zshrc`, entre os marcadores `# >>> m2m >>>` e `# <<< m2m <<<`:

- `m2m-up` roda `scripts/m2m-up.sh`: renova o certificado se preciso e sobe `m2m-db`, `m2m-queue`, `m2m-db-migrate`, `m2m-back`, `m2m-front` e os logs, reconstruindo as imagens que mudaram.
- `m2m-down` remove containers e rede e **preserva os volumes** (banco e fila).
- Sem os atalhos, rode no diretório de instalação `sh scripts/m2m-up.sh` e `docker compose --env-file .env down`.
- Os containers têm nome fixo: `m2m-db-postgresql`, `m2m-queue-rabbitmq`, `m2m-db-pg-migrate`, `m2m-back-java` e `m2m-front-angular`. Os comandos `docker compose` continuam usando o nome do serviço (`m2m-db`, `m2m-back` etc.).
- Nunca use `down -v` sem querer apagar os dados: ele remove os volumes.

A interface fica no endereço escolhido no quiz (`PUBLIC_URL`). Com o certificado da CA local, o navegador mostra o site como seguro. A documentação da API está em `/api/docs` (exige login).

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
- **Remover uma máquina nunca apaga backups.** Os backups feitos a partir dela continuam na lista e restauráveis em outras máquinas. Os guardados nela continuam no disco dela e aparecem como **Armazenamento indisponível** (sem restaurar, baixar nem excluir); eles voltam a ficar disponíveis quando uma máquina com o mesmo nome é cadastrada de novo, ou pela reindexação. A confirmação da remoção mostra quantos são.
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
tar --zstd -xpf Perfil_LOCAL_20261022-131227.tar.zst -C restaurado
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
  --in  /dados/Perfil_LOCAL_20261022-131227.tar.zst.enc \
  --out /dados/Perfil_LOCAL_20261022-131227.tar.zst
```

Em seguida, extraia com `tar --zstd -xpf`, como acima. Chave errada ou arquivo adulterado terminam com erro e **nada é gravado pela metade**.

## Recuperação do próprio m2m

Quatro peças permitem reconstruir o m2m do zero:

1. **Chave mestra:** o `.env` (`APP_SECRET_KEY`) guardado no cofre. Sem ela, credenciais e backups criptografados são irrecuperáveis.
2. **Backup diário do banco:** o job `DB_BACKUP` roda todo dia (padrão 01:30, em **Configurações → Manutenção**) e grava `m2m-db_AAAAMMDD-HHMMSS.sql.zst` (pg_dump compactado) no armazenamento configurado (padrão `/home/backup/m2m-db/`), mantendo os 7 mais recentes. Para restaurar num banco novo:

   ```bash
   zstd -dc m2m-db_20261022-013000.sql.zst | docker exec -i m2m-db-postgresql psql -U m2m -d m2m
   ```

3. **Exportar/importar configuração:** em **Configurações → Exportar e importar configuração**, um arquivo cifrado por senha (Argon2id + AES-256-GCM) com máquinas (e credenciais), identidades de servidor, chave SSH, perfis com as chaves de criptografia, mapeamentos, agendamentos e SMTP. A mesma exportação vai no arquivo de reinstalação do instalador. A importação mostra uma **prévia** (o que será criado e substituído) antes de aplicar. Máquinas e perfis são casados pelo nome.
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

# frontend (app do m2m e quiz do instalador)
cd front && npm ci && npm run lint && npx ng test --watch=false && npx ng build

# instalador (módulo Go em installer/, sem Go no host)
docker run --rm -v "$PWD/installer:/src" -w /src golang:1.27.3-bookworm go test ./...

# e2e (Playwright): o stack principal precisa estar no ar; com E2E_SERVERS=1 o próprio e2e sobe os
# servidores de teste (SFTP, OpenSSH, FTP, FTPS e SMTP em container) e os derruba ao terminar
docker compose up -d --build
cd e2e && npm ci && E2E_SERVERS=1 npx playwright test
```

Variáveis do e2e (todas opcionais):

| Variável | Para que serve | Padrão |
|---|---|---|
| `E2E_SANDBOX` | Diretório do host onde os testes gravam (só as pastas `e2e-*` são apagadas); precisa ficar abaixo de `/home` | `~/m2m-sandbox` |
| `E2E_MAQUINA_LOCAL` | Nome da máquina LOCAL da instalação testada | `LOCAL` |
| `E2E_MAQUINA_REAL_NOME`, `E2E_MAQUINA_REAL_ENDERECO` | Máquina real cadastrada, só lida (por exemplo `servidor` e `192.168.0.10:22`); sem elas, o teste é pulado | — |

Regras do projeto:

- **Nenhum teste que grave ou exclua arquivos roda em máquinas reais.** Só servidores SFTP/FTP/FTPS em container e o diretório sandbox (`E2E_SANDBOX`). Máquinas reais só são lidas (teste de conexão, navegação, backup com origem nelas, verificação).
- O CI (GitHub Actions, em `.github/workflows/`) roda lint, testes do back, do front e do instalador, verificação de licenças e build das imagens a cada push e pull request; o e2e roda manualmente; as tags `vX.Y.Z` publicam a release com o instalador.
- `./scripts/gerar-third-party-notices.sh` gera `THIRD-PARTY-NOTICES.md` e falha se alguma dependência tiver licença fora da lista permitida.
- O esquema do banco vive em `db/migrations/` (Flyway, aplicado pelo serviço `m2m-db-migrate`).

### Gerar o instalador

```bash
scripts/gerar-instalador.sh                         # exige a árvore sem alterações não commitadas
scripts/gerar-instalador.sh --permitir-alteracoes   # inclui as alterações de arquivos já versionados
```

O script embute o código do commit (`git archive`) e o quiz (`front/projects/instalador`), roda os testes do instalador e grava em `dist/instalador/` o executável `m2m-instalador-<versão>-linux-amd64` e o `SHA256SUMS`. Não exige Go nem Node no host: constrói em containers `node:24` e `golang`. Com `--sem-docker`, usa o `node` e o `go` do `PATH`. O `./instalar.sh` usa este mesmo script e abre o resultado em modo clone.

Nas tags `vX.Y.Z`, o workflow de release gera o executável e publica a release com ele e o `SHA256SUMS`.

Para desenvolver o instalador: módulo Go em `installer/` (código em `internal/`) e o quiz em `front/projects/instalador` (`cd front && npx ng test instalador --watch=false`).

## Apoie o projeto

O m2m é gratuito e de código aberto. Se ele é útil para você, considere apoiar o desenvolvimento com uma doação:

- **Pix** (chave aleatória): `38743a63-f228-4426-927e-e439ca6fa767`
- **PayPal** (cartão de crédito ou saldo PayPal, em reais): [doar pelo PayPal](https://www.paypal.com/donate/?business=RELWG6ZBS4D4U&no_recurring=0&item_name=O+m2m+%C3%A9+gratuito+e+de+c%C3%B3digo+aberto.+Sua+doa%C3%A7%C3%A3o+ajuda+a+manter+backups%2C+sincroniza%C3%A7%C3%A3o+e+transfer%C3%AAncias+evoluindo.&currency_code=BRL)

As mesmas formas de doação aparecem na tela **Sobre** do m2m. Obrigado!

## Créditos e licenças

- A **tela de transferência** é **inspirada no FileZilla Client** (Tim Kosse e colaboradores, GPL-3.0-or-later; <https://filezilla-project.org>). O m2m usou o FileZilla apenas como referência funcional e de experiência de uso: **não contém código do FileZilla**. "FileZilla" é marca registrada de seus titulares e não é usada como nome, logotipo ou funcionalidade do m2m.
- Protocolos: Apache MINA SSHD (SFTP) e Apache Commons Net (FTP/FTPS), ambos Apache 2.0. Criptografia de backups: Google Tink. Compressão: zstd.
- As licenças das dependências estão em [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) e na tela **Sobre**.
- O m2m é software livre, distribuído sob a **licença MIT** (ver [`LICENSE`](LICENSE)). Copyright (c) 2026 Vovolinux.
