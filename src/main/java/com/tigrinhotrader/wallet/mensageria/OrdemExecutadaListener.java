package com.tigrinhotrader.wallet.mensageria;

import com.tigrinhotrader.wallet.servico.CarteiraService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrdemExecutadaListener {

    private final CarteiraService carteiraService;

    public OrdemExecutadaListener(CarteiraService carteiraService) {
        this.carteiraService = carteiraService;
    }

    @RabbitListener(queues = MensageriaConfig.FILA_ORDENS)
    public void aoReceber(OrdemExecutadaEvento evento) {
        carteiraService.aplicarResultadoRodada(evento);
    }
}
