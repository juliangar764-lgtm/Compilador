package compilador.ast;

import compilador.ast.Expresion.Binaria;
import compilador.ast.Expresion.Conversion;
import compilador.ast.Expresion.LiteralBooleano;
import compilador.ast.Expresion.LiteralDecimal;
import compilador.ast.Expresion.LiteralTexto;
import compilador.ast.Expresion.Llamada;
import compilador.ast.Expresion.Numero;
import compilador.ast.Expresion.Unaria;
import compilador.ast.Expresion.Variable;
import compilador.ast.Sentencia.Asignacion;
import compilador.ast.Sentencia.Bloque;
import compilador.ast.Sentencia.Declaracion;
import compilador.ast.Sentencia.Imprimir;
import compilador.ast.Sentencia.Mientras;
import compilador.ast.Sentencia.Para;
import compilador.ast.Sentencia.Regresa;
import compilador.ast.Sentencia.SentenciaLlamada;
import compilador.ast.Sentencia.Si;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dibuja el árbol sintáctico como texto (opción --ast):
 *
 * <pre>
 * Programa
 * └── Funcion principal()
 *     └── Imprimir
 *         └── Operador +
 *             ├── Número 1
 *             └── Número 2
 * </pre>
 */
public final class ImpresorAst {

    private ImpresorAst() {
    }

    /** Nodo genérico de texto: una etiqueta y sus hijos. */
    private record Nodo(String texto, List<Nodo> hijos) {
        Nodo(String texto, Nodo... hijos) {
            this(texto, List.of(hijos));
        }
    }

    public static String imprimir(Programa programa) {
        Nodo raiz = new Nodo("Programa", programa.funciones().stream().map(ImpresorAst::funcion).toList());
        StringBuilder sb = new StringBuilder();
        dibujar(raiz, "", "", sb);
        return sb.toString();
    }

    private static void dibujar(Nodo nodo, String prefijoPropio, String prefijoHijos, StringBuilder sb) {
        sb.append(prefijoPropio).append(nodo.texto()).append('\n');
        List<Nodo> hijos = nodo.hijos();
        for (int i = 0; i < hijos.size(); i++) {
            boolean ultimo = i == hijos.size() - 1;
            dibujar(hijos.get(i),
                    prefijoHijos + (ultimo ? "└── " : "├── "),
                    prefijoHijos + (ultimo ? "    " : "│   "),
                    sb);
        }
    }

    private static Nodo funcion(Funcion f) {
        String parametros = f.parametros().stream()
                .map(p -> p.tipo() + " " + p.nombre())
                .collect(Collectors.joining(", "));
        String retorno = f.tipoRetorno() == Tipo.VACIO ? "" : " : " + f.tipoRetorno();
        return new Nodo("Funcion " + f.nombre() + "(" + parametros + ")" + retorno, contenido(f.cuerpo()));
    }

    /** Las sentencias de un bloque, sin agregar un nodo "Bloque" intermedio. */
    private static List<Nodo> contenido(Bloque bloque) {
        return bloque.sentencias().stream().map(ImpresorAst::sentencia).toList();
    }

    private static Nodo sentencia(Sentencia sentencia) {
        return switch (sentencia) {
            case Bloque b -> new Nodo("Bloque", contenido(b));
            case Declaracion d -> {
                String texto = "Declaracion " + d.tipo() + " " + d.nombre();
                yield d.valorInicial()
                        .map(valor -> new Nodo(texto + " =", expresion(valor)))
                        .orElseGet(() -> new Nodo(texto));
            }
            case Asignacion a -> new Nodo("Asignacion " + a.nombre() + " =", expresion(a.valor()));
            case Si si -> {
                List<Nodo> hijos = new ArrayList<>();
                hijos.add(new Nodo("condición", expresion(si.condicion())));
                hijos.add(new Nodo("entonces", contenido(si.entonces())));
                si.sino().ifPresent(sino -> hijos.add(sino instanceof Bloque b
                        ? new Nodo("sino", contenido(b))
                        : new Nodo("sino", sentencia(sino))));
                yield new Nodo("Si", hijos);
            }
            case Mientras m -> new Nodo("Mientras",
                    new Nodo("condición", expresion(m.condicion())),
                    new Nodo("cuerpo", contenido(m.cuerpo())));
            case Para p -> {
                List<Nodo> hijos = new ArrayList<>();
                p.inicio().ifPresent(inicio -> hijos.add(new Nodo("inicio", sentencia(inicio))));
                p.condicion().ifPresent(c -> hijos.add(new Nodo("condición", expresion(c))));
                p.paso().ifPresent(paso -> hijos.add(new Nodo("paso", sentencia(paso))));
                hijos.add(new Nodo("cuerpo", contenido(p.cuerpo())));
                yield new Nodo("Para", hijos);
            }
            case Regresa r -> r.valor()
                    .map(valor -> new Nodo("Regresa", expresion(valor)))
                    .orElseGet(() -> new Nodo("Regresa"));
            case Imprimir i -> new Nodo(i.saltoDeLinea() ? "Imprimir" : "Escribir", expresion(i.valor()));
            case SentenciaLlamada s -> expresion(s.llamada());
        };
    }

    private static Nodo expresion(Expresion expresion) {
        return switch (expresion) {
            case Numero n -> new Nodo("Número " + n.valor());
            case LiteralDecimal d -> new Nodo("Decimal " + d.valor());
            case LiteralBooleano b -> new Nodo("Booleano " + (b.valor() ? "verdadero" : "falso"));
            case LiteralTexto t -> new Nodo("Texto \"" + escapar(t.valor()) + "\"");
            case Variable v -> new Nodo("Variable " + v.nombre());
            case Unaria u -> new Nodo("Operador " + u.operador().simbolo(), expresion(u.operando()));
            case Binaria b -> new Nodo("Operador " + b.operador().simbolo(),
                    expresion(b.izquierda()), expresion(b.derecha()));
            case Conversion c -> new Nodo("Conversión a " + c.destino(), expresion(c.valor()));
            case Llamada l -> new Nodo("Llamada " + l.nombre(),
                    l.argumentos().stream().map(ImpresorAst::expresion).toList());
        };
    }

    /** Vuelve a escribir los caracteres especiales como escapes, para que el árbol quede en una línea. */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
