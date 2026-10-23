Criar um quiz para instalação limpa.
O quiz deve ter campos para informar tudo que pode ser personalizado e configurado para a primeira execução do m2m.
Somente personalizações e configurações essenciais à primeira execução devem estar no quiz.
O quiz deve ter steps por escopo. Exemplo: Containers > Usuários > rede > máquinas, etc,
Deve ser possível pular steps do quiz que não impeçam a primeira execução.
Para ações que o quiz em angular não conseguir resolver sem intervenção do usuário, mostrar o comando que o usuário deverá colar no tty, com explicação do que faz, para que serve e ao final o comando.

Ao final do quiz, deve ter um botão para Iniciar m2m, que fará a configuração e subirá os containers necessários.
Ao final da instalação, exibir opção para fazer backup da instalação limpa atual executada, em um arquivo .json, para que possa ser reaproveitada em uma futura nova instalação, mas aproveitando o que foi inserido pelo usuário no quiz.
Ao iniciar um quiz de instalação, perguntar se tem arquivo com dados para importação do quiz. Caso exista, passar por todos os steps do quiz, lendo o arquivo, preenchendo com as informações dele e aguardando o usuário confirmar ou alterar as informações importadas.
Criar um quiz para desinstalação total do m2m.