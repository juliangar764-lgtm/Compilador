package compilador;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.fail;

/** Utilidades de prueba para compilar programas .mio con gcc y ejecutarlos. */
final class Ejecucion {

    private static final long LIMITE_SEGUNDOS = 10;
    private static final AtomicInteger CONTADOR = new AtomicInteger();

    /** Lo que produjo un programa al ejecutarse. */
    record Resultado(int codigoSalida, String salida, String errores) { }

    private Ejecucion() {
    }

    static boolean gccDisponible() {
        try {
            Process gcc = new ProcessBuilder("gcc", "--version").redirectErrorStream(true).start();
            gcc.getInputStream().readAllBytes();
            return gcc.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Compila el código fuente a un ejecutable dentro del directorio indicado y lo ejecuta sin entrada. */
    static Resultado compilarYEjecutar(String codigoFuente, Path directorio) throws Exception {
        return compilarYEjecutar(codigoFuente, directorio, "");
    }

    /** Igual, pero el programa recibe el texto indicado como si se escribiera en el teclado. */
    static Resultado compilarYEjecutar(String codigoFuente, Path directorio, String entrada) throws Exception {
        Path ejecutable = directorio.resolve("programa-" + CONTADOR.incrementAndGet());
        Compilador.crearEjecutable(Compilador.compilar(codigoFuente), ejecutable);
        return ejecutar(ejecutable, entrada);
    }

    static Resultado ejecutar(Path ejecutable) throws IOException, InterruptedException {
        return ejecutar(ejecutable, "");
    }

    static Resultado ejecutar(Path ejecutable, String entrada) throws IOException, InterruptedException {
        Path directorio = ejecutable.toAbsolutePath().getParent();
        Path archivoEntrada = Files.writeString(Files.createTempFile(directorio, "entrada-", ".txt"), entrada);
        Path salida = Files.createTempFile(directorio, "salida-", ".txt");
        Path errores = Files.createTempFile(directorio, "errores-", ".txt");
        Process proceso = new ProcessBuilder(ejecutable.toAbsolutePath().toString())
                .redirectInput(archivoEntrada.toFile())
                .redirectOutput(salida.toFile())
                .redirectError(errores.toFile())
                .start();
        if (!proceso.waitFor(LIMITE_SEGUNDOS, TimeUnit.SECONDS)) {
            proceso.destroyForcibly();
            fail("el programa tardó más de " + LIMITE_SEGUNDOS + " segundos");
        }
        return new Resultado(proceso.exitValue(), Files.readString(salida), Files.readString(errores));
    }
}
