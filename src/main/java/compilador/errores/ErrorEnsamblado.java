package compilador.errores;

import java.io.Serial;

/**
 * Error al convertir el ensamblador generado en un ejecutable con gcc
 * (gcc no está instalado, no se pudo escribir el archivo, etc.).
 * No es un error del programa del usuario, sino del entorno.
 */
public final class ErrorEnsamblado extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public ErrorEnsamblado(String mensaje) {
        super(mensaje);
    }

    public ErrorEnsamblado(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
