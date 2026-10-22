package compilador.ast;

/** Lugar del código fuente donde aparece un nodo del árbol, para reportar errores. */
public record Posicion(int linea, int columna) {

    @Override
    public String toString() {
        return linea + ":" + columna;
    }
}
