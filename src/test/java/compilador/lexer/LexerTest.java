package compilador.lexer;

import compilador.errores.ErrorCompilacion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static compilador.lexer.TipoToken.*;
import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    /** Analiza el código y regresa solo los tipos de los tokens. */
    private List<TipoToken> tipos(String codigo) {
        return Lexer.analizar(codigo).stream().map(Token::tipo).toList();
    }

    @Test
    void declaracionDeVariable() {
        assertEquals(
                List.of(ENTERO, IDENTIFICADOR, ASIGNACION, NUMERO, PUNTO_COMA, FIN),
                tipos("entero x = 10;"));
    }

    @Test
    void codigoVacioSoloTieneFin() {
        assertEquals(List.of(FIN), tipos(""));
        assertEquals(List.of(FIN), tipos("   \n\t  \n"));
    }

    @Test
    void operadoresDeUnoYDosCaracteres() {
        assertEquals(
                List.of(MAS, MENOS, POR, ENTRE, MODULO,
                        ASIGNACION, IGUAL_IGUAL, DIFERENTE,
                        MENOR, MENOR_IGUAL, MAYOR, MAYOR_IGUAL, FIN),
                tipos("+ - * / % = == != < <= > >="));
    }

    @Test
    void operadoresPegadosSinEspacios() {
        assertEquals(
                List.of(IDENTIFICADOR, MENOR_IGUAL, NUMERO, FIN),
                tipos("x<=10"));
    }

    @Test
    void signosDePuntuacion() {
        assertEquals(
                List.of(PAREN_IZQ, PAREN_DER, LLAVE_IZQ, LLAVE_DER, COMA, PUNTO_COMA, DOS_PUNTOS, FIN),
                tipos("(){},;:"));
    }

    @Test
    void palabrasReservadas() {
        assertEquals(
                List.of(ENTERO, BOOLEANO, VERDADERO, FALSO, SI, SINO, MIENTRAS, PARA,
                        FUNCION, REGRESA, IMPRIMIR, Y, O, NO, FIN),
                tipos("entero booleano verdadero falso si sino mientras para "
                        + "funcion regresa imprimir y o no"));
    }

    @Test
    void identificadoresQueEmpiezanComoPalabraReservada() {
        // "siguiente" empieza con "si" pero es un identificador
        List<Token> tokens = Lexer.analizar("siguiente enteros no_valido yo");
        for (int i = 0; i < 4; i++) {
            assertEquals(IDENTIFICADOR, tokens.get(i).tipo());
        }
        assertEquals("siguiente", tokens.get(0).lexema());
    }

    @Test
    void identificadoresConAcentosYGuionBajo() {
        List<Token> tokens = Lexer.analizar("año _temp x2 número");
        assertEquals(List.of("año", "_temp", "x2", "número"),
                tokens.subList(0, 4).stream().map(Token::lexema).toList());
        assertTrue(tokens.subList(0, 4).stream().allMatch(t -> t.tipo() == IDENTIFICADOR));
    }

    @Test
    void numeroGuardaSuTexto() {
        Token t = Lexer.analizar("12345").get(0);
        assertEquals(NUMERO, t.tipo());
        assertEquals("12345", t.lexema());
    }

    @Test
    void comentariosSeIgnoran() {
        assertEquals(
                List.of(ENTERO, IDENTIFICADOR, PUNTO_COMA, FIN),
                tipos("// comentario\nentero /* otro\ncomentario */ x; // final"));
    }

    @Test
    void divisionNoSeConfundeConComentario() {
        assertEquals(List.of(NUMERO, ENTRE, NUMERO, FIN), tipos("10 / 2"));
    }

    @Test
    void lineaYColumnaCorrectas() {
        List<Token> tokens = Lexer.analizar("entero x;\n  x = 5;");
        // "x" de la segunda línea
        Token x = tokens.get(3);
        assertEquals(IDENTIFICADOR, x.tipo());
        assertEquals(2, x.linea());
        assertEquals(3, x.columna());
        // "5"
        Token cinco = tokens.get(5);
        assertEquals(2, cinco.linea());
        assertEquals(7, cinco.columna());
    }

    @Test
    void lineasCorrectasDespuesDeComentarioDeBloque() {
        List<Token> tokens = Lexer.analizar("/* a\nb\nc */ x");
        assertEquals(3, tokens.get(0).linea());
        assertEquals(6, tokens.get(0).columna());
    }

    // ---------------- Errores ----------------

    @Test
    void caracterNoReconocido() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar("entero x = 5;\nentero @y;"));
        assertTrue(e.getMessage().contains("'@'"));
        assertEquals(2, e.getLinea());
        assertEquals(8, e.getColumna());
    }

    @Test
    void signoDeAdmiracionSolo() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar("!x"));
        assertTrue(e.getMessage().contains("no"));
    }

    @Test
    void comentarioSinCerrar() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar("x /* nunca se cierra"));
        assertTrue(e.getMessage().contains("sin cerrar"));
        assertEquals(3, e.getColumna());
    }

    @Test
    void numeroMalFormado() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar("12abc"));
        assertTrue(e.getMessage().contains("12abc"));
    }

    @Test
    void numeroDemasiadoGrande() {
        assertDoesNotThrow(() -> Lexer.analizar("9223372036854775807"));
        assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar("9223372036854775808"));
    }

    // ---------------- Decimales ----------------

    @Test
    void numeroDecimal() {
        Token t = Lexer.analizar("3.14").get(0);
        assertEquals(NUMERO_DECIMAL, t.tipo());
        assertEquals("3.14", t.lexema());
    }

    @Test
    void enteroYDecimalSeDistinguen() {
        assertEquals(List.of(NUMERO, MAS, NUMERO_DECIMAL, FIN), tipos("2 + 2.0"));
    }

    @Test
    void decimalSinParteDecimal() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar("x = 5.;"));
        assertEquals("número mal formado '5.' (después del punto debe haber dígitos, por ejemplo 5.0)",
                e.getMessage());
    }

    @Test
    void decimalSinParteEntera() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar("x = .5;"));
        assertEquals("número mal formado '.5' (escribe 0.5)", e.getMessage());
        assertEquals(5, e.getColumna());
    }

    @Test
    void puntoSuelto() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar("a.b"));
        assertEquals("carácter no reconocido '.'", e.getMessage());
    }

    @Test
    void decimalDemasiadoGrande() {
        String enorme = "1" + "0".repeat(400) + ".0";
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar(enorme));
        assertTrue(e.getMessage().endsWith("es demasiado grande"), e.getMessage());
    }

    @Test
    void palabrasDecimalYEscribir() {
        assertEquals(List.of(DECIMAL, IDENTIFICADOR, ESCRIBIR, FIN), tipos("decimal x escribir"));
    }

    // ---------------- Textos ----------------

    @Test
    void textoSimple() {
        Token t = Lexer.analizar("\"Hola mundo\"").get(0);
        assertEquals(CADENA, t.tipo());
        assertEquals("\"Hola mundo\"", t.lexema());
        assertEquals("Hola mundo", Lexer.valorDeTexto(t.lexema()));
    }

    @Test
    void textoVacio() {
        Token t = Lexer.analizar("\"\"").get(0);
        assertEquals(CADENA, t.tipo());
        assertEquals("", Lexer.valorDeTexto(t.lexema()));
    }

    @Test
    void textoConEscapes() {
        Token t = Lexer.analizar("\"a\\nb\\tc\\\"d\\\\e\"").get(0);
        assertEquals("a\nb\tc\"d\\e", Lexer.valorDeTexto(t.lexema()));
    }

    @Test
    void textoConAcentosYSimbolos() {
        Token t = Lexer.analizar("\"¡Año, canción! // no es comentario\"").get(0);
        assertEquals("¡Año, canción! // no es comentario", Lexer.valorDeTexto(t.lexema()));
    }

    @Test
    void textoDentroDeUnaSentencia() {
        assertEquals(List.of(IMPRIMIR, PAREN_IZQ, CADENA, MAS, IDENTIFICADOR, PAREN_DER, PUNTO_COMA, FIN),
                tipos("imprimir(\"x = \" + x);"));
    }

    @Test
    void palabraReservadaTexto() {
        assertEquals(List.of(TEXTO, IDENTIFICADOR, FIN), tipos("texto nombre"));
    }

    @Test
    void textoSinCerrarAlFinalDelArchivo() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar("x = \"hola"));
        assertTrue(e.getMessage().startsWith("texto sin cerrar"), e.getMessage());
        assertEquals(5, e.getColumna());
    }

    @Test
    void textoNoPuedeOcuparVariasLineas() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar("\"hola\nmundo\""));
        assertTrue(e.getMessage().startsWith("texto sin cerrar"), e.getMessage());
        assertEquals(1, e.getLinea());
    }

    @Test
    void escapeNoValido() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar("\"ab\\qc\""));
        assertEquals("secuencia de escape no válida '\\q' (se permiten \\n, \\t, \\\" y \\\\)", e.getMessage());
        assertEquals(4, e.getColumna());
    }

    @Test
    void diagonalAlFinalDelTexto() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class, () -> Lexer.analizar("\"abc\\"));
        assertTrue(e.getMessage().startsWith("texto sin cerrar"), e.getMessage());
    }

    @Test
    void ignoraMarcaBomAlInicio() {
        List<Token> tokens = Lexer.analizar("﻿entero x;");
        assertEquals(ENTERO, tokens.get(0).tipo());
        assertEquals(1, tokens.get(0).columna());
    }

    @Test
    void caracterInvisibleSeMuestraConSuCodigo() {
        // Espacio "no separable", común al copiar código de páginas web
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar("entero x;"));
        assertTrue(e.getMessage().contains("U+00A0"), e.getMessage());
    }

    @Test
    void emojiSeReportaCompleto() {
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar("x = 😀;"));
        assertTrue(e.getMessage().contains("'😀'"), e.getMessage());
    }

    @Test
    void listaDeTokensNoSePuedeModificar() {
        List<Token> tokens = Lexer.analizar("x");
        assertThrows(UnsupportedOperationException.class, () -> tokens.add(tokens.get(0)));
    }

    @Test
    void mensajeDeErrorSenalaLaPosicion() {
        String codigo = "entero x = 5;\nentero @y;";
        ErrorCompilacion e = assertThrows(ErrorCompilacion.class,
                () -> Lexer.analizar(codigo));
        String esperado = """
                prueba.mio:2:8: error léxico: carácter no reconocido '@'
                    entero @y;
                           ^""";
        assertEquals(esperado, e.formatear("prueba.mio", codigo));
    }
}
