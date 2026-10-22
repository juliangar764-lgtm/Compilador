package compilador.lexer;

/**
 * Una "palabra" del programa: su tipo, el texto exacto que se leyó (lexema)
 * y la posición donde aparece, para poder reportar errores.
 */
public record Token(TipoToken tipo, String lexema, int linea, int columna) {

    @Override
    public String toString() {
        return tipo + (lexema.isEmpty() ? "" : " '" + lexema + "'")
                + " (" + linea + ":" + columna + ")";
    }
}
