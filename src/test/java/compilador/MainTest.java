package compilador;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Pruebas de la línea de comandos: opciones, mensajes y códigos de salida. */
class MainTest {

    @TempDir
    Path directorio;

    private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errores = new ByteArrayOutputStream();

    private int ejecutar(String... args) {
        return Main.ejecutar(args,
                new PrintStream(salida, true, StandardCharsets.UTF_8),
                new PrintStream(errores, true, StandardCharsets.UTF_8));
    }

    private String salida() {
        return salida.toString(StandardCharsets.UTF_8);
    }

    private String errores() {
        return errores.toString(StandardCharsets.UTF_8);
    }

    private Path programa(String nombre, String codigo) throws IOException {
        return Files.writeString(directorio.resolve(nombre), codigo);
    }

    @Test
    void sinArgumentosMuestraElUso() {
        assertEquals(Main.ERROR_USO, ejecutar());
        assertTrue(errores().contains("falta el archivo a compilar"));
        assertTrue(errores().contains("Uso:"));
    }

    @Test
    void ayuda() {
        assertEquals(Main.EXITO, ejecutar("--ayuda"));
        assertTrue(salida().contains("Uso:"));
        assertEquals("", errores());
    }

    @Test
    void opcionDesconocida() {
        assertEquals(Main.ERROR_USO, ejecutar("--rapido", "x.mio"));
        assertTrue(errores().contains("opción desconocida '--rapido'"));
    }

    @Test
    void opcionOSinArchivo() {
        assertEquals(Main.ERROR_USO, ejecutar("x.mio", "-o"));
        assertTrue(errores().contains("falta el nombre del archivo después de '-o'"));
    }

    @Test
    void dosArchivos() {
        assertEquals(Main.ERROR_USO, ejecutar("a.mio", "b.mio"));
        assertTrue(errores().contains("solo se puede compilar un archivo a la vez"));
    }

    @Test
    void extensionIncorrecta() {
        assertEquals(Main.ERROR_USO, ejecutar("programa.txt"));
        assertTrue(errores().contains("extensión .mio"));
    }

    @Test
    void archivoInexistente() {
        assertEquals(Main.ERROR_COMPILACION, ejecutar(directorio.resolve("no_existe.mio").toString()));
        assertTrue(errores().contains("no se pudo leer el archivo"));
    }

    @Test
    void errorDeCompilacionMuestraLineaYFlecha() throws IOException {
        Path archivo = programa("malo.mio", "funcion principal() {\n    imprimir(z);\n}\n");
        assertEquals(Main.ERROR_COMPILACION, ejecutar(archivo.toString(), "-S", "-o", directorio.resolve("x.s").toString()));
        String esperado = archivo + ":2:14: error semántico: la variable 'z' no está declarada\n"
                + "        imprimir(z);\n"
                + "                 ^\n";
        assertEquals(esperado, errores());
        assertFalse(Files.exists(directorio.resolve("x.s")), "no debe generarse salida si hay errores");
    }

    @Test
    void soloEnsamblador() throws IOException {
        Path archivo = programa("hola.mio", "funcion principal() { imprimir(1); }");
        Path destino = directorio.resolve("hola.s");
        assertEquals(Main.EXITO, ejecutar("-S", archivo.toString(), "-o", destino.toString()));
        assertTrue(Files.readString(destino).contains("fn_principal:"));
        assertEquals("", errores());
    }

    @Test
    void mostrarTokensYArbol() throws IOException {
        Path archivo = programa("t.mio", "funcion principal() { imprimir(1 + 2); }");
        assertEquals(Main.EXITO, ejecutar("--tokens", "--ast", "-S", "-o",
                directorio.resolve("t.s").toString(), archivo.toString()));
        assertTrue(salida().contains("IMPRIMIR"));
        assertTrue(salida().contains("└── Funcion principal()"));
        assertTrue(salida().contains("Operador +"));
    }

    @Test
    void tokensSeMuestranAunqueHayaErrorDeSintaxis() throws IOException {
        Path archivo = programa("t.mio", "funcion principal() { imprimir(1 }");
        assertEquals(Main.ERROR_COMPILACION, ejecutar("--tokens", archivo.toString()));
        assertTrue(salida().contains("PAREN_IZQ"));
        assertTrue(errores().contains("error sintáctico"));
    }

    @Test
    void creaEjecutable() throws Exception {
        assumeTrue(Ejecucion.gccDisponible(), "gcc no está instalado");
        Path archivo = programa("suma.mio", "funcion principal() { imprimir(40 + 2); }");
        Path ejecutable = directorio.resolve("suma");
        assertEquals(Main.EXITO, ejecutar(archivo.toString(), "-o", ejecutable.toString()));
        assertEquals("42\n", Ejecucion.ejecutar(ejecutable).salida());
    }

    @Test
    void nombreDeSalidaPorDefecto() {
        Opciones opciones = Opciones.leer(new String[] {"ejemplos/factorial.mio"});
        assertEquals(Path.of("factorial"), opciones.archivoSalida());
        Opciones conS = Opciones.leer(new String[] {"-S", "ejemplos/factorial.mio"});
        assertEquals(Path.of("factorial.s"), conS.archivoSalida());
    }
}
