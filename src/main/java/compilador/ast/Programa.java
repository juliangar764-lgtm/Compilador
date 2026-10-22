package compilador.ast;

import java.util.List;

/** Raíz del árbol sintáctico: todas las funciones del archivo. */
public record Programa(List<Funcion> funciones) {

    public Programa {
        funciones = List.copyOf(funciones);
    }
}
