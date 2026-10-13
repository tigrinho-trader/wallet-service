# wallet-service

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Carteira de moedas do jogo de cada usuario no Flecha Certeira (jogo de arco e flecha usado num estudo sobre previsao da direcao de precos de mercado): saldo, historico de transacoes, compra de moedas por Pix (AbacatePay) e um pagamento de teste em modo sandbox (simula Stripe/Mercado Pago) para demonstrar o fluxo tecnico de deposito/saque.

## Como interage com os outros servicos

- Consome (assincrono, via fila): evento `ordem.executada` do `trading-service`, para aplicar o resultado de cada rodada no saldo.
- Exposto (sincrono, via API Gateway): consulta de saldo e historico pro frontend.

## API (via gateway: `/api/carteira`)

O jogador e identificado pelo cabecalho `X-Usuario-Id`, preenchido pelo api-gateway a partir do `sub` do JWT do Auth0 (o gateway descarta o que vier do cliente).

| Metodo | Caminho | Descricao |
|---|---|---|
| GET | `/carteira` | Saldo. No primeiro acesso cria a carteira com 1.000 moedas de boas-vindas |
| GET | `/carteira/extrato` | Transacoes, mais recentes primeiro |
| POST | `/carteira/depositos` | `{"valor":100}` deposito sandbox (recusa acima de 5.000 por operacao) |
| POST | `/carteira/saques` | `{"valor":100}` saque sandbox (422 se faltar saldo) |

Com `WALLET_SANDBOX_HABILITADO=false` (`wallet.sandbox.habilitado`) depositos e saques respondem 422 "Pagamentos desabilitados": e o interruptor para desligar o sandbox antes de plugar um provedor real (Pix) no lugar do `SandboxGatewayPagamento`.

## Compra de moedas por Pix (AbacatePay)

| Metodo | Caminho | Descricao |
|---|---|---|
| GET | `/carteira/compras/config` | `{pixHabilitado, sandboxHabilitado, pacotes, centavosPorMoeda, minimoMoedas, maximoMoedas}` (limites do modo ativo: Pix ou deposito de teste) |
| POST | `/carteira/compras` | `{"moedas":500}` cria a cobranca Pix (qualquer quantidade entre `wallet.pix.minimo-moedas` e `wallet.pix.maximo-moedas`, padrao 10 a 10.000; `wallet.pix.pacotes` sao so sugestoes da loja) e devolve QR Code (`brCodeBase64`) e copia e cola (`brCode`). 422 quantidade invalida ou cobrancas em aberto demais; 503 sem chave/provedor fora do ar |
| GET | `/carteira/compras/{id}` | Status da compra. Se pendente, o servidor consulta o AbacatePay; quando vira `PAGA` credita as moedas **uma unica vez** (transacao `COMPRA_PIX`, referencia = id da cobranca) e devolve o `saldo` |
| POST | `/carteira/compras/{id}/simular-pagamento` | So em modo de desenvolvimento (chave `abc_dev_`): paga a cobranca de teste no AbacatePay e credita |

O cliente nunca informa que pagou: o servidor pergunta ao AbacatePay (`GET /v2/transparents/check`). O webhook
do AbacatePay exige URL HTTPS publica; quando houver deploy com dominio, ele entra como atalho para a mesma
confirmacao.

| Variavel | Padrao | Uso |
|---|---|---|
| `ABACATEPAY_API_KEY` | vazio (Pix desligado) | chave da API. **Nunca** no repositorio: so no ambiente / `.env` ignorado pelo git |
| `ABACATEPAY_URL` | `https://api.abacatepay.com/v2` | base da API |
| `PIX_CENTAVOS_POR_MOEDA` | `10` | preco de cada moeda (10 centavos = 10 moedas por R$ 1,00) |

Teste ao vivo com o AbacatePay (modo dev, sem dinheiro real): `ABACATEPAY_API_KEY=abc_dev_... mvn test -Dtest=AbacatePayAoVivoTest`.

As rodadas do Flecha Certeira chegam pelo mesmo evento `ordem.executada` (tipo `FLECHA_CERTEIRA`, simbolo = campo), publicado pelo trading-service ao encerrar a sessao.

Consumo de `ordem.executada` (fila duravel `wallet.ordem-executada`):

- aplica o `valorLiquido` da rodada (positivo, negativo ou zero) no saldo, registrando `GANHO_RODADA`, `PERDA_RODADA` ou `EMPATE_RODADA`;
- idempotente: o `ordemId` vira a `referencia` unica da transacao, entao evento repetido e ignorado;
- 3 tentativas em caso de erro e depois a mensagem vai pra `wallet.ordem-executada.dlq`.

## Rodando

```bash
mvn verify            # testes + relatorio Jacoco (target/site/jacoco); falha abaixo de 80% de linhas
```

Variaveis: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBITMQ_HOST`. Ambiente completo: `docker-compose.yml` do repo `api-gateway`.

## Repositorios do projeto

- [trading-service](https://github.com/tigrinho-trader/trading-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [api-gateway](https://github.com/tigrinho-trader/api-gateway)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
