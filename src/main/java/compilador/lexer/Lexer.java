package compilador.lexer;

import compilador.errores.ErrorCompilacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static compilador.lexer.TipoToken.*;

/**
 * Analizador léxico: convierte el texto del programa en una lista de tokens.
 *
 * Recorre el código carácter por carácter. En cada paso decide qué token
 * empieza en esa posición, lo consume completo y lo agrega a la lista.
 * Los espacios, saltos de línea y comentarios se ignoran.
 */
public final class Lexer {

    private static final String FASE = "léxico";

    /** Marca invisible que algunos editores de Windows ponen al inicio del archivo. */
    private static final char MARCA_BOM = '﻿';

    private static final Map<String, TipoToken> PALABRAS_RESERVADAS = Map.ofEntries(
            Map.entry("entero", ENTERO),
            Map.entry("decimal", DECIMAL),
            Map.entry("booleano", BOOLEANO),
            Map.entry("texto", TEXTO),
            Map.entry("verdadero", VERDADERO),
            Map.entry("falso", FALSO),
            Map.entry("si", SI),
            Map.entry("sino", SINO),
            Map.entry("mientras", MIENTRAS),
            Map.entry("para", PARA),
            Map.entry("funcion", FUNCION),
            Map.entry("regresa", REGRESA),
            Map.entry("imprimir", IMPRIMIR),
            Map.entry("escribir", ESCRIBIR),
            Map.entry("y", Y),
            Map.entry("o", O),
            Map.entry("no", NO)
    );

    /** Secuencias de escape permitidas dentro de un texto: \n, \t, \" y \\. */
    private static final Map<Character, Character> ESCAPES = Map.of(
            'n', '\n',
            't', '\t',
            '"', '"',
            '\\', '\\'
    );

    private final String fuente;
    private final List<Token> tokens = new ArrayList<>();

    private int actual = 0;     // posición del siguiente carácter por leer
    private int linea = 1;      // línea del siguiente carácter por leer
    private int columna = 1;    // columna del siguiente carácter por leer

    // Dónde empieza el token que se está leyendo
    private int inicio;
    private int lineaInicio;
    private int columnaInicio;

    private Lexer(String fuente) {
        this.fuente = fuente;
        if (!fuente.isEmpty() && fuente.charAt(0) == MARCA_BOM) {
            actual = 1;
        }
    }

    /** Analiza el código fuente completo y regresa sus tokens. El último siempre es FIN. */
    public static List<Token> analizar(String fuente) {
        return new Lexer(fuente).tokenizar();
    }

    private List<Token> tokenizar() {
        while (!finDeArchivo()) {
            inicio = actual;
            lineaInicio = linea;
            columnaInicio = columna;
            analizarToken();
        }
        tokens.add(new Token(FIN, "", linea, columna));
        return List.copyOf(tokens);
    }

    private void analizarToken() {
        char c = avanzar();
        switch (c) {
            // Espacios en blanco: no producen tokens
            case ' ', '\t', '\r', '\n' -> { }

            // Símbolos de un solo carácter
            case '(' -> agregar(PAREN_IZQ);
            case ')' -> agregar(PAREN_DER);
            case '{' -> agregar(LLAVE_IZQ);
            case '}' -> agregar(LLAVE_DER);
            case ',' -> agregar(COMA);
            case ';' -> agregar(PUNTO_COMA);
            case ':' -> agregar(DOS_PUNTOS);
            case '+' -> agregar(MAS);
            case '-' -> agregar(MENOS);
            case '*' -> agregar(POR);
            case '%' -> agregar(MODULO);

            // Símbolos que pueden ser de uno o dos caracteres
            case '=' -> agregar(coincide('=') ? IGUAL_IGUAL : ASIGNACION);
            case '<' -> agregar(coincide('=') ? MENOR_IGUAL : MENOR);
            case '>' -> agregar(coincide('=') ? MAYOR_IGUAL : MAYOR);
            case '!' -> {
                if (!coincide('=')) {
                    throw error("se esperaba '=' después de '!' (para negar usa 'no')");
                }
                agregar(DIFERENTE);
            }

            // '/' puede ser división o el inicio de un comentario
            case '/' -> {
                if (coincide('/')) {
                    comentarioDeLinea();
                } else if (coincide('*')) {
                    comentarioDeBloque();
                } else {
                    agregar(ENTRE);
                }
            }

            case '"' -> texto();

            // Un punto solo puede aparecer dentro de un número decimal
            case '.' -> {
                if (esDigito(ver())) {
                    while (esDigito(ver())) {
                        avanzar();
                    }
                    throw error("número mal formado '" + textoActual() + "' (escribe 0" + textoActual() + ")");
                }
                throw error("carácter no reconocido '.'");
            }

            default -> {
                if (esDigito(c)) {
                    numero();
                } else if (esLetra(c)) {
                    identificadorOPalabraReservada();
                } else {
                    // Caracteres fuera del plano básico (como emojis) ocupan dos char en Java
                    if (Character.isHighSurrogate(c) && Character.isLowSurrogate(ver())) {
                        avanzar();
                    }
                    throw error("carácter no reconocido " + describirCaracter(textoActual()));
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Tokens de varios caracteres
    // ---------------------------------------------------------------

    /** Número entero (42) o decimal (3.14). Un decimal lleva dígitos a los dos lados del punto. */
    private void numero() {
        while (esDigito(ver())) {
            avanzar();
        }

        boolean esDecimal = false;
        if (ver() == '.') {
            avanzar();
            if (!esDigito(ver())) {
                throw error("número mal formado '" + textoActual()
                        + "' (después del punto debe haber dígitos, por ejemplo " + textoActual() + "0)");
            }
            while (esDigito(ver())) {
                avanzar();
            }
            esDecimal = true;
        }

        // Algo como "12abc" no es un número ni un identificador válido
        if (esLetra(ver())) {
            while (esLetra(ver()) || esDigito(ver())) {
                avanzar();
            }
            throw error("número mal formado '" + textoActual() + "'");
        }

        if (esDecimal) {
            // Los decimales son de 64 bits (double): hasta 1.7976931348623157 × 10^308
            if (Double.isInfinite(Double.parseDouble(textoActual()))) {
                throw error("el número " + textoActual() + " es demasiado grande");
            }
            agregar(NUMERO_DECIMAL);
            return;
        }

        // Los enteros son de 64 bits, igual que los registros de x86-64
        try {
            Long.parseLong(textoActual());
        } catch (NumberFormatException e) {
            throw error("el número " + textoActual() + " es demasiado grande (máximo "
                    + Long.MAX_VALUE + ")");
        }
        agregar(NUMERO);
    }

    private void identificadorOPalabraReservada() {
        while (esLetra(ver()) || esDigito(ver())) {
            avanzar();
        }
        TipoToken tipo = PALABRAS_RESERVADAS.getOrDefault(textoActual(), IDENTIFICADOR);
        agregar(tipo);
    }

    /**
     * Texto entre comillas: "Hola\n". Debe cerrarse en la misma línea.
     * Aquí solo se valida; el valor real (con los escapes ya convertidos) lo da valorDeTexto().
     */
    private void texto() {
        while (ver() != '"') {
            if (finDeArchivo() || ver() == '\n') {
                throw error("texto sin cerrar: falta '\"' antes del final de la línea");
            }
            if (ver() == '\\') {
                int lineaEscape = linea;
                int columnaEscape = columna;
                avanzar();
                if (finDeArchivo() || ver() == '\n') {
                    throw error("texto sin cerrar: falta '\"' antes del final de la línea");
                }
                if (!ESCAPES.containsKey(ver())) {
                    throw new ErrorCompilacion(FASE, "secuencia de escape no válida '\\" + ver()
                            + "' (se permiten \\n, \\t, \\\" y \\\\)", lineaEscape, columnaEscape);
                }
            }
            avanzar();
        }
        avanzar();  // comilla de cierre
        agregar(CADENA);
    }

    /**
     * Convierte el lexema de un texto, con comillas y escapes ("Hola\n"), en su valor real.
     * El lexema debe venir de un token CADENA, que el lexer ya validó.
     */
    public static String valorDeTexto(String lexema) {
        StringBuilder valor = new StringBuilder();
        for (int i = 1; i < lexema.length() - 1; i++) {
            char c = lexema.charAt(i);
            if (c == '\\') {
                c = ESCAPES.get(lexema.charAt(++i));
            }
            valor.append(c);
        }
        return valor.toString();
    }

    /** Comentario de una línea: // hasta el final de la línea. */
    private void comentarioDeLinea() {
        while (ver() != '\n' && !finDeArchivo()) {
            avanzar();
        }
    }

    /** Comentario de bloque: desde /* hasta el siguiente cierre. */
    private void comentarioDeBloque() {
        while (!finDeArchivo()) {
            if (ver() == '*' && verSiguiente() == '/') {
                avanzar();
                avanzar();
                return;
            }
            avanzar();
        }
        throw error("comentario '/*' sin cerrar");
    }

    // ---------------------------------------------------------------
    // Funciones auxiliares para recorrer el texto
    // ---------------------------------------------------------------

    /** Consume el carácter actual y lo regresa, llevando la cuenta de línea y columna. */
    private char avanzar() {
        char c = fuente.charAt(actual++);
        if (c == '\n') {
            linea++;
            columna = 1;
        } else {
            columna++;
        }
        return c;
    }

    /** Si el carácter actual es el esperado, lo consume y regresa true. */
    private boolean coincide(char esperado) {
        if (finDeArchivo() || fuente.charAt(actual) != esperado) {
            return false;
        }
        avanzar();
        return true;
    }

    /** Mira el carácter actual sin consumirlo. */
    private char ver() {
        return finDeArchivo() ? '\0' : fuente.charAt(actual);
    }

    /** Mira el carácter que sigue al actual sin consumirlo. */
    private char verSiguiente() {
        return actual + 1 >= fuente.length() ? '\0' : fuente.charAt(actual + 1);
    }

    private boolean finDeArchivo() {
        return actual >= fuente.length();
    }

    private String textoActual() {
        return fuente.substring(inicio, actual);
    }

    private void agregar(TipoToken tipo) {
        tokens.add(new Token(tipo, textoActual(), lineaInicio, columnaInicio));
    }

    private static boolean esDigito(char c) {
        return c >= '0' && c <= '9';
    }

    /** Letras (incluye acentos y ñ) o guion bajo. */
    private static boolean esLetra(char c) {
        return Character.isLetter(c) || c == '_';
    }

    /** Los caracteres invisibles se muestran por su código para que el usuario pueda encontrarlos. */
    private static String describirCaracter(String caracter) {
        int codigo = caracter.codePointAt(0);
        boolean invisible = Character.isISOControl(codigo)
                || Character.isSpaceChar(codigo)
                || Character.getType(codigo) == Character.FORMAT;
        return invisible ? String.format("(invisible, código U+%04X)", codigo) : "'" + caracter + "'";
    }

    private ErrorCompilacion error(String mensaje) {
        return new ErrorCompilacion(FASE, mensaje, lineaInicio, columnaInicio);
    }
}
