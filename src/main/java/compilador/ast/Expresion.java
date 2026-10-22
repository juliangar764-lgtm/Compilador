package compilador.ast;

import java.util.List;

/**
 * Expresiones: partes del programa que producen un valor (5, x + 1, factorial(n)...).
 *
 * Es una interfaz sellada: solo los records de este archivo pueden implementarla.
 * Gracias a eso, un switch sobre una Expresion obliga a manejar todos los casos.
 */
public sealed interface Expresion {

    Posicion pos();

    /** Número entero literal: 42 */
    record Numero(long valor, Posicion pos) implements Expresion { }

    /** Número decimal literal: 3.14 */
    record LiteralDecimal(double valor, Posicion pos) implements Expresion { }

    /** verdadero o falso */
    record LiteralBooleano(boolean valor, Posicion pos) implements Expresion { }

    /** Texto literal: "Hola". El valor ya tiene los escapes convertidos (\n es un salto de línea real). */
    record LiteralTexto(String valor, Posicion pos) implements Expresion { }

    /** Uso del valor de una variable: x */
    record Variable(String nombre, Posicion pos) implements Expresion, ReferenciaVariable { }

    /** -x, no listo. La posición es la del operador. */
    record Unaria(OperadorUnario operador, Expresion operando, Posicion pos) implements Expresion { }

    /** a + b, a < b, a y b... La posición es la del operador. */
    record Binaria(OperadorBinario operador, Expresion izquierda, Expresion derecha, Posicion pos)
            implements Expresion { }

    /**
     * Conversión explícita de tipo: entero(3.7), decimal("2.5"), texto(42).
     * La posición es la del nombre del tipo.
     */
    record Conversion(Tipo destino, Expresion valor, Posicion pos) implements Expresion { }

    /** Llamada a función: factorial(n - 1) */
    record Llamada(String nombre, List<Expresion> argumentos, Posicion pos) implements Expresion {
        public Llamada {
            argumentos = List.copyOf(argumentos);
        }
    }
}
