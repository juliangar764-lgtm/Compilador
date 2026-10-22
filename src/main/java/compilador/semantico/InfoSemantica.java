package compilador.semantico;

import compilador.ast.Expresion;
import compilador.ast.Funcion;
import compilador.ast.ReferenciaVariable;
import compilador.ast.Tipo;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Lo que el análisis semántico descubre del programa y el generador de código necesita:
 * <ul>
 *   <li>el tipo de cada expresión (para saber cómo operarla e imprimirla);</li>
 *   <li>la ranura de memoria y el tipo de la variable de cada referencia;</li>
 *   <li>cuántas ranuras necesita cada función.</li>
 * </ul>
 *
 * Se usan IdentityHashMap porque cada nodo del árbol es único aunque dos nodos
 * tengan el mismo contenido (los records comparan por contenido con equals).
 */
public final class InfoSemantica {

    /** Ranura y tipo de una variable. */
    private record Variable(int ranura, Tipo tipo) { }

    private final Map<Expresion, Tipo> tipos = new IdentityHashMap<>();
    private final Map<ReferenciaVariable, Variable> variables = new IdentityHashMap<>();
    private final Map<Funcion, Integer> totalRanuras = new IdentityHashMap<>();

    InfoSemantica() {
    }

    void registrarTipo(Expresion expresion, Tipo tipo) {
        tipos.put(expresion, tipo);
    }

    void registrarVariable(ReferenciaVariable referencia, int ranura, Tipo tipo) {
        variables.put(referencia, new Variable(ranura, tipo));
    }

    void registrarTotalRanuras(Funcion funcion, int total) {
        totalRanuras.put(funcion, total);
    }

    public Tipo tipoDe(Expresion expresion) {
        return obtener(tipos, expresion, "el tipo");
    }

    /** Número de ranura (0, 1, 2...) de la variable a la que se refiere el nodo. */
    public int ranuraDe(ReferenciaVariable referencia) {
        return obtener(variables, referencia, "la variable").ranura();
    }

    /** Tipo declarado de la variable a la que se refiere el nodo. */
    public Tipo tipoDeVariable(ReferenciaVariable referencia) {
        return obtener(variables, referencia, "la variable").tipo();
    }

    /** Cuántas variables (incluyendo parámetros) necesita guardar la función. */
    public int totalRanuras(Funcion funcion) {
        return obtener(totalRanuras, funcion, "el total de ranuras");
    }

    private static <K, V> V obtener(Map<K, V> mapa, K clave, String dato) {
        V valor = mapa.get(clave);
        if (valor == null) {
            throw new IllegalStateException("no se conoce " + dato + " de " + clave
                    + "; ¿se ejecutó el análisis semántico sobre este árbol?");
        }
        return valor;
    }
}
