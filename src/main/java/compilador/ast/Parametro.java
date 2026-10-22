package compilador.ast;

/** Parámetro de una función: entero n */
public record Parametro(Tipo tipo, String nombre, Posicion pos) implements ReferenciaVariable { }
