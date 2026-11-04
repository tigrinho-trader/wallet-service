package com.tigrinhotrader.wallet.repasse;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pix copia-e-cola (BR Code, formato EMV): achar dentro de uma resposta JSON e ler os campos. */
final class CodigoPix {

    private CodigoPix() {
    }

    /** Procura em qualquer campo da resposta um BR Code (comeca com 000201 e tem BR.GOV.BCB.PIX). */
    static String acharEm(JsonNode no) {
        if (no == null) {
            return null;
        }
        if (no.isTextual()) {
            String texto = no.asText().trim();
            return texto.startsWith("000201") && texto.toUpperCase().contains("BR.GOV.BCB.PIX") ? texto : null;
        }
        for (JsonNode filho : no) {
            String achado = acharEm(filho);
            if (achado != null) {
                return achado;
            }
        }
        return null;
    }

    /** Campos de topo: id de 2 digitos, tamanho de 2 digitos, valor. 54 = valor, 59 = recebedor. */
    static Map<String, String> campos(String codigo) {
        Map<String, String> campos = new LinkedHashMap<>();
        int i = 0;
        while (i + 4 <= codigo.length()) {
            String id = codigo.substring(i, i + 2);
            int tamanho = Integer.parseInt(codigo.substring(i + 2, i + 4));
            campos.put(id, codigo.substring(i + 4, Math.min(codigo.length(), i + 4 + tamanho)));
            i += 4 + tamanho;
        }
        return campos;
    }

    static BigDecimal valor(String codigo) {
        String valor = campos(codigo).get("54");
        return valor == null ? null : new BigDecimal(valor);
    }
}
