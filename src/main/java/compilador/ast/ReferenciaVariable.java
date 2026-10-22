package compilador.ast;

/**
 * Nodo que declara o usa una variable (parámetro, declaración, asignación o lectura).
 * El análisis semántico le asigna a cada uno la ranura de memoria de su variable.
 */
public sealed interface ReferenciaVariable
        permits Parametro, Sentencia.Declaracion, Sentencia.Asignacion, Expresion.Variable {

    String nombre();

    Posicion pos();
}
