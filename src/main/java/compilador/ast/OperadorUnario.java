package compilador.ast;

/** Operadores que trabajan con un solo valor: -x, no x. */
public enum OperadorUnario {

    /** Cambia el signo de un entero. */
    NEGATIVO("-"),
    /** Niega un booleano. */
    NO("no");

    private final String simbolo;

    OperadorUnario(String simbolo) {
        this.simbolo = simbolo;
    }

    public String simbolo() {
        return simbolo;
    }
}
