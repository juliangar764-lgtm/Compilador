package compilador;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Opciones de la línea de comandos.
 *
 * @param entrada          programa .mio a compilar
 * @param salida           archivo de salida indicado con -o (si no, se calcula del nombre de entrada)
 * @param soloEnsamblador  -S: generar solo el .s, sin crear el ejecutable
 * @param mostrarTokens    --tokens: imprimir los tokens
 * @param mostrarAst       --ast: imprimir el árbol sintáctico
 * @param ayuda            -h / --ayuda: mostrar el uso y salir
 */
record Opciones(Path entrada, Optional<Path> salida, boolean soloEnsamblador,
                boolean mostrarTokens, boolean mostrarAst, boolean ayuda) {

    static final String EXTENSION = ".mio";

    static final String USO = """
            Uso: java -jar compilador.jar [opciones] <archivo.mio>

            Compila un programa .mio a un ejecutable de Linux x86-64 (necesita gcc).

            Opciones:
              -o <archivo>   Archivo de salida (por defecto, el nombre del programa sin .mio)
              -S             Solo genera el código ensamblador (.s), sin crear el ejecutable
              --tokens       Muestra los tokens del análisis léxico
              --ast          Muestra el árbol sintáctico
              -h, --ayuda    Muestra esta ayuda
            """;

    /**
     * Interpreta los argumentos del programa.
     *
     * @throws IllegalArgumentException si los argumentos no son válidos
     */
    static Opciones leer(String[] args) {
        Path entrada = null;
        Path salida = null;
        boolean soloEnsamblador = false;
        boolean mostrarTokens = false;
        boolean mostrarAst = false;
        boolean ayuda = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--ayuda" -> ayuda = true;
                case "-S" -> soloEnsamblador = true;
                case "--tokens" -> mostrarTokens = true;
                case "--ast" -> mostrarAst = true;
                case "-o" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("falta el nombre del archivo después de '-o'");
                    }
                    salida = Path.of(args[++i]);
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("opción desconocida '" + arg + "'");
                    }
                    if (entrada != null) {
                        throw new IllegalArgumentException("solo se puede compilar un archivo a la vez");
                    }
                    entrada = Path.of(arg);
                }
            }
        }

        if (!ayuda) {
            if (entrada == null) {
                throw new IllegalArgumentException("falta el archivo a compilar");
            }
            String nombre = entrada.getFileName().toString();
            if (!nombre.endsWith(EXTENSION) || nombre.length() == EXTENSION.length()) {
                throw new IllegalArgumentException("el archivo debe tener extensión " + EXTENSION + ": " + entrada);
            }
        }
        return new Opciones(entrada, Optional.ofNullable(salida), soloEnsamblador, mostrarTokens, mostrarAst, ayuda);
    }

    /** El archivo a generar: el de -o, o el nombre del programa sin .mio (con .s si es -S). */
    Path archivoSalida() {
        return salida.orElseGet(() -> {
            String nombre = entrada.getFileName().toString();
            String base = nombre.substring(0, nombre.length() - EXTENSION.length());
            return Path.of(soloEnsamblador ? base + ".s" : base);
        });
    }
}
