# wallet-service

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Saldo ficticio de cada usuario (nao movimenta dinheiro real), historico de transacoes e integracao com gateway de pagamento em modo sandbox (Stripe/Mercado Pago), so para demonstrar o fluxo tecnico de deposito/saque sem risco legal.

## Como interage com os outros servicos

- Consome (assincrono, via fila): evento `ordem.executada` do `trading-service`, para debitar/creditar saldo.
- Exposto (sincrono, via API Gateway): consulta de saldo e historico pro frontend.

## Repositorios do projeto

- [trading-service](https://github.com/tigrinho-trader/trading-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [api-gateway](https://github.com/tigrinho-trader/api-gateway)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
