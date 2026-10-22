package compilador;

import compilador.Ejecucion.Resultado;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pruebas de punta a punta: compilan programas reales con gcc, los ejecutan
 * y comparan lo que imprimen con lo esperado.
 *
 * Cada programa X.mio de src/test/resources/programas tiene un X.salida con
 * la salida exacta que debe producir y, si lee del teclado, un X.entrada con los datos.
 */
class ProgramasTest {

    @TempDir
    Path directorio;

    @BeforeAll
    static void requiereGcc() {
        assumeTrue(Ejecucion.gccDisponible(), "gcc no está instalado: se omiten las pruebas de ejecución");
    }

    @TestFactory
    Stream<DynamicTest> programasDePrueba() throws IOException, URISyntaxException {
        Path carpeta = Path.of(ProgramasTest.class.getResource("/programas").toURI());
        List<Path> programas;
        try (Stream<Path> archivos = Files.list(carpeta)) {
            programas = archivos.filter(p -> p.toString().endsWith(".mio")).sorted().toList();
        }
        assertFalse(programas.isEmpty(), "no hay programas de prueba en " + carpeta);

        return programas.stream().map(programa -> DynamicTest.dynamicTest(
                programa.getFileName().toString(),
                () -> {
                    String esperado = Files.readString(archivoHermano(programa, ".salida"));
                    Resultado resultado = Ejecucion.compilarYEjecutar(
                            Files.readString(programa), directorio, entradaDe(programa));
                    assertEquals(0, resultado.codigoSalida(), "errores: " + resultado.errores());
                    assertEquals(esperado, resultado.salida());
                }));
    }

    @TestFactory
    Stream<DynamicTest> ejemplosDelProyecto() throws IOException {
        List<Path> ejemplos;
        try (Stream<Path> archivos = Files.list(Path.of("ejemplos"))) {
            ejemplos = archivos.filter(p -> p.toString().endsWith(".mio")).sorted().toList();
        }
        return ejemplos.stream().map(ejemplo -> DynamicTest.dynamicTest(
                ejemplo.getFileName().toString(),
                () -> {
                    Resultado resultado = Ejecucion.compilarYEjecutar(
                            Files.readString(ejemplo), directorio, entradaDe(ejemplo));
                    assertEquals(0, resultado.codigoSalida(), "errores: " + resultado.errores());
                    assertFalse(resultado.salida().isEmpty());
                }));
    }

    /** El archivo con el mismo nombre que el programa pero con otra extensión. */
    private static Path archivoHermano(Path programa, String extension) {
        return programa.resolveSibling(programa.getFileName().toString().replace(".mio", extension));
    }

    /** Lo que el programa recibe por el teclado: su archivo .entrada, o nada. */
    private static String entradaDe(Path programa) throws IOException {
        Path entrada = archivoHermano(programa, ".entrada");
        return Files.exists(entrada) ? Files.readString(entrada) : "";
    }

    @Test
    void divisionEntreCeroTerminaConError() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar("""
                funcion principal() {
                    entero cero = 0;
                    imprimir(1);
                    imprimir(10 / cero);
                    imprimir(2);
                }
                """, directorio);
        assertEquals(1, resultado.codigoSalida());
        assertEquals("1\n", resultado.salida(), "lo impreso antes del error debe aparecer");
        assertEquals("Error en tiempo de ejecución (línea 4): división entre cero\n", resultado.errores());
    }

    @Test
    void moduloEntreCeroTerminaConError() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar(
                "funcion principal() {\n entero cero;\n imprimir(7 % cero);\n}", directorio);
        assertEquals(1, resultado.codigoSalida());
        assertTrue(resultado.errores().contains("línea 3"), resultado.errores());
    }

    @Test
    void recursionProfunda() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar("""
                funcion suma(entero n) : entero {
                    si (n == 0) { regresa 0; }
                    regresa n + suma(n - 1);
                }
                funcion principal() { imprimir(suma(100000)); }
                """, directorio);
        assertEquals(0, resultado.codigoSalida(), resultado.errores());
        assertEquals("5000050000\n", resultado.salida());
    }

    @Test
    void muchosArgumentos() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar("""
                funcion f(entero a, entero b, entero c, entero d, entero e, entero f, entero g, entero h) : entero {
                    regresa a - b + c - d + e - f + g - h * 10;
                }
                funcion principal() { imprimir(f(1, 2, 3, 4, 5, 6, 7, 8)); }
                """, directorio);
        // 1 - 2 + 3 - 4 + 5 - 6 + 7 - 80: si el orden de los argumentos estuviera mal, no daría -76
        assertEquals("-76\n", resultado.salida());
    }

    @Test
    void entradaQueNoEsNumeroTerminaConError() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar("""
                funcion principal() {
                    escribir("Edad: ");
                    entero edad = leerEntero();
                    imprimir(edad);
                }
                """, directorio, "veinte\n");
        assertEquals(1, resultado.codigoSalida());
        assertEquals("Edad: ", resultado.salida(), "la pregunta debe mostrarse antes del error");
        assertEquals("Error en tiempo de ejecución (línea 3): \"veinte\" no es un número entero válido\n",
                resultado.errores());
    }

    @Test
    void entradaConTextoDespuesDelNumero() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar(
                "funcion principal() { imprimir(leerDecimal()); }", directorio, "2.5 kg\n");
        assertEquals(1, resultado.codigoSalida());
        assertTrue(resultado.errores().contains("\"2.5 kg\" no es un número decimal válido"), resultado.errores());
    }

    @Test
    void enteroDemasiadoGrandeAlLeer() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar(
                "funcion principal() { imprimir(leerEntero()); }", directorio, "99999999999999999999\n");
        assertEquals(1, resultado.codigoSalida());
        assertTrue(resultado.errores().contains("no es un número entero válido"), resultado.errores());
    }

    @Test
    void leerSinDatosTerminaConError() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar(
                "funcion principal() {\n texto a = leerTexto();\n texto b = leerTexto();\n}", directorio, "solo una\n");
        assertEquals(1, resultado.codigoSalida());
        assertEquals("Error en tiempo de ejecución (línea 3): no hay más datos para leer\n", resultado.errores());
    }

    @Test
    void leerLineaVaciaYSinSaltoFinal() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar("""
                funcion principal() {
                    imprimir("[" + leerTexto() + "]");
                    imprimir("[" + leerTexto() + "]");
                    imprimir("[" + leerTexto() + "]");
                }
                """, directorio, "\r\n  con espacios  \nsin salto final");
        assertEquals(0, resultado.codigoSalida(), resultado.errores());
        assertEquals("[]\n[  con espacios  ]\n[sin salto final]\n", resultado.salida());
    }

    @Test
    void conversionDeTextoInvalido() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar(
                "funcion principal() {\n imprimir(entero(\"12x\"));\n}", directorio);
        assertEquals(1, resultado.codigoSalida());
        assertEquals("Error en tiempo de ejecución (línea 2): \"12x\" no es un número entero válido\n",
                resultado.errores());
    }

    @Test
    void programaSinSalida() throws Exception {
        Resultado resultado = Ejecucion.compilarYEjecutar("funcion principal() { }", directorio);
        assertEquals(0, resultado.codigoSalida());
        assertEquals("", resultado.salida());
    }
}
