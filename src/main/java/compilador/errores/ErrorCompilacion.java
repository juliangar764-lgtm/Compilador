package compilador.errores;

import java.io.Serial;

/**
 * Error encontrado en el programa fuente durante cualquier fase del compilador.
 * Guarda la fase, la línea y la columna para poder señalar dónde está el problema.
 */
public final class ErrorCompilacion extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Se usa como línea cuando el error no corresponde a un lugar concreto del código. */
    private static final int SIN_POSICION = 0;

    private final String fase;
    private final int linea;
    private final int columna;

    public ErrorCompilacion(String fase, String mensaje, int linea, int columna) {
        super(mensaje);
        this.fase = fase;
        this.linea = linea;
        this.columna = columna;
    }

    /** Error del programa completo, sin una posición concreta (por ejemplo, "falta 'principal'"). */
    public ErrorCompilacion(String fase, String mensaje) {
        this(fase, mensaje, SIN_POSICION, SIN_POSICION);
    }

    public String getFase() {
        return fase;
    }

    public int getLinea() {
        return linea;
    }

    public int getColumna() {
        return columna;
    }

    public boolean tienePosicion() {
        return linea != SIN_POSICION;
    }

    /**
     * Arma un mensaje que muestra la línea del código y una flecha bajo el error:
     *
     * <pre>
     *   ejemplo.mio:3:9: error léxico: carácter no reconocido '@'
     *       entero @x = 5;
     *              ^
     * </pre>
     */
    public String formatear(String nombreArchivo, String codigoFuente) {
        if (!tienePosicion()) {
            return nombreArchivo + ": error " + fase + ": " + getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(nombreArchivo).append(':').append(linea).append(':').append(columna)
          .append(": error ").append(fase).append(": ").append(getMessage());

        String[] lineas = codigoFuente.split("\n", -1);
        if (linea <= lineas.length) {
            String textoLinea = lineas[linea - 1].replace("\r", "");
            sb.append('\n').append("    ").append(textoLinea);
            sb.append('\n').append("    ");
            // Se respetan los tabuladores para que la flecha quede alineada
            for (int i = 0; i < columna - 1 && i < textoLinea.length(); i++) {
                sb.append(textoLinea.charAt(i) == '\t' ? '\t' : ' ');
            }
            sb.append('^');
        }
        return sb.toString();
    }
}
