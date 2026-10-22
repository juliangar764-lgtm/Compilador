package compilador.generador;

import compilador.Compilador;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeneradorX86Test {

    @Test
    void etiquetasDeFuncionesAscii() {
        assertEquals("fn_factorial", GeneradorX86.etiquetaFuncion("factorial"));
        assertEquals("fn_rax", GeneradorX86.etiquetaFuncion("rax"));
        assertEquals("fn_main", GeneradorX86.etiquetaFuncion("main"));
    }

    @Test
    void etiquetasConAcentosSoloUsanAscii() {
        String etiqueta = GeneradorX86.etiquetaFuncion("añoSiguiente");
        assertEquals("fn_a_u0000f1oSiguiente", etiqueta);
        assertTrue(etiqueta.chars().allMatch(c -> c < 128));
    }

    @Test
    void etiquetasNuncaChocan() {
        List<String> nombres = List.of("a_b", "a__b", "añ", "a_u0000f1", "a_", "a__", "x1", "x_1");
        Set<String> etiquetas = new HashSet<>();
        for (String nombre : nombres) {
            assertTrue(etiquetas.add(GeneradorX86.etiquetaFuncion(nombre)), "etiqueta repetida para " + nombre);
        }
    }

    @Test
    void literalesDeTextoParaElEnsamblador() {
        assertEquals("\"Hola mundo\"", GeneradorX86.literalEnsamblador("Hola mundo"));
        assertEquals("\"\"", GeneradorX86.literalEnsamblador(""));
        assertEquals("\"a\\\"b\\\\c\"", GeneradorX86.literalEnsamblador("a\"b\\c"));
        assertEquals("\"a\\012b\\011c\"", GeneradorX86.literalEnsamblador("a\nb\tc"));
        // ñ en UTF-8 son los bytes C3 B1 = 303 261 en octal
        assertEquals("\"a\\303\\261o\"", GeneradorX86.literalEnsamblador("año"));
    }

    @Test
    void textoRepetidoSeGuardaUnaSolaVez() {
        String asm = Compilador.compilar("""
                funcion principal() {
                    imprimir("hola");
                    imprimir("hola");
                    imprimir("adiós");
                }""");
        assertEquals(2, asm.split("\\.string \"hola\"", -1).length, "\"hola\" debe aparecer una sola vez");
        assertTrue(asm.contains(".Lcadena_0:"));
        assertTrue(asm.contains(".Lcadena_1:"));
        assertFalse(asm.contains(".Lcadena_2:"));
    }

    @Test
    void decimalesUsanRegistrosXmm() {
        String asm = Compilador.compilar("funcion principal() { imprimir(1.5 * 2); }");
        assertTrue(asm.contains("cvtsi2sd xmm0, rax"), "el 2 debe convertirse a decimal");
        assertTrue(asm.contains("mulsd xmm0, xmm1"));
        assertTrue(asm.contains("call _rt_decimal_a_texto"));
        // 1.5 en IEEE 754 es 0x3FF8000000000000
        assertTrue(asm.contains("mov rax, " + 0x3FF8000000000000L));
    }

    @Test
    void enteroAsignadoADecimalSeConvierte() {
        String asm = Compilador.compilar("funcion principal() { decimal d = 5; }");
        assertTrue(asm.contains("cvtsi2sd xmm0, rax"));
    }

    @Test
    void lecturaPasaLaLineaParaLosErrores() {
        String asm = Compilador.compilar("funcion principal() {\n entero n = leerEntero();\n}");
        assertTrue(asm.contains("mov rdi, 2"));
        assertTrue(asm.contains("call _rt_leer_entero"));
    }

    @Test
    void escribirNoAgregaSaltoDeLinea() {
        String asm = Compilador.compilar("funcion principal() { escribir(1); escribir(\"a\"); }");
        assertTrue(asm.contains("call _rt_escribir_entero"));
        assertTrue(asm.contains("call _rt_escribir_texto"));
        assertFalse(asm.contains("call _rt_imprimir_"));
    }

    @Test
    void textoSinValorInicialEmpiezaVacio() {
        String asm = Compilador.compilar("funcion principal() { texto t; imprimir(t); }");
        assertTrue(asm.contains("lea rax, [rip + .Ltexto_vacio]"));
    }

    @Test
    void incluyeMainYLasLineasDelProgramaComoComentarios() {
        String asm = Compilador.compilar("funcion principal() {\n    imprimir(42);\n}\n");
        assertTrue(asm.contains(".intel_syntax noprefix"));
        assertTrue(asm.contains(".globl main"));
        assertTrue(asm.contains("call fn_principal"));
        assertTrue(asm.contains("# [línea 2] imprimir(42);"));
        assertTrue(asm.contains("call _rt_imprimir_entero"));
    }

    @Test
    void imprimirBooleanoLoConvierteATexto() {
        String asm = Compilador.compilar("funcion principal() { imprimir(1 < 2); }");
        assertTrue(asm.contains("call _rt_booleano_a_texto"));
        assertTrue(asm.contains("call _rt_imprimir_texto"));
    }

    @Test
    void concatenacionConvierteLosNumeros() {
        String asm = Compilador.compilar("funcion principal() { imprimir(\"x = \" + 5); }");
        assertTrue(asm.contains("call _rt_entero_a_texto"));
        assertTrue(asm.contains("call _rt_concatenar"));
    }

    @Test
    void elMismoProgramaGeneraSiempreElMismoCodigo() {
        String programa = "funcion principal() { entero i = 0; mientras (i < 3) { i = i + 1; } }";
        assertEquals(Compilador.compilar(programa), Compilador.compilar(programa));
    }
}
