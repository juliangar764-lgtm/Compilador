package compilador.ast;

import java.util.Arrays;
import java.util.Optional;

/**
 * Funciones que ya vienen con el lenguaje. Se llaman igual que las funciones del usuario,
 * pero no se pueden redefinir. Ninguna recibe argumentos.
 */
public enum Predefinida {

    /** Lee una línea del teclado (sin el salto de línea). */
    LEER_TEXTO("leerTexto", Tipo.TEXTO),
    /** Lee una línea del teclado y la convierte a entero. */
    LEER_ENTERO("leerEntero", Tipo.ENTERO),
    /** Lee una línea del teclado y la convierte a decimal. */
    LEER_DECIMAL("leerDecimal", Tipo.DECIMAL);

    private final String nombre;
    private final Tipo tipoRetorno;

    Predefinida(String nombre, Tipo tipoRetorno) {
        this.nombre = nombre;
        this.tipoRetorno = tipoRetorno;
    }

    public String nombre() {
        return nombre;
    }

    public Tipo tipoRetorno() {
        return tipoRetorno;
    }

    public static Optional<Predefinida> buscar(String nombre) {
        return Arrays.stream(values()).filter(p -> p.nombre.equals(nombre)).findFirst();
    }
}
