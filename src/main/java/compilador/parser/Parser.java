package compilador.parser;

import compilador.ast.Expresion;
import compilador.ast.Expresion.Binaria;
import compilador.ast.Expresion.Conversion;
import compilador.ast.Expresion.LiteralDecimal;
import compilador.ast.Expresion.LiteralBooleano;
import compilador.ast.Expresion.LiteralTexto;
import compilador.ast.Expresion.Llamada;
import compilador.ast.Expresion.Numero;
import compilador.ast.Expresion.Unaria;
import compilador.ast.Expresion.Variable;
import compilador.ast.Funcion;
import compilador.ast.OperadorBinario;
import compilador.ast.OperadorUnario;
import compilador.ast.Parametro;
import compilador.ast.Posicion;
import compilador.ast.Programa;
import compilador.ast.Sentencia;
import compilador.ast.Sentencia.Asignacion;
import compilador.ast.Sentencia.Bloque;
import compilador.ast.Sentencia.Declaracion;
import compilador.ast.Sentencia.Imprimir;
import compilador.ast.Sentencia.Mientras;
import compilador.ast.Sentencia.Para;
import compilador.ast.Sentencia.Regresa;
import compilador.ast.Sentencia.SentenciaLlamada;
import compilador.ast.Sentencia.Si;
import compilador.ast.Tipo;
import compilador.errores.ErrorCompilacion;
import compilador.lexer.Lexer;
import compilador.lexer.TipoToken;
import compilador.lexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static compilador.lexer.TipoToken.*;

/**
 * Analizador sintáctico descendente recursivo.
 *
 * Recibe la lista de tokens y construye el árbol sintáctico (AST).
 * Cada regla de la gramática (ver docs/gramatica.md) corresponde a un método
 * con el mismo nombre. La precedencia de los operadores sale del orden en que
 * se llaman los métodos de expresiones: o → y → igualdad → comparación → suma → producto → unario.
 */
public final class Parser {

    private static final String FASE = "sintáctico";

    /** Límite de anidamiento: evita desbordar la pila de Java con programas patológicos. */
    static final int MAXIMO_ANIDAMIENTO = 256;

    private static final Map<TipoToken, Tipo> TIPOS = Map.of(
            ENTERO, Tipo.ENTERO,
            DECIMAL, Tipo.DECIMAL,
            BOOLEANO, Tipo.BOOLEANO,
            TEXTO, Tipo.TEXTO);

    private static final Map<TipoToken, OperadorBinario> OPERADORES_O = Map.of(
            O, OperadorBinario.O);
    private static final Map<TipoToken, OperadorBinario> OPERADORES_Y = Map.of(
            Y, OperadorBinario.Y);
    private static final Map<TipoToken, OperadorBinario> OPERADORES_IGUALDAD = Map.of(
            IGUAL_IGUAL, OperadorBinario.IGUAL,
            DIFERENTE, OperadorBinario.DIFERENTE);
    private static final Map<TipoToken, OperadorBinario> OPERADORES_COMPARACION = Map.of(
            MENOR, OperadorBinario.MENOR,
            MENOR_IGUAL, OperadorBinario.MENOR_IGUAL,
            MAYOR, OperadorBinario.MAYOR,
            MAYOR_IGUAL, OperadorBinario.MAYOR_IGUAL);
    private static final Map<TipoToken, OperadorBinario> OPERADORES_SUMA = Map.of(
            MAS, OperadorBinario.SUMA,
            MENOS, OperadorBinario.RESTA);
    private static final Map<TipoToken, OperadorBinario> OPERADORES_PRODUCTO = Map.of(
            POR, OperadorBinario.MULTIPLICACION,
            ENTRE, OperadorBinario.DIVISION,
            MODULO, OperadorBinario.MODULO);

    private final List<Token> tokens;
    private int actual = 0;
    private int anidamiento = 0;

    private Parser(List<Token> tokens) {
        if (tokens.isEmpty() || tokens.getLast().tipo() != FIN) {
            throw new IllegalArgumentException("la lista de tokens debe terminar en FIN");
        }
        this.tokens = tokens;
    }

    /** Construye el árbol sintáctico del programa completo. */
    public static Programa analizar(List<Token> tokens) {
        return new Parser(tokens).programa();
    }

    // ---------------------------------------------------------------
    // Programa y funciones
    // ---------------------------------------------------------------

    /** programa → funcion* FIN */
    private Programa programa() {
        List<Funcion> funciones = new ArrayList<>();
        while (!verifica(FIN)) {
            funciones.add(funcion());
        }
        return new Programa(funciones);
    }

    /** funcion → "funcion" IDENTIFICADOR "(" parametros? ")" ( ":" tipo )? bloque */
    private Funcion funcion() {
        if (!verifica(FUNCION)) {
            Token t = actual();
            String pista = esInicioDeTipo()
                    ? " (las variables se declaran dentro de una función)"
                    : "";
            throw errorEn(t, "se esperaba 'funcion' pero se encontró " + describir(t) + pista);
        }
        avanzar();
        Token nombre = consumir(IDENTIFICADOR, "el nombre de la función");
        consumir(PAREN_IZQ);

        List<Parametro> parametros = new ArrayList<>();
        if (!verifica(PAREN_DER)) {
            do {
                Tipo tipo = tipo();
                Token nombreParametro = consumir(IDENTIFICADOR, "el nombre del parámetro");
                parametros.add(new Parametro(tipo, nombreParametro.lexema(), pos(nombreParametro)));
            } while (coincide(COMA));
        }
        consumir(PAREN_DER);

        Tipo tipoRetorno = coincide(DOS_PUNTOS) ? tipo() : Tipo.VACIO;
        Bloque cuerpo = bloque();
        return new Funcion(nombre.lexema(), parametros, tipoRetorno, cuerpo, pos(nombre));
    }

    /** tipo → "entero" | "decimal" | "booleano" | "texto" */
    private Tipo tipo() {
        Tipo tipo = TIPOS.get(actual().tipo());
        if (tipo == null) {
            throw errorEsperado("un tipo ('entero', 'decimal', 'booleano' o 'texto')");
        }
        avanzar();
        return tipo;
    }

    /** ¿El token actual empieza un tipo (y por lo tanto una declaración)? */
    private boolean esInicioDeTipo() {
        return TIPOS.containsKey(actual().tipo());
    }

    // ---------------------------------------------------------------
    // Sentencias
    // ---------------------------------------------------------------

    /** bloque → "{" sentencia* "}" */
    private Bloque bloque() {
        Token llave = consumir(LLAVE_IZQ);
        entrarAnidamiento(llave);
        List<Sentencia> sentencias = new ArrayList<>();
        while (!verifica(LLAVE_DER)) {
            if (verifica(FIN)) {
                throw errorEn(llave, "falta '}' para cerrar este bloque");
            }
            sentencias.add(sentencia());
        }
        avanzar();
        salirAnidamiento();
        return new Bloque(sentencias, pos(llave));
    }

    private Sentencia sentencia() {
        Token t = actual();
        return switch (t.tipo()) {
            case ENTERO, DECIMAL, BOOLEANO, TEXTO -> {
                Declaracion declaracion = declaracion();
                consumir(PUNTO_COMA);
                yield declaracion;
            }
            case IDENTIFICADOR -> asignacionOLlamada();
            case SI -> si();
            case MIENTRAS -> mientras();
            case PARA -> para();
            case REGRESA -> regresa();
            case IMPRIMIR, ESCRIBIR -> imprimir();
            case LLAVE_IZQ -> bloque();
            case SINO -> throw errorEn(t, "'sino' sin un 'si' antes");
            case FUNCION -> throw errorEn(t,
                    "se esperaba '}' antes de 'funcion' (no se pueden definir funciones dentro de otras)");
            default -> throw errorEn(t, "se esperaba una sentencia pero se encontró " + describirConPista(t));
        };
    }

    /** declaracion → tipo IDENTIFICADOR ( "=" expresion )? */
    private Declaracion declaracion() {
        Tipo tipo = tipo();
        Token nombre = consumir(IDENTIFICADOR, "el nombre de la variable");
        Optional<Expresion> valorInicial = coincide(ASIGNACION) ? Optional.of(expresion()) : Optional.empty();
        return new Declaracion(tipo, nombre.lexema(), valorInicial, pos(nombre));
    }

    /** asignacion → IDENTIFICADOR "=" expresion */
    private Asignacion asignacion() {
        Token nombre = consumir(IDENTIFICADOR, "el nombre de una variable");
        consumir(ASIGNACION);
        Expresion valor = expresion();
        return new Asignacion(nombre.lexema(), valor, pos(nombre));
    }

    /** Una sentencia que empieza con un nombre: "x = 5;" o "saludar();" */
    private Sentencia asignacionOLlamada() {
        TipoToken siguiente = tokens.get(actual + 1).tipo();
        if (siguiente == ASIGNACION) {
            Asignacion asignacion = asignacion();
            consumir(PUNTO_COMA);
            return asignacion;
        }
        if (siguiente == PAREN_IZQ) {
            Llamada llamada = llamada();
            consumir(PUNTO_COMA);
            return new SentenciaLlamada(llamada);
        }
        Token nombre = avanzar();
        throw errorEsperado("'=' o '(' después de '" + nombre.lexema() + "'");
    }

    /** si → "si" "(" expresion ")" bloque ( "sino" ( si | bloque ) )? */
    private Si si() {
        Token palabraSi = avanzar();
        entrarAnidamiento(palabraSi);
        consumir(PAREN_IZQ, "'(' después de 'si'");
        Expresion condicion = expresion();
        consumir(PAREN_DER);
        Bloque entonces = bloque();

        Optional<Sentencia> sino = Optional.empty();
        if (coincide(SINO)) {
            sino = Optional.of(verifica(SI) ? si() : bloque());
        }
        salirAnidamiento();
        return new Si(condicion, entonces, sino, pos(palabraSi));
    }

    /** mientras → "mientras" "(" expresion ")" bloque */
    private Mientras mientras() {
        Token palabraMientras = avanzar();
        consumir(PAREN_IZQ, "'(' después de 'mientras'");
        Expresion condicion = expresion();
        consumir(PAREN_DER);
        Bloque cuerpo = bloque();
        return new Mientras(condicion, cuerpo, pos(palabraMientras));
    }

    /** para → "para" "(" ( declaracion | asignacion )? ";" expresion? ";" asignacion? ")" bloque */
    private Para para() {
        Token palabraPara = avanzar();
        consumir(PAREN_IZQ, "'(' después de 'para'");

        Optional<Sentencia> inicio = Optional.empty();
        if (esInicioDeTipo()) {
            inicio = Optional.of(declaracion());
        } else if (verifica(IDENTIFICADOR)) {
            inicio = Optional.of(asignacion());
        }
        consumir(PUNTO_COMA);

        Optional<Expresion> condicion = verifica(PUNTO_COMA) ? Optional.empty() : Optional.of(expresion());
        consumir(PUNTO_COMA);

        Optional<Asignacion> paso = verifica(PAREN_DER) ? Optional.empty() : Optional.of(asignacion());
        consumir(PAREN_DER);

        Bloque cuerpo = bloque();
        return new Para(inicio, condicion, paso, cuerpo, pos(palabraPara));
    }

    /** regresa → "regresa" expresion? ";" */
    private Regresa regresa() {
        Token palabraRegresa = avanzar();
        boolean sinValor = verifica(PUNTO_COMA) || verifica(LLAVE_DER);
        Optional<Expresion> valor = sinValor ? Optional.empty() : Optional.of(expresion());
        consumir(PUNTO_COMA);
        return new Regresa(valor, pos(palabraRegresa));
    }

    /** imprimir → ( "imprimir" | "escribir" ) "(" expresion ")" ";" */
    private Imprimir imprimir() {
        Token palabra = avanzar();
        consumir(PAREN_IZQ, "'(' después de '" + palabra.lexema() + "'");
        Expresion valor = expresion();
        consumir(PAREN_DER);
        consumir(PUNTO_COMA);
        return new Imprimir(valor, palabra.tipo() == IMPRIMIR, pos(palabra));
    }

    // ---------------------------------------------------------------
    // Expresiones, de menor a mayor precedencia
    // ---------------------------------------------------------------

    /** expresion → o */
    private Expresion expresion() {
        entrarAnidamiento(actual());
        Expresion expresion = o();
        salirAnidamiento();
        return expresion;
    }

    /** o → y ( "o" y )* */
    private Expresion o() {
        return binaria(this::y, OPERADORES_O);
    }

    /** y → igualdad ( "y" igualdad )* */
    private Expresion y() {
        return binaria(this::igualdad, OPERADORES_Y);
    }

    /** igualdad → comparacion ( ( "==" | "!=" ) comparacion )* */
    private Expresion igualdad() {
        return binaria(this::comparacion, OPERADORES_IGUALDAD);
    }

    /** comparacion → suma ( ( "<" | "<=" | ">" | ">=" ) suma )* */
    private Expresion comparacion() {
        return binaria(this::suma, OPERADORES_COMPARACION);
    }

    /** suma → producto ( ( "+" | "-" ) producto )* */
    private Expresion suma() {
        return binaria(this::producto, OPERADORES_SUMA);
    }

    /** producto → unario ( ( "*" | "/" | "%" ) unario )* */
    private Expresion producto() {
        return binaria(this::unario, OPERADORES_PRODUCTO);
    }

    /**
     * Reconoce una cadena de operadores del mismo nivel, asociando a la izquierda:
     * 10 - 3 - 2 se agrupa como (10 - 3) - 2.
     */
    private Expresion binaria(Supplier<Expresion> siguienteNivel, Map<TipoToken, OperadorBinario> operadores) {
        Expresion izquierda = siguienteNivel.get();
        while (operadores.containsKey(actual().tipo())) {
            Token operador = avanzar();
            Expresion derecha = siguienteNivel.get();
            izquierda = new Binaria(operadores.get(operador.tipo()), izquierda, derecha, pos(operador));
        }
        return izquierda;
    }

    /** unario → ( "-" | "no" ) unario | primario */
    private Expresion unario() {
        if (verifica(MENOS) || verifica(NO)) {
            Token operador = avanzar();
            entrarAnidamiento(operador);
            Expresion operando = unario();
            salirAnidamiento();
            OperadorUnario tipo = operador.tipo() == MENOS ? OperadorUnario.NEGATIVO : OperadorUnario.NO;
            return new Unaria(tipo, operando, pos(operador));
        }
        return primario();
    }

    /**
     * primario → NUMERO | NUMERO_DECIMAL | CADENA | "verdadero" | "falso"
     *          | conversion | llamada | IDENTIFICADOR | "(" expresion ")"
     */
    private Expresion primario() {
        Token t = actual();
        return switch (t.tipo()) {
            case NUMERO -> {
                avanzar();
                yield new Numero(Long.parseLong(t.lexema()), pos(t));
            }
            case NUMERO_DECIMAL -> {
                avanzar();
                yield new LiteralDecimal(Double.parseDouble(t.lexema()), pos(t));
            }
            case ENTERO, DECIMAL, TEXTO -> {
                if (tokens.get(actual + 1).tipo() == PAREN_IZQ) {
                    yield conversion();
                }
                throw errorEn(t, "se esperaba una expresión pero se encontró " + describirConPista(t));
            }
            case VERDADERO, FALSO -> {
                avanzar();
                yield new LiteralBooleano(t.tipo() == VERDADERO, pos(t));
            }
            case CADENA -> {
                avanzar();
                yield new LiteralTexto(Lexer.valorDeTexto(t.lexema()), pos(t));
            }
            case IDENTIFICADOR -> {
                if (tokens.get(actual + 1).tipo() == PAREN_IZQ) {
                    yield llamada();
                }
                avanzar();
                yield new Variable(t.lexema(), pos(t));
            }
            case PAREN_IZQ -> {
                avanzar();
                Expresion interior = expresion();
                consumir(PAREN_DER);
                yield interior;
            }
            default -> {
                if (t.tipo().esPalabraReservada()) {
                    throw errorEn(t, "se esperaba una expresión pero se encontró " + describirConPista(t));
                }
                throw errorEsperado("una expresión");
            }
        };
    }

    /** conversion → ( "entero" | "decimal" | "texto" ) "(" expresion ")" */
    private Conversion conversion() {
        Token nombreTipo = actual();
        Tipo destino = tipo();
        consumir(PAREN_IZQ);
        Expresion valor = expresion();
        consumir(PAREN_DER, "')' para cerrar la conversión a " + destino);
        return new Conversion(destino, valor, pos(nombreTipo));
    }

    /** llamada → IDENTIFICADOR "(" ( expresion ( "," expresion )* )? ")" */
    private Llamada llamada() {
        Token nombre = consumir(IDENTIFICADOR);
        consumir(PAREN_IZQ);
        List<Expresion> argumentos = new ArrayList<>();
        if (!verifica(PAREN_DER)) {
            do {
                argumentos.add(expresion());
            } while (coincide(COMA));
        }
        consumir(PAREN_DER, "')' para cerrar la llamada a '" + nombre.lexema() + "'");
        return new Llamada(nombre.lexema(), argumentos, pos(nombre));
    }

    // ---------------------------------------------------------------
    // Funciones auxiliares para recorrer los tokens
    // ---------------------------------------------------------------

    private Token actual() {
        return tokens.get(actual);
    }

    /** Consume el token actual y lo regresa. Nunca avanza más allá de FIN. */
    private Token avanzar() {
        Token t = tokens.get(actual);
        if (t.tipo() != FIN) {
            actual++;
        }
        return t;
    }

    private boolean verifica(TipoToken tipo) {
        return actual().tipo() == tipo;
    }

    /** Si el token actual es del tipo indicado, lo consume y regresa true. */
    private boolean coincide(TipoToken tipo) {
        if (verifica(tipo)) {
            avanzar();
            return true;
        }
        return false;
    }

    /** Exige que el token actual sea del tipo indicado; si no, es un error de sintaxis. */
    private Token consumir(TipoToken tipo) {
        return consumir(tipo, tipo.descripcion());
    }

    private Token consumir(TipoToken tipo, String esperado) {
        if (verifica(tipo)) {
            return avanzar();
        }
        Token encontrado = actual();
        if (tipo == IDENTIFICADOR && encontrado.tipo().esPalabraReservada()) {
            throw errorEn(encontrado, "se esperaba " + esperado + " pero se encontró " + describirConPista(encontrado));
        }
        throw errorEsperado(esperado);
    }

    private void entrarAnidamiento(Token t) {
        if (++anidamiento > MAXIMO_ANIDAMIENTO) {
            throw errorEn(t, "el programa tiene demasiados niveles de anidamiento (máximo "
                    + MAXIMO_ANIDAMIENTO + ")");
        }
    }

    private void salirAnidamiento() {
        anidamiento--;
    }

    private static Posicion pos(Token t) {
        return new Posicion(t.linea(), t.columna());
    }

    private static String describir(Token t) {
        return switch (t.tipo()) {
            case FIN -> "el fin del archivo";
            case NUMERO, NUMERO_DECIMAL -> "el número " + t.lexema();
            case CADENA -> "el texto " + t.lexema();
            default -> "'" + t.lexema() + "'";
        };
    }

    /** Como describir, pero explica cuando alguien intentó usar una palabra reservada como nombre. */
    private static String describirConPista(Token t) {
        if (t.tipo().esPalabraReservada()) {
            return "'" + t.lexema() + "', que es una palabra reservada y no se puede usar como nombre";
        }
        return describir(t);
    }

    /**
     * Error "se esperaba X". Si el token encontrado ya está en otra línea,
     * lo más probable es que falte algo al final de la línea anterior
     * (el típico ';' olvidado), así que el error se señala ahí.
     */
    private ErrorCompilacion errorEsperado(String esperado) {
        Token encontrado = actual();
        if (actual > 0) {
            Token anterior = tokens.get(actual - 1);
            if (encontrado.linea() > anterior.linea()) {
                return new ErrorCompilacion(FASE, "se esperaba " + esperado + " al final de la línea",
                        anterior.linea(), anterior.columna() + anterior.lexema().length());
            }
        }
        return errorEn(encontrado, "se esperaba " + esperado + " pero se encontró " + describir(encontrado));
    }

    private static ErrorCompilacion errorEn(Token t, String mensaje) {
        return new ErrorCompilacion(FASE, mensaje, t.linea(), t.columna());
    }
}
