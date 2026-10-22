package compilador.generador;

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
import compilador.semantico.InfoSemantica;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generador de código: recorre el árbol sintáctico y produce ensamblador x86-64
 * para Linux, en sintaxis Intel del ensamblador GNU (as).
 *
 * Modelo de ejecución (detallado en docs/gramatica.md):
 * <ul>
 *   <li>Toda expresión deja su resultado en rax. Los resultados intermedios se guardan en la pila.</li>
 *   <li>Un entero es su valor de 64 bits; un decimal son los 64 bits de su double (IEEE 754),
 *       que se pasan a xmm0/xmm1 para operar; un booleano es 1 o 0; un texto es la dirección
 *       de sus bytes en UTF-8, terminados en 0 (como en C).</li>
 *   <li>Cada variable o parámetro ocupa una ranura de 8 bytes en el marco de su función:
 *       la ranura k está en [rbp - 8*(k+1)].</li>
 *   <li>Los argumentos se meten a la pila de izquierda a derecha; la función que llama los retira.
 *       Al entrar, la función copia sus parámetros a sus ranuras.</li>
 *   <li>El valor de retorno se deja en rax.</li>
 *   <li>imprimir, leer, los textos y los errores en tiempo de ejecución usan rutinas de soporte
 *       que llaman a la biblioteca de C.</li>
 * </ul>
 */
public final class GeneradorX86 {

    private static final int BYTES_POR_RANURA = 8;
    /** Distancia de rbp al último argumento: rbp guardado (8 bytes) + dirección de retorno (8 bytes). */
    private static final int DESPLAZAMIENTO_ARGUMENTOS = 16;

    private static final String PREFIJO_FUNCION = "fn_";
    private static final String FUNCION_PRINCIPAL = "principal";

    // Rutinas de soporte (definidas en generarRutinasDeSoporte)
    private static final String RT_IMPRIMIR_ENTERO = "_rt_imprimir_entero";
    private static final String RT_ESCRIBIR_ENTERO = "_rt_escribir_entero";
    private static final String RT_IMPRIMIR_TEXTO = "_rt_imprimir_texto";
    private static final String RT_ESCRIBIR_TEXTO = "_rt_escribir_texto";
    private static final String RT_ENTERO_A_TEXTO = "_rt_entero_a_texto";
    private static final String RT_DECIMAL_A_TEXTO = "_rt_decimal_a_texto";
    private static final String RT_BOOLEANO_A_TEXTO = "_rt_booleano_a_texto";
    private static final String RT_TEXTO_A_ENTERO = "_rt_texto_a_entero";
    private static final String RT_TEXTO_A_DECIMAL = "_rt_texto_a_decimal";
    private static final String RT_CONCATENAR = "_rt_concatenar";
    private static final String RT_TEXTOS_IGUALES = "_rt_textos_iguales";
    private static final String RT_ERROR_DIVISION = "_rt_error_division";
    private static final Map<Predefinida, String> RT_PREDEFINIDAS = Map.of(
            Predefinida.LEER_TEXTO, "_rt_leer_texto",
            Predefinida.LEER_ENTERO, "_rt_leer_entero",
            Predefinida.LEER_DECIMAL, "_rt_leer_decimal");
    private static final String TEXTO_VACIO = ".Ltexto_vacio";

    /** Columna donde empiezan los comentarios al final de una instrucción. */
    private static final int COLUMNA_COMENTARIO = 36;

    private final Programa programa;
    private final InfoSemantica info;
    private final List<String> lineasFuente;
    private final Map<String, Funcion> funciones = new HashMap<>();
    private final StringBuilder asm = new StringBuilder();
    /** Textos literales del programa y su etiqueta. Un texto repetido se guarda una sola vez. */
    private final Map<String, String> etiquetasDeTextos = new LinkedHashMap<>();

    private int contadorEtiquetas = 0;
    private int ultimaLineaComentada = 0;
    private Funcion funcionActual;
    private String etiquetaRetorno;

    private GeneradorX86(Programa programa, InfoSemantica info, String codigoFuente) {
        this.programa = programa;
        this.info = info;
        this.lineasFuente = Arrays.stream(codigoFuente.split("\n", -1))
                .map(linea -> linea.replace("\r", ""))
                .toList();
        for (Funcion funcion : programa.funciones()) {
            funciones.put(funcion.nombre(), funcion);
        }
    }

    /**
     * Genera el ensamblador del programa completo.
     *
     * @param programa     árbol sintáctico ya revisado por el análisis semántico
     * @param info         resultado del análisis semántico de ese árbol
     * @param codigoFuente el texto original, para poner las líneas como comentarios en el ensamblador
     */
    public static String generar(Programa programa, InfoSemantica info, String codigoFuente) {
        return new GeneradorX86(programa, info, codigoFuente).generarPrograma();
    }

    /**
     * Nombre de la etiqueta en ensamblador de una función del lenguaje.
     *
     * Se agrega el prefijo "fn_" para que no choque con registros (rax), con 'main'
     * o con funciones de C. Las letras con acento se codifican porque el ensamblador
     * solo acepta ASCII: "añoSiguiente" queda como "fn_a_u0000f1oSiguiente".
     * El '_' se duplica para que la codificación nunca produzca dos nombres iguales.
     */
    static String etiquetaFuncion(String nombre) {
        StringBuilder etiqueta = new StringBuilder(PREFIJO_FUNCION);
        nombre.codePoints().forEach(c -> {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                etiqueta.appendCodePoint(c);
            } else if (c == '_') {
                etiqueta.append("__");
            } else {
                etiqueta.append(String.format("_u%06x", c));
            }
        });
        return etiqueta.toString();
    }

    /**
     * Escribe un texto como literal de la directiva .string del ensamblador.
     * Todo lo que no es ASCII imprimible (acentos, saltos de línea...) se escribe byte por byte
     * en octal, así el ensamblador guarda exactamente los bytes UTF-8 del texto.
     */
    static String literalEnsamblador(String texto) {
        StringBuilder literal = new StringBuilder("\"");
        for (byte b : texto.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c == '"' || c == '\\') {
                literal.append('\\').append((char) c);
            } else if (c >= 32 && c < 127) {
                literal.append((char) c);
            } else {
                literal.append(String.format("\\%03o", c));
            }
        }
        return literal.append('"').toString();
    }

    private String generarPrograma() {
        asm.append("# Generado por el compilador de .mio: ensamblador x86-64 (sintaxis Intel) para Linux\n");
        instruccion(".intel_syntax noprefix");
        instruccion(".text");
        for (Funcion funcion : programa.funciones()) {
            generarFuncion(funcion);
        }
        generarMain();
        generarRutinasDeSoporte();
        generarDatos();
        return asm.toString();
    }

    // ---------------------------------------------------------------
    // Funciones
    // ---------------------------------------------------------------

    private void generarFuncion(Funcion funcion) {
        String nombre = etiquetaFuncion(funcion.nombre());
        funcionActual = funcion;
        etiquetaRetorno = ".Lretorno_" + nombre;

        asm.append('\n');
        comentarLineaFuente(funcion.pos(), "");
        etiqueta(nombre);

        // Prólogo: crea el marco de la función y reserva espacio para sus variables
        instruccion("push rbp");
        instruccion("mov rbp, rsp");
        int ranuras = info.totalRanuras(funcion);
        if (ranuras > 0) {
            instruccion("sub rsp, " + ranuras * BYTES_POR_RANURA,
                    "espacio para " + ranuras + (ranuras == 1 ? " variable" : " variables"));
        }

        // Copia cada argumento (que está en la pila de quien llamó) a la ranura de su parámetro
        int totalParametros = funcion.parametros().size();
        for (int i = 0; i < totalParametros; i++) {
            int desplazamiento = DESPLAZAMIENTO_ARGUMENTOS + BYTES_POR_RANURA * (totalParametros - 1 - i);
            instruccion("mov rax, QWORD PTR [rbp + " + desplazamiento + "]");
            instruccion("mov " + direccion(funcion.parametros().get(i)) + ", rax",
                    "parámetro " + funcion.parametros().get(i).nombre());
        }

        generarSentencia(funcion.cuerpo());

        // Epílogo: destruye el marco y regresa a quien llamó (el resultado ya está en rax)
        etiqueta(etiquetaRetorno);
        instruccion("mov rsp, rbp");
        instruccion("pop rbp");
        instruccion("ret");
    }

    /** main de C: la biblioteca de C lo llama al iniciar el programa, y él llama a 'principal'. */
    private void generarMain() {
        asm.append("\n# Punto de entrada del programa\n");
        instruccion(".globl main");
        etiqueta("main");
        instruccion("push rbp");
        instruccion("mov rbp, rsp");
        instruccion("call " + etiquetaFuncion(FUNCION_PRINCIPAL));
        instruccion("xor eax, eax", "código de salida 0: todo salió bien");
        instruccion("pop rbp");
        instruccion("ret");
    }

    // ---------------------------------------------------------------
    // Sentencias
    // ---------------------------------------------------------------

    private void generarSentencia(Sentencia sentencia) {
        comentarLineaFuente(sentencia.pos(), "    ");
        switch (sentencia) {
            case Bloque b -> b.sentencias().forEach(this::generarSentencia);
            case Declaracion d -> {
                if (d.valorInicial().isPresent()) {
                    generarExpresionComo(d.valorInicial().get(), d.tipo());
                } else if (d.tipo() == Tipo.TEXTO) {
                    instruccion("lea rax, [rip + " + TEXTO_VACIO + "]", "valor inicial: texto vacío");
                } else {
                    instruccion("mov rax, 0", "valor inicial: 0, 0.0 o falso");
                }
                instruccion("mov " + direccion(d) + ", rax", d.nombre());
            }
            case Asignacion a -> {
                generarExpresionComo(a.valor(), info.tipoDeVariable(a));
                instruccion("mov " + direccion(a) + ", rax", a.nombre());
            }
            case Si si -> {
                String etiquetaFin = nuevaEtiqueta("fin_si");
                generarExpresion(si.condicion());
                instruccion("test rax, rax");
                if (si.sino().isEmpty()) {
                    instruccion("je " + etiquetaFin, "condición falsa: se salta el bloque");
                    generarSentencia(si.entonces());
                } else {
                    String etiquetaSino = nuevaEtiqueta("sino");
                    instruccion("je " + etiquetaSino, "condición falsa: salta al sino");
                    generarSentencia(si.entonces());
                    instruccion("jmp " + etiquetaFin);
                    etiqueta(etiquetaSino);
                    generarSentencia(si.sino().get());
                }
                etiqueta(etiquetaFin);
            }
            case Mientras m -> {
                String etiquetaInicio = nuevaEtiqueta("mientras");
                String etiquetaFin = nuevaEtiqueta("fin_mientras");
                etiqueta(etiquetaInicio);
                generarExpresion(m.condicion());
                instruccion("test rax, rax");
                instruccion("je " + etiquetaFin, "condición falsa: sale del ciclo");
                generarSentencia(m.cuerpo());
                instruccion("jmp " + etiquetaInicio);
                etiqueta(etiquetaFin);
            }
            case Para p -> {
                String etiquetaInicio = nuevaEtiqueta("para");
                String etiquetaFin = nuevaEtiqueta("fin_para");
                p.inicio().ifPresent(this::generarSentencia);
                etiqueta(etiquetaInicio);
                p.condicion().ifPresent(condicion -> {
                    generarExpresion(condicion);
                    instruccion("test rax, rax");
                    instruccion("je " + etiquetaFin, "condición falsa: sale del ciclo");
                });
                generarSentencia(p.cuerpo());
                p.paso().ifPresent(this::generarSentencia);
                instruccion("jmp " + etiquetaInicio);
                etiqueta(etiquetaFin);
            }
            case Regresa r -> {
                r.valor().ifPresent(valor -> generarExpresionComo(valor, funcionActual.tipoRetorno()));
                instruccion("jmp " + etiquetaRetorno);
            }
            case Imprimir i -> generarImprimir(i);
            case SentenciaLlamada s -> generarExpresion(s.llamada());
        }
    }

    /** imprimir (con salto de línea) o escribir (sin salto de línea) de cualquier valor. */
    private void generarImprimir(Imprimir imprimir) {
        generarExpresion(imprimir.valor());
        Tipo tipo = info.tipoDe(imprimir.valor());
        if (tipo == Tipo.ENTERO) {
            instruccion("mov rdi, rax");
            instruccion("call " + (imprimir.saltoDeLinea() ? RT_IMPRIMIR_ENTERO : RT_ESCRIBIR_ENTERO));
        } else {
            convertirATexto(tipo);
            instruccion("mov rdi, rax");
            instruccion("call " + (imprimir.saltoDeLinea() ? RT_IMPRIMIR_TEXTO : RT_ESCRIBIR_TEXTO));
        }
    }

    // ---------------------------------------------------------------
    // Expresiones: todas dejan su resultado en rax
    // ---------------------------------------------------------------

    private void generarExpresion(Expresion expresion) {
        switch (expresion) {
            case Numero n -> instruccion("mov rax, " + n.valor());
            case LiteralDecimal d -> instruccion("mov rax, " + Double.doubleToRawLongBits(d.valor()),
                    "bits del decimal " + d.valor());
            case LiteralBooleano b -> instruccion("mov rax, " + (b.valor() ? 1 : 0),
                    b.valor() ? "verdadero" : "falso");
            case LiteralTexto t -> instruccion("lea rax, [rip + " + etiquetaDeTexto(t.valor()) + "]",
                    "dirección del texto");
            case Variable v -> instruccion("mov rax, " + direccion(v), v.nombre());
            case Unaria u -> {
                generarExpresion(u.operando());
                switch (u.operador()) {
                    case NEGATIVO -> {
                        if (info.tipoDe(u) == Tipo.DECIMAL) {
                            instruccion("btc rax, 63", "cambia el bit de signo del decimal");
                        } else {
                            instruccion("neg rax");
                        }
                    }
                    case NO -> instruccion("xor rax, 1", "no: 1 → 0, 0 → 1");
                }
            }
            case Binaria b -> generarBinaria(b);
            case Conversion c -> generarConversion(c);
            case Llamada l -> generarLlamada(l);
        }
    }

    /**
     * Genera la expresión y, si hace falta, convierte el resultado al tipo que se espera.
     * El único caso es entero → decimal (por ejemplo, "decimal d = 5;").
     */
    private void generarExpresionComo(Expresion expresion, Tipo destino) {
        generarExpresion(expresion);
        if (destino == Tipo.DECIMAL && info.tipoDe(expresion) == Tipo.ENTERO) {
            enteroADecimal();
        }
    }

    private void generarBinaria(Binaria binaria) {
        OperadorBinario operador = binaria.operador();
        Tipo izquierda = info.tipoDe(binaria.izquierda());
        Tipo derecha = info.tipoDe(binaria.derecha());

        if (operador.categoria() == OperadorBinario.Categoria.LOGICO) {
            generarLogica(binaria);
        } else if (info.tipoDe(binaria) == Tipo.TEXTO) {
            generarConcatenacion(binaria);
        } else if (operador.categoria() == OperadorBinario.Categoria.IGUALDAD && izquierda == Tipo.TEXTO) {
            generarComparacionDeTextos(binaria);
        } else if (izquierda == Tipo.DECIMAL || derecha == Tipo.DECIMAL) {
            generarOperacionDecimal(binaria);
        } else {
            generarOperacionEntera(binaria);
        }
    }

    /** Aritmética y comparaciones de enteros (y == / != de booleanos): los dos operandos se evalúan siempre. */
    private void generarOperacionEntera(Binaria binaria) {
        generarExpresion(binaria.izquierda());
        instruccion("push rax", "guarda el operando izquierdo");
        generarExpresion(binaria.derecha());
        instruccion("mov rcx, rax", "rcx = operando derecho");
        instruccion("pop rax", "rax = operando izquierdo");

        switch (binaria.operador()) {
            case SUMA -> instruccion("add rax, rcx");
            case RESTA -> instruccion("sub rax, rcx");
            case MULTIPLICACION -> instruccion("imul rax, rcx");
            case DIVISION, MODULO -> generarDivision(binaria.operador(), binaria.pos());
            case MENOR -> comparar("setl");
            case MENOR_IGUAL -> comparar("setle");
            case MAYOR -> comparar("setg");
            case MAYOR_IGUAL -> comparar("setge");
            case IGUAL -> comparar("sete");
            case DIFERENTE -> comparar("setne");
            case Y, O -> throw new IllegalStateException("los operadores lógicos se generan en generarLogica");
        }
    }

    /** rax = (rax OP rcx) ? 1 : 0 */
    private void comparar(String instruccionSet) {
        instruccion("cmp rax, rcx");
        instruccion(instruccionSet + " al");
        instruccion("movzx eax, al", "rax = 1 si se cumple, 0 si no");
    }

    /**
     * Divide rax entre rcx. Se revisan dos casos que en x86 detienen el programa con una excepción:
     * dividir entre cero (se reporta un error claro con la línea) y el mínimo entero entre -1
     * (se calcula directamente: x / -1 = -x, x % -1 = 0).
     */
    private void generarDivision(OperadorBinario operador, Posicion pos) {
        String divisorValido = nuevaEtiqueta("divisor_valido");
        String divisionNormal = nuevaEtiqueta("division_normal");
        String fin = nuevaEtiqueta("fin_division");

        instruccion("test rcx, rcx");
        instruccion("jne " + divisorValido);
        instruccion("mov rdi, " + pos.linea(), "línea del error");
        instruccion("call " + RT_ERROR_DIVISION, "división entre cero: termina el programa");
        etiqueta(divisorValido);

        instruccion("cmp rcx, -1");
        instruccion("jne " + divisionNormal);
        if (operador == OperadorBinario.DIVISION) {
            instruccion("neg rax", "x / -1 = -x");
        } else {
            instruccion("xor eax, eax", "x % -1 = 0");
        }
        instruccion("jmp " + fin);

        etiqueta(divisionNormal);
        instruccion("cqo", "extiende el signo de rax a rdx:rax");
        instruccion("idiv rcx", "rax = cociente, rdx = residuo");
        if (operador == OperadorBinario.MODULO) {
            instruccion("mov rax, rdx");
        }
        etiqueta(fin);
    }

    /**
     * Aritmética y comparaciones con decimales, en los registros xmm del procesador.
     * Si uno de los operandos es entero, primero se convierte a decimal.
     * Dividir un decimal entre cero no es error: da infinito o NaN, como en Java.
     */
    private void generarOperacionDecimal(Binaria binaria) {
        generarExpresionComo(binaria.izquierda(), Tipo.DECIMAL);
        instruccion("push rax", "guarda el operando izquierdo");
        generarExpresionComo(binaria.derecha(), Tipo.DECIMAL);
        instruccion("movq xmm1, rax", "xmm1 = operando derecho");
        instruccion("pop rax");
        instruccion("movq xmm0, rax", "xmm0 = operando izquierdo");

        switch (binaria.operador()) {
            case SUMA -> operarDecimales("addsd");
            case RESTA -> operarDecimales("subsd");
            case MULTIPLICACION -> operarDecimales("mulsd");
            case DIVISION -> operarDecimales("divsd");
            // ucomisd deja las banderas como una comparación sin signo; "seta"/"setae" dan 0 si hay un NaN.
            // a < b se calcula como b > a, y a <= b como b >= a.
            case MENOR -> compararDecimales("xmm1, xmm0", "seta");
            case MENOR_IGUAL -> compararDecimales("xmm1, xmm0", "setae");
            case MAYOR -> compararDecimales("xmm0, xmm1", "seta");
            case MAYOR_IGUAL -> compararDecimales("xmm0, xmm1", "setae");
            case IGUAL -> {
                instruccion("ucomisd xmm0, xmm1");
                instruccion("sete al", "iguales...");
                instruccion("setnp cl", "...y ninguno es NaN");
                instruccion("and al, cl");
                instruccion("movzx eax, al");
            }
            case DIFERENTE -> {
                instruccion("ucomisd xmm0, xmm1");
                instruccion("setne al", "distintos...");
                instruccion("setp cl", "...o alguno es NaN");
                instruccion("or al, cl");
                instruccion("movzx eax, al");
            }
            case MODULO, Y, O -> throw new IllegalStateException(
                    "operador no válido para decimales: " + binaria.operador());
        }
    }

    /** xmm0 = xmm0 OP xmm1, y el resultado se regresa a rax. */
    private void operarDecimales(String instruccionSse) {
        instruccion(instruccionSse + " xmm0, xmm1");
        instruccion("movq rax, xmm0");
    }

    private void compararDecimales(String operandos, String instruccionSet) {
        instruccion("ucomisd " + operandos);
        instruccion(instruccionSet + " al");
        instruccion("movzx eax, al", "rax = 1 si se cumple, 0 si no");
    }

    /** rax (entero) → rax (bits del decimal con el mismo valor). */
    private void enteroADecimal() {
        instruccion("cvtsi2sd xmm0, rax", "entero → decimal");
        instruccion("movq rax, xmm0");
    }

    /**
     * 'y' / 'o' con corto circuito: si el lado izquierdo ya decide el resultado,
     * el derecho no se evalúa. Los booleanos siempre valen 0 o 1.
     */
    private void generarLogica(Binaria binaria) {
        boolean esY = binaria.operador() == OperadorBinario.Y;
        String fin = nuevaEtiqueta(esY ? "fin_y" : "fin_o");
        generarExpresion(binaria.izquierda());
        instruccion("test rax, rax");
        if (esY) {
            instruccion("je " + fin, "falso y ... = falso");
        } else {
            instruccion("jne " + fin, "verdadero o ... = verdadero");
        }
        generarExpresion(binaria.derecha());
        etiqueta(fin);
    }

    /** texto + valor: convierte cada lado a texto y los une en un texto nuevo. */
    private void generarConcatenacion(Binaria binaria) {
        generarExpresion(binaria.izquierda());
        convertirATexto(info.tipoDe(binaria.izquierda()));
        instruccion("push rax", "guarda el texto izquierdo");
        generarExpresion(binaria.derecha());
        convertirATexto(info.tipoDe(binaria.derecha()));
        instruccion("mov rsi, rax", "rsi = texto derecho");
        instruccion("pop rdi", "rdi = texto izquierdo");
        instruccion("call " + RT_CONCATENAR);
    }

    /** texto == texto compara el contenido, no las direcciones. */
    private void generarComparacionDeTextos(Binaria binaria) {
        generarExpresion(binaria.izquierda());
        instruccion("push rax");
        generarExpresion(binaria.derecha());
        instruccion("mov rsi, rax");
        instruccion("pop rdi");
        instruccion("call " + RT_TEXTOS_IGUALES, "rax = 1 si tienen el mismo contenido");
        if (binaria.operador() == OperadorBinario.DIFERENTE) {
            instruccion("xor rax, 1");
        }
    }

    /** entero(x), decimal(x), texto(x). Las conversiones desde texto revisan el formato al ejecutar. */
    private void generarConversion(Conversion conversion) {
        generarExpresion(conversion.valor());
        Tipo origen = info.tipoDe(conversion.valor());
        Tipo destino = conversion.destino();
        if (origen == destino) {
            return;
        }
        switch (destino) {
            case TEXTO -> convertirATexto(origen);
            case ENTERO -> {
                if (origen == Tipo.DECIMAL) {
                    instruccion("movq xmm0, rax");
                    instruccion("cvttsd2si rax, xmm0", "decimal → entero, sin la parte decimal");
                } else {
                    convertirTextoANumero(RT_TEXTO_A_ENTERO, conversion.pos());
                }
            }
            case DECIMAL -> {
                if (origen == Tipo.ENTERO) {
                    enteroADecimal();
                } else {
                    convertirTextoANumero(RT_TEXTO_A_DECIMAL, conversion.pos());
                }
            }
            case BOOLEANO, VACIO -> throw new IllegalStateException("conversión no válida a " + destino);
        }
    }

    private void convertirTextoANumero(String rutina, Posicion pos) {
        instruccion("mov rdi, rax", "texto a convertir");
        instruccion("mov rsi, " + pos.linea(), "línea, por si el texto no es un número");
        instruccion("call " + rutina);
    }

    /** Convierte el valor de rax (del tipo indicado) a texto, dejando la dirección del texto en rax. */
    private void convertirATexto(Tipo tipo) {
        switch (tipo) {
            case ENTERO -> {
                instruccion("mov rdi, rax");
                instruccion("call " + RT_ENTERO_A_TEXTO);
            }
            case DECIMAL -> {
                instruccion("mov rdi, rax");
                instruccion("call " + RT_DECIMAL_A_TEXTO);
            }
            case BOOLEANO -> {
                instruccion("mov rdi, rax");
                instruccion("call " + RT_BOOLEANO_A_TEXTO);
            }
            case TEXTO -> { }
            case VACIO -> throw new IllegalStateException("una función sin valor no se puede convertir a texto");
        }
    }

    private void generarLlamada(Llamada llamada) {
        Optional<Predefinida> predefinida = Predefinida.buscar(llamada.nombre());
        if (predefinida.isPresent()) {
            instruccion("mov rdi, " + llamada.pos().linea(), "línea, por si hay un error al leer");
            instruccion("call " + RT_PREDEFINIDAS.get(predefinida.get()));
            return;
        }

        List<Parametro> parametros = funciones.get(llamada.nombre()).parametros();
        List<Expresion> argumentos = llamada.argumentos();
        for (int i = 0; i < argumentos.size(); i++) {
            generarExpresionComo(argumentos.get(i), parametros.get(i).tipo());
            instruccion("push rax", "argumento " + (i + 1));
        }
        instruccion("call " + etiquetaFuncion(llamada.nombre()));
        if (!argumentos.isEmpty()) {
            instruccion("add rsp, " + argumentos.size() * BYTES_POR_RANURA, "retira los argumentos de la pila");
        }
    }

    // ---------------------------------------------------------------
    // Rutinas de soporte y datos
    // ---------------------------------------------------------------

    private void generarRutinasDeSoporte() {
        asm.append("""

                # ---------------------------------------------------------------
                # Rutinas de soporte. Llaman a la biblioteca de C, que exige que
                # la pila esté alineada a 16 bytes: por eso cada una la alinea con
                # "and rsp, -16" y la restaura al terminar.
                # ---------------------------------------------------------------

                # imprimir(entero): el valor llega en rdi
                _rt_imprimir_entero:
                    push rbp
                    mov rbp, rsp
                    and rsp, -16
                    mov rsi, rdi
                    lea rdi, [rip + .Lformato_entero]
                    xor eax, eax
                    call printf@PLT
                    mov rsp, rbp
                    pop rbp
                    ret

                # escribir(entero): igual, pero sin salto de línea
                _rt_escribir_entero:
                    push rbp
                    mov rbp, rsp
                    and rsp, -16
                    mov rsi, rdi
                    lea rdi, [rip + .Lformato_numero]
                    xor eax, eax
                    call printf@PLT
                    mov rsp, rbp
                    pop rbp
                    ret

                # imprimir(texto): la dirección del texto llega en rdi
                _rt_imprimir_texto:
                    push rbp
                    mov rbp, rsp
                    and rsp, -16
                    call puts@PLT
                    mov rsp, rbp
                    pop rbp
                    ret

                # escribir(texto): sin salto de línea
                _rt_escribir_texto:
                    push rbp
                    mov rbp, rsp
                    and rsp, -16
                    mov rsi, rdi
                    lea rdi, [rip + .Lformato_texto]
                    xor eax, eax
                    call printf@PLT
                    mov rsp, rbp
                    pop rbp
                    ret

                # entero → texto nuevo con sus dígitos. rdi = número, rax = texto
                _rt_entero_a_texto:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 16
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi
                    mov edi, 21                         # "-9223372036854775808" + el 0 final
                    call malloc@PLT
                    test rax, rax
                    je _rt_error_memoria
                    mov QWORD PTR [rbp - 16], rax
                    mov rdi, rax
                    mov esi, 21
                    lea rdx, [rip + .Lformato_numero]
                    mov rcx, QWORD PTR [rbp - 8]
                    xor eax, eax
                    call snprintf@PLT
                    mov rax, QWORD PTR [rbp - 16]
                    mov rsp, rbp
                    pop rbp
                    ret

                # decimal → texto con la menor cantidad de dígitos (15, 16 o 17) que lo
                # representa exactamente, como Java: 0.1 + 0.2 = 0.30000000000000004, 2 = 2.0.
                # rdi = bits del decimal, rax = texto
                _rt_decimal_a_texto:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 32
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi        # el decimal
                    lea rax, [rip + .Ltexto_nan]
                    movq xmm0, rdi
                    ucomisd xmm0, xmm0
                    jp .Lrt_decimal_listo               # NaN es el único valor distinto de sí mismo
                    mov rax, rdi
                    btr rax, 63                         # valor absoluto
                    movabs rcx, 0x7ff0000000000000      # infinito
                    cmp rax, rcx
                    jne .Lrt_decimal_finito
                    lea rax, [rip + .Ltexto_infinito]
                    lea rcx, [rip + .Ltexto_menos_infinito]
                    test rdi, rdi
                    cmovs rax, rcx
                    jmp .Lrt_decimal_listo
                .Lrt_decimal_finito:
                    mov edi, 32
                    call malloc@PLT
                    test rax, rax
                    je _rt_error_memoria
                    mov QWORD PTR [rbp - 16], rax       # texto nuevo
                    mov DWORD PTR [rbp - 24], 15        # dígitos de precisión
                .Lrt_decimal_formatear:
                    mov rdi, QWORD PTR [rbp - 16]
                    mov esi, 32
                    lea rdx, [rip + .Lformato_decimal]
                    mov ecx, DWORD PTR [rbp - 24]
                    movq xmm0, QWORD PTR [rbp - 8]
                    mov eax, 1                          # printf recibe 1 valor en registros xmm
                    call snprintf@PLT
                    cmp DWORD PTR [rbp - 24], 17
                    je .Lrt_decimal_punto               # 17 dígitos siempre alcanzan
                    mov rdi, QWORD PTR [rbp - 16]
                    xor esi, esi
                    call strtod@PLT                     # ¿el texto regresa al mismo número?
                    movq rax, xmm0
                    cmp rax, QWORD PTR [rbp - 8]
                    je .Lrt_decimal_punto
                    inc DWORD PTR [rbp - 24]
                    jmp .Lrt_decimal_formatear
                .Lrt_decimal_punto:
                    mov rdi, QWORD PTR [rbp - 16]       # si quedó "2", se agrega ".0"
                    lea rsi, [rip + .Lcaracteres_decimales]
                    call strpbrk@PLT
                    test rax, rax
                    jne .Lrt_decimal_texto
                    mov rdi, QWORD PTR [rbp - 16]
                    call strlen@PLT
                    add rax, QWORD PTR [rbp - 16]
                    mov DWORD PTR [rax], 0x302e         # '.', '0' y el 0 final
                .Lrt_decimal_texto:
                    mov rax, QWORD PTR [rbp - 16]
                .Lrt_decimal_listo:
                    mov rsp, rbp
                    pop rbp
                    ret

                # booleano → "verdadero" o "falso". rdi = 0 o 1, rax = texto
                _rt_booleano_a_texto:
                    lea rax, [rip + .Ltexto_falso]
                    lea rcx, [rip + .Ltexto_verdadero]
                    test rdi, rdi
                    cmovne rax, rcx
                    ret

                # ¿El texto solo tiene espacios o tabuladores? rdi = texto, rax = 1 o 0
                _rt_solo_espacios:
                    movzx eax, BYTE PTR [rdi]
                    test al, al
                    je .Lrt_espacios_si
                    cmp al, 32
                    je .Lrt_espacios_siguiente
                    cmp al, 9
                    je .Lrt_espacios_siguiente
                    xor eax, eax
                    ret
                .Lrt_espacios_siguiente:
                    inc rdi
                    jmp _rt_solo_espacios
                .Lrt_espacios_si:
                    mov eax, 1
                    ret

                # texto → entero. rdi = texto, rsi = línea del programa, rax = número.
                # Si el texto no es un entero válido, termina el programa con un error.
                _rt_texto_a_entero:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 48
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi        # texto
                    mov QWORD PTR [rbp - 16], rsi       # línea
                    call __errno_location@PLT
                    mov QWORD PTR [rbp - 24], rax       # dirección de errno
                    mov DWORD PTR [rax], 0
                    mov rdi, QWORD PTR [rbp - 8]
                    lea rsi, [rbp - 32]                 # strtol dice aquí dónde dejó de leer
                    mov edx, 10
                    call strtol@PLT
                    mov QWORD PTR [rbp - 40], rax       # número
                    mov rax, QWORD PTR [rbp - 24]
                    cmp DWORD PTR [rax], 0
                    jne .Lrt_entero_invalido            # no cabe en 64 bits
                    mov rdi, QWORD PTR [rbp - 32]
                    cmp rdi, QWORD PTR [rbp - 8]
                    je .Lrt_entero_invalido             # no había ningún dígito
                    call _rt_solo_espacios
                    test eax, eax
                    je .Lrt_entero_invalido             # sobra algo después del número
                    mov rax, QWORD PTR [rbp - 40]
                    mov rsp, rbp
                    pop rbp
                    ret
                .Lrt_entero_invalido:
                    mov rdi, QWORD PTR [rbp - 16]
                    mov rsi, QWORD PTR [rbp - 8]
                    lea rdx, [rip + .Lnombre_entero]
                    call _rt_error_conversion

                # texto → decimal. rdi = texto, rsi = línea del programa, rax = bits del decimal.
                _rt_texto_a_decimal:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 32
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi        # texto
                    mov QWORD PTR [rbp - 16], rsi       # línea
                    lea rsi, [rbp - 24]                 # strtod dice aquí dónde dejó de leer
                    call strtod@PLT
                    movq QWORD PTR [rbp - 32], xmm0     # número
                    mov rdi, QWORD PTR [rbp - 24]
                    cmp rdi, QWORD PTR [rbp - 8]
                    je .Lrt_decimal_invalido            # no había ningún número
                    call _rt_solo_espacios
                    test eax, eax
                    je .Lrt_decimal_invalido            # sobra algo después del número
                    mov rax, QWORD PTR [rbp - 32]
                    btr rax, 63
                    movabs rcx, 0x7ff0000000000000
                    cmp rax, rcx
                    jae .Lrt_decimal_invalido           # infinito o NaN: no se aceptan como dato
                    mov rax, QWORD PTR [rbp - 32]
                    mov rsp, rbp
                    pop rbp
                    ret
                .Lrt_decimal_invalido:
                    mov rdi, QWORD PTR [rbp - 16]
                    mov rsi, QWORD PTR [rbp - 8]
                    lea rdx, [rip + .Lnombre_decimal]
                    call _rt_error_conversion

                # leerTexto(): lee una línea del teclado, sin el salto de línea.
                # rdi = línea del programa (para el error si ya no hay datos), rax = texto
                _rt_leer_texto:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 32
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi        # línea
                    xor edi, edi
                    call fflush@PLT                     # muestra la pregunta antes de esperar la respuesta
                    mov QWORD PTR [rbp - 16], 0         # texto (getline lo crea)
                    mov QWORD PTR [rbp - 24], 0         # tamaño reservado
                    lea rdi, [rbp - 16]
                    lea rsi, [rbp - 24]
                    mov rdx, QWORD PTR [rip + stdin@GOTPCREL]
                    mov rdx, QWORD PTR [rdx]            # stdin
                    call getline@PLT
                    cmp rax, -1
                    je .Lrt_leer_sin_datos
                    mov rcx, QWORD PTR [rbp - 16]       # rcx = texto, rax = largo
                .Lrt_leer_quitar_salto:
                    test rax, rax
                    je .Lrt_leer_listo
                    movzx edx, BYTE PTR [rcx + rax - 1]
                    cmp dl, 10                          # '\\n'
                    je .Lrt_leer_quitar
                    cmp dl, 13                          # '\\r' (archivos de Windows)
                    jne .Lrt_leer_listo
                .Lrt_leer_quitar:
                    dec rax
                    mov BYTE PTR [rcx + rax], 0
                    jmp .Lrt_leer_quitar_salto
                .Lrt_leer_listo:
                    mov rax, rcx
                    mov rsp, rbp
                    pop rbp
                    ret
                .Lrt_leer_sin_datos:
                    mov rdi, QWORD PTR [rbp - 8]
                    call _rt_error_sin_datos

                # leerEntero(): rdi = línea del programa, rax = número
                _rt_leer_entero:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 16
                    mov QWORD PTR [rbp - 8], rdi
                    call _rt_leer_texto
                    mov rdi, rax
                    mov rsi, QWORD PTR [rbp - 8]
                    call _rt_texto_a_entero
                    mov rsp, rbp
                    pop rbp
                    ret

                # leerDecimal(): rdi = línea del programa, rax = bits del decimal
                _rt_leer_decimal:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 16
                    mov QWORD PTR [rbp - 8], rdi
                    call _rt_leer_texto
                    mov rdi, rax
                    mov rsi, QWORD PTR [rbp - 8]
                    call _rt_texto_a_decimal
                    mov rsp, rbp
                    pop rbp
                    ret

                # Concatena dos textos en uno nuevo. rdi = a, rsi = b, rax = a + b
                _rt_concatenar:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 48
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi        # a
                    mov QWORD PTR [rbp - 16], rsi       # b
                    call strlen@PLT
                    mov QWORD PTR [rbp - 24], rax       # largo de a
                    mov rdi, QWORD PTR [rbp - 16]
                    call strlen@PLT
                    mov QWORD PTR [rbp - 32], rax       # largo de b
                    mov rdi, QWORD PTR [rbp - 24]
                    lea rdi, [rdi + rax + 1]            # largo de a + largo de b + el 0 final
                    call malloc@PLT
                    test rax, rax
                    je _rt_error_memoria
                    mov QWORD PTR [rbp - 40], rax       # texto nuevo
                    mov rdi, rax
                    mov rsi, QWORD PTR [rbp - 8]
                    mov rdx, QWORD PTR [rbp - 24]
                    call memcpy@PLT                     # copia a
                    mov rdi, QWORD PTR [rbp - 40]
                    add rdi, QWORD PTR [rbp - 24]
                    mov rsi, QWORD PTR [rbp - 16]
                    mov rdx, QWORD PTR [rbp - 32]
                    add rdx, 1
                    call memcpy@PLT                     # copia b con su 0 final
                    mov rax, QWORD PTR [rbp - 40]
                    mov rsp, rbp
                    pop rbp
                    ret

                # ¿Dos textos tienen el mismo contenido? rdi = a, rsi = b, rax = 1 o 0
                _rt_textos_iguales:
                    push rbp
                    mov rbp, rsp
                    and rsp, -16
                    call strcmp@PLT
                    test eax, eax
                    sete al
                    movzx eax, al
                    mov rsp, rbp
                    pop rbp
                    ret

                # ---------------------------------------------------------------
                # Errores en tiempo de ejecución: muestran lo que ya se imprimió,
                # escriben el mensaje en stderr y terminan con el código 1.
                # No regresan, así que no necesitan restaurar registros.
                # ---------------------------------------------------------------

                # División entre cero. rdi = línea del programa
                _rt_error_division:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 16
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi
                    xor edi, edi
                    call fflush@PLT
                    mov edi, 2                          # stderr
                    lea rsi, [rip + .Lmensaje_division]
                    mov rdx, QWORD PTR [rbp - 8]
                    xor eax, eax
                    call dprintf@PLT
                    mov edi, 1
                    call exit@PLT

                # Un texto que no es número. rdi = línea, rsi = texto, rdx = "entero" o "decimal"
                _rt_error_conversion:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 32
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi
                    mov QWORD PTR [rbp - 16], rsi
                    mov QWORD PTR [rbp - 24], rdx
                    xor edi, edi
                    call fflush@PLT
                    mov edi, 2
                    lea rsi, [rip + .Lmensaje_conversion]
                    mov rdx, QWORD PTR [rbp - 8]
                    mov rcx, QWORD PTR [rbp - 16]
                    mov r8, QWORD PTR [rbp - 24]
                    xor eax, eax
                    call dprintf@PLT
                    mov edi, 1
                    call exit@PLT

                # Se intentó leer pero ya no hay datos. rdi = línea del programa
                _rt_error_sin_datos:
                    push rbp
                    mov rbp, rsp
                    sub rsp, 16
                    and rsp, -16
                    mov QWORD PTR [rbp - 8], rdi
                    xor edi, edi
                    call fflush@PLT
                    mov edi, 2
                    lea rsi, [rip + .Lmensaje_sin_datos]
                    mov rdx, QWORD PTR [rbp - 8]
                    xor eax, eax
                    call dprintf@PLT
                    mov edi, 1
                    call exit@PLT

                # Sin memoria para un texto nuevo
                _rt_error_memoria:
                    push rbp
                    mov rbp, rsp
                    and rsp, -16
                    xor edi, edi
                    call fflush@PLT
                    mov edi, 2
                    lea rsi, [rip + .Lmensaje_memoria]
                    xor eax, eax
                    call dprintf@PLT
                    mov edi, 1
                    call exit@PLT
                """);
    }

    /** Sección de datos de solo lectura: los textos fijos de las rutinas y los textos del programa. */
    private void generarDatos() {
        asm.append("""

                # ---------------------------------------------------------------
                # Datos de solo lectura
                # ---------------------------------------------------------------
                    .section .rodata
                .Lformato_entero:
                    .string "%ld\\n"
                .Lformato_numero:
                    .string "%ld"
                .Lformato_texto:
                    .string "%s"
                .Lformato_decimal:
                    .string "%.*g"
                .Lcaracteres_decimales:
                    .string ".e"
                .Ltexto_verdadero:
                    .string "verdadero"
                .Ltexto_falso:
                    .string "falso"
                .Ltexto_vacio:
                    .string ""
                .Ltexto_nan:
                    .string "NaN"
                .Ltexto_infinito:
                    .string "infinito"
                .Ltexto_menos_infinito:
                    .string "-infinito"
                .Lnombre_entero:
                    .string "entero"
                .Lnombre_decimal:
                    .string "decimal"
                .Lmensaje_division:
                    .string "Error en tiempo de ejecución (línea %ld): división entre cero\\n"
                .Lmensaje_conversion:
                    .string "Error en tiempo de ejecución (línea %ld): \\"%s\\" no es un número %s válido\\n"
                .Lmensaje_sin_datos:
                    .string "Error en tiempo de ejecución (línea %ld): no hay más datos para leer\\n"
                .Lmensaje_memoria:
                    .string "Error en tiempo de ejecución: no hay memoria suficiente\\n"
                """);

        if (!etiquetasDeTextos.isEmpty()) {
            asm.append("\n# Textos del programa\n");
            etiquetasDeTextos.forEach((texto, etiqueta) -> {
                etiqueta(etiqueta);
                instruccion(".string " + literalEnsamblador(texto));
            });
        }

        asm.append("\n# La pila no necesita ser ejecutable\n");
        instruccion(".section .note.GNU-stack,\"\",@progbits");
    }

    // ---------------------------------------------------------------
    // Auxiliares para escribir el ensamblador
    // ---------------------------------------------------------------

    /** Dirección en memoria de la variable: la ranura k está en [rbp - 8*(k+1)]. */
    private String direccion(ReferenciaVariable referencia) {
        int desplazamiento = (info.ranuraDe(referencia) + 1) * BYTES_POR_RANURA;
        return "QWORD PTR [rbp - " + desplazamiento + "]";
    }

    /** Etiqueta del texto literal en la sección de datos; se crea la primera vez que aparece. */
    private String etiquetaDeTexto(String texto) {
        return etiquetasDeTextos.computeIfAbsent(texto, t -> ".Lcadena_" + etiquetasDeTextos.size());
    }

    private String nuevaEtiqueta(String descripcion) {
        return ".L" + descripcion + "_" + contadorEtiquetas++;
    }

    private void etiqueta(String nombre) {
        asm.append(nombre).append(":\n");
    }

    private void instruccion(String texto) {
        asm.append("    ").append(texto).append('\n');
    }

    private void instruccion(String texto, String comentario) {
        StringBuilder linea = new StringBuilder("    ").append(texto);
        linea.append(" ".repeat(Math.max(1, COLUMNA_COMENTARIO - linea.length())));
        asm.append(linea).append("# ").append(comentario).append('\n');
    }

    /** Pone la línea original del programa como comentario, para poder leer el ensamblador. */
    private void comentarLineaFuente(Posicion pos, String sangria) {
        int numero = pos.linea();
        if (numero == ultimaLineaComentada || numero < 1 || numero > lineasFuente.size()) {
            return;
        }
        ultimaLineaComentada = numero;
        asm.append(sangria).append("# [línea ").append(numero).append("] ")
           .append(lineasFuente.get(numero - 1).strip()).append('\n');
    }
}
