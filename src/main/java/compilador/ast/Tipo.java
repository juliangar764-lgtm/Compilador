package compilador.ast;

/** Tipos de dato del lenguaje. */
public enum Tipo {

    ENTERO("entero"),
    DECIMAL("decimal"),
    BOOLEANO("booleano"),
    TEXTO("texto"),
    /** "Sin valor": el tipo de retorno de una función que no regresa nada. */
    VACIO("vacío");

    private final String nombre;

    Tipo(String nombre) {
        this.nombre = nombre;
    }

    /** ¿Es un número (entero o decimal)? */
    public boolean esNumerico() {
        return this == ENTERO || this == DECIMAL;
    }

    /**
     * ¿Se puede guardar un valor de tipo 'origen' donde se espera este tipo?
     * Además de los tipos iguales, un entero cabe en un decimal (igual que en Java).
     */
    public boolean aceptaValorDe(Tipo origen) {
        return this == origen || (this == DECIMAL && origen == ENTERO);
    }

    /** El nombre tal como se escribe en el lenguaje, para los mensajes de error. */
    @Override
    public String toString() {
        return nombre;
    }
}
