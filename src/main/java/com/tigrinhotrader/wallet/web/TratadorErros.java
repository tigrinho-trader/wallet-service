package com.tigrinhotrader.wallet.web;

import com.tigrinhotrader.wallet.dominio.SaldoInsuficienteException;
import com.tigrinhotrader.wallet.pix.CompraNaoEncontradaException;
import com.tigrinhotrader.wallet.pix.PixIndisponivelException;
import com.tigrinhotrader.wallet.servico.CupomJaUsadoException;
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

    @ExceptionHandler(CupomJaUsadoException.class)
    ProblemDetail cupomJaUsado(CupomJaUsadoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CompraNaoEncontradaException.class)
    ProblemDetail naoEncontrada(CompraNaoEncontradaException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PixIndisponivelException.class)
    ProblemDetail pixIndisponivel(PixIndisponivelException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail semUsuario(MissingRequestHeaderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Cabecalho " + e.getHeaderName() + " ausente");
    }
}
