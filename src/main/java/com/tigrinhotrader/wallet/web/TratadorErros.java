package com.tigrinhotrader.wallet.web;

import com.tigrinhotrader.wallet.dominio.SaldoInsuficienteException;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TratadorErros {

    @ExceptionHandler({RegraNegocioException.class, SaldoInsuficienteException.class})
    ProblemDetail regraNegocio(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail semUsuario(MissingRequestHeaderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Cabecalho " + e.getHeaderName() + " ausente");
    }
}
