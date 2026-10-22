package compilador.semantico;

import compilador.ast.Expresion;
import compilador.ast.Expresion.Binaria;
import compilador.ast.Expresion.Conversion;
import compilador.ast.Expresion.LiteralBooleano;
import compilador.ast.Expresion.LiteralDecimal;
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
import compilador.ast.Predefinida;
import compilador.ast.Programa;
import compilador.ast.ReferenciaVariable;
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Análisis semántico: revisa que el programa tenga sentido, aunque su sintaxis sea correcta.
 *
 * <ul>
 *   <li>Existe 'principal' y no recibe parámetros ni regresa valor.</li>
 *   <li>No hay funciones repetidas ni con el nombre de una predefinida; las llamadas usan
 *       funciones que existen, con la cantidad y tipos de argumentos correctos.</li>
 *   <li>Las variables se declaran antes de usarse, una sola vez, y solo se usan
 *       dentro de su ámbito (el bloque donde se declararon).</li>
 *   <li>Los tipos de cada operación, asignación, condición, conversión y 'regresa' son correctos.
 *       Un entero se acepta donde se espera un decimal; el '+' con un texto es una concatenación.</li>
 *   <li>Una función con tipo de retorno regresa un valor en todos los caminos.</li>
 * </ul>
 *
 * Además le asigna a cada variable una ranura de memoria para el generador de código.
 */
public final class AnalizadorSemantico {

    private static final String FASE = "semántico";
    private static final String PRINCIPAL = "principal";

    /** Variable o parámetro visible en el ámbito actual. */
    private record Simbolo(String nombre, Tipo tipo, int ranura, Posicion declaradoEn) { }

    private final Programa programa;
    private final InfoSemantica info = new InfoSemantica();
    private final Map<String, Funcion> funciones = new HashMap<>();

    // Estado de la función que se está analizando
    private final Deque<Map<String, Simbolo>> ambitos = new ArrayDeque<>();
    private Funcion funcionActual;
    private int siguienteRanura;

    private AnalizadorSemantico(Programa programa) {
        this.programa = programa;
    }

    /** Revisa el programa completo. Lanza ErrorCompilacion en el primer error que encuentre. */
    public static InfoSemantica analizar(Programa programa) {
        return new AnalizadorSemantico(programa).analizarPrograma();
    }

    private InfoSemantica analizarPrograma() {
        // Primero se registran todas las funciones, para poder llamar a una
        // función definida más abajo (y permitir la recursión mutua).
        for (Funcion f : programa.funciones()) {
            if (Predefinida.buscar(f.nombre()).isPresent()) {
                throw error(f.pos(), "'" + f.nombre() + "' es una función predefinida del lenguaje; usa otro nombre");
            }
            Funcion previa = funciones.putIfAbsent(f.nombre(), f);
            if (previa != null) {
                throw error(f.pos(), "la función '" + f.nombre() + "' ya está definida (línea "
                        + previa.pos().linea() + ")");
            }
        }
        verificarPrincipal();
        for (Funcion f : programa.funciones()) {
            analizarFuncion(f);
        }
        return info;
    }

    private void verificarPrincipal() {
        Funcion principal = funciones.get(PRINCIPAL);
        if (principal == null) {
            throw new ErrorCompilacion(FASE, "falta la función 'principal', que es donde empieza el programa");
        }
        if (!principal.parametros().isEmpty()) {
            throw error(principal.pos(), "la función 'principal' no debe recibir parámetros");
        }
        if (principal.tipoRetorno() != Tipo.VACIO) {
            throw error(principal.pos(), "la función 'principal' no debe tener tipo de retorno");
        }
    }

    private void analizarFuncion(Funcion funcion) {
        funcionActual = funcion;
        siguienteRanura = 0;
        ambitos.clear();
        ambitos.push(new HashMap<>());
        for (Parametro p : funcion.parametros()) {
            declarar(p, p.tipo());
        }
        analizarSentencia(funcion.cuerpo());
        ambitos.pop();

        if (funcion.tipoRetorno() != Tipo.VACIO && !siempreRegresa(funcion.cuerpo())) {
            throw error(funcion.pos(), "la función '" + funcion.nombre() + "' debe regresar un valor de tipo "
                    + funcion.tipoRetorno() + " en todos los casos (falta un 'regresa')");
        }
        info.registrarTotalRanuras(funcion, siguienteRanura);
    }

    // ---------------------------------------------------------------
    // Sentencias
    // ---------------------------------------------------------------

    private void analizarSentencia(Sentencia sentencia) {
        switch (sentencia) {
            case Bloque b -> {
                ambitos.push(new HashMap<>());
                b.sentencias().forEach(this::analizarSentencia);
                ambitos.pop();
            }
            case Declaracion d -> {
                // El valor se revisa antes de declarar: "entero x = x;" es un error
                d.valorInicial().ifPresent(valor -> exigirTipo(valor, d.tipo(), encontrado ->
                        "la variable '" + d.nombre() + "' es de tipo " + d.tipo()
                                + ", pero se le asigna un valor " + encontrado));
                declarar(d, d.tipo());
            }
            case Asignacion a -> {
                Simbolo variable = buscarVariable(a);
                info.registrarVariable(a, variable.ranura(), variable.tipo());
                exigirTipo(a.valor(), variable.tipo(), encontrado ->
                        "la variable '" + a.nombre() + "' es de tipo " + variable.tipo()
                                + ", pero se le asigna un valor " + encontrado);
            }
            case Si si -> {
                exigirCondicion(si.condicion(), "si");
                analizarSentencia(si.entonces());
                si.sino().ifPresent(this::analizarSentencia);
            }
            case Mientras m -> {
                exigirCondicion(m.condicion(), "mientras");
                analizarSentencia(m.cuerpo());
            }
            case Para p -> {
                // La variable declarada en el 'para' solo existe dentro del ciclo
                ambitos.push(new HashMap<>());
                p.inicio().ifPresent(this::analizarSentencia);
                p.condicion().ifPresent(condicion -> exigirCondicion(condicion, "para"));
                p.paso().ifPresent(this::analizarSentencia);
                analizarSentencia(p.cuerpo());
                ambitos.pop();
            }
            case Regresa r -> analizarRegresa(r);
            case Imprimir i -> tipoDeValor(i.valor());
            case SentenciaLlamada s -> tipoDe(s.llamada());
        }
    }

    private void analizarRegresa(Regresa regresa) {
        String nombre = funcionActual.nombre();
        Tipo esperado = funcionActual.tipoRetorno();

        if (regresa.valor().isEmpty()) {
            if (esperado != Tipo.VACIO) {
                throw error(regresa.pos(), "'regresa' debe incluir un valor de tipo " + esperado
                        + " (la función '" + nombre + "' regresa " + esperado + ")");
            }
            return;
        }

        Expresion valor = regresa.valor().get();
        if (esperado == Tipo.VACIO) {
            throw error(valor.pos(), "la función '" + nombre
                    + "' no tiene tipo de retorno, así que 'regresa' no puede llevar un valor");
        }
        exigirTipo(valor, esperado, encontrado ->
                "la función '" + nombre + "' debe regresar " + esperado + ", pero aquí regresa un valor " + encontrado);
    }

    /**
     * ¿La sentencia termina siempre con un 'regresa'?
     * Es un análisis conservador: los ciclos cuentan como "puede que no",
     * porque su condición podría ser falsa desde el inicio.
     */
    private boolean siempreRegresa(Sentencia sentencia) {
        return switch (sentencia) {
            case Regresa r -> true;
            case Bloque b -> b.sentencias().stream().anyMatch(this::siempreRegresa);
            case Si si -> si.sino().isPresent()
                    && siempreRegresa(si.entonces())
                    && siempreRegresa(si.sino().get());
            default -> false;
        };
    }

    // ---------------------------------------------------------------
    // Expresiones
    // ---------------------------------------------------------------

    /** Calcula y registra el tipo de una expresión. Solo una llamada puede ser VACIO. */
    private Tipo tipoDe(Expresion expresion) {
        Tipo tipo = switch (expresion) {
            case Numero n -> Tipo.ENTERO;
            case LiteralDecimal d -> Tipo.DECIMAL;
            case LiteralBooleano b -> Tipo.BOOLEANO;
            case LiteralTexto t -> Tipo.TEXTO;
            case Variable v -> {
                Simbolo variable = buscarVariable(v);
                info.registrarVariable(v, variable.ranura(), variable.tipo());
                yield variable.tipo();
            }
            case Unaria u -> tipoDeUnaria(u);
            case Binaria b -> tipoDeBinaria(b);
            case Conversion c -> tipoDeConversion(c);
            case Llamada l -> tipoDeLlamada(l);
        };
        info.registrarTipo(expresion, tipo);
        return tipo;
    }

    /** Como tipoDe, pero exige que la expresión produzca un valor (no una función sin retorno). */
    private Tipo tipoDeValor(Expresion expresion) {
        Tipo tipo = tipoDe(expresion);
        if (tipo == Tipo.VACIO && expresion instanceof Llamada llamada) {
            throw error(llamada.pos(), "la función '" + llamada.nombre()
                    + "' no regresa un valor, así que no se puede usar como parte de una expresión");
        }
        return tipo;
    }

    private Tipo tipoDeUnaria(Unaria unaria) {
        Tipo operando = tipoDeValor(unaria.operando());
        String simbolo = unaria.operador().simbolo();
        if (unaria.operador() == OperadorUnario.NEGATIVO) {
            if (!operando.esNumerico()) {
                throw error(unaria.pos(), "el operador '" + simbolo
                        + "' necesita un valor numérico (entero o decimal), pero recibió un valor " + operando);
            }
            return operando;
        }
        if (operando != Tipo.BOOLEANO) {
            throw error(unaria.pos(), "el operador '" + simbolo
                    + "' necesita un valor booleano, pero recibió un valor " + operando);
        }
        return Tipo.BOOLEANO;
    }

    private Tipo tipoDeBinaria(Binaria binaria) {
        Tipo izquierda = tipoDeValor(binaria.izquierda());
        Tipo derecha = tipoDeValor(binaria.derecha());
        OperadorBinario operador = binaria.operador();
        return switch (operador.categoria()) {
            case ARITMETICO -> {
                // texto + cualquier valor: concatenación (el otro valor se convierte a texto)
                boolean hayTexto = izquierda == Tipo.TEXTO || derecha == Tipo.TEXTO;
                if (operador == OperadorBinario.SUMA && hayTexto) {
                    yield Tipo.TEXTO;
                }
                // El residuo solo tiene sentido con enteros
                if (operador == OperadorBinario.MODULO) {
                    exigirOperandos(binaria, izquierda, derecha, Tipo.ENTERO);
                    yield Tipo.ENTERO;
                }
                exigirNumericos(binaria, izquierda, derecha);
                // Si uno de los dos es decimal, el resultado es decimal (5 / 2.0 = 2.5)
                yield izquierda == Tipo.DECIMAL || derecha == Tipo.DECIMAL ? Tipo.DECIMAL : Tipo.ENTERO;
            }
            case COMPARACION -> {
                exigirNumericos(binaria, izquierda, derecha);
                yield Tipo.BOOLEANO;
            }
            case LOGICO -> {
                exigirOperandos(binaria, izquierda, derecha, Tipo.BOOLEANO);
                yield Tipo.BOOLEANO;
            }
            case IGUALDAD -> {
                boolean comparables = izquierda == derecha || (izquierda.esNumerico() && derecha.esNumerico());
                if (!comparables) {
                    throw error(binaria.pos(), "no se puede comparar con '" + operador.simbolo()
                            + "' un valor " + izquierda + " con un valor " + derecha);
                }
                yield Tipo.BOOLEANO;
            }
        };
    }

    private void exigirOperandos(Binaria binaria, Tipo izquierda, Tipo derecha, Tipo esperado) {
        if (izquierda != esperado || derecha != esperado) {
            throw error(binaria.pos(), "el operador '" + binaria.operador().simbolo()
                    + "' necesita dos valores de tipo " + esperado + ", pero recibió " + izquierda + " y " + derecha);
        }
    }

    private void exigirNumericos(Binaria binaria, Tipo izquierda, Tipo derecha) {
        if (!izquierda.esNumerico() || !derecha.esNumerico()) {
            throw error(binaria.pos(), "el operador '" + binaria.operador().simbolo()
                    + "' necesita dos valores numéricos (entero o decimal), pero recibió " + izquierda + " y " + derecha);
        }
    }

    /**
     * entero(x) y decimal(x) aceptan números y textos ("42" se convierte al ejecutar);
     * texto(x) acepta cualquier valor.
     */
    private Tipo tipoDeConversion(Conversion conversion) {
        Tipo origen = tipoDeValor(conversion.valor());
        Tipo destino = conversion.destino();
        boolean valida = destino == Tipo.TEXTO || origen.esNumerico() || origen == Tipo.TEXTO;
        if (!valida) {
            throw error(conversion.pos(), "no se puede convertir un valor " + origen + " a " + destino);
        }
        return destino;
    }

    private Tipo tipoDeLlamada(Llamada llamada) {
        String nombre = llamada.nombre();
        List<Expresion> argumentos = llamada.argumentos();

        Optional<Predefinida> predefinida = Predefinida.buscar(nombre);
        if (predefinida.isPresent()) {
            if (!argumentos.isEmpty()) {
                throw error(llamada.pos(), "la función '" + nombre + "' no recibe argumentos, pero se le pasaron "
                        + argumentos.size());
            }
            return predefinida.get().tipoRetorno();
        }

        Funcion funcion = funciones.get(nombre);
        if (funcion == null) {
            throw error(llamada.pos(), "la función '" + nombre + "' no existe");
        }

        List<Parametro> parametros = funcion.parametros();
        if (parametros.size() != argumentos.size()) {
            throw error(llamada.pos(), "la función '" + nombre + "' recibe "
                    + cantidad(parametros.size(), "argumento") + ", pero se le pasaron " + argumentos.size());
        }
        for (int i = 0; i < argumentos.size(); i++) {
            Parametro parametro = parametros.get(i);
            int numero = i + 1;
            exigirTipo(argumentos.get(i), parametro.tipo(), encontrado ->
                    "el argumento " + numero + " de '" + nombre + "' (" + parametro.nombre() + ") debe ser "
                            + parametro.tipo() + ", pero es " + encontrado);
        }
        return funcion.tipoRetorno();
    }

    // ---------------------------------------------------------------
    // Ámbitos y variables
    // ---------------------------------------------------------------

    /**
     * Declara una variable en el ámbito actual. No se permite repetir un nombre que
     * todavía es visible (ni siquiera en un bloque interior), igual que en Java.
     */
    private void declarar(ReferenciaVariable referencia, Tipo tipo) {
        Simbolo previo = buscar(referencia.nombre());
        if (previo != null) {
            throw error(referencia.pos(), "ya existe una variable llamada '" + referencia.nombre()
                    + "' (declarada en la línea " + previo.declaradoEn().linea() + ")");
        }
        Simbolo simbolo = new Simbolo(referencia.nombre(), tipo, siguienteRanura++, referencia.pos());
        ambitos.peek().put(simbolo.nombre(), simbolo);
        info.registrarVariable(referencia, simbolo.ranura(), tipo);
    }

    private Simbolo buscarVariable(ReferenciaVariable referencia) {
        Simbolo simbolo = buscar(referencia.nombre());
        if (simbolo == null) {
            throw error(referencia.pos(), "la variable '" + referencia.nombre() + "' no está declarada");
        }
        return simbolo;
    }

    private Simbolo buscar(String nombre) {
        for (Map<String, Simbolo> ambito : ambitos) {
            Simbolo simbolo = ambito.get(nombre);
            if (simbolo != null) {
                return simbolo;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Auxiliares
    // ---------------------------------------------------------------

    /** Exige que la expresión se pueda guardar donde se espera el tipo indicado. */
    private void exigirTipo(Expresion expresion, Tipo esperado, Function<Tipo, String> mensaje) {
        Tipo encontrado = tipoDeValor(expresion);
        if (!esperado.aceptaValorDe(encontrado)) {
            throw error(expresion.pos(), mensaje.apply(encontrado));
        }
    }

    private void exigirCondicion(Expresion condicion, String sentencia) {
        exigirTipo(condicion, Tipo.BOOLEANO, encontrado ->
                "la condición del '" + sentencia + "' debe ser booleana, pero es de tipo " + encontrado);
    }

    private static String cantidad(int n, String palabra) {
        return n + " " + palabra + (n == 1 ? "" : "s");
    }

    private static ErrorCompilacion error(Posicion pos, String mensaje) {
        return new ErrorCompilacion(FASE, mensaje, pos.linea(), pos.columna());
    }
}
