package compilador.parser;

import compilador.ast.Expresion;
import compilador.ast.Expresion.Binaria;
import compilador.ast.Expresion.Conversion;
import compilador.ast.Expresion.LiteralDecimal;
import compilador.ast.Expresion.LiteralBooleano;
import compilador.ast.Expresion.LiteralTexto;
import compilador.ast.Expresion.Llamada;
import compilador.ast.Expresion.Numero;
import compilador.ast.Expresion.Unaria;
import compilador.ast.Expresion.Variable;
import compilador.ast.Funcion;
import compilador.ast.Programa;
import compilador.ast.Sentencia;
import compilador.ast.Sentencia.Asignacion;
import compilador.ast.Sentencia.Bloque;
import compilador.ast.Sentencia.Declaracion;
import compilador.ast.Sentencia.Imprimir;
import compilador.ast.Sentencia.Mientras;
import compilador.ast.Sentencia.Para;
import compilador.ast.Sentencia.Regresa;
import compilador.ast.Sentencia.SentenciaLlamada;
import compilador.ast.Sentencia.Si;
import compilador.ast.Tipo;
import compilador.errores.ErrorCompilacion;
import compilador.lexer.Lexer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private static Programa analizar(String codigo) {
        return Parser.analizar(Lexer.analizar(codigo));
    }

    /** Analiza sentencias dentro de una función principal y regresa la lista. */
    private static List<Sentencia> sentencias(String cuerpo) {
        return analizar("funcion principal() {\n" + cuerpo + "\n}").funciones().get(0).cuerpo().sentencias();
    }

    /** Analiza una expresión y la regresa con paréntesis que muestran cómo se agrupó. */
    private static String agrupacion(String expresion) {
        Imprimir imprimir = (Imprimir) sentencias("imprimir(" + expresion + ");").get(0);
        return texto(imprimir.valor());
    }

    private static String texto(Expresion e) {
        return switch (e) {
            case Numero n -> Long.toString(n.valor());
            case LiteralDecimal d -> Double.toString(d.valor());
            case Conversion c -> c.destino() + "(" + texto(c.valor()) + ")";
            case LiteralBooleano b -> b.valor() ? "verdadero" : "falso";
            case LiteralTexto t -> "\"" + t.valor() + "\"";
            case Variable v -> v.nombre();
            case Unaria u -> "(" + u.operador().simbolo() + " " + texto(u.operando()) + ")";
            case Binaria b -> "(" + texto(b.izquierda()) + " " + b.operador().simbolo() + " " + texto(b.derecha()) + ")";
            case Llamada l -> l.nombre() + "("
                    + l.argumentos().stream().map(ParserTest::texto).collect(Collectors.joining(", ")) + ")";
        };
    }

    private static ErrorCompilacion error(String codigo) {
        return assertThrows(ErrorCompilacion.class, () -> analizar(codigo));
    }

    @Nested
    class Expresiones {

        @Test
        void multiplicacionAntesQueSuma() {
            assertEquals("(1 + (2 * 3))", agrupacion("1 + 2 * 3"));
            assertEquals("((1 * 2) + 3)", agrupacion("1 * 2 + 3"));
        }

        @Test
        void asociatividadIzquierda() {
            assertEquals("((10 - 3) - 2)", agrupacion("10 - 3 - 2"));
            assertEquals("((100 / 10) / 2)", agrupacion("100 / 10 / 2"));
        }

        @Test
        void parentesisCambianLaAgrupacion() {
            assertEquals("((1 + 2) * 3)", agrupacion("(1 + 2) * 3"));
        }

        @Test
        void precedenciaDeComparacionIgualdadYLogica() {
            assertEquals("(((a + 1) < b) == verdadero)", agrupacion("a + 1 < b == verdadero"));
            assertEquals("(a o (b y (no c)))", agrupacion("a o b y no c"));
            assertEquals("((x > 0) y (x < 10))", agrupacion("x > 0 y x < 10"));
        }

        @Test
        void operadoresUnarios() {
            assertEquals("(- (- x))", agrupacion("- -x"));
            assertEquals("((- x) * 2)", agrupacion("-x * 2"));
            assertEquals("(no (a == b))", agrupacion("no (a == b)"));
        }

        @Test
        void llamadas() {
            assertEquals("f()", agrupacion("f()"));
            assertEquals("f(1, g(2), (x + 1))", agrupacion("f(1, g(2), x + 1)"));
        }

        @Test
        void textos() {
            assertEquals("\"hola\"", agrupacion("\"hola\""));
            assertEquals("((\"x = \" + x) + \"!\")", agrupacion("\"x = \" + x + \"!\""));
            assertEquals("(\"a\" + (2 * 3))", agrupacion("\"a\" + 2 * 3"));
        }

        @Test
        void decimales() {
            assertEquals("3.14", agrupacion("3.14"));
            assertEquals("(1.5 + (2 * 0.5))", agrupacion("1.5 + 2 * 0.5"));
            assertEquals("(- 2.5)", agrupacion("-2.5"));
        }

        @Test
        void conversiones() {
            assertEquals("entero(3.7)", agrupacion("entero(3.7)"));
            assertEquals("(decimal(x) / 2)", agrupacion("decimal(x) / 2"));
            assertEquals("texto((1 + 2))", agrupacion("texto(1 + 2)"));
        }

        @Test
        void funcionesDeLectura() {
            assertEquals("(leerEntero() + 1)", agrupacion("leerEntero() + 1"));
        }

        @Test
        void textoConEscapesTieneSuValorReal() {
            Imprimir imprimir = (Imprimir) sentencias("imprimir(\"a\\nb\");").get(0);
            assertEquals("a\nb", ((LiteralTexto) imprimir.valor()).valor());
        }

        @Test
        void literales() {
            assertEquals("9223372036854775807", agrupacion("9223372036854775807"));
            assertEquals("verdadero", agrupacion("verdadero"));
            assertEquals("falso", agrupacion("falso"));
        }
    }

    @Nested
    class Sentencias {

        @Test
        void funcionConParametrosYTipoDeRetorno() {
            Funcion f = analizar("funcion suma(entero a, booleano b) : entero { regresa a; }")
                    .funciones().get(0);
            assertEquals("suma", f.nombre());
            assertEquals(Tipo.ENTERO, f.tipoRetorno());
            assertEquals(2, f.parametros().size());
            assertEquals("a", f.parametros().get(0).nombre());
            assertEquals(Tipo.BOOLEANO, f.parametros().get(1).tipo());
        }

        @Test
        void funcionSinTipoDeRetornoEsVacio() {
            Funcion f = analizar("funcion principal() { }").funciones().get(0);
            assertEquals(Tipo.VACIO, f.tipoRetorno());
            assertTrue(f.parametros().isEmpty());
            assertTrue(f.cuerpo().sentencias().isEmpty());
        }

        @Test
        void declaraciones() {
            List<Sentencia> s = sentencias("entero x = 5; booleano listo;");
            Declaracion conValor = (Declaracion) s.get(0);
            assertEquals(Tipo.ENTERO, conValor.tipo());
            assertEquals("x", conValor.nombre());
            assertTrue(conValor.valorInicial().isPresent());
            Declaracion sinValor = (Declaracion) s.get(1);
            assertEquals(Tipo.BOOLEANO, sinValor.tipo());
            assertTrue(sinValor.valorInicial().isEmpty());
        }

        @Test
        void declaracionDeDecimal() {
            Declaracion d = (Declaracion) sentencias("decimal precio = 19.99;").get(0);
            assertEquals(Tipo.DECIMAL, d.tipo());
        }

        @Test
        void imprimirYEscribir() {
            List<Sentencia> s = sentencias("imprimir(1); escribir(2);");
            assertTrue(((Imprimir) s.get(0)).saltoDeLinea());
            assertFalse(((Imprimir) s.get(1)).saltoDeLinea());
        }

        @Test
        void conversionNoEsDeclaracion() {
            // "entero(x)" sin nombre de variable al inicio de una sentencia no tiene sentido
            ErrorCompilacion e = error("funcion principal() { entero(5); }");
            assertTrue(e.getMessage().startsWith("se esperaba el nombre de la variable"), e.getMessage());
        }

        @Test
        void nombreDeTipoSinParentesisEnExpresion() {
            ErrorCompilacion e = error("funcion principal() { imprimir(decimal); }");
            assertTrue(e.getMessage().contains("'decimal', que es una palabra reservada"), e.getMessage());
        }

        @Test
        void declaracionDeTexto() {
            Declaracion d = (Declaracion) sentencias("texto nombre = \"Ana\";").get(0);
            assertEquals(Tipo.TEXTO, d.tipo());
            assertEquals("nombre", d.nombre());
        }

        @Test
        void parametroYRetornoDeTexto() {
            Funcion f = analizar("funcion saludo(texto nombre) : texto { regresa nombre; }").funciones().get(0);
            assertEquals(Tipo.TEXTO, f.tipoRetorno());
            assertEquals(Tipo.TEXTO, f.parametros().get(0).tipo());
        }

        @Test
        void asignacionYLlamadaComoSentencia() {
            List<Sentencia> s = sentencias("x = 1; saludar();");
            assertInstanceOf(Asignacion.class, s.get(0));
            SentenciaLlamada llamada = assertInstanceOf(SentenciaLlamada.class, s.get(1));
            assertEquals("saludar", llamada.llamada().nombre());
        }

        @Test
        void siConSinoSiYSino() {
            Si si = (Si) sentencias("si (a) { } sino si (b) { } sino { }").get(0);
            Si sinoSi = assertInstanceOf(Si.class, si.sino().orElseThrow());
            assertInstanceOf(Bloque.class, sinoSi.sino().orElseThrow());
        }

        @Test
        void siSinSino() {
            Si si = (Si) sentencias("si (a) { imprimir(1); }").get(0);
            assertTrue(si.sino().isEmpty());
            assertEquals(1, si.entonces().sentencias().size());
        }

        @Test
        void mientras() {
            Mientras m = (Mientras) sentencias("mientras (i < 10) { i = i + 1; }").get(0);
            assertEquals("(i < 10)", texto(m.condicion()));
            assertEquals(1, m.cuerpo().sentencias().size());
        }

        @Test
        void paraCompleto() {
            Para p = (Para) sentencias("para (entero i = 0; i < 10; i = i + 1) { }").get(0);
            assertInstanceOf(Declaracion.class, p.inicio().orElseThrow());
            assertEquals("(i < 10)", texto(p.condicion().orElseThrow()));
            assertEquals("i", p.paso().orElseThrow().nombre());
        }

        @Test
        void paraConAsignacionInicial() {
            Para p = (Para) sentencias("para (i = 0; i < 3; i = i + 1) { }").get(0);
            assertInstanceOf(Asignacion.class, p.inicio().orElseThrow());
        }

        @Test
        void paraSinPartes() {
            Para p = (Para) sentencias("para (;;) { }").get(0);
            assertTrue(p.inicio().isEmpty());
            assertTrue(p.condicion().isEmpty());
            assertTrue(p.paso().isEmpty());
        }

        @Test
        void regresaConYSinValor() {
            List<Sentencia> s = sentencias("regresa; regresa 5;");
            assertTrue(((Regresa) s.get(0)).valor().isEmpty());
            assertTrue(((Regresa) s.get(1)).valor().isPresent());
        }

        @Test
        void bloqueAnidado() {
            Bloque b = (Bloque) sentencias("{ entero x; }").get(0);
            assertEquals(1, b.sentencias().size());
        }

        @Test
        void posicionesDeLosNodos() {
            Declaracion d = (Declaracion) sentencias("  entero valor = 1;").get(0);
            assertEquals(2, d.pos().linea());
            assertEquals(10, d.pos().columna());
        }

        @Test
        void programaVacio() {
            assertTrue(analizar("").funciones().isEmpty());
        }
    }

    @Nested
    class Errores {

        @Test
        void faltaPuntoYComaSeSenalaAlFinalDeLaLinea() {
            ErrorCompilacion e = error("funcion principal() {\n entero x = 5\n imprimir(x);\n}");
            assertEquals("se esperaba ';' al final de la línea", e.getMessage());
            assertEquals(2, e.getLinea());
            assertEquals(14, e.getColumna());
        }

        @Test
        void faltaParentesis() {
            ErrorCompilacion e = error("funcion principal() { imprimir(1; }");
            assertEquals("se esperaba ')' pero se encontró ';'", e.getMessage());
        }

        @Test
        void faltaExpresion() {
            ErrorCompilacion e = error("funcion principal() { entero x = ; }");
            assertEquals("se esperaba una expresión pero se encontró ';'", e.getMessage());
        }

        @Test
        void expresionSinEfectoNoEsSentencia() {
            ErrorCompilacion e = error("funcion principal() { x + 1; }");
            assertTrue(e.getMessage().startsWith("se esperaba '=' o '(' después de 'x'"), e.getMessage());
        }

        @Test
        void sentenciaInvalida() {
            ErrorCompilacion e = error("funcion principal() { 5 = x; }");
            assertEquals("se esperaba una sentencia pero se encontró el número 5", e.getMessage());
        }

        @Test
        void faltaLlaveDeCierre() {
            ErrorCompilacion e = error("funcion principal() {\n imprimir(1);\n");
            assertEquals("falta '}' para cerrar este bloque", e.getMessage());
            assertEquals(1, e.getLinea());
            assertEquals(21, e.getColumna());
        }

        @Test
        void funcionDentroDeOtra() {
            ErrorCompilacion e = error("funcion a() {\nfuncion b() { }\n}");
            assertTrue(e.getMessage().contains("no se pueden definir funciones dentro de otras"));
        }

        @Test
        void variableFueraDeFuncion() {
            ErrorCompilacion e = error("entero x = 5;");
            assertTrue(e.getMessage().contains("las variables se declaran dentro de una función"));
        }

        @Test
        void sinoSinSi() {
            ErrorCompilacion e = error("funcion principal() { sino { } }");
            assertEquals("'sino' sin un 'si' antes", e.getMessage());
        }

        @Test
        void tipoDeParametroInvalido() {
            ErrorCompilacion e = error("funcion f(x) { }");
            assertTrue(e.getMessage().startsWith("se esperaba un tipo"), e.getMessage());
        }

        @Test
        void faltaParentesisDeCierreEnLlamada() {
            ErrorCompilacion e = error("funcion principal() { f(1, 2; }");
            assertEquals("se esperaba ')' para cerrar la llamada a 'f' pero se encontró ';'", e.getMessage());
        }

        @Test
        void comaSobranteEnArgumentos() {
            ErrorCompilacion e = error("funcion principal() { f(1, ); }");
            assertEquals("se esperaba una expresión pero se encontró ')'", e.getMessage());
        }

        @Test
        void anidamientoExcesivo() {
            String profundo = "(".repeat(Parser.MAXIMO_ANIDAMIENTO + 1) + "1"
                    + ")".repeat(Parser.MAXIMO_ANIDAMIENTO + 1);
            ErrorCompilacion e = error("funcion principal() { imprimir(" + profundo + "); }");
            assertTrue(e.getMessage().contains("anidamiento"), e.getMessage());
        }

        @Test
        void anidamientoPermitido() {
            String profundo = "(".repeat(100) + "1" + ")".repeat(100);
            assertDoesNotThrow(() -> analizar("funcion principal() { imprimir(" + profundo + "); }"));
        }

        @Test
        void palabraReservadaComoNombreDeVariable() {
            ErrorCompilacion e = error("funcion principal() { entero y = 5; }");
            assertEquals("se esperaba el nombre de la variable pero se encontró 'y', "
                    + "que es una palabra reservada y no se puede usar como nombre", e.getMessage());
        }

        @Test
        void palabraReservadaEnUnaExpresion() {
            ErrorCompilacion e = error("funcion principal() { imprimir(o); }");
            assertTrue(e.getMessage().contains("'o', que es una palabra reservada"), e.getMessage());
        }

        @Test
        void palabraReservadaAlInicioDeUnaSentencia() {
            ErrorCompilacion e = error("funcion principal() { y = 1; }");
            assertTrue(e.getMessage().startsWith("se esperaba una sentencia pero se encontró 'y', que es una palabra"),
                    e.getMessage());
        }

        @Test
        void palabraReservadaComoNombreDeFuncion() {
            ErrorCompilacion e = error("funcion si() { }");
            assertTrue(e.getMessage().contains("'si', que es una palabra reservada"), e.getMessage());
        }

        @Test
        void errorEsSintactico() {
            assertEquals("sintáctico", error("funcion").getFase());
        }
    }
}
