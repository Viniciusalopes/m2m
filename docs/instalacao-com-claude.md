# Roteiro de instalação do m2m para o Claude

Este arquivo é lido pelo Claude Code quando alguém pede para instalar o m2m (pelo prompt do README ou pelo comando `/instalar-m2m`). Ele descreve **o que você, Claude, deve fazer** para acompanhar a instalação do m2m numa máquina Ubuntu (ou derivada). Fale com a pessoa em português do Brasil, em frases curtas, dizendo o que vai fazer antes de cada passo.

A instalação tem duas partes:

1. o **`instalar.sh`**, que a **pessoa** roda no terminal dela: ele confere os mínimos de memória e disco, prepara a máquina (pacotes, Docker, grupo `docker`), pedindo a senha do `sudo`, e abre o quiz;
2. o **quiz no navegador**, que a pessoa responde para configurar o m2m.

O seu papel é conferir a máquina, obter e conferir o script, explicar o que ele faz, ajudar com o endereço do quiz e acompanhar até o m2m no ar.

## Regras (valem do início ao fim)

1. **Nunca execute `sudo`, `su`, `pkexec` nem o `instalar.sh`, e nunca peça a senha da pessoa.** O script pede a senha do `sudo` no terminal dela; por isso é ela quem o roda, num terminal dela.
2. **Não crie, edite, leia nem exiba o `.env`** nem o arquivo da instalação (`.json`). Quem gera o `.env` é o quiz. A única exceção está no passo 6: ler as linhas `PUBLIC_URL=` e `HTTPS_PORT=`, e nada além delas.
3. **Não responda o quiz pela pessoa** nem escolha valores por ela. Se ela pedir uma sugestão, explique as opções e os padrões do quiz.
4. **Não envie nada da instalação para fora da máquina**: nada de colar logs, endereços com token, chaves ou senhas em serviços externos.
5. **Não instale por cima de uma instalação existente** e **nunca rode `docker compose down -v`**, `docker volume rm` ou `docker system prune`. Para remover o m2m, a pessoa roda o mesmo script e escolhe Desinstalar no quiz.
6. O endereço do quiz tem um **token de uso único**: se a pessoa colar o endereço aqui, não o repita nem o grave em arquivo.
7. Se algo falhar fora do previsto, pare, mostre o erro e pergunte como seguir. Não tente contornar as regras acima.

## Passo 1: conferir o sistema

Rode e interprete:

```bash
cat /etc/os-release
uname -m
```

- Continue só se `uname -m` for `x86_64` e se `ID=ubuntu` ou se `ID_LIKE` contiver `ubuntu` (derivados como Linux Mint, Pop!_OS e Zorin OS). Num derivado, o Ubuntu de base é o `UBUNTU_CODENAME`.
- Se não for compatível, explique que o m2m é para Ubuntu e derivados em amd64, aponte a seção "Instalação limpa" do README (<https://github.com/Viniciusalopes/m2m#instalação-limpa-ubuntu-e-derivados>) e **encerre sem alterar nada**.

## Passo 2: procurar uma instalação existente

```bash
ls ~/.config/m2m/instalacao.json 2>/dev/null
docker ps -a --filter label=com.docker.compose.project=m2m --format '{{.Names}}\t{{.Status}}' 2>/dev/null
```

Se aparecer o registro ou algum container, diga o que encontrou e pergunte se a pessoa quer **desinstalar antes** (rodando o script e escolhendo Desinstalar o m2m no quiz) ou parar aqui. Não siga com uma instalação nova sem a resposta. O `docker ps` pode falhar por permissão numa máquina ainda não preparada: isso não é um problema.

## Passo 3: obter o `instalar.sh`

Confira se a pasta atual é um clone do m2m:

```bash
test -f instalar.sh && test -f docker-compose.yml && test -d .git && echo clone
```

- **Clone:** o script é o `./instalar.sh` da pasta atual. Não baixe nada.
- **Fora de um clone:** baixe o script e o `SHA256SUMS` da última release numa pasta própria (não use `~/m2m`, que é o diretório padrão da instalação) e confira:

  ```bash
  mkdir -p ~/m2m-instalador && cd ~/m2m-instalador
  curl -fsSLO https://github.com/Viniciusalopes/m2m/releases/latest/download/instalar.sh
  curl -fsSLO https://github.com/Viniciusalopes/m2m/releases/latest/download/SHA256SUMS
  sha256sum -c SHA256SUMS --ignore-missing
  ```

  - Se o `curl` não existir, peça que a pessoa rode `sudo apt-get update && sudo apt-get install -y curl` no terminal dela e tente de novo.
  - Se o `sha256sum` não responder `instalar.sh: OK`, **pare**: apague os arquivos baixados e avise que o download não confere.

## Passo 4: explicar o script e pedir que a pessoa o rode

Resuma o que o script faz, antes de ela rodá-lo:

- confere o sistema, a memória (4 GiB) e o disco (16 GiB livres); abaixo disso, para sem alterar nada, e cabe à pessoa aumentar a memória ou liberar espaço (ou aumentar o disco) antes de rodar de novo;
- mostra a lista do que vai mudar e pede uma confirmação;
- com `sudo` (a senha é pedida no terminal dela), instala só o que falta: o Docker (ou, se já houver o Docker do Ubuntu, só o Compose e o Buildx), `openssl`, `certutil`, `curl` e `git`, e inclui o usuário no grupo `docker`. Nunca roda `apt-get upgrade`. Se faltar disco e a raiz for um volume LVM com espaço sobrando, oferece estendê-lo, com confirmação;
- abre o quiz com o grupo `docker` já valendo;
- quando o instalador for encerrado, pergunta se instala a CA local do m2m na confiança do sistema.

Peça que ela rode, **num terminal dela** (outra janela ou outra sessão SSH), na pasta do script:

- fora de um clone: `cd ~/m2m-instalador && sh instalar.sh`
- no clone: `./instalar.sh`

Se ela acessa esta máquina por SSH (sem tela aqui) a partir de um computador Linux ou macOS, recomende o caminho mais simples: **no computador dela**, `curl -fsSLO https://github.com/Viniciusalopes/m2m/releases/latest/download/instalar-remoto.sh && sh instalar-remoto.sh usuario@maquina`. Esse comando faz tudo numa conexão só: roda o `instalar.sh` aqui, abre o quiz sozinho no navegador dela e, ao encerrar, importa a CA nos navegadores dela. Você continua acompanhando daqui (passo 6).

Sem o `instalar-remoto.sh`, o quiz abre no navegador do computador dela por um túnel que vem **na própria sessão SSH em que o script roda**: essa sessão precisa ser aberta com `ssh -L 8765:127.0.0.1:8765 usuario@maquina` (ou, de uma vez, `ssh -t -L 8765:127.0.0.1:8765 usuario@maquina 'cd ~/m2m-instalador && sh instalar.sh'`). O script confere isso antes de mudar qualquer coisa. Um segundo terminal só para o túnel não é preciso.

E que avise quando o script mostrar o endereço do quiz, ou se ele parar com alguma mensagem. Se o script parar, peça que ela cole a mensagem aqui e explique o que ela significa; quem decide e roda de novo é a pessoa.

## Passo 5: ajudar com o quiz

O script mostra uma destas situações no terminal dela:

- **"Abrindo o quiz no navegador"**: o navegador já abriu.
- **"Abra no navegador do seu computador (o endereço passa pelo túnel desta sessão SSH)"**: a pessoa está numa sessão SSH aberta com o túnel. Ela abre o endereço `http://127.0.0.1:8765/...` no navegador do computador dela. Se o navegador disser "conexão recusada", a sessão não tem o `-L`: ela deve encerrar o script (Ctrl+C), sair e entrar de novo com o `ssh -L` do passo 4.
- **"a porta 8765, a do túnel, já está em uso"**: há outro instalador aberto (por exemplo, de uma tentativa anterior). Peça que ela o encerre e rode o script de novo.

Explique que, a partir daqui, **ela responde o quiz** e que você acompanha. Resuma as etapas: Containers, Segurança, Usuários, Rede, Máquinas, Logs e Revisão. Avise sobre duas delas:

- **Segurança:** guardar a chave mestra num cofre de senhas antes de avançar;
- **Revisão:** o botão **Iniciar m2m** começa a instalação.

Se a tela Início disser que a máquina não está pronta, o quiz foi aberto sem o script ou algo falhou na preparação: peça que ela encerre o instalador e rode o script de novo.

## Passo 6: acompanhar a instalação

Depois que a pessoa clicar em **Iniciar m2m**, acompanhe a cada 30 segundos:

```bash
sg docker -c "docker ps -a --filter label=com.docker.compose.project=m2m --format '{{.Names}}\t{{.Status}}'"
```

(O `sg docker` é preciso porque a sua sessão foi aberta antes de a pessoa entrar no grupo `docker`. Se o `docker ps` já funcionar sem ele, use direto.)

O build da primeira vez leva de 5 a 15 minutos. A instalação termina quando os containers `m2m-db-postgresql`, `m2m-queue-rabbitmq`, `m2m-back-java`, `m2m-front-angular` e `m2m-logs-grafana` estão `healthy` e o quiz mostra **"O m2m está no ar"**. Para conferir por conta própria, leia só o endereço e a porta do `.env` e teste o `/healthz`:

```bash
DIR="$(grep -o '"diretorio": *"[^"]*"' ~/.config/m2m/instalacao.json | cut -d'"' -f4)"
grep -E '^(PUBLIC_URL|HTTPS_PORT)=' "$DIR/.env"
curl -sk "https://127.0.0.1:<HTTPS_PORT>/healthz"
```

Se um passo do quiz falhar, peça que a pessoa leia o log do passo no quiz e clique em **Tentar de novo**. Ajude a interpretar o erro (por exemplo, falta de internet ou de disco), sem mexer nos arquivos da instalação.

## Passo 7: concluir e resumir

Lembre a pessoa de clicar em **Encerrar o instalador** (ou dar Ctrl+C no terminal do script): o script então pergunta se instala a CA local do m2m na confiança do sistema e mostra o endereço. Depois, resuma:

- o **endereço** (`PUBLIC_URL`) e o usuário administrador que ela escolheu no quiz;
- que vale **baixar o arquivo da instalação** (`.json`) na tela final e guardá-lo com a chave mestra num cofre de senhas, e que, para reinstalar ou atualizar depois, a desinstalação pelo quiz oferece o **arquivo de reinstalação**, que leva também a configuração do m2m e devolve os backups à lista na reinstalação;
- que a tela final oferece os **atalhos `m2m-up` e `m2m-down`**;
- onde ler mais: o README do projeto (<https://github.com/Viniciusalopes/m2m>), em especial "Primeiro uso com segurança".

Fora de um clone, a pasta `~/m2m-instalador` pode ficar: o mesmo script serve para desinstalar depois.
