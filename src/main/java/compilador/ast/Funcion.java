package compilador.ast;

import java.util.List;

/**
 * Definición de función:
 * funcion nombre(entero a, booleano b) : entero { ... }
 * Si no se escribe ": tipo", el tipo de retorno es VACIO.
 */
public record Funcion(String nombre, List<Parametro> parametros, Tipo tipoRetorno,
                      Sentencia.Bloque cuerpo, Posicion pos) {

    public Funcion {
        parametros = List.copyOf(parametros);
    }
}
