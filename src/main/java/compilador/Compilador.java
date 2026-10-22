package compilador;

import compilador.ast.Programa;
import compilador.errores.ErrorEnsamblado;
import compilador.generador.GeneradorX86;
import compilador.lexer.Lexer;
import compilador.lexer.Token;
import compilador.parser.Parser;
import compilador.semantico.AnalizadorSemantico;
import compilador.semantico.InfoSemantica;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Une las fases del compilador y la creación del ejecutable. */
public final class Compilador {

    private Compilador() {
    }

    /**
     * Ejecuta las cuatro fases y regresa el programa en ensamblador x86-64.
     *
     * @throws compilador.errores.ErrorCompilacion si el programa tiene un error
     */
    public static String compilar(String codigoFuente) {
        List<Token> tokens = Lexer.analizar(codigoFuente);                  // 1. análisis léxico
        Programa programa = Parser.analizar(tokens);                        // 2. análisis sintáctico
        InfoSemantica info = AnalizadorSemantico.analizar(programa);        // 3. análisis semántico
        return GeneradorX86.generar(programa, info, codigoFuente);          // 4. generación de código
    }

    /**
     * Ensambla y enlaza el código con gcc para producir un ejecutable.
     * gcc se encarga de llamar al ensamblador (as) y al enlazador (ld) con la biblioteca de C.
     */
    public static void crearEjecutable(String ensamblador, Path ejecutable) throws ErrorEnsamblado {
        Path archivoEnsamblador = null;
        try {
            archivoEnsamblador = Files.createTempFile("compilador-", ".s");
            Files.writeString(archivoEnsamblador, ensamblador);
            ejecutarGcc(archivoEnsamblador, ejecutable);
        } catch (IOException e) {
            throw new ErrorEnsamblado("no se pudo escribir un archivo temporal: " + e.getMessage(), e);
        } finally {
            borrarSiExiste(archivoEnsamblador);
        }
    }

    private static void ejecutarGcc(Path archivoEnsamblador, Path ejecutable) throws ErrorEnsamblado {
        Process gcc;
        try {
            gcc = new ProcessBuilder("gcc", "-o", ejecutable.toString(), archivoEnsamblador.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new ErrorEnsamblado("no se pudo ejecutar 'gcc'. ¿Está instalado? (sudo apt install gcc)", e);
        }

        try (InputStream salidaGcc = gcc.getInputStream()) {
            String mensajes = new String(salidaGcc.readAllBytes(), StandardCharsets.UTF_8);
            if (gcc.waitFor() != 0) {
                throw new ErrorEnsamblado("gcc no pudo crear el ejecutable:\n" + mensajes.strip());
            }
        } catch (IOException e) {
            throw new ErrorEnsamblado("error al leer la salida de gcc: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorEnsamblado("se interrumpió la ejecución de gcc", e);
        }
    }

    private static void borrarSiExiste(Path archivo) {
        if (archivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException e) {
            // Solo es un archivo temporal: si no se puede borrar, no afecta el resultado
        }
    }
}
