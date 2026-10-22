package compilador.ast;

/** Operadores que trabajan con dos valores: a + b, a < b, a y b... */
public enum OperadorBinario {

    SUMA("+", Categoria.ARITMETICO),
    RESTA("-", Categoria.ARITMETICO),
    MULTIPLICACION("*", Categoria.ARITMETICO),
    DIVISION("/", Categoria.ARITMETICO),
    MODULO("%", Categoria.ARITMETICO),

    MENOR("<", Categoria.COMPARACION),
    MENOR_IGUAL("<=", Categoria.COMPARACION),
    MAYOR(">", Categoria.COMPARACION),
    MAYOR_IGUAL(">=", Categoria.COMPARACION),

    IGUAL("==", Categoria.IGUALDAD),
    DIFERENTE("!=", Categoria.IGUALDAD),

    Y("y", Categoria.LOGICO),
    O("o", Categoria.LOGICO);

    /** Agrupa los operadores según los tipos que aceptan y el tipo que producen. */
    public enum Categoria {
        /** entero, entero → entero */
        ARITMETICO,
        /** entero, entero → booleano */
        COMPARACION,
        /** dos valores del mismo tipo → booleano */
        IGUALDAD,
        /** booleano, booleano → booleano (con corto circuito) */
        LOGICO
    }

    private final String simbolo;
    private final Categoria categoria;

    OperadorBinario(String simbolo, Categoria categoria) {
        this.simbolo = simbolo;
        this.categoria = categoria;
    }

    public String simbolo() {
        return simbolo;
    }

    public Categoria categoria() {
        return categoria;
    }
}
