package compilador.lexer;

import java.util.EnumSet;
import java.util.Set;

/**
 * Todos los tipos de token que existen en el lenguaje.
 * Cada uno guarda cómo se nombra en los mensajes de error ("se esperaba ';'").
 */
public enum TipoToken {

    // Literales e identificadores
    NUMERO("un número"),
    NUMERO_DECIMAL("un número decimal"),
    CADENA("un texto entre comillas"),
    IDENTIFICADOR("un nombre"),

    // Palabras reservadas: tipos de dato
    ENTERO("'entero'"),
    DECIMAL("'decimal'"),
    BOOLEANO("'booleano'"),
    TEXTO("'texto'"),

    // Palabras reservadas: valores booleanos
    VERDADERO("'verdadero'"),
    FALSO("'falso'"),

    // Palabras reservadas: control de flujo
    SI("'si'"),
    SINO("'sino'"),
    MIENTRAS("'mientras'"),
    PARA("'para'"),
    FUNCION("'funcion'"),
    REGRESA("'regresa'"),
    IMPRIMIR("'imprimir'"),
    ESCRIBIR("'escribir'"),

    // Palabras reservadas: operadores lógicos
    Y("'y'"),
    O("'o'"),
    NO("'no'"),

    // Operadores aritméticos
    MAS("'+'"),
    MENOS("'-'"),
    POR("'*'"),
    ENTRE("'/'"),
    MODULO("'%'"),

    // Operadores de comparación
    IGUAL_IGUAL("'=='"),
    DIFERENTE("'!='"),
    MENOR("'<'"),
    MENOR_IGUAL("'<='"),
    MAYOR("'>'"),
    MAYOR_IGUAL("'>='"),

    // Asignación
    ASIGNACION("'='"),

    // Signos de puntuación
    PAREN_IZQ("'('"),
    PAREN_DER("')'"),
    LLAVE_IZQ("'{'"),
    LLAVE_DER("'}'"),
    COMA("','"),
    PUNTO_COMA("';'"),
    DOS_PUNTOS("':'"),

    // Fin del archivo
    FIN("el fin del archivo");

    /** Las palabras reservadas están declaradas juntas, de ENTERO a NO. */
    private static final Set<TipoToken> PALABRAS_RESERVADAS = EnumSet.range(ENTERO, NO);

    private final String descripcion;

    TipoToken(String descripcion) {
        this.descripcion = descripcion;
    }

    /** Cómo se nombra este token en los mensajes de error. */
    public String descripcion() {
        return descripcion;
    }

    /** ¿Es una palabra del lenguaje (si, mientras, y...) que no se puede usar como nombre? */
    public boolean esPalabraReservada() {
        return PALABRAS_RESERVADAS.contains(this);
    }
}
