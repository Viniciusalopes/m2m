# Roteiro de instalação do m2m para o Claude

Este arquivo é lido pelo Claude Code quando alguém pede para instalar o m2m (pelo prompt do README ou pelo comando `/instalar-m2m`). Ele descreve **o que você, Claude, deve fazer** para levar uma máquina Ubuntu (ou derivada) até o m2m no ar, usando o instalador oficial com quiz. Fale com a pessoa em português do Brasil, em frases curtas, dizendo o que vai fazer antes de cada passo.

O m2m é configurado por um **quiz no navegador**. Quem responde o quiz é a pessoa. O seu papel é preparar a máquina, obter e iniciar o instalador, mostrar o endereço do quiz, ajudar com os comandos que o quiz pedir e acompanhar até o fim.

## Regras (valem do início ao fim)

1. **Nunca execute `sudo`, `su` ou `pkexec`, e nunca peça a senha da pessoa.** Quando algo exigir administrador, mostre o comando e peça que a pessoa o rode **em outro terminal** e avise quando terminar.
2. **Não crie, edite, leia nem exiba o `.env`** nem o arquivo da instalação (`.json`). Quem gera o `.env` é o quiz. A única exceção está no passo 7: ler as linhas `PUBLIC_URL=` e `HTTPS_PORT=`, e nada além delas.
3. **Não responda o quiz pela pessoa** nem escolha valores por ela. Se ela pedir uma sugestão, explique as opções e os padrões do quiz.
4. **Não envie nada da instalação para fora da máquina**: nada de colar logs, endereços com token, chaves ou senhas em serviços externos.
5. **Não instale por cima de uma instalação existente** e **nunca rode `docker compose down -v`**, `docker volume rm` ou `docker system prune`. Para remover o m2m, use a opção Desinstalar do próprio quiz.
6. O endereço do quiz tem um **token de uso único**: mostre-o só para a pessoa, nesta conversa.
7. Se algo falhar fora do previsto, pare, mostre o erro e pergunte como seguir. Não tente contornar as regras acima.

## Passo 1: conferir o sistema

Rode e interprete:

```bash
cat /etc/os-release
uname -m
```

- Continue só se `uname -m` for `x86_64` e se `ID=ubuntu` ou se `ID_LIKE` contiver `ubuntu` (derivados como Linux Mint, Pop!_OS e Zorin OS). Num derivado, o Ubuntu de base é o `UBUNTU_CODENAME`.
- Se não for compatível, explique que o instalador é para Ubuntu e derivados em amd64, aponte a seção "Instalação limpa" do README (<https://github.com/Viniciusalopes/m2m#instalação-limpa-ubuntu-e-derivados>) e **encerre sem alterar nada**.

## Passo 2: procurar uma instalação existente

```bash
ls ~/.config/m2m/instalacao.json 2>/dev/null
docker ps -a --filter label=com.docker.compose.project=m2m --format '{{.Names}}\t{{.Status}}' 2>/dev/null
```

Se aparecer o registro ou algum container, diga o que encontrou e pergunte se a pessoa quer **desinstalar antes** (pelo quiz, na opção Desinstalar o m2m) ou parar aqui. Não siga com uma instalação nova sem a resposta.

## Passo 3: escolher o caminho

Confira se a pasta atual é um clone do m2m e se o Docker funciona para o usuário:

```bash
test -f instalar.sh && test -f docker-compose.yml && test -d .git && echo clone
docker info >/dev/null 2>&1 && echo docker-ok
```

- **Clone e `docker-ok`:** use o caminho B, com o `./instalar.sh` (passo 4B).
- **Qualquer outro caso:** use o caminho A, com o executável pronto (passo 4A). Ele não precisa de Docker antes: o próprio quiz mostra os comandos para instalá-lo.

## Passo 4A: executável pronto

Baixe o instalador e o `SHA256SUMS` da última release numa pasta própria (não use `~/m2m`, que é o diretório padrão da instalação):

```bash
mkdir -p ~/m2m-instalador && cd ~/m2m-instalador
curl -fsSL https://api.github.com/repos/Viniciusalopes/m2m/releases/latest \
  | grep -o '"browser_download_url": *"[^"]*"' | cut -d'"' -f4 \
  | grep -E 'linux-amd64$|SHA256SUMS$' \
  | while read -r url; do curl -fsSLO "$url"; done
sha256sum -c SHA256SUMS --ignore-missing
chmod +x m2m-instalador-*-linux-amd64
```

- Se o `curl` não existir, peça que a pessoa rode em outro terminal `sudo apt-get update && sudo apt-get install -y curl` e tente de novo.
- Se o `sha256sum` não responder `OK`, **pare**: apague os arquivos baixados e avise que o download não confere.

Inicie o instalador em segundo plano, guardando a saída num log:

```bash
cd ~/m2m-instalador
nohup ./m2m-instalador-*-linux-amd64 > instalador.log 2>&1 &
sleep 2; cat instalador.log
```

## Passo 4B: a partir do clone

Na pasta do clone:

```bash
nohup ./instalar.sh > instalador.log 2>&1 &
```

A primeira execução compila o instalador em containers e pode levar alguns minutos. Acompanhe com `tail -n 5 instalador.log` a cada 30 segundos, até aparecer o endereço do quiz. Se o log terminar com uma mensagem de erro do `instalar.sh` (por exemplo, sem permissão no Docker), mostre a mensagem e os comandos que ela sugere, e peça que a pessoa os rode em outro terminal.

## Passo 5: mostrar o quiz para a pessoa

O log mostra uma destas situações:

- **"Abrindo o quiz no navegador"**: o navegador da pessoa já abriu. Mostre também o endereço do log, caso ela precise abri-lo de novo.
- **"Esta sessão não tem interface gráfica"**: a pessoa está numa sessão SSH. Mostre o comando `ssh -L ...` do log, que ela deve rodar **no computador dela**, e o endereço `http://127.0.0.1:...` para abrir no navegador dela depois do túnel.

Explique que, a partir daqui, **ela responde o quiz** e que você acompanha. Resuma as etapas: Pré-requisitos, Containers, Segurança, Usuários, Rede, Máquinas, Logs e Revisão. Avise sobre duas delas:

- **Segurança:** guardar a chave mestra num cofre de senhas antes de avançar;
- **Revisão:** o botão **Iniciar m2m** começa a instalação.

## Passo 6: ajudar durante o quiz

- **Comandos com `sudo` no quiz (etapa Pré-requisitos):** se a pessoa colar o comando aqui, explique o que ele faz e peça que ela o rode **em outro terminal**; depois, que clique em **Verificar de novo** no quiz.
- **"Abrir o instalador de novo com o grupo docker":** depois que a pessoa rodar o `usermod`, encerre o instalador e abra-o de novo com o grupo aplicado, mostrando o novo endereço (o token muda):

  ```bash
  # o [m] evita que o pkill case com o próprio comando que o executa
  pkill -INT -f '[m]2m-instalador-.*-linux-amd64'; sleep 1
  cd ~/m2m-instalador && nohup sg docker -c "$(ls ./m2m-instalador-*-linux-amd64)" > instalador.log 2>&1 &
  sleep 2; cat instalador.log
  ```

  No caminho B, rode de novo `nohup sg docker -c ./instalar.sh > instalador.log 2>&1 &` na pasta do clone.
- **Dúvidas sobre as etapas:** explique as opções. O README tem a tabela "O que o quiz pergunta".

## Passo 7: acompanhar a instalação

Depois que a pessoa clicar em **Iniciar m2m**, acompanhe a cada 30 segundos:

```bash
docker ps -a --filter label=com.docker.compose.project=m2m --format '{{.Names}}\t{{.Status}}'
```

O build da primeira vez leva de 5 a 15 minutos. A instalação termina quando os containers `m2m-db-postgresql`, `m2m-queue-rabbitmq`, `m2m-back-java`, `m2m-front-angular` e `m2m-logs-grafana` estão `healthy` e o quiz mostra **"O m2m está no ar"**. Para conferir por conta própria, leia só o endereço e a porta do `.env` e teste o `/healthz`:

```bash
DIR="$(grep -o '"diretorio": *"[^"]*"' ~/.config/m2m/instalacao.json | cut -d'"' -f4)"
grep -E '^(PUBLIC_URL|HTTPS_PORT)=' "$DIR/.env"
curl -sk "https://127.0.0.1:<HTTPS_PORT>/healthz"
```

Se um passo do quiz falhar, peça que a pessoa leia o log do passo no quiz e clique em **Tentar de novo**. Ajude a interpretar o erro (por exemplo, falta de internet ou de disco), sem mexer nos arquivos da instalação.

## Passo 8: resumo final

Quando o m2m estiver no ar, resuma para a pessoa:

- o **endereço** (`PUBLIC_URL`) e o usuário administrador que ela escolheu no quiz;
- que ela deve **rodar o comando para confiar no certificado** mostrado na tela final do quiz (em outro terminal), se quiser que curl e outros navegadores confiem no m2m;
- que vale **baixar o arquivo da instalação** (`.json`) na tela final e guardá-lo com a chave mestra num cofre de senhas, e que, para reinstalar ou atualizar depois, a desinstalação pelo quiz oferece o **arquivo de reinstalação**, que leva também a configuração do m2m e devolve os backups à lista na reinstalação;
- que a tela final oferece os **atalhos `m2m-up` e `m2m-down`**;
- que o botão **Encerrar o instalador** fecha o quiz (ou ele se encerra sozinho depois de um tempo sem uso);
- onde ler mais: o README do projeto (<https://github.com/Viniciusalopes/m2m>), em especial "Primeiro uso com segurança".

No caminho A, a pasta `~/m2m-instalador` pode ser apagada depois: o executável só é preciso de novo para desinstalar, e pode ser baixado outra vez.
