# wallet-service

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Saldo ficticio de cada usuario (nao movimenta dinheiro real), historico de transacoes e integracao com gateway de pagamento em modo sandbox (Stripe/Mercado Pago), so para demonstrar o fluxo tecnico de deposito/saque sem risco legal.

## Como interage com os outros servicos

- Consome (assincrono, via fila): evento `ordem.executada` do `trading-service`, para debitar/creditar saldo.
- Exposto (sincrono, via API Gateway): consulta de saldo e historico pro frontend.

## API (via gateway: `/api/carteira`)

O jogador e identificado pelo cabecalho `X-Usuario-Id`, preenchido pelo api-gateway a partir do `sub` do JWT do Auth0 (o gateway descarta o que vier do cliente).

| Metodo | Caminho | Descricao |
|---|---|---|
| GET | `/carteira` | Saldo. No primeiro acesso cria a carteira com 1.000 fichas de bonus |
| GET | `/carteira/extrato` | Transacoes, mais recentes primeiro |
| POST | `/carteira/depositos` | `{"valor":100}` deposito sandbox (recusa acima de 5.000 por operacao) |
| POST | `/carteira/saques` | `{"valor":100}` saque sandbox (422 se faltar saldo) |

Com `WALLET_SANDBOX_HABILITADO=false` (`wallet.sandbox.habilitado`) depositos e saques respondem 422 "Pagamentos desabilitados": e o interruptor para desligar o sandbox antes de plugar um provedor real (Pix) no lugar do `SandboxGatewayPagamento`.

As rodadas do Flecha Certeira chegam pelo mesmo evento `ordem.executada` (tipo `FLECHA_CERTEIRA`, simbolo = campo), publicado pelo trading-service ao encerrar a sessao.

Consumo de `ordem.executada` (fila duravel `wallet.ordem-executada`):

- aplica o `valorLiquido` da rodada (lucro ou prejuizo) no saldo;
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
