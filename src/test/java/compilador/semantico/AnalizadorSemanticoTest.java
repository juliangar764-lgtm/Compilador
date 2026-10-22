package compilador.semantico;

import compilador.ast.Funcion;
import compilador.ast.Programa;
import compilador.ast.Sentencia.Declaracion;
import compilador.ast.Sentencia.Imprimir;
import compilador.ast.Tipo;
import compilador.errores.ErrorCompilacion;
import compilador.lexer.Lexer;
import compilador.parser.Parser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalizadorSemanticoTest {

    private static Programa arbol(String codigo) {
        return Parser.analizar(Lexer.analizar(codigo));
    }

    private static void valido(String codigo) {
        assertDoesNotThrow(() -> AnalizadorSemantico.analizar(arbol(codigo)));
    }

    /** Envuelve las sentencias en una función principal. */
    private static void principalValido(String cuerpo) {
        valido("funcion principal() {\n" + cuerpo + "\n}");
    }

    private static ErrorCompilacion error(String codigo) {
        Programa programa = arbol(codigo);
        return assertThrows(ErrorCompilacion.class, () -> AnalizadorSemantico.analizar(programa));
    }

    private static ErrorCompilacion errorEnPrincipal(String cuerpo) {
        return error("funcion principal() {\n" + cuerpo + "\n}");
    }

    private static void assertMensaje(String esperado, ErrorCompilacion e) {
        assertEquals(esperado, e.getMessage());
        assertEquals("semántico", e.getFase());
    }

    @Nested
    class Principal {

        @Test
        void programaMinimo() {
            valido("funcion principal() { }");
        }

        @Test
        void faltaPrincipal() {
            ErrorCompilacion e = error("funcion otra() { }");
            assertMensaje("falta la función 'principal', que es donde empieza el programa", e);
            assertFalse(e.tienePosicion());
        }

        @Test
        void principalConParametros() {
            assertMensaje("la función 'principal' no debe recibir parámetros",
                    error("funcion principal(entero x) { }"));
        }

        @Test
        void principalConTipoDeRetorno() {
            assertMensaje("la función 'principal' no debe tener tipo de retorno",
                    error("funcion principal() : entero { regresa 0; }"));
        }
    }

    @Nested
    class Variables {

        @Test
        void declaracionYUso() {
            principalValido("entero x = 5; x = x + 1; imprimir(x);");
        }

        @Test
        void variableNoDeclarada() {
            ErrorCompilacion e = errorEnPrincipal("imprimir(z);");
            assertMensaje("la variable 'z' no está declarada", e);
            assertEquals(2, e.getLinea());
            assertEquals(10, e.getColumna());
        }

        @Test
        void asignacionAVariableNoDeclarada() {
            assertMensaje("la variable 'z' no está declarada", errorEnPrincipal("z = 1;"));
        }

        @Test
        void variableEnSuPropiaInicializacion() {
            assertMensaje("la variable 'x' no está declarada", errorEnPrincipal("entero x = x + 1;"));
        }

        @Test
        void variableRedeclarada() {
            assertMensaje("ya existe una variable llamada 'x' (declarada en la línea 2)",
                    errorEnPrincipal("entero x;\nbooleano x;"));
        }

        @Test
        void noSePuedeOcultarUnaVariableEnUnBloqueInterior() {
            assertMensaje("ya existe una variable llamada 'x' (declarada en la línea 2)",
                    errorEnPrincipal("entero x;\n{ entero x; }"));
        }

        @Test
        void noSePuedeRedeclararUnParametro() {
            ErrorCompilacion e = error("funcion f(entero n) { entero n; }\nfuncion principal() { }");
            assertMensaje("ya existe una variable llamada 'n' (declarada en la línea 1)", e);
        }

        @Test
        void parametrosRepetidos() {
            ErrorCompilacion e = error("funcion f(entero a, entero a) { }\nfuncion principal() { }");
            assertTrue(e.getMessage().startsWith("ya existe una variable llamada 'a'"));
        }

        @Test
        void variableFueraDeSuAmbito() {
            assertMensaje("la variable 'x' no está declarada",
                    errorEnPrincipal("{ entero x = 1; }\nimprimir(x);"));
        }

        @Test
        void variableDelParaSoloExisteEnElCiclo() {
            assertMensaje("la variable 'i' no está declarada",
                    errorEnPrincipal("para (entero i = 0; i < 3; i = i + 1) { }\nimprimir(i);"));
        }

        @Test
        void mismaVariableEnBloquesHermanos() {
            principalValido("""
                    para (entero i = 0; i < 3; i = i + 1) { }
                    para (entero i = 0; i < 3; i = i + 1) { }
                    { entero x; } { booleano x; }""");
        }

        @Test
        void variableConElNombreDeUnaFuncion() {
            valido("funcion f() { }\nfuncion principal() { entero f = 1; f(); imprimir(f); }");
        }
    }

    @Nested
    class Tipos {

        @Test
        void asignarBooleanoAEntero() {
            assertMensaje("la variable 'x' es de tipo entero, pero se le asigna un valor booleano",
                    errorEnPrincipal("entero x = verdadero;"));
        }

        @Test
        void asignarEnteroABooleano() {
            assertMensaje("la variable 'b' es de tipo booleano, pero se le asigna un valor entero",
                    errorEnPrincipal("booleano b;\nb = 1;"));
        }

        @Test
        void aritmeticaConBooleanos() {
            ErrorCompilacion e = errorEnPrincipal("imprimir(1 + verdadero);");
            assertMensaje("el operador '+' necesita dos valores numéricos (entero o decimal), pero recibió entero y booleano", e);
            assertEquals(12, e.getColumna());
        }

        @Test
        void comparacionConBooleanos() {
            assertMensaje("el operador '<' necesita dos valores numéricos (entero o decimal), pero recibió booleano y booleano",
                    errorEnPrincipal("imprimir(verdadero < falso);"));
        }

        @Test
        void logicaConEnteros() {
            assertMensaje("el operador 'y' necesita dos valores de tipo booleano, pero recibió entero y entero",
                    errorEnPrincipal("imprimir(1 y 2);"));
        }

        @Test
        void igualdadEntreTiposDistintos() {
            assertMensaje("no se puede comparar con '==' un valor entero con un valor booleano",
                    errorEnPrincipal("imprimir(1 == verdadero);"));
        }

        @Test
        void igualdadEntreMismosTipos() {
            principalValido("imprimir(1 == 2); imprimir(verdadero != falso);");
        }

        @Test
        void negativoDeBooleano() {
            assertMensaje("el operador '-' necesita un valor numérico (entero o decimal), pero recibió un valor booleano",
                    errorEnPrincipal("imprimir(-verdadero);"));
        }

        @Test
        void noDeEntero() {
            assertMensaje("el operador 'no' necesita un valor booleano, pero recibió un valor entero",
                    errorEnPrincipal("imprimir(no 5);"));
        }

        @Test
        void comparacionesEncadenadasNoTienenSentido() {
            // (1 < 2) < 3 compara un booleano con un entero
            assertTrue(errorEnPrincipal("imprimir(1 < 2 < 3);").getMessage().contains("'<'"));
        }

        @Test
        void condicionesDebenSerBooleanas() {
            assertMensaje("la condición del 'si' debe ser booleana, pero es de tipo entero",
                    errorEnPrincipal("si (1) { }"));
            assertMensaje("la condición del 'mientras' debe ser booleana, pero es de tipo entero",
                    errorEnPrincipal("mientras (0) { }"));
            assertMensaje("la condición del 'para' debe ser booleana, pero es de tipo entero",
                    errorEnPrincipal("para (; 1;) { }"));
        }

        @Test
        void textos() {
            principalValido("""
                    texto nombre = "Ana";
                    texto vacio;
                    vacio = nombre;
                    imprimir("Hola " + nombre);
                    imprimir(nombre == "Ana");
                    imprimir(nombre != vacio);""");
        }

        @Test
        void concatenacionConCualquierTipo() {
            Programa programa = arbol("""
                    funcion principal() {
                        imprimir("a" + 1);
                        imprimir(1 + "a");
                        imprimir("a" + verdadero);
                        imprimir(1 + 2 + "a");
                    }""");
            InfoSemantica info = AnalizadorSemantico.analizar(programa);
            for (var sentencia : programa.funciones().get(0).cuerpo().sentencias()) {
                assertEquals(Tipo.TEXTO, info.tipoDe(((Imprimir) sentencia).valor()));
            }
        }

        @Test
        void soloLaSumaConcatena() {
            assertMensaje("el operador '-' necesita dos valores numéricos (entero o decimal), pero recibió texto y entero",
                    errorEnPrincipal("imprimir(\"a\" - 1);"));
            assertMensaje("el operador '*' necesita dos valores numéricos (entero o decimal), pero recibió texto y entero",
                    errorEnPrincipal("imprimir(\"a\" * 3);"));
        }

        @Test
        void textosNoSeComparanConMenorQue() {
            assertMensaje("el operador '<' necesita dos valores numéricos (entero o decimal), pero recibió texto y texto",
                    errorEnPrincipal("imprimir(\"a\" < \"b\");"));
        }

        @Test
        void igualdadEntreTextoYNumero() {
            assertMensaje("no se puede comparar con '==' un valor texto con un valor entero",
                    errorEnPrincipal("imprimir(\"5\" == 5);"));
        }

        @Test
        void asignarNumeroATexto() {
            assertMensaje("la variable 't' es de tipo texto, pero se le asigna un valor entero",
                    errorEnPrincipal("texto t = 5;"));
        }

        @Test
        void textoComoCondicion() {
            assertMensaje("la condición del 'si' debe ser booleana, pero es de tipo texto",
                    errorEnPrincipal("si (\"verdadero\") { }"));
        }

        @Test
        void concatenarFuncionSinValor() {
            ErrorCompilacion e = error("funcion f() { }\nfuncion principal() { imprimir(\"a\" + f()); }");
            assertTrue(e.getMessage().startsWith("la función 'f' no regresa un valor"), e.getMessage());
        }

        @Test
        void seRegistraElTipoDeCadaExpresion() {
            Programa programa = arbol("funcion principal() { imprimir(1 < 2); imprimir(3 * 4); }");
            InfoSemantica info = AnalizadorSemantico.analizar(programa);
            var sentencias = programa.funciones().get(0).cuerpo().sentencias();
            assertEquals(Tipo.BOOLEANO, info.tipoDe(((Imprimir) sentencias.get(0)).valor()));
            assertEquals(Tipo.ENTERO, info.tipoDe(((Imprimir) sentencias.get(1)).valor()));
        }
    }

    @Nested
    class Decimales {

        private Tipo tipoDeExpresion(String expresion) {
            Programa programa = arbol("funcion principal() { imprimir(" + expresion + "); }");
            InfoSemantica info = AnalizadorSemantico.analizar(programa);
            Imprimir imprimir = (Imprimir) programa.funciones().get(0).cuerpo().sentencias().get(0);
            return info.tipoDe(imprimir.valor());
        }

        @Test
        void tiposDeLasOperaciones() {
            assertEquals(Tipo.DECIMAL, tipoDeExpresion("1.5 + 2.5"));
            assertEquals(Tipo.DECIMAL, tipoDeExpresion("1 + 2.5"));
            assertEquals(Tipo.DECIMAL, tipoDeExpresion("7 / 2.0"));
            assertEquals(Tipo.ENTERO, tipoDeExpresion("7 / 2"));
            assertEquals(Tipo.DECIMAL, tipoDeExpresion("-1.5"));
            assertEquals(Tipo.BOOLEANO, tipoDeExpresion("1 < 2.5"));
            assertEquals(Tipo.BOOLEANO, tipoDeExpresion("2 == 2.0"));
            assertEquals(Tipo.TEXTO, tipoDeExpresion("\"x = \" + 1.5"));
        }

        @Test
        void enteroCabeEnDecimal() {
            valido("""
                    funcion mitad(decimal x) : decimal { regresa x / 2; }
                    funcion uno() : decimal { regresa 1; }
                    funcion principal() {
                        decimal d = 5;
                        d = 7;
                        imprimir(mitad(3) + uno());
                    }""");
        }

        @Test
        void decimalNoCabeEnEntero() {
            assertMensaje("la variable 'x' es de tipo entero, pero se le asigna un valor decimal",
                    errorEnPrincipal("entero x = 2.5;"));
            assertMensaje("el argumento 1 de 'f' (n) debe ser entero, pero es decimal",
                    error("funcion f(entero n) { }\nfuncion principal() { f(1.5); }"));
        }

        @Test
        void moduloSoloConEnteros() {
            assertMensaje("el operador '%' necesita dos valores de tipo entero, pero recibió decimal y entero",
                    errorEnPrincipal("imprimir(5.5 % 2);"));
        }

        @Test
        void decimalYBooleanoNoSeOperan() {
            assertMensaje("el operador '*' necesita dos valores numéricos (entero o decimal), pero recibió decimal y booleano",
                    errorEnPrincipal("imprimir(1.5 * verdadero);"));
        }

        @Test
        void conversiones() {
            assertEquals(Tipo.ENTERO, tipoDeExpresion("entero(3.7)"));
            assertEquals(Tipo.ENTERO, tipoDeExpresion("entero(\"42\")"));
            assertEquals(Tipo.DECIMAL, tipoDeExpresion("decimal(5)"));
            assertEquals(Tipo.DECIMAL, tipoDeExpresion("decimal(\"2.5\")"));
            assertEquals(Tipo.TEXTO, tipoDeExpresion("texto(verdadero)"));
            assertEquals(Tipo.TEXTO, tipoDeExpresion("texto(1.5)"));
        }

        @Test
        void conversionDeBooleanoANumero() {
            assertMensaje("no se puede convertir un valor booleano a entero",
                    errorEnPrincipal("imprimir(entero(verdadero));"));
        }
    }

    @Nested
    class Lectura {

        @Test
        void funcionesDeLectura() {
            principalValido("""
                    texto nombre = leerTexto();
                    entero edad = leerEntero();
                    decimal estatura = leerDecimal();
                    decimal otra = leerEntero();
                    escribir("Hola, " + nombre);""");
        }

        @Test
        void lecturaConTipoIncorrecto() {
            assertMensaje("la variable 'n' es de tipo entero, pero se le asigna un valor texto",
                    errorEnPrincipal("entero n = leerTexto();"));
        }

        @Test
        void lecturaNoRecibeArgumentos() {
            assertMensaje("la función 'leerEntero' no recibe argumentos, pero se le pasaron 1",
                    errorEnPrincipal("entero n = leerEntero(\"Edad: \");"));
        }

        @Test
        void noSePuedeRedefinirUnaFuncionPredefinida() {
            assertMensaje("'leerTexto' es una función predefinida del lenguaje; usa otro nombre",
                    error("funcion leerTexto() : texto { regresa \"\"; }\nfuncion principal() { }"));
        }
    }

    @Nested
    class Funciones {

        @Test
        void llamadaAFuncionDefinidaDespues() {
            valido("funcion principal() { imprimir(doble(2)); }\n"
                    + "funcion doble(entero n) : entero { regresa n * 2; }");
        }

        @Test
        void recursionMutua() {
            valido("""
                    funcion par(entero n) : booleano { si (n == 0) { regresa verdadero; } regresa impar(n - 1); }
                    funcion impar(entero n) : booleano { si (n == 0) { regresa falso; } regresa par(n - 1); }
                    funcion principal() { imprimir(par(4)); }""");
        }

        @Test
        void funcionRepetida() {
            assertMensaje("la función 'f' ya está definida (línea 1)",
                    error("funcion f() { }\nfuncion f() { }\nfuncion principal() { }"));
        }

        @Test
        void funcionInexistente() {
            assertMensaje("la función 'g' no existe", errorEnPrincipal("g();"));
        }

        @Test
        void cantidadDeArgumentos() {
            String f = "funcion f(entero a, entero b) : entero { regresa a + b; }\n";
            assertMensaje("la función 'f' recibe 2 argumentos, pero se le pasaron 1",
                    error(f + "funcion principal() { imprimir(f(1)); }"));
            String g = "funcion g(entero a) { }\n";
            assertMensaje("la función 'g' recibe 1 argumento, pero se le pasaron 0",
                    error(g + "funcion principal() { g(); }"));
        }

        @Test
        void tipoDeArgumento() {
            assertMensaje("el argumento 2 de 'f' (b) debe ser booleano, pero es entero",
                    error("funcion f(entero a, booleano b) { }\nfuncion principal() { f(1, 2); }"));
        }

        @Test
        void funcionSinValorEnUnaExpresion() {
            assertMensaje("la función 'f' no regresa un valor, así que no se puede usar como parte de una expresión",
                    error("funcion f() { }\nfuncion principal() { imprimir(f()); }"));
        }

        @Test
        void llamadaConValorComoSentenciaEsValida() {
            valido("funcion f() : entero { regresa 1; }\nfuncion principal() { f(); }");
        }

        @Test
        void regresaConValorEnFuncionSinTipo() {
            assertMensaje("la función 'principal' no tiene tipo de retorno, así que 'regresa' no puede llevar un valor",
                    errorEnPrincipal("regresa 1;"));
        }

        @Test
        void regresaSinValorEnFuncionConTipo() {
            assertMensaje("'regresa' debe incluir un valor de tipo entero (la función 'f' regresa entero)",
                    error("funcion f() : entero { regresa; }\nfuncion principal() { }"));
        }

        @Test
        void regresaConTipoIncorrecto() {
            assertMensaje("la función 'f' debe regresar entero, pero aquí regresa un valor booleano",
                    error("funcion f() : entero { regresa verdadero; }\nfuncion principal() { }"));
        }

        @Test
        void faltaRegresa() {
            assertMensaje("la función 'f' debe regresar un valor de tipo entero en todos los casos (falta un 'regresa')",
                    error("funcion f(entero n) : entero { si (n > 0) { regresa 1; } }\nfuncion principal() { }"));
        }

        @Test
        void regresaEnAmbasRamasEsSuficiente() {
            valido("funcion f(entero n) : entero { si (n > 0) { regresa 1; } sino { regresa 0; } }\n"
                    + "funcion principal() { }");
        }

        @Test
        void regresaEnCadenaDeSinoSi() {
            valido("""
                    funcion signo(entero n) : entero {
                        si (n > 0) { regresa 1; } sino si (n < 0) { regresa -1; } sino { regresa 0; }
                    }
                    funcion principal() { }""");
        }

        @Test
        void regresaSoloDentroDeUnCicloNoEsSuficiente() {
            ErrorCompilacion e = error("funcion f() : entero { mientras (verdadero) { regresa 1; } }\n"
                    + "funcion principal() { }");
            assertTrue(e.getMessage().contains("en todos los casos"));
        }

        @Test
        void regresaSinValorEnFuncionSinTipo() {
            principalValido("regresa;");
        }
    }

    @Nested
    class Ranuras {

        @Test
        void parametrosPrimeroYLuegoVariablesLocales() {
            Programa programa = arbol("""
                    funcion f(entero a, entero b) : entero {
                        entero c = a;
                        { entero d = b; }
                        regresa c;
                    }
                    funcion principal() { }""");
            InfoSemantica info = AnalizadorSemantico.analizar(programa);
            Funcion f = programa.funciones().get(0);

            assertEquals(4, info.totalRanuras(f));
            assertEquals(0, info.ranuraDe(f.parametros().get(0)));
            assertEquals(1, info.ranuraDe(f.parametros().get(1)));
            Declaracion c = (Declaracion) f.cuerpo().sentencias().get(0);
            assertEquals(2, info.ranuraDe(c));
        }

        @Test
        void funcionSinVariables() {
            Programa programa = arbol("funcion principal() { imprimir(1); }");
            InfoSemantica info = AnalizadorSemantico.analizar(programa);
            assertEquals(0, info.totalRanuras(programa.funciones().get(0)));
        }
    }
}
