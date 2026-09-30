package com.tigrinhotrader.wallet.mensageria;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MensageriaConfig {

    public static final String ROTA_ORDEM_EXECUTADA = "ordem.executada";
    public static final String FILA_ORDENS = "wallet.ordem-executada";
    public static final String FILA_ORDENS_DLQ = FILA_ORDENS + ".dlq";

    @Bean
    TopicExchange eventosExchange(@Value("${mensageria.exchange:tigrinho.eventos}") String nome) {
        return new TopicExchange(nome, true, false);
    }

    /** Duravel: resultado de rodada nao pode se perder se a carteira estiver fora do ar. */
    @Bean
    Queue filaOrdens() {
        return QueueBuilder.durable(FILA_ORDENS)
                .deadLetterExchange("")
                .deadLetterRoutingKey(FILA_ORDENS_DLQ)
                .build();
    }

    @Bean
    Queue filaOrdensDlq() {
        return QueueBuilder.durable(FILA_ORDENS_DLQ).build();
    }

    @Bean
    Binding bindingOrdens(Queue filaOrdens, TopicExchange eventosExchange) {
        return BindingBuilder.bind(filaOrdens).to(eventosExchange).with(ROTA_ORDEM_EXECUTADA);
    }

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter conversor = new Jackson2JsonMessageConverter(
                objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        // cada servico tem suas proprias classes de evento: usa o tipo do parametro do listener
        conversor.setAlwaysConvertToInferredType(true);
        return conversor;
    }
}
