package com.tigrinhotrader.wallet.saque;

import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chave Pix de destino, ja no formato que o Asaas pede: CPF e CNPJ so com digitos (e digitos verificadores
 * validos), celular com DDD + 9 digitos (sem +55), e-mail e chave aleatoria em minusculas.
 */
public record ChavePix(Tipo tipo, String valor) {

    /** Os nomes sao os do campo pixAddressKeyType do Asaas. */
    public enum Tipo { CPF, CNPJ, EMAIL, PHONE, EVP }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern EVP = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public static ChavePix de(String tipo, String chave) {
        if (tipo == null || tipo.isBlank() || chave == null || chave.isBlank()) {
            throw new RegraNegocioException("Informe o tipo e a chave Pix");
        }
        Tipo t;
        try {
            t = Tipo.valueOf(tipo.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RegraNegocioException("Tipo de chave invalido: use CPF, CNPJ, EMAIL, PHONE ou EVP");
        }
        String c = chave.trim();
        return switch (t) {
            case CPF -> {
                String d = digitos(c);
                if (!cpfValido(d)) {
                    throw new RegraNegocioException("CPF invalido");
                }
                yield new ChavePix(t, d);
            }
            case CNPJ -> {
                String d = digitos(c);
                if (!cnpjValido(d)) {
                    throw new RegraNegocioException("CNPJ invalido");
                }
                yield new ChavePix(t, d);
            }
            case EMAIL -> {
                String e = c.toLowerCase(Locale.ROOT);
                if (e.length() > 77 || !EMAIL.matcher(e).matches()) {
                    throw new RegraNegocioException("E-mail invalido");
                }
                yield new ChavePix(t, e);
            }
            case PHONE -> {
                String d = digitos(c);
                if (d.length() == 13 && d.startsWith("55")) {
                    d = d.substring(2); // +55 digitado: o Asaas quer so DDD + numero
                }
                if (d.length() != 11) {
                    throw new RegraNegocioException("Celular invalido: informe DDD + 9 digitos");
                }
                yield new ChavePix(t, d);
            }
            case EVP -> {
                String e = c.toLowerCase(Locale.ROOT);
                if (!EVP.matcher(e).matches()) {
                    throw new RegraNegocioException("Chave aleatoria invalida");
                }
                yield new ChavePix(t, e);
            }
        };
    }

    /** O suficiente para reconhecer a chave na tela, sem mostra-la inteira. */
    public static String mascarar(Tipo tipo, String valor) {
        if (valor == null) {
            return "";
        }
        return switch (tipo) {
            case CPF -> "***." + valor.substring(3, 6) + "." + valor.substring(6, 9) + "-**";
            case CNPJ -> "**.***." + valor.substring(5, 8) + "/" + valor.substring(8, 12) + "-**";
            case EMAIL -> {
                int arroba = valor.indexOf('@');
                yield valor.substring(0, Math.min(2, arroba)) + "***" + valor.substring(arroba);
            }
            case PHONE -> "(" + valor.substring(0, 2) + ") *****-" + valor.substring(7);
            case EVP -> valor.substring(0, 8) + "-****-" + valor.substring(valor.length() - 4);
        };
    }

    private static String digitos(String texto) {
        return texto.replaceAll("\\D", "");
    }

    static boolean cpfValido(String d) {
        if (d.length() != 11 || d.chars().distinct().count() == 1) {
            return false;
        }
        return digito(d, 9, 10) == d.charAt(9) - '0' && digito(d, 10, 11) == d.charAt(10) - '0';
    }

    private static int digito(String d, int quantos, int pesoInicial) {
        int soma = 0;
        for (int i = 0; i < quantos; i++) {
            soma += (d.charAt(i) - '0') * (pesoInicial - i);
        }
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }

    static boolean cnpjValido(String d) {
        if (d.length() != 14 || d.chars().distinct().count() == 1) {
            return false;
        }
        int[] pesos1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] pesos2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return digitoCnpj(d, pesos1) == d.charAt(12) - '0' && digitoCnpj(d, pesos2) == d.charAt(13) - '0';
    }

    private static int digitoCnpj(String d, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (d.charAt(i) - '0') * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
