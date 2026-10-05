# HeadQLink · Manual do utilizador

**Idiomas:** [Español](MANUAL.md) · **Português** · [English](MANUAL.en.md)

Manual para condutores da versão **0.2** do HeadQLink (fork [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink)).
Os nomes de botões e menus aparecem «entre aspas», tal como a aplicação os mostra em **Português (Portugal)**.

> [!NOTE]
> **Português do Brasil.** Se escolher o idioma «Português (Brasil)», alguns nomes mudam:
>
> | Portugal | Brasil |
> |---|---|
> | «Ligar» / «Desligar» | «Conectar» / «Desconectar» |
> | «Definições» | «Configurações» |
> | «Hotspot do telemóvel» | «Ponto de acesso do celular» |
> | «Estado» · «Em direto» | «Status» · «Ao vivo» |
> | «Exportar registo» | «Exportar log» |
> | «Info. da aplicação» · «Forçar paragem» | «Info do app» · «Forçar parada» |
> | «Modo de programador» | «Modo de desenvolvedor» |
> | «A iniciar o Auto…» / «A fechar o Auto…» | «Iniciando o Auto…» / «Fechando o Auto…» |
> | «Esperar pelo carro» | «Esperar o carro» |
> | Transferências | Downloads |

> [!WARNING]
> Faça toda a configuração **com o carro estacionado**. Não mexa no telemóvel enquanto conduz, e não veja vídeos, TV,
> páginas web nem jogos no ecrã do carro se não estiver parado.

**Resumo rápido**

1. Instale o APK a partir das [Releases do GitHub](https://github.com/CharlysEV/headqlink/releases).
2. Abra o HeadQLink e siga o assistente de 3 passos até a «Verificação» dizer «Tudo pronto».
3. No carro: ative o hotspot do telemóvel, abra a aplicação de espelhamento no ecrã do carro e toque em «Ligar».
4. Quando o Android Auto aparecer, bloqueie o telemóvel e guarde-o.

## Índice

1. [O que é e o que precisa](#1-o-que-é-e-o-que-precisa)
2. [Instalação](#2-instalação)
3. [Primeiro arranque](#3-primeiro-arranque)
4. [Utilização no carro](#4-utilização-no-carro)
5. [Definições úteis](#5-definições-úteis)
6. [Calor e bateria](#6-calor-e-bateria)
7. [Resolução de problemas](#7-resolução-de-problemas)
8. [Exportar o registo para pedir ajuda](#8-exportar-o-registo-para-pedir-ajuda)
9. [Segurança, privacidade e aviso legal](#9-segurança-privacidade-e-aviso-legal)

---

## 1. O que é e o que precisa

O HeadQLink leva o **Android Auto** ao ecrã do **Leapmotor C10** através da ligação de espelhamento que o carro já
traz (a da aplicação QDLink/SSPLink). Não é preciso nenhum adaptador nem cabo: o telemóvel e o carro comunicam por
Wi-Fi, e o telemóvel pode ficar **bloqueado e com o ecrã desligado**. Não precisa de root.

Há dois modos à escolha:

| Modo | O que vê no carro |
|---|---|
| «Auto» | Android Auto em ecrã inteiro: mapas, música e mensagens. |
| «Auto estendido» (recomendado pela aplicação) | O mesmo, com um painel próprio à esquerda com mais informação do carro e da viagem (rota, condução, viagens, instrumentos, eficiência) e mais funcionalidades (fotos, vídeos, web, TV, rádio, jogos). |

**O que precisa**

| | |
|---|---|
| Carro | Leapmotor C10 com a aplicação de espelhamento (projeção do telemóvel) no ecrã. |
| Telemóvel | Android com o **Android Auto** instalado. Recomenda-se **Android 10 ou superior**; escolher o idioma da aplicação precisa do Android 13. |
| Testado em | Samsung Galaxy **S25 Ultra** com **Android 16** e Android Auto 17.7. Outros telemóveis podem funcionar, mas não foram testados. |
| Android Auto 17.4 ou superior | É preciso ativar uma vez o **modo de programador** do Android Auto e o serviço de **acessibilidade** do HeadQLink. A aplicação guia-o (secção 3). Com versões anteriores não é preciso. |
| Internet (opcional) | Só para funções do «Auto estendido», como rotas, meteorologia ou rádio. |

> [!NOTE]
> **Estado dos testes.** O HeadQLink é um projeto pessoal e experimental. Numa viagem real com um C10, um S25 Ultra e a
> ligação «Hotspot do telemóvel», o Android Auto funcionou em duas sessões de cerca de 16 minutos cada. Nesse teste o
> telemóvel aqueceu demasiado, por isso esta versão acrescenta o perfil de imagem «Carro», o ecrã do telemóvel
> desligado e a adaptação ao calor (secção 6). A ligação Wi-Fi Direct com o motor QDAuto ainda não foi testada no carro.

---

## 2. Instalação

O HeadQLink não está no Google Play: instala-se com um ficheiro APK.

### Instalar

1. No telemóvel, abra [github.com/CharlysEV/headqlink/releases](https://github.com/CharlysEV/headqlink/releases).
2. Na versão mais recente, abra **Assets** e toque no ficheiro `.apk` (algo como `com.headqlink.app_0.2….apk`).
3. Abra o ficheiro transferido (a partir da notificação ou em Os meus ficheiros › Transferências).
4. O Android avisa que não pode instalar aplicações desconhecidas dessa fonte. Toque em **Definições**, ative
   **Permitir desta fonte** e volte atrás.
5. Toque em **Instalar**.

<!-- captura: aviso do Android «instalar aplicações desconhecidas» -->

> [!TIP]
> **Aviso do Play Protect.** Como a aplicação não vem do Google Play, o Play Protect pode avisar que é desconhecida ou
> bloqueá-la. Se confia neste projeto, toque em **Mais detalhes › Instalar mesmo assim**.
>
> **Samsung:** se o **Bloqueador automático** estiver ativo (Definições › Segurança e privacidade), não deixa instalar
> APK. Desative-o para instalar e volte a ativá-lo depois, se quiser.

### Atualizar

1. Transfira o APK novo da mesma página de Releases.
2. Instale-o por cima do que tem: as suas definições mantêm-se.
3. Abra o HeadQLink e veja a «Verificação»: **o Android costuma desativar a acessibilidade ao atualizar**, e é preciso
   voltar a ativá-la.

> [!IMPORTANT]
> Só é possível atualizar com um APK **assinado com a mesma chave**, ou seja, das Releases deste fork. Se o Android
> disser que a aplicação não foi instalada ou que entra em conflito com outra, é porque tem uma versão com outra
> assinatura (por exemplo, o HeadQLink original de ryazrm, que usa o mesmo identificador, ou uma que compilou). Desinstale-a
> primeiro; perderá as definições dela.

### Desinstalar

1. Se não a voltar a usar: nas definições do Android Auto pode sair do modo de programador (menu ⋮).
2. Definições do Android › Aplicações › HeadQLink › **Desinstalar**.
3. Os registos exportados ficam em Transferências/HeadQLink: apague-os à mão se não os quiser.

---

## 3. Primeiro arranque

Da primeira vez que abre o HeadQLink aparece um assistente de 3 passos. Faça-o com o carro estacionado; melhor ainda,
dentro do carro e com a aplicação de espelhamento aberta no ecrã dele.

### Passo 1 de 3: boas-vindas

Ecrã «O seu telemóvel, no ecrã do C10», com o que é preciso «Antes de começar». Toque em «Começar».

### Passo 2 de 3: modo e ligação

<!-- captura: passo 2 do assistente, modo e ligação -->

**«O que quer ver no carro?»** Escolha «Auto estendido» ou «Auto» (tabela da secção 1).

> [!NOTE]
> **Privacidade no «Auto estendido».** Os painéis usam a localização do telemóvel, se der autorização, e serviços
> abertos da internet: OpenStreetMap e OSRM para a rota e os carregadores, Open-Meteo para a meteorologia e o vento, e
> radio-browser.info para a rádio. Para fazer as contas, a esses serviços chega a sua posição ou o seu destino. Se não
> quiser, escolha «Auto» ou não conceda a autorização «Fotos, vídeos e localização».

**«Ligação ao carro»**

| Opção | Como funciona | Recomendação |
|---|---|---|
| «Hotspot do telemóvel» | O carro liga-se ao hotspot do seu telemóvel. | **Recomendada**: é a que foi testada no C10, também com o ecrã desligado. |
| «Wi-Fi Direct» | O carro cria a rede e o telemóvel liga-se a ela. O hotspot do telemóvel tem de estar desligado. | É a ligação do HeadQLink original. Com o motor QDAuto ainda não foi testada no carro. |

> [!TIP]
> A aplicação vem com «Hotspot do telemóvel» selecionado, com a etiqueta «Recomendado». Se preferir «Wi-Fi Direct»,
> toque nele antes de tocar em «Continuar».

Toque em «Continuar». Pode alterar o modo e a ligação quando quiser com o botão «Alterar» do ecrã principal.

### Passo 3 de 3: «Preparar o Auto estendido» (ou «Preparar o Auto»)

Aqui está a **Verificação**: uma lista com tudo o que a sua configuração precisa. É a mesma que verá depois no menu ⚙ ›
«Verificação».

<!-- captura: ecrã Verificação -->

**Como a ler**

- No topo aparece «Tudo pronto» (verde) ou «Faltam N coisas»: a vermelho se faltar algo obrigatório e a âmbar se só
  faltar algo recomendado.
- Cada linha tem um ícone (✓ feito, ! falta, ✕ erro, ? desconhecido, … a verificar, i sugestão), a importância
  («Obrigatório», «Recomendado», «Opcional» ou «Sugestão») e um **botão que o leva à definição exata**.
- Atualiza-se sozinha quando volta ao HeadQLink.

**Linha a linha.** Só aparecem as que importam para o seu modo e a sua ligação.

| Linha | O que significa | O que tocar |
|---|---|---|
| «Android Auto» | Tem de estar instalado e ativado. Mostra a versão. | «Instalar» (Play Store) ou «Info. da aplicação» se estiver desativado. |
| «Acessibilidade do HeadQLink» (Obrigatório com Android Auto 17.4 ou superior) | O HeadQLink usa-a para iniciar o Android Auto sem que o veja e para receber os toques do carro. Nas Definições do Android chama-se **«HeadQLink tátil»**. | «Ativar» e ligue «HeadQLink tátil». Veja o aviso abaixo. |
| «Modo de programador do Android Auto» (Obrigatório com 17.4 ou superior) | O Android Auto só aceita um «ecrã de carro» dentro do telemóvel através do modo de programador. Ativa-se uma vez. | «Abrir AA» e siga o guia abaixo. «Verificar» confirma-o (precisa da acessibilidade ativada). |
| «Notificações» (Recomendado) | Para ver o estado da ligação e os avisos. | «Permitir» (ou «Abrir» se as bloqueou). |
| «Sem restrições de bateria» (Recomendado) | Para que o Android não feche o HeadQLink com o ecrã desligado nem bloqueie a ligação automática. | «Permitir» e aceite o aviso do Android. |
| «Samsung: aplicações nunca suspensas» (Sugestão, só Samsung) | A Samsung fecha aplicações em segundo plano por conta própria. A aplicação não consegue verificar isto. | «Abrir» e escolha «Sem restrições». Além disso: Definições › Bateria › Limites de utilização em segundo plano › Aplicações nunca suspensas › adicione o HeadQLink. |
| «Gestor de energia (…)» (Sugestão, outras marcas) | Xiaomi, Honor, Oppo e outras têm a sua própria poupança de bateria. | «Abrir» e permita o arranque automático ou a atividade em segundo plano. |
| «Hotspot do telemóvel» (Obrigatório com «Hotspot do telemóvel») | O hotspot tem de estar ativo. Se estiver bem, diz «Hotspot ativo (…)». | «Abrir» e ative-o. |
| «Banda de 5 GHz» (Sugestão) | A aplicação não consegue ler isto. Em 5 GHz funciona melhor, porque em 2,4 GHz o rádio é partilhado com o Bluetooth do carro. | «Abrir»: escolha 5 GHz e desative o desligar automático do hotspot. |
| «Dispositivos Wi-Fi próximos» ou «Localização» (Obrigatório com «Wi-Fi Direct») | Autorização para entrar na rede Wi-Fi Direct do carro («Localização» no Android 12 ou anterior). | «Permitir» (ou «Abrir» se a recusou). |
| «Wi-Fi ligado» (Obrigatório com «Wi-Fi Direct») | O Wi-Fi Direct precisa do Wi-Fi ligado, mesmo sem estar ligado a nenhuma rede. | «Abrir». |
| «Hotspot desligado» (Obrigatório com «Wi-Fi Direct») | O Wi-Fi Direct não funciona com o hotspot ativo. | «Abrir» e desligue-o. |
| «QDLink» (Recomendado) | A aplicação oficial QDLink está instalada no telemóvel. Se estiver aberta, ocupa a porta 18463 e o carro não encontra o HeadQLink. | «Info. da aplicação» › «Forçar paragem» antes de ligar. |
| «QDLink» ou «Porta 18463» a vermelho («Ocupada») | O QDLink (ou outra aplicação) está aberto neste momento. | «Forçar paragem». |
| «Bluetooth (dispositivos próximos)» (Obrigatório se ativar a ligação automática) | Para reconhecer o Bluetooth do carro e ligar sozinho. | «Permitir». |
| «Apresentar sobre outras aplicações» (Opcional) | Para abrir o Android Auto com o telemóvel em segundo plano. | «Permitir». |
| «Fotos, vídeos e localização» (Opcional, «Auto estendido») | Para a galeria e os painéis de condução no carro. | «Permitir». |

> [!IMPORTANT]
> **«Definição restrita» ao ativar a acessibilidade.** No Android 13 ou superior, às aplicações instaladas a partir de
> um APK o Android não deixa ativar a acessibilidade à primeira. Se ao ativar «HeadQLink tátil» aparecer «Definição
> restrita»:
>
> 1. Toque no segundo botão da linha, «Info. da aplicação» (ou vá a Definições › Aplicações › HeadQLink).
> 2. Toque no menu ⋮ (canto superior direito) › **«Permitir definições restritas»** e confirme. Esta opção aparece
>    depois de ter tentado ativá-la uma vez.
> 3. Volte, toque em «Ativar» e ligue «HeadQLink tátil».
>
> Se a linha disser «Ativada mas sem funcionar (o Android parou-a)», desative-a e volte a ativá-la.

**Como ativar o modo de programador do Android Auto** (o mesmo guia que a aplicação mostra):

1. Toque em «Abrir AA». Se não aparecer, toque primeiro em «Verificar».
2. Desloque-se até ao fim das definições do Android Auto.
3. Toque **10 vezes em «Versão»** e aceite o aviso.
4. Volte ao HeadQLink: a verificação é automática.

<!-- captura: definições do Android Auto com «Versão» no fim -->

Com a ligação «Hotspot do telemóvel», por baixo da lista aparece também «Como ligar o carro ao hotspot» (secção 4).

Toque em **«Concluir»**. Se faltar algo obrigatório, o aviso «Ainda falta» diz-lhe o quê: pode «Voltar» ou «Concluir
mesmo assim» e completar mais tarde.

> [!NOTE]
> Ao concluir o assistente, e sempre que abre o HeadQLink já configurado, **a aplicação começa a ligar sozinha** (é como
> tocar em «Ligar») e aguarda o carro até 5 minutos (ou «Esperar pelo carro», se for mais). Com o Android Auto 17.4 ou
> superior verá por um momento a camada
> «A iniciar o Auto…»: não toque em nada. Se não estiver no carro, toque em «Desligar».

---

## 4. Utilização no carro

### O ecrã principal

<!-- captura: ecrã principal do HeadQLink -->

- No topo, o modo e a ligação (por exemplo «Auto estendido · Hotspot do telemóvel») com o botão «Alterar», e o menu ⚙.
- O interruptor «Ligar ao detetar o Bluetooth do carro».
- O cartão «Estado». No topo, o painel «Em direto»: dois números grandes, os **fps** (imagens por segundo) e os
  **Mbps** (Mbit/s) do vídeo que chega ao carro, cada um com uma barra que enche conforme o que o seu perfil de imagem
  dá (com «Carro», 30 fps). O ponto de «Em direto» pisca enquanto chega vídeo; sem vídeo, os números mostram «—». Por
  baixo, uma linha para cada parte da ligação:

| Linha | O que lhe diz |
|---|---|
| «Carro» | «Desligado», «A procurar…», «Encontrado, a ligar…», «Ligado» (com o tamanho do ecrã do carro) ou «A voltar a ligar… (Android Auto em espera)». |
| «Rede» | «Hotspot ativo (…)», «Hotspot desligado: ative-o», «Wi-Fi Direct: a procurar o carro» ou «Wi-Fi Direct: no grupo do carro (…)». Se o hotspot estiver desligado, tocar na linha abre as definições dele. |
| «Imagem» | O vídeo que está a ser enviado ao carro. |
| «Auto» | O estado do Android Auto e, se não arrancar, porquê (por exemplo «Não arranca: desbloqueie o telemóvel»). |
| «Requisitos» | «Tudo pronto» ou «Faltam N coisas». Tocar abre a «Verificação». |

- Um texto de ajuda e o botão grande «Ligar» / «Desligar».

### Passo a passo com «Hotspot do telemóvel» (recomendado)

1. **Ative o hotspot do telemóvel**, de preferência em 5 GHz e sem desativação automática.
2. **Só da primeira vez:** no carro, vá a Definições › Wi-Fi e escolha o hotspot do seu telemóvel. Depois o carro
   liga-se sozinho.
3. **Abra a aplicação de espelhamento** no ecrã do carro.
4. **Abra o HeadQLink** com o telemóvel desbloqueado. Começa a ligar sozinho; se o tinha aberto e desligado, toque em
   «Ligar». Com o Android Auto 17.4 ou superior verá por um momento «A iniciar o Auto…».
5. A linha «Carro» passa a «Encontrado, a ligar…» e depois a «Ligado». **O Android Auto aparece no ecrã do carro.**
6. **Bloqueie o telemóvel** e deixe-o num sítio fresco. A imagem continua a chegar ao carro com o ecrã desligado.

> [!CAUTION]
> Com o hotspot ativo, **o carro pode usar os seus dados móveis.**

**Com «Wi-Fi Direct»:** mantenha o hotspot desligado e o Wi-Fi ligado, abra a aplicação de espelhamento no carro e
depois o HeadQLink. A linha «Rede» passa de «Wi-Fi Direct: a procurar o carro» a «Wi-Fi Direct: no grupo do carro (…)».

> [!IMPORTANT]
> Se o QDLink estiver instalado no telemóvel, **force a paragem dele antes de ligar**. Se estiver aberto, ocupa a porta
> de que o HeadQLink precisa e o carro não o encontra.

### O que vê e como se usa

- **«Auto»:** Android Auto em ecrã inteiro.
- **«Auto estendido»:** um painel à esquerda, do lado do condutor, com «Auto», «Carro» (separadores Rota, Condução,
  Viagens, Instrumentos e Eficiência), «Fotos», «Vídeos», «Web», «TV», «Rádio», «Jogos» e «Definições». Com o Android
  Auto no ecrã, o painel esconde-se sozinho ao fim de alguns segundos; toque na margem esquerda para que volte. Fotos,
  vídeos, web, TV e jogos são **só para quando o carro está parado**.
- **Ecrã tátil:** funciona como no Android Auto, com **multitoque** até 3 dedos (por exemplo, juntar os dedos para fazer
  zoom no mapa).
- **Botões do volante:** reproduzir/pausa, seguinte e anterior funcionam através do **Bluetooth do carro**, sem
  emparelhar mais nada.
- **Som:** a música e as indicações saem do telemóvel pelo **Bluetooth do carro**, por isso o telemóvel tem de estar
  ligado a ele como de costume. As chamadas vão pelo mãos-livres do carro.

### Cortes e religação automática

- Se a ligação ao carro cair por um momento, a linha «Carro» diz «A voltar a ligar… (Android Auto em espera)» e a
  imagem volta sozinha, normalmente em menos de um segundo, **sem reiniciar o Android Auto** (com o motor QDAuto, o
  predefinido).
- Se o carro **desaparecer durante mais tempo** (por exemplo, porque o desliga numa paragem curta), ao fim de 30
  segundos o HeadQLink deixa de enviar imagem e põe o **Android Auto em pausa**, mas **continua à escuta do carro**
  durante «Esperar pelo carro» (**5 minutos** por predefinição; muda-se em «Definições de imagem» › «Avançado»). A
  notificação diz «A aguardar o carro · Android Auto em pausa». Se o carro voltar nesse tempo, a imagem regressa **de
  imediato**, sem desbloquear o telemóvel nem tocar em nada.
- Passado «Esperar pelo carro», o HeadQLink fecha tudo: o Android Auto, o servidor dele e a ligação. Para voltar a
  usá-lo, toque em «Ligar», ou deixe isso à ligação automática por Bluetooth.
- Se tocar em «Ligar» e em **5 minutos** (ou «Esperar pelo carro», se for mais) não aparecer nenhum carro, o HeadQLink
  para sozinho.

### No fim da viagem

- Desligue o carro (o HeadQLink fecha-se sozinho quando termina «Esperar pelo carro», 5 minutos por predefinição) ou
  toque em «Desligar» para o fechar já.
- Se o telemóvel estava bloqueado quando se fechou, verá a notificação «Auto em espera até desbloquear o telemóvel
  (depois fecha-se sozinho)». **Desbloqueie o telemóvel uma vez** para que feche de vez: verá durante uns segundos «A
  fechar o Auto…» (secção 9). Se voltar ao carro antes de o desbloquear e usar a ligação automática por Bluetooth, o
  Android Auto regressa de imediato, sem passar por «A fechar o Auto…».
- «A fechar o Auto…» e «A iniciar o Auto…» duram uns segundos. Se algo os prender, desaparecem sozinhos ao fim de
  15 s; se o servidor do Android Auto puder ter ficado ligado, verá «O servidor do Android Auto continua ligado · Toque
  para o desligar»: toque nela com o telemóvel desbloqueado.
- Desligue o hotspot se não precisar dele.

### Ligação automática: «Ligar ao detetar o Bluetooth do carro»

Com este interruptor, o HeadQLink começa a aguardar o carro assim que o telemóvel se liga ao Bluetooth dele, sem abrir
a aplicação.

1. Ative-o no ecrã principal.
2. No aviso «Ligação automática», escreva um texto contido no **nome Bluetooth do carro** (vem `Leapmotor_BT`; não
   distingue maiúsculas) e toque em «Guardar».
3. Conceda a autorização de Bluetooth e retire as restrições de bateria quando lhe for pedido. Caso contrário, o
   Android não o deixa arrancar em segundo plano.

Depois:

- Se o Bluetooth se desligar sem ter chegado a ligar ao carro, o HeadQLink para. Se se desligar depois de uma sessão
  (desliga o carro), continua a aguardar o carro até terminar «Esperar pelo carro».
- Se o Android Auto ainda estava em pausa (volta antes de terminar «Esperar pelo carro», ou ficou em espera com o
  telemóvel bloqueado), a imagem regressa de imediato, **sem desbloquear o telemóvel**.
- Se o Android não o deixar arrancar em segundo plano, verá a notificação «Carro detetado · Toque para ligar o
  HeadQLink». Toque nela.
- Se for preciso iniciar o Android Auto e o telemóvel estiver bloqueado, verá a notificação **«Desbloqueie o telemóvel
  para iniciar o Android Auto»**. Desbloqueie-o (com o carro parado): o HeadQLink inicia-o sozinho, sem abrir a
  aplicação (verá por um momento «A iniciar o Auto…»).

---

## 5. Definições úteis

Está tudo no menu ⚙ do ecrã principal: «Verificação», «Definições de imagem», «Lista de TV» e «Lista de rádio» (só no
«Auto estendido»), «Idioma» e «Diagnóstico».

### «Definições de imagem»

<!-- captura: Definições de imagem -->

**«Perfil de imagem».** A aplicação marca com «Recomendado» o que melhor se adapta ao seu telemóvel. Em telemóveis
potentes é «Carro».

| Perfil | O que faz |
|---|---|
| «Automático (…)» | Segue o recomendado para este telemóvel. **É a melhor opção se não sabe qual escolher.** |
| «Carro» (recomendado) | O que o carro pede (no C10, resolução completa a 30 fps e cerca de 5 Mbit/s), sem forçar o telemóvel. É o que menos aquece e menos bateria gasta. |
| «Muito alto» | Resolução completa a 60 fps com a latência mínima. Gasta mais. O C10 não mostra mais de 30 fps. |
| «Alto» | Resolução completa a 60 fps sem forçar o telemóvel: menos bateria e calor, um pouco mais de atraso. |
| «Médio» | 720p a 45 fps constantes, recodificado no telemóvel. |
| «Básico» | 720p a 30 fps, sem recodificação: o mais leve para o telemóvel. No «Auto estendido» funciona como «Médio». |
| «Muito baixo» | 720p a 20 fps e taxa de bits baixa: o mínimo para a bateria e a ligação. |

Se estiver ligado, ao guardar outro perfil o carro e o Android Auto voltam a ligar-se sozinhos em poucos segundos.

> [!TIP]
> Se numa versão anterior escolheu «Muito alto» à mão, fica assim. Mude para «Automático» ou «Carro»: é o que evita que o
> telemóvel aqueça (secção 6).

**Outras opções de «Definições de imagem»:**

- «Ocultar o painel lateral após alguns segundos (Auto estendido)»: ativada por predefinição.
- «Manter o ecrã do telemóvel ligado (mais calor e bateria; caso contrário, desliga-se como sempre e a projeção
  continua)»: desativada por predefinição. **Deixe-a desativada**, salvo se precisar dela.
- «Avançado ▾» › «Motor do protocolo»:
  - «QDAuto (recomendado)»: o motor predefinido, com religação sem reiniciar o Android Auto e adaptação ao calor.
  - «headqlink original»: o motor do HeadQLink original, como **plano B** se o QDAuto lhe der problemas.

  A mudança «Aplica-se na próxima ligação»: toque em «Desligar» e depois em «Ligar».
- «Avançado ▾» › «Esperar pelo carro»: «1 min», «5 min (recomendado)» ou «15 min». É o tempo que o HeadQLink continua
  à escuta do carro, com o Android Auto em pausa, depois de o perder (secção 4). Mais tempo = regressa de imediato
  depois de paragens mais longas; menos = o servidor do Android Auto desliga-se mais cedo. Vale já para a próxima
  paragem.
- O resto de «Avançado» (fps, kbps, largura, altura, perfil H.264, otimizações de latência, limitador) é para testes.
  Deixe-o vazio ou como está.

### Definições a partir do carro («Auto estendido»)

O botão «Definições» do painel do carro permite mudar o perfil de imagem («Aplicar · volta a ligar em poucos
segundos»), ocultar o painel sozinho e as otimizações de latência, e ver a ligação e o motor em uso. Use-o só com o
carro parado.

### «Idioma»

«Idioma» (Android 13 ou superior): «Idioma do sistema», «Español», «English», «Português (Portugal)» ou «Português
(Brasil)». A interface do carro muda na próxima ligação. No Android 12 ou anterior, a aplicação usa o idioma do
sistema.

### «Lista de TV» e «Lista de rádio» («Auto estendido»)

Cole o URL de uma lista M3U ou toque em «Escolher ficheiro». Sem lista de rádio, o carro mostra as estações populares.

### «Diagnóstico»

- «Exportar registo»: secção 8.
- «Teste sem Android Auto (padrão)»: mostra no carro uma imagem de teste em vez do Android Auto. Serve para saber se o
  que falha é a ligação ou o Android Auto. **Desative-o no fim.**
- «Opções de teste (QDAuto)»: para testar o motor. Normalmente, deixe-as como estão. Entre elas está «Esperar que o carro
  volte, em segundos (5-600; vazio = 30)».

---

## 6. Calor e bateria

Projetar vídeo, Wi-Fi e GPS ao mesmo tempo aquece o telemóvel. No primeiro teste longo, com o perfil «Muito alto» a
60 fps, o S25 Ultra chegou em 15 minutos ao estado térmico «grave» do Android e depois ao «crítico». Por isso esta
versão traz três mudanças:

1. **Perfil «Carro»:** envia 30 fps e a taxa de bits que o C10 pede (cerca de 5 Mbit/s). É exatamente o que o carro
   mostra: mais não se vê melhor e aquece mais.
2. **Ecrã do telemóvel desligado:** o ecrã apaga-se como de costume e a projeção continua. A opção de manter o ecrã
   ligado vem desativada.
3. **Adaptação térmica automática** (Android 10 ou superior e motor QDAuto; com «Básico» no modo «Auto» não atua,
   porque esse perfil não recodifica):

| Estado térmico do Android | O que o HeadQLink faz |
|---|---|
| Normal ou ligeiro | Nada: os fps e a taxa de bits da sessão. |
| Moderado | Desce para **24 fps** e 70 % da taxa de bits. |
| Grave ou pior | Desce para **20 fps** e no máximo **3 Mbit/s**. |

Sobe de nível de imediato. Volta ao normal quando o telemóvel está **60 segundos seguidos** mais fresco. Fá-lo sem
cortar a sessão nem reiniciar o Android Auto, e sem avisos: só notará a imagem um pouco menos fluida. Fica anotado no
registo.

**Conselhos**

- Use o perfil «Automático» ou «Carro».
- **Bloqueie o telemóvel** assim que o Android Auto aparecer.
- Evite o **sol direto**: não o deixe no tablier nem encostado ao para-brisas.
- Prefira um **suporte ventilado ou com ventoinha**, por exemplo na grelha do ar condicionado, a um compartimento
  fechado. Se o carregador sem fios o aquecer muito, carregue-o por cabo.
- Enquanto projeta, **não grave vídeo em 4K**, não jogue nem use outras aplicações pesadas no telemóvel.
- Tire uma capa grossa no verão.
- Em viagens longas, leve o telemóvel a carregar; se possível, por cabo.

---

## 7. Resolução de problemas

Comece sempre pelo menu ⚙ › «Verificação»: cada linha a vermelho tem o seu botão.

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| O carro não aparece («A procurar…» o tempo todo) | O carro não está no hotspot do telemóvel, o hotspot desligou-se sozinho, ou a aplicação de espelhamento não está aberta no carro. Com Wi-Fi Direct: o hotspot ativo ou o Wi-Fi desligado. | Veja a linha «Rede»: deve dizer «Hotspot ativo (…)». Ligue o carro ao hotspot (Definições › Wi-Fi do carro) e abra a aplicação de espelhamento. Desative o desligar automático do hotspot. Ao fim de 5 minutos sem carro o HeadQLink para: toque em «Ligar» outra vez. |
| «A porta 18463 está ocupada (o QDLink está aberto?)», ou «Porta 18463 · Ocupada» na «Verificação» | O QDLink (ou outra aplicação) está aberto no telemóvel e ocupa a porta. | «Forçar paragem» do QDLink. O HeadQLink tenta de novo a cada 5 s e o aviso desaparece sozinho. |
| Ecrã preto no carro, ou a linha «Auto» com um erro | O Android Auto não arrancou: o telemóvel estava bloqueado («Não arranca: desbloqueie o telemóvel»), falta o modo de programador ou a acessibilidade. | Desbloqueie o telemóvel (com o aviso «Desbloqueie o telemóvel para iniciar o Android Auto», arranca sozinho ao desbloquear). Se continuar, veja a «Verificação» e toque em «Desligar» e «Ligar». Para saber o que falha, ative Diagnóstico › «Teste sem Android Auto (padrão)»: se vir a imagem de teste, a ligação está bem e o problema é o Android Auto (desative-o depois). Se nada resultar, experimente o motor «headqlink original». |
| A imagem vai aos solavancos ou com atraso | Hotspot em 2,4 GHz, telemóvel quente, perfil demasiado alto ou ecrã do telemóvel ligado (com ele, o Android procura redes Wi-Fi muitas vezes). | Hotspot em 5 GHz, perfil «Automático» ou «Carro», bloqueie o telemóvel e arrefeça-o. Se continuar, experimente «Médio». |
| Desliga-se muitas vezes | Desligar automático do hotspot, poupança de bateria, QDLink aberto ou a aplicação de espelhamento do carro fechada. | Os cortes curtos voltam a ligar sozinhos («A voltar a ligar…»). Se forem longos ou frequentes: «Sem restrições de bateria», «Samsung: aplicações nunca suspensas», desative o desligar automático e feche o QDLink. Se continuar, exporte o registo (secção 8). |
| A acessibilidade desativa-se sozinha | O Android desativa-a ao atualizar a aplicação, ou se a aplicação fechou de repente. Alguns fabricantes também. | «Verificação» › «Ativar». Se disser «Ativada mas sem funcionar», desative-a e volte a ativá-la. Se disser «Definição restrita», «Permitir definições restritas» (secção 3). Retire as restrições de bateria. |
| O Android Auto pede no carro que olhe para o telemóvel | É a primeira vez que o Android Auto vê este «ecrã de carro», ou precisa de lhe pedir uma autorização ou confirmação. | Estacione, desbloqueie o telemóvel e aceite o que o Android Auto pedir. Normalmente só acontece uma vez. |
| O telemóvel aquece muito | Perfil «Muito alto» ou «Alto», sol direto, ecrã ligado, carregamento sem fios. | Secção 6. |
| Notificação «Servidor do Android Auto aberto · Desbloqueie o telemóvel para o fechar» | A sessão terminou com o telemóvel bloqueado e o servidor do Android Auto continua aberto. | Desbloqueie o telemóvel: o HeadQLink fecha-o. |
| Depois de uma paragem demora muito a voltar, ou é preciso fechar e abrir a aplicação | A paragem durou mais do que «Esperar pelo carro» e o HeadQLink fechou tudo; ao desbloquear, primeiro fecha o Android Auto («A fechar o Auto…») e depois é preciso iniciá-lo outra vez. | Suba «Esperar pelo carro» para «15 min» (Definições de imagem › Avançado) e ative a ligação automática por Bluetooth: ao voltar, o Android Auto regressa de imediato sem desbloquear. Se vir «Desbloqueie o telemóvel para iniciar o Android Auto», desbloqueie-o e aguarde: inicia sozinho, sem abrir a aplicação. |
| «A fechar o Auto…» ou «A iniciar o Auto…» não desaparece, ou a notificação «O servidor do Android Auto continua ligado · Toque para o desligar» | A automatização das definições do Android Auto não terminou (por exemplo, o telemóvel bloqueou-se a meio). | A camada desaparece sozinha ao fim de 15 s. Toque na notificação com o telemóvel desbloqueado para desligar o servidor. Se se repetir, exporte o registo (secção 8): as linhas «ciclo:» contam cada passo. |
| A ligação automática não arranca | O nome Bluetooth não coincide, ou o Android não a deixa arrancar em segundo plano. | Reveja o texto do aviso «Ligação automática», retire as restrições de bateria ou toque na notificação «Toque para ligar o HeadQLink». |
| Não instala ou não atualiza | Play Protect, Bloqueador automático da Samsung ou uma versão com outra assinatura. | Secção 2. |

---

## 8. Exportar o registo para pedir ajuda

Se algo falhar, o registo ajuda muito a encontrar a causa. **Exporte-o logo a seguir ao problema** e, se puder, anote a
hora em que aconteceu.

1. Menu ⚙ › «Diagnóstico».
2. Toque em **«Exportar registo»**. Verá «A exportar o registo…» e depois «Guardado em Transferências/HeadQLink/…».
3. Abre-se «Partilhar registo do HeadQLink»: envie-o por e-mail, Telegram, Drive ou a aplicação que quiser, ou deixe-o
   guardado.

<!-- captura: ecrã Diagnóstico com «Exportar registo» -->

**Onde fica:** `Transferências/HeadQLink/HeadQLink-log-AAAAMMDD-HHMMSS.zip`. No Android 9 ou anterior fica na pasta da
aplicação (`Android/data/com.headqlink.app/files/exports/`) e o aviso indica o caminho exato.

**O que tem o ZIP**

| Ficheiro | Conteúdo |
|---|---|
| `resumen.txt` | Versão da aplicação, modelo do telemóvel e versão do Android, as suas definições, ligação e motor, estado atual, interfaces de rede, últimas sessões e a lista de ficheiros incluídos (em espanhol). |
| `logs/` | O registo completo, com `sessions.csv` (uma linha por sessão com o carro). |
| `car/`, `perf/`, `logcat/`, `crash/` | Diário do carro, desempenho, registo do sistema da aplicação e encerramentos inesperados (os mais recentes). |

**Privacidade do registo**

- **Inclui:** identificadores do carro (CarUUID, ProjectID), endereços IP, nomes de rede, modelo do telemóvel e as suas
  definições.
- **Não inclui:** as suas viagens com GPS (`trips/` nunca é exportado), coordenadas nem destinos, nem capturas de ecrã.
  Os registos de versões anteriores podiam ter alguma localização.
- **Nada é enviado sozinho:** decide com quem o partilha. Melhor em privado, sem o publicar aberto.

Pode pedir ajuda na página do projeto no GitHub: [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink).

---

## 9. Segurança, privacidade e aviso legal

### Autorizações e para que servem

| Autorização | Para quê | Quando |
|---|---|---|
| Acessibilidade «HeadQLink tátil» | Iniciar e parar o servidor do Android Auto tocando no menu de programador dele, e receber os toques do carro. **Está limitada ao Android Auto:** não vê outras aplicações. | Android Auto 17.4 ou superior |
| Dispositivos Wi-Fi próximos (ou Localização no Android 12 ou anterior) | Entrar na rede Wi-Fi Direct do carro. | Só com «Wi-Fi Direct» |
| Notificações | Ver o estado da ligação e os avisos. | Sempre (recomendado) |
| Bluetooth (dispositivos próximos) | Reconhecer o Bluetooth do carro. | Só com a ligação automática |
| Sem restrições de bateria | Continuar a funcionar com o ecrã desligado. | Recomendado |
| Apresentar sobre outras aplicações | Abrir o Android Auto com o telemóvel em segundo plano. | Opcional |
| Fotos, vídeos e localização | Galeria e painéis de condução. | Opcional, «Auto estendido» |
| Internet | Serviços do «Auto estendido» (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info) e as suas listas de TV e rádio. | Só essas funções |

- O HeadQLink **não envia telemetria** nem tem anúncios.
- **Não partilha o GPS** com o Android Auto por predefinição.
- Os serviços internos não estão abertos a outras aplicações.
- O APK declara outras autorizações herdadas do Open Headunit, como o microfone. O HeadQLink não as pede durante a
  configuração.

> [!IMPORTANT]
> **O servidor do Android Auto.** Com o Android Auto 17.4 ou superior, o HeadQLink liga o «servidor de unidade
> principal» do modo de programador do Android Auto. Enquanto está ligado, escuta em todas as redes do telemóvel, por
> isso **o HeadQLink desliga-o quando termina**: ao tocar em «Desligar» ou quando acaba «Esperar pelo carro» (no
> máximo 15 minutos sem carro, com o Android Auto em pausa e a ocupar o servidor). Com o telemóvel bloqueado não o
> consegue desligar, e deixa-o em espera até o desbloquear («Auto em espera até desbloquear o telemóvel…»).
> **Desbloqueie o telemóvel depois de cada viagem.**

**Quando não o usar:**

- Toque em «Desligar» se abriu o HeadQLink fora do carro: começa a ligar sozinho quando se abre.
- Desligue o hotspot.
- Desative «Ligar ao detetar o Bluetooth do carro» se não quiser que arranque sozinho.
- Se deixar de o usar durante uns tempos, desative a acessibilidade «HeadQLink tátil» e, se quiser, saia do modo de
  programador do Android Auto.

### Aviso legal

- **O software é fornecido «TAL COMO ESTÁ», SEM GARANTIAS de qualquer tipo.** É experimental, usa um protocolo não
  documentado e pode falhar ou deixar de funcionar a qualquer momento.
- **Os autores não se responsabilizam** por danos de qualquer tipo: acidentes, lesões, danos no carro, no telemóvel ou
  a terceiros, perda de dados, multas, perda de garantias ou incumprimento de condições de terceiros. Quem o usa fá-lo
  **por sua conta e risco**.
- **Não o use enquanto conduz.** O condutor é o único responsável por cumprir o código da estrada. Não veja vídeos, TV,
  páginas web nem jogos com o carro em andamento.
- O HeadQLink **não é afiliado, apoiado nem patrocinado** pela Leapmotor, Google, Neusoft, Open Headunit nem por qualquer
  outra empresa ou projeto. Leapmotor, C10, Android Auto e QDLink são marcas dos respetivos proprietários.

### Licença e créditos

- Licença **[GNU AGPL-3.0](LICENSE)**. Tem direito a obter o código-fonte: está em
  [github.com/CharlysEV/headqlink](https://github.com/CharlysEV/headqlink). As secções 15 e 16 da licença (ausência de
  garantia e limitação de responsabilidade) também se aplicam.
- Baseado no **[headqlink](https://github.com/ryazrm/headqlink)** de **ryazrm**, que por sua vez parte do
  **[Open Headunit](https://github.com/andreknieriem/open-headunit)** de **André Rinas (andreknieriem)**, e do trabalho
  original de **Michael Reid** ([aviso de copyright](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)). O HeadQLink é um projeto
  independente, sem relação com o Open Headunit nem com os seus autores.
- Motor de protocolo **QDAuto**: [CharlysEV/qdauto](https://github.com/CharlysEV/qdauto).
