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
> | «Arranque manual» | «Início manual» |
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
| «Auto» (recomendado) | Android Auto em ecrã inteiro: mapas, música e mensagens. É o mais leve para o telemóvel e para a ligação. |
| «Auto estendido» | O mesmo, com um painel próprio à esquerda com mais informação do carro e da viagem (rota, condução, viagens, instrumentos, eficiência) e mais funcionalidades (fotos, vídeos, web, TV, rádio, jogos). |

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
| «Cabo USB» (etiqueta «Experimental») | O telemóvel liga-se por cabo à porta USB de dados do carro, sem Wi-Fi. | **Experimental**: só funciona se o carro puser o telemóvel em modo acessório, e ainda não se sabe se o C10 o faz. Veja [Ligação por cabo USB (experimental)](#ligação-por-cabo-usb-experimental). |

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
| «Acessibilidade do HeadQLink» (Obrigatório com Android Auto 17.4 ou superior; Opcional com o arranque manual) | O HeadQLink usa-a para iniciar o Android Auto sem que o veja e para receber os toques do carro. Nas Definições do Android chama-se **«HeadQLink tátil»**. | «Ativar» e ligue «HeadQLink tátil». Veja o aviso abaixo. |
| «Sem acessibilidade?» (Sugestão, só se faltar a acessibilidade) | Pode ser o utilizador a iniciar o servidor do Android Auto e não a ativar. | «Arranque manual» (ver [Sem acessibilidade](#sem-acessibilidade-arranque-manual-do-servidor-do-android-auto)). |
| «Modo de programador do Android Auto» (Obrigatório com 17.4 ou superior) | O Android Auto só aceita um «ecrã de carro» dentro do telemóvel através do modo de programador. Ativa-se uma vez. | «Abrir AA» e siga o guia abaixo. «Verificar» confirma-o (precisa da acessibilidade ativada). |
| «Servidor do Android Auto» (Informação, só com o arranque manual) | O que se sabe **sem se ligar a ele** (uma ligação de teste bloqueá-lo-ia): «Em uso pelo HeadQLink», «Não atende» (as tentativas com o carro falham) ou, se não se sabe, como iniciá-lo. Nunca impede ligar: se não atender, o HeadQLink avisa e volta a tentar. | «Abrir AA» (⋮ › «Parar servidor» se aparecer e ⋮ › «Iniciar servidor da unidade principal») ou «Modo automático». |
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
| «Localização sempre» (Recomendado, «Auto estendido») | Para que os dados do carro continuem com o telemóvel bloqueado (ver [O que vê e como se usa](#o-que-vê-e-como-se-usa)). | «Abrir»: explica porquê e abre a página do Android; escolha «Permitir sempre» («Permitir o tempo todo» no Brasil). Se ainda não tiver a localização, pede-a antes. |

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
>
> Prefere evitar tudo isto? Veja [Sem acessibilidade](#sem-acessibilidade-arranque-manual-do-servidor-do-android-auto).

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

### Sem acessibilidade (arranque manual do servidor do Android Auto)

Se não quiser (ou não puder) ativar a acessibilidade, escolha **«Definições de imagem» › «Avançado ▾» › «Arranque do
servidor do Android Auto» › «Manual (sem acessibilidade)»**, ou toque em «Arranque manual» na linha «Sem
acessibilidade?» da «Verificação». Com «Auto» e «Auto estendido» o HeadQLink deixa de a usar: a acessibilidade passa a
«Opcional» («Só para o modo automático») e aparece a linha «Servidor do Android Auto».

> [!IMPORTANT]
> **O servidor do Android Auto continua a atender enquanto estiver ligado, desde que as ligações se fechem de forma
> ordenada.** O HeadQLink fecha sempre a sua assim (Desligar, fim da viagem, mudança de perfil de imagem), por isso
> **não é preciso reiniciá-lo entre viagens**. O que o bloqueia é uma ligação **cortada a meio** (algo que se liga e se
> vai embora sem dizer nada, ou uma sessão que cai de repente): a partir daí aceita ligações mas não responde a ninguém
> até o parar e voltar a iniciar. Por isso o HeadQLink **não o verifica antes de ligar** (verificá-lo seria exatamente
> isso): diz-o a primeira tentativa real com o carro.

1. Inicie o servidor: «Abrir AA» › ⋮ (canto superior direito) › **«Iniciar servidor da unidade principal»**. Se essa
   opção não aparecer, ative primeiro o modo de programador (10 toques em «Versão»). Se o menu disser «Parar servidor
   da unidade principal», já está ligado: não é preciso mexer. Ao reiniciar o telemóvel (ou ao atualizar-se o Android
   Auto) pode desligar-se: aí é preciso voltar a iniciá-lo.
2. Toque em «Ligar» (ou deixe-o arrancar sozinho por Bluetooth ou por cabo). Quando o carro se liga, o HeadQLink lança
   o Android Auto e espera que ele **responda** (6 s no máximo). Com o servidor ligado funciona também **com o telemóvel
   bloqueado**: sem desbloquear nem automatizar nada.
3. Se não responder (desligado, ou bloqueado por uma ligação cortada a meio), verá a notificação **«Inicie (ou
   reinicie) o servidor do Android Auto»** e a linha «Auto» (e o widget) dirá «À espera do servidor do Android Auto».
   Toque nela: Android Auto › ⋮ › «Parar servidor» (se aparecer) e ⋮ › «Iniciar servidor da unidade principal», e
   volte. O HeadQLink volta a tentar sozinho a cada 5 s enquanto o carro estiver ligado; assim que o Android Auto
   responde, a notificação desaparece sozinha.
4. O resto, como com o automático: se o carro se for embora, 30 s de vídeo ativo e depois o Android Auto em pausa;
   quando acaba «Esperar pelo carro» (ou com «Desligar») o HeadQLink fecha o Android Auto de forma ordenada. A única
   coisa que não faz é desligar o servidor (sem acessibilidade não consegue): **fica ligado, e a viagem seguinte volta a
   usá-lo sem o reiniciar**, também com o telemóvel bloqueado, enquanto continuar ligado.

O que muda, e porque é que o automático continua a ser o **recomendado**:

- É preciso iniciá-lo à mão quando estiver desligado (depois de reiniciar o telemóvel, por exemplo) e, se alguma vez
  ficar bloqueado, pará-lo e voltar a iniciá-lo.
- **Fica ligado** até o parar, e escuta em toda a rede: numa **Wi-Fi pública**, qualquer aparelho dessa rede poderia
  tentar ligar-se a ele (e, se se ligar e for embora sem mais, deixá-lo bloqueado). Pare-o quando não o for usar.
- O modo «App» (uma aplicação concreta no carro) continua a precisar da acessibilidade para os toques.

> [!TIP]
> **Instalar com o Obtainium costuma evitar o passo das «definições restritas».** O Obtainium instala com o
> instalador por sessões do Android e, no Android 13 e 14, a acessibilidade costuma poder ativar-se à primeira. No
> Android 15 ou superior não é garantido.

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

### Ligação por cabo USB (experimental)

> **Testado no C10 (2026-10-06): 5 minutos a 40 fps sem cortes**, e o telemóvel carrega. Se o carro não responder em poucos segundos, **desligue e volte a ligar o cabo**: o carro só fala nos primeiros segundos depois de ligar o cabo.

Tal como a aplicação original do carro, o HeadQLink pode levar a imagem **por cabo** em vez de por Wi-Fi: o carro põe o
telemóvel em «modo acessório» e a sessão de sempre passa pelo cabo. **Ainda não se sabe se o C10 o faz** (a aplicação
original diz que só alguns modelos), por isso é experimental.

1. Use um **cabo de dados** (não um só de carregamento) e ligue-o à **porta USB de dados** do carro (a da música ou do
   Android Auto/CarPlay, não uma só de carregamento).
2. Abra a aplicação de espelhamento no ecrã do carro.
3. Se o carro puser o telemóvel em modo acessório, o Android abre o HeadQLink sozinho. Se o **QDLink** também estiver
   instalado, o Android pode perguntar **que aplicação abre «QDriveLink»**: escolha o **HeadQLink** e **«Sempre»** (ou
   desinstale o QDLink).
4. A linha «Rede» diz «Cabo USB: Neusoft QDriveLink 1» e o carro liga-se como sempre.

- **Não é preciso o hotspot.** Com o cabo ligado, **o cabo tem prioridade**: mesmo que a ligação escolhida seja outra,
  o HeadQLink põe o Wi-Fi em pausa e, ao retirar o cabo, volta a ele. Para usar só o cabo, escolha «Cabo USB» em
  «Alterar».
- Retirar o cabo é como se o carro se fosse embora (vídeo ativo 30 s e depois o Android Auto em pausa); ao voltar a
  ligá-lo, retoma de imediato.

**Se não funcionar**, exporte o registo ([secção 8](#8-exportar-o-registo-para-pedir-ajuda)) logo a seguir a ligar o
cabo e envie-no-lo, com a hora. Procuramos as linhas **`HQL/USB`**: o que diz o Android do cabo (`USB_STATE`), se o
carro pôs o telemóvel em modo acessório e com que nome (fabricante, modelo e versão). Com isso saberemos se o C10 o
admite.

### O que vê e como se usa

- **«Auto»:** Android Auto em ecrã inteiro.
- **«Auto estendido»:** um painel à esquerda, do lado do condutor, com «Auto», «Carro» (separadores Rota, Condução,
  Viagens, Instrumentos, Eficiência e Estado), «Fotos», «Vídeos», «Web», «TV», «Rádio», «Jogos» e «Definições». Com o Android
  Auto no ecrã, o painel esconde-se sozinho ao fim de alguns segundos; toque na margem esquerda para que volte. Fotos,
  vídeos, web, TV e jogos são **só para quando o carro está parado**.
- **Secção «Carro»** (estimado com os sensores do telemóvel e serviços abertos; com a conta Leapmotor, também com dados
  reais do carro: ver [Dados reais do carro](#dados-reais-do-carro-conta-leapmotor)):
  - «Rota»: destino, chegada, energia e consumo previstos; o perfil de elevação colorido pela inclinação (a verde as
    descidas, onde se recupera energia), o vento por troços, os carregadores e a bateria prevista; a bateria à chegada
    num anel (indique a sua % com «−5»/«+5»), o tempo no destino e os carregadores ao longo da rota («Ir» abre a
    navegação do Google Maps).
  - «Condução»: a próxima manobra em grande, com uma barra que se esvazia até à viragem, as faixas e a manobra seguinte;
    a velocidade com o sinal do limite, o rumo, a altitude, a inclinação e o sol até ao pôr do sol.
  - «Viagens»: a viagem em curso e as guardadas, com o percurso, consumo, desnível e custo; os km dos últimos 14 dias e
    os recordes.
  - «Instrumentos»: velocímetro com o limite e a máxima, forças G com o rasto dos últimos segundos e os picos,
    inclinação e uma nota de suavidade (0-100) pelos solavancos ao acelerar, travar e virar.
  - «Eficiência»: a potência dos últimos 2 minutos (a verde o que se recupera), o consumo agora, da viagem e médio, para
    onde vai a energia, o custo da viagem, o CO₂ que não saiu por um tubo de escape e uma dica.
  - «Estado» (com a conta Leapmotor): a bateria, a autonomia, a carga, as pressões, as portas e o conta-quilómetros
    reais do carro, com a idade do dado. Sem conta, explica como a configurar.
  - **Com o telemóvel bloqueado: «Localização sempre».** Rota, Condução, Viagens, Instrumentos e Eficiência usam o GPS
    do telemóvel. Se a localização do HeadQLink for só «Permitir durante a utilização da app», o Android só lhe dá o
    GPS com o HeadQLink à vista ou se a ligação passou a primeiro plano com o HeadQLink à frente. Quando arranca sem ele
    (a ligação automática por Bluetooth, o widget, o arranque do Android Auto…), ao bloquear o telemóvel o ecrã do
    carro continua e os sensores de movimento também, mas a velocidade, a rota, a viagem e o consumo param até
    desbloquear. O HeadQLink recupera-o sozinho assim que o vê com o telemóvel desbloqueado, mas para não depender disso
    a «Verificação» recomenda no «Auto estendido» **«Localização sempre»**: «Abrir» › «Permitir sempre» (ou Definições ›
    Apps › HeadQLink › Autorizações › Localização). No «Auto» não é preciso. O HeadQLink só usa o GPS com a ligação em
    curso ou os ecrãs «Carro» abertos.
  - **Sem GPS, sem números congelados.** Com mais de 5 s sem posições, a velocidade mostra «—» e os painéis dizem «GPS
    em pausa · telemóvel bloqueado (ative a localização «sempre»)» (ou «sem GPS», por exemplo num túnel). Quando volta,
    o troço sem posições soma-se à viagem em linha reta, à sua velocidade média, em vez de contar como se o carro
    tivesse estado parado.
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
  usá-lo, toque em «Ligar», ou deixe isso à ligação automática por Bluetooth. Com o arranque manual do servidor fecha
  tudo da mesma forma exceto o servidor, que continua ligado para a próxima viagem ([Sem
  acessibilidade](#sem-acessibilidade-arranque-manual-do-servidor-do-android-auto)).
- Se tocar em «Ligar» e em **5 minutos** (ou «Esperar pelo carro», se for mais) não aparecer nenhum carro, o HeadQLink
  para sozinho. Enquanto o carro se continuar a anunciar, mesmo sem chegar a ligar, a espera recomeça.

### O vídeo adapta-se à ligação

A rádio entre o telemóvel e o carro nem sempre leva os 5 Mbit/s que o C10 pede: com o carro longe do telemóvel, em
2,4 GHz ou com interferências, a imagem ia aos saltos e com atraso. Agora, com os perfis «Carro», «Automático», «Médio»
e «Muito baixo» (os que recodificam no telemóvel):

- O HeadQLink mede a ligação dez vezes por segundo (dados à espera de envio e tempo de ida e volta) e, se entupir a
  sério (meio segundo seguido com dados acumulados ou com o tempo de ida e volta disparado), **baixa a taxa de bits**
  (×0,75 de cada vez, nunca abaixo de metade do que o carro pede: 2,5 Mbit/s no C10); se mesmo assim continuar
  entupida, desce para **24 fps**. Quando a ligação leva 3 segundos limpa, volta depressa (primeiro os fps, depois
  +25 % de taxa de bits a cada 3 s): do mínimo ao que o carro pede em cerca de 12 s. As retransmissões soltas do Wi-Fi
  não contam (são normais).
- O que não cabe na ligação é descartado no telemóvel antes de acumular atraso (no máximo ~150 ms em fila).
- Se o carro deixar de ler por um momento mas continuar a falar (heartbeats, toques), a sessão aguenta até **20 s**
  antes de a dar por perdida (antes, 10 s), deitando fora o vídeo velho e mandando uma imagem fresca assim que puder.

No registo (secção 8) vê-se como `enlace: congestión (outq 96 KB 300 ms, retrans +21) → bitrate 3.6 Mbit/s`,
`enlace: enlace limpio 5 s → bitrate 4.1 Mbit/s`, e no resumo de cada sessão `enlace: bitrate mín. 2.5 Mbit/s ·
congestiones 4`.

### No fim da viagem

- Desligue o carro (o HeadQLink fecha-se sozinho quando termina «Esperar pelo carro», 5 minutos por predefinição) ou
  toque em «Desligar» para o fechar já.
- Se o telemóvel estava bloqueado quando se fechou, verá a notificação «Auto em espera até desbloquear o telemóvel
  (depois fecha-se sozinho)». **Desbloqueie o telemóvel uma vez** para que feche de vez: verá durante uns segundos «A
  fechar o Auto…» (secção 9). Se voltar ao carro antes de o desbloquear e usar a ligação automática por Bluetooth, o
  Android Auto regressa de imediato, sem passar por «A fechar o Auto…». Com o arranque manual não é preciso: o Android
  Auto fecha-se logo e o servidor dele continua ligado.
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

### Widget e botão das definições rápidas

Para ligar sem abrir a aplicação:

- **Widget «HeadQLink»** (ecrã principal): menu ⚙ › «Adicionar widget ao ecrã principal», ou mantenha premido um espaço
  livre do ecrã principal › Widgets › HeadQLink. Ocupa 4x2 e pode reduzi-lo até 2x2.
  - O **botão grande** faz o mesmo que «Ligar» / «Desligar» na aplicação: se faltar algo obrigatório, abre a
    «Verificação». A cor indica o estado: cinzento desligado, âmbar a procurar ou a aguardar o carro, verde com imagem no
    carro (com os fps e os Mbit/s, atualizados a cada 5 s) e vermelho se houver um problema (por exemplo, o QDLink
    aberto).
  - Em baixo, a **ligação**: «Hotspot», «Wi-Fi Direct» ou «Cabo USB». Toque noutra para a mudar; com o HeadQLink em
    funcionamento muda de imediato, sem reiniciar o Android Auto. Se o cabo do carro estiver ligado e em uso, continua a
    ter prioridade: a ligação escolhida é aquela a que volta ao retirá-lo.
  - Em cima, o **modo** («Auto» / «Estendido»). Com o HeadQLink em funcionamento, mudá-lo volta a ligar o vídeo e o
    Android Auto, como ao guardar outro perfil de imagem.
  - Em 2x2 ficam o botão, o estado e o ícone da ligação: toque nele para passar à seguinte.
  - «HEADQLINK» abre a aplicação sem ligar. O widget só se atualiza quando algo muda: não gasta bateria em segundo
    plano.
- **Botão «HeadQLink» das definições rápidas** (Android 8+): com Android 13+, menu ⚙ › «Adicionar botão às definições
  rápidas»; caso contrário, abra as definições rápidas, toque no lápis (editar) e arraste-o. Ao tocar nele liga ou
  desliga (para ligar com o telemóvel bloqueado, pede primeiro para o desbloquear); mantendo-o premido abre a aplicação.
  Por baixo indica o estado («A procurar o carro…», «30 fps · 4,8 Mbit/s»…).

---

## 5. Definições úteis

Está tudo no menu ⚙ do ecrã principal: «Verificação», «Definições de imagem», «Lista de TV» e «Lista de rádio» (só no
«Auto estendido»), «Idioma», «Adicionar widget ao ecrã principal», «Adicionar botão às definições rápidas» (Android 13+)
e «Diagnóstico».

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

**«Fluidez».** Só conta com os perfis «Carro» e «Automático»:

- «30 fps · menos calor (recomendado)»: o que o carro pede (30 fps e cerca de 5 Mbit/s no C10). É o que menos aquece
  o telemóvel.
- «60 fps · fluidez máxima»: 60 fps e entre 8 e 12 Mbit/s, como o HeadQLink original (o C10 aceita-os). A imagem fica
  mais fluida, mas o telemóvel aquece mais: se chegar ao estado térmico «moderado», o HeadQLink desce sozinho para
  30 fps (secção 6). Se estiver ligado, ao guardar o carro e o Android Auto voltam a ligar-se sozinhos; se não, vale
  para a próxima sessão.

**Outras opções de «Definições de imagem»:**

- «Ocultar o painel lateral após alguns segundos (Auto estendido)»: ativada por predefinição.
- «Manter o ecrã do telemóvel ligado (mais calor e bateria; caso contrário, desliga-se como sempre e a projeção
  continua)»: desativada por predefinição. **Deixe-a desativada**, salvo se precisar dela.
- «Proteção térmica»: o que o HeadQLink faz quando o Android avisa que o telemóvel está a aquecer (secção 6).
  - «Normal (recomendada)»: desce para 30 fps em «moderado» (só se a sessão vai a 60), para 24 fps em «grave» e para
    20 fps em «crítico», sempre com menos taxa de bits.
  - «Suave»: só baixa a taxa de bits; os fps não descem de 30, salvo em «crítico» (20 fps). Para quem prefere a fluidez
    mesmo com o telemóvel mais quente.
  - «Desligada»: não muda nada, só o anota no registo.

  Aplica-se de imediato, sem voltar a ligar.
- «Avançado ▾» › «Motor do protocolo»:
  - «QDAuto (recomendado)»: o motor predefinido, com religação sem reiniciar o Android Auto e adaptação ao calor.
  - «headqlink original»: o motor do HeadQLink original, como **plano B** se o QDAuto lhe der problemas.

  A mudança «Aplica-se na próxima ligação»: toque em «Desligar» e depois em «Ligar».
- «Avançado ▾» › «Esperar pelo carro»: «1 min», «5 min (recomendado)» ou «15 min». É o tempo que o HeadQLink continua
  à escuta do carro, com o Android Auto em pausa, depois de o perder (secção 4). Mais tempo = regressa de imediato
  depois de paragens mais longas; menos = o servidor do Android Auto desliga-se mais cedo. Vale já para a próxima
  paragem.
- «Avançado ▾» › «Arranque do servidor do Android Auto»: «Automático (acessibilidade) · Recomendado» ou «Manual (sem
  acessibilidade)» (secção 3, [Sem acessibilidade](#sem-acessibilidade-arranque-manual-do-servidor-do-android-auto)).
  Vale de imediato.
- O resto de «Avançado» (fps, kbps, largura, altura, perfil H.264, otimizações de latência, limitador) é para testes.
  Deixe-o vazio ou como está.

### Definições a partir do carro («Auto estendido»)

O botão «Definições» do painel do carro permite mudar o perfil de imagem e a «Fluidez» («Aplicar · volta a ligar em
poucos segundos»), ocultar o painel sozinho e as otimizações de latência, o «Preço da eletricidade» (€/kWh, para o custo
das viagens; 0,20 por omissão), e ver a ligação e o motor em uso. Use-o só com o carro parado.

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
- «Pré-visualização do modo estendido»: o painel do carro no telemóvel (na horizontal), com um trajeto de demonstração;
  toca-se como no carro. Nada é guardado e não está disponível com o carro ligado.

### Dados reais do carro (conta Leapmotor)

**O que é.** Opcional. Com a sua conta Leapmotor, o HeadQLink lê da nuvem da Leapmotor o estado do seu carro (bateria e
autonomia, carga, pressões dos pneus, portas, mala e fecho, temperaturas e conta-quilómetros) e usa-o no «Auto
estendido»:

- **«Estado»** (o sexto separador de «Carro»): a bateria num anel com a autonomia e os kWh que restam; a carga
  (corrente alternada ou carga rápida, com a potência e o tempo que falta) ou, sem cabo, a potência que sai ou entra; a
  temperatura da bateria (com «Bateria fria: menos carga rápida e menos regeneração» abaixo de 10 °C); as quatro
  pressões sobre o carro visto de cima, a âmbar o pneu baixo (abaixo de 2,1 bar, 0,3 bar ou mais abaixo dos outros, ou
  com o aviso do próprio carro); as portas e a mala abertas, o fecho, o conta-quilómetros e de quando é o dado. A nuvem
  não indica as janelas.
- **«Rota»**: a % de agora é a real (desaparecem «−5»/«+5») e a bateria à chegada sai dela com a capacidade da sua
  versão.
- **«Eficiência»**: o consumo da viagem é o real (o que a bateria desceu vezes a sua capacidade, entre os km do
  conta-quilómetros) assim que a bateria desce 2 %; antes diz «ainda pouco consumo para medir». Junto à potência
  estimada aparece a real se o dado for recente. A repartição da energia continua estimada.
- **«Viagens»**: cada viagem guarda a % e os km do carro no início e no fim; com eles, o seu consumo real (marcado
  «REAL») e os totais reais em «Recordes».

Cada valor diz de onde vem: «real · há 40 s» (a idade do dado: a nuvem não é em tempo real e, com o carro desligado,
dá o último que soube) ou «estimado».

**Como se configura** (⚙ › «Dados do carro (conta Leapmotor)», Android 6 ou superior):

1. **Certificado de cliente.** É o mesmo que a app LMB10 pede; o HeadQLink não o inclui nem o fornece. Toque em
   «Importar certificado…» e escolha no seletor de ficheiros app.crt e app.key: os dois de uma vez (toque longo para
   marcar dois) ou **um a seguir ao outro** (diz qual falta; «Começar de novo» esquece o que foi escolhido a meio), um
   .pem com os dois blocos ou um .p12/.pfx (se tiver palavra-passe, é pedida).
2. **Conta.** O seu e-mail e palavra-passe da Leapmotor, e «Entrar». A palavra-passe não é guardada.
3. **Carro.** Se a conta tiver um, fica escolhido; se tiver vários, escolha o seu.
4. **Bateria.** A nuvem não indica a versão: «C10 Life · 69,9 kWh», «C10 ProMax · 81,9 kWh» ou «Outra» (os kWh à
   mão). Serve para passar a % a kWh.
5. **«Ler o estado agora»** para o confirmar: bateria, autonomia, carga, pressões e a idade do dado.

Com o «Auto estendido» em funcionamento (ou a «Pré-visualização do modo estendido»), o HeadQLink lê o carro a cada 60 s
(a cada 30 s com a secção «Carro» no ecrã) e, se falhar, aos 2, 5 e 10 minutos. Para ao desligar. «Usar os dados reais
do carro» desativa-o sem apagar nada.

**Privacidade e segurança.**

- Só de leitura: o HeadQLink **nunca envia ordens ao carro** (nem trancar, nem climatização, nem carga, nada).
- Os seus dados vão **só para os servidores da Leapmotor**. A posição do carro não é lida nem guardada.
- O certificado e a sessão ficam **cifrados** com uma chave do Android Keystore, fora das cópias de segurança. A
  palavra-passe não é guardada: se a sessão expirar, «Estado» avisa e volta a entrar no telemóvel.
- O servidor da Leapmotor usa um certificado da sua própria autoridade. O HeadQLink verifica que a chave é a conhecida;
  se um dia mudar, não se liga até que a aceite no telemóvel (só numa rede de confiança).
- No registo: o e-mail mascarado (c\*\*\*@e\*\*\*.com) e uma linha por leitura (%, autonomia, carga, kW, km e latência),
  sem tokens, VIN nem posição.
- «Terminar sessão e apagar dados» apaga deste telemóvel o certificado, a sessão e as definições da conta.

> [!WARNING]
> Usa uma **API não oficial** da Leapmotor: pode deixar de funcionar a qualquer momento, e o HeadQLink não tem relação
> com a Leapmotor. Com o **C10 REEV** (autonomia alargada) o consumo real não serve: o gerador carrega a bateria em
> andamento.

O protocolo vem do **[LMB10](https://github.com/txurtxil/LPB10)**, de **txurtxil** (GPL-3.0).

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

| Estado térmico do Android | «Proteção térmica» Normal (recomendada) | «Suave» |
|---|---|---|
| Normal ou ligeiro | Nada: os fps e a taxa de bits da sessão. | Nada. |
| Moderado | Para **30 fps** se a sessão vai a 60 por causa da «Fluidez» (a 30, os mesmos fps) e 80 % da taxa de bits. | Só a taxa de bits, para 80 %. |
| Grave | Desce para **24 fps** e no máximo **3,5 Mbit/s**. | **30 fps** no máximo e 3,5 Mbit/s. |
| Crítico ou pior | Desce para **20 fps** e no máximo **3 Mbit/s**. | O mesmo: 20 fps e 3 Mbit/s. |

Sobe de nível de imediato. Volta ao normal quando o telemóvel está **30 segundos seguidos** mais fresco. Fá-lo sem
cortar a sessão nem reiniciar o Android Auto, e sem avisos: só notará a imagem um pouco menos fluida. Fica anotado no
registo. Com a «Proteção térmica» em «Desligada» não muda nada (só o anota). Se além disso a ligação for à justa, manda
o limite mais baixo dos dois (calor ou ligação).

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
| O carro não aparece («A procurar…» o tempo todo) | O carro não está no hotspot do telemóvel, o hotspot desligou-se sozinho, ou a aplicação de espelhamento não está aberta no carro. Com Wi-Fi Direct: o hotspot ativo ou o Wi-Fi desligado. | Veja a linha «Rede»: deve dizer «Hotspot ativo (…)». Ligue o carro ao hotspot (Definições › Wi-Fi do carro) e abra a aplicação de espelhamento. Desative o desligar automático do hotspot. Ao fim de 5 minutos sem anúncios do carro o HeadQLink para: toque em «Ligar» outra vez. |
| «A porta 18463 está ocupada (o QDLink está aberto?)», ou «Porta 18463 · Ocupada» na «Verificação» | O QDLink (ou outra aplicação) está aberto no telemóvel e ocupa a porta. | «Forçar paragem» do QDLink. O HeadQLink tenta de novo a cada 5 s e o aviso desaparece sozinho. |
| Ecrã preto no carro, ou a linha «Auto» com um erro | O Android Auto não arrancou: o telemóvel estava bloqueado («Não arranca: desbloqueie o telemóvel»), falta o modo de programador ou a acessibilidade. | Desbloqueie o telemóvel (com o aviso «Desbloqueie o telemóvel para iniciar o Android Auto», arranca sozinho ao desbloquear). Se continuar, veja a «Verificação» e toque em «Desligar» e «Ligar». Para saber o que falha, ative Diagnóstico › «Teste sem Android Auto (padrão)»: se vir a imagem de teste, a ligação está bem e o problema é o Android Auto (desative-o depois). Se nada resultar, experimente o motor «headqlink original». |
| A imagem vai aos solavancos ou com atraso | Hotspot em 2,4 GHz, telemóvel quente, perfil demasiado alto ou ecrã do telemóvel ligado (com ele, o Android procura redes Wi-Fi muitas vezes). | Hotspot em 5 GHz, perfil «Automático» ou «Carro», bloqueie o telemóvel e arrefeça-o. Se continuar, experimente «Médio». |
| Muitos cortes de rádio (a imagem para meio segundo vezes sem conta; no registo, linhas «Corte … RADIO» e «enlace muy congestionado») | O rádio entre o telemóvel e o carro não dá mais: hotspot em 2,4 GHz, o telemóvel num bolso, num tabuleiro metálico ou longe do ecrã. O HeadQLink já baixa a qualidade o mais que pode (até 1,2 Mbit/s e 20 fps), mas com o rádio saturado não chega. | Ponha o hotspot em **5 GHz** (Definições › Hotspot › Banda). Tire o telemóvel de bolsos e tabuleiros metálicos e deixe-o perto do ecrã do carro. O mais estável é o **cabo USB**. No registo, a linha «radio de la zona Wi-Fi» diz a banda e o canal quando o Android os deixa ler. |
| A imagem fica menos fluida do que com o HeadQLink original | O original enviava 60 fps; o HeadQLink envia 30 (o que o carro pede) para o telemóvel não aquecer. Ou o telemóvel já está quente e a adaptação térmica baixou os fps, ou a ligação vai à justa e baixaram-se a taxa de bits ou os fps. | «Definições de imagem» › «Fluidez» › «60 fps · fluidez máxima» (com o perfil «Carro» ou «Automático»). Se o calor baixa os fps, «Proteção térmica» › «Suave» (secção 6). No registo, as linhas «HQL/Térmico» («estado térmico 2» ou mais) e «enlace:» dizem qual das duas coisas se passa. |
| A imagem congela uns 10 s e depois volta a ligar | Em versões anteriores, uma imagem completa de mais de ~512 KB bloqueava o recetor do carro, que deixava de ler até a ligação cair. **Corrigido**: o HeadQLink já não envia nenhuma tão grande e mantém-nas em cerca de 300 KB no máximo. | Atualize o HeadQLink. Se acontecer com o perfil «Básico» (aí o tamanho é decidido pelo Android Auto), use «Automático» ou «Carro». Se continuar, exporte o registo (secção 8). |
| Desliga-se muitas vezes | Desligar automático do hotspot, poupança de bateria, QDLink aberto ou a aplicação de espelhamento do carro fechada. | Os cortes curtos voltam a ligar sozinhos («A voltar a ligar…»). Se forem longos ou frequentes: «Sem restrições de bateria», «Samsung: aplicações nunca suspensas», desative o desligar automático e feche o QDLink. Se continuar, exporte o registo (secção 8). |
| Notificação «Inicie (ou reinicie) o servidor do Android Auto», ou a linha «Auto» diz «À espera do servidor do Android Auto» | Arranque manual e o Android Auto não atende: o servidor está desligado (depois de reiniciar o telemóvel ou atualizar o Android Auto) ou bloqueado: uma ligação cortou-se a meio (algo se ligou e foi embora sem dizer nada, ou uma sessão caiu de repente). | Toque na notificação ou na linha: Android Auto › ⋮ › «Parar servidor» (se aparecer) e ⋮ › «Iniciar servidor da unidade principal». O HeadQLink volta a tentar a cada 5 s e continua sozinho. |
| A acessibilidade desativa-se sozinha | O Android desativa-a ao atualizar a aplicação, ou se a aplicação fechou de repente. Alguns fabricantes também. | «Verificação» › «Ativar». Se disser «Ativada mas sem funcionar», desative-a e volte a ativá-la. Se disser «Definição restrita», «Permitir definições restritas» (secção 3). Retire as restrições de bateria. |
| O Android Auto pede no carro que olhe para o telemóvel | É a primeira vez que o Android Auto vê este «ecrã de carro», ou precisa de lhe pedir uma autorização ou confirmação. | Estacione, desbloqueie o telemóvel e aceite o que o Android Auto pedir. Normalmente só acontece uma vez. |
| O telemóvel aquece muito | Perfil «Muito alto» ou «Alto», sol direto, ecrã ligado, carregamento sem fios. | Secção 6. |
| Notificação «Servidor do Android Auto aberto · Desbloqueie o telemóvel para o fechar» | A sessão terminou com o telemóvel bloqueado e o servidor do Android Auto continua aberto. | Desbloqueie o telemóvel: o HeadQLink fecha-o. |
| Depois de uma paragem demora muito a voltar, ou é preciso fechar e abrir a aplicação | A paragem durou mais do que «Esperar pelo carro» e o HeadQLink fechou tudo; ao desbloquear, primeiro fecha o Android Auto («A fechar o Auto…») e depois é preciso iniciá-lo outra vez. | Suba «Esperar pelo carro» para «15 min» (Definições de imagem › Avançado) e ative a ligação automática por Bluetooth: ao voltar, o Android Auto regressa de imediato sem desbloquear. Se vir «Desbloqueie o telemóvel para iniciar o Android Auto», desbloqueie-o e aguarde: inicia sozinho, sem abrir a aplicação. |
| «A fechar o Auto…» ou «A iniciar o Auto…» não desaparece, ou a notificação «O servidor do Android Auto continua ligado · Toque para o desligar» | A automatização das definições do Android Auto não terminou (por exemplo, o telemóvel bloqueou-se a meio). | A camada desaparece sozinha ao fim de 15 s. Toque na notificação com o telemóvel desbloqueado para desligar o servidor. Se se repetir, exporte o registo (secção 8): as linhas «ciclo:» contam cada passo. |
| Os dados do carro congelam ao bloquear o telemóvel (velocidade, rota, viagem, consumo; voltam ao desbloquear) | A localização do HeadQLink é só «durante a utilização da app» e o Android corta-lhe o GPS com o telemóvel bloqueado. O ecrã do carro e os sensores de movimento continuam; o GPS não. | «Verificação» › «Localização sempre» › «Abrir» › «Permitir sempre». No registo, «GPS: ubicación todo el tiempo sí · con el móvil bloqueado llega» confirma que já funciona. |
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
| Localização sempre | Que os painéis de «Carro» continuem com o telemóvel bloqueado. Só se usa com a ligação em curso ou os ecrãs «Carro» abertos. | Recomendado, «Auto estendido» |
| Internet | Serviços do «Auto estendido» (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info), as suas listas de TV e rádio e, se a configurar, a nuvem da Leapmotor (dados reais do carro, só de leitura). | Só essas funções |

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
>
> Com o arranque manual (sem acessibilidade), o HeadQLink não o consegue desligar: no fim fecha o Android Auto, mas o
> servidor continua ligado (é isso que deixa começar a viagem seguinte sem mexer em nada). **Pare-o** (Android Auto ›
> ⋮ › «Parar servidor da unidade principal») se não o for usar, sobretudo numa Wi-Fi pública.

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
- Dados reais do carro (conta Leapmotor): cliente só de leitura portado do **[LMB10](https://github.com/txurtxil/LPB10)**,
  de **txurtxil** (GPL-3.0); ver [NOTICE](NOTICE).
