package compilador;

import compilador.ast.ImpresorAst;
import compilador.errores.ErrorCompilacion;
import compilador.errores.ErrorEnsamblado;
import compilador.lexer.Lexer;
import compilador.lexer.Token;
import compilador.parser.Parser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Punto de entrada del compilador desde la línea de comandos.
 *
 * Uso: java -jar target/compilador.jar [opciones] programa.mio
 */
public final class Main {

    static final int EXITO = 0;
    static final int ERROR_COMPILACION = 1;
    static final int ERROR_USO = 2;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(ejecutar(args, System.out, System.err));
    }

    /** Hace todo el trabajo y regresa el código de salida. Separado de main() para poder probarlo. */
    static int ejecutar(String[] args, PrintStream salida, PrintStream errores) {
        Opciones opciones;
        try {
            opciones = Opciones.leer(args);
        } catch (IllegalArgumentException e) {
            errores.println("error: " + e.getMessage());
            errores.println();
            errores.print(Opciones.USO);
            return ERROR_USO;
        }
        if (opciones.ayuda()) {
            salida.print(Opciones.USO);
            return EXITO;
        }

        Path entrada = opciones.entrada();
        String codigoFuente;
        try {
            codigoFuente = Files.readString(entrada);
        } catch (IOException e) {
            errores.println("error: no se pudo leer el archivo '" + entrada + "' (¿existe y está en UTF-8?)");
            return ERROR_COMPILACION;
        }

        Path archivoSalida = opciones.archivoSalida();
        try {
            if (opciones.mostrarTokens()) {
                imprimirTokens(Lexer.analizar(codigoFuente), salida);
            }
            if (opciones.mostrarAst()) {
                salida.print(ImpresorAst.imprimir(Parser.analizar(Lexer.analizar(codigoFuente))));
                salida.println();
            }

            String ensamblador = Compilador.compilar(codigoFuente);
            if (opciones.soloEnsamblador()) {
                Files.writeString(archivoSalida, ensamblador);
            } else {
                Compilador.crearEjecutable(ensamblador, archivoSalida);
            }
            return EXITO;
        } catch (ErrorCompilacion e) {
            errores.println(e.formatear(entrada.toString(), codigoFuente));
            return ERROR_COMPILACION;
        } catch (ErrorEnsamblado e) {
            errores.println("error: " + e.getMessage());
            return ERROR_COMPILACION;
        } catch (IOException e) {
            errores.println("error: no se pudo escribir '" + archivoSalida + "': " + e.getMessage());
            return ERROR_COMPILACION;
        } catch (StackOverflowError e) {
            errores.println("error: el programa es demasiado complejo (expresiones demasiado largas o anidadas)");
            return ERROR_COMPILACION;
        }
    }

    private static void imprimirTokens(List<Token> tokens, PrintStream salida) {
        salida.printf("%-9s %-15s %s%n", "LÍN:COL", "TIPO", "LEXEMA");
        salida.println("-".repeat(40));
        for (Token t : tokens) {
            salida.printf("%-9s %-15s %s%n", t.linea() + ":" + t.columna(), t.tipo(), t.lexema());
        }
        salida.println();
    }
}
