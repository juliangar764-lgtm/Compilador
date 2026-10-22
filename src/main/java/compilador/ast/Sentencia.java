package compilador.ast;

import java.util.List;
import java.util.Optional;

/**
 * Sentencias: instrucciones que se ejecutan y no producen un valor.
 *
 * Es una interfaz sellada: solo los records de este archivo pueden implementarla.
 */
public sealed interface Sentencia {

    Posicion pos();

    /** { sentencias } — crea un ámbito nuevo para las variables. */
    record Bloque(List<Sentencia> sentencias, Posicion pos) implements Sentencia {
        public Bloque {
            sentencias = List.copyOf(sentencias);
        }
    }

    /** entero x = 5;   o   booleano listo;   (sin valor inicial empieza en 0 / 0.0 / falso / "") */
    record Declaracion(Tipo tipo, String nombre, Optional<Expresion> valorInicial, Posicion pos)
            implements Sentencia, ReferenciaVariable { }

    /** x = expresión; */
    record Asignacion(String nombre, Expresion valor, Posicion pos)
            implements Sentencia, ReferenciaVariable { }

    /** si (condición) { ... } sino { ... }. El sino puede ser otro Si: "sino si". */
    record Si(Expresion condicion, Bloque entonces, Optional<Sentencia> sino, Posicion pos)
            implements Sentencia { }

    /** mientras (condición) { ... } */
    record Mientras(Expresion condicion, Bloque cuerpo, Posicion pos) implements Sentencia { }

    /**
     * para (inicio; condición; paso) { ... }. Las tres partes son opcionales.
     * El inicio es una Declaracion o una Asignacion. Sin condición, el ciclo no termina solo.
     */
    record Para(Optional<Sentencia> inicio, Optional<Expresion> condicion, Optional<Asignacion> paso,
                Bloque cuerpo, Posicion pos) implements Sentencia { }

    /** regresa;   o   regresa expresión; */
    record Regresa(Optional<Expresion> valor, Posicion pos) implements Sentencia { }

    /**
     * imprimir(expresión);  escribe el valor y un salto de línea.
     * escribir(expresión);  escribe el valor sin salto de línea (saltoDeLinea = false).
     */
    record Imprimir(Expresion valor, boolean saltoDeLinea, Posicion pos) implements Sentencia { }

    /** Llamada a función usada como sentencia: saludar(); */
    record SentenciaLlamada(Expresion.Llamada llamada) implements Sentencia {
        @Override
        public Posicion pos() {
            return llamada.pos();
        }
    }
}
