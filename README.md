# Compilador `.mio`

Compilador de un lenguaje de programación con palabras clave en español. Convierte los programas
en ejecutables nativos de Linux x86-64.

```
programa.mio ──► Lexer ──► Parser ──► Análisis semántico ──► Generador ──► programa.s ──► gcc ──► ejecutable
                tokens      árbol      tipos y variables       x86-64
```

Proyecto de la materia **Traductores de bajo nivel**.

## Ejemplo

```
// ejemplos/hola.mio
funcion principal() {
    imprimir("¡Hola, mundo!");
}
```

```
$ java -jar target/compilador.jar ejemplos/hola.mio -o salida/hola
$ ./salida/hola
¡Hola, mundo!
```

Un programa interactivo, que lee del teclado ([ejemplos/calculadora.mio](ejemplos/calculadora.mio)):

```
$ ./salida/calculadora
=== Calculadora ===
Primer número: 7.5
Operación (+, -, *, /): *
Segundo número: 4
7.5 * 4.0 = 30.0
```

Para probarlo sin escribir los datos, se le puede mandar un archivo como entrada:
`./salida/calculadora < ejemplos/calculadora.entrada`.

Un programa con funciones y recursión:

```
// ejemplos/factorial.mio
funcion factorial(entero n) : entero {
    si (n <= 1) {
        regresa 1;
    }
    regresa n * factorial(n - 1);
}

funcion principal() {
    entero i = 1;
    mientras (i <= 5) {
        imprimir(factorial(i));
        i = i + 1;
    }
}
```

```
$ java -jar target/compilador.jar ejemplos/factorial.mio -o salida/factorial
$ ./salida/factorial
1
2
6
24
120
```

## Requisitos

- Java 21 o más reciente
- Maven 3.9 o más reciente
- gcc, Linux x86-64 (para crear los ejecutables)

## Compilar el compilador

```
mvn package
```

Esto compila el proyecto, corre todas las pruebas y genera `target/compilador.jar`.

## Uso

```
java -jar target/compilador.jar [opciones] <archivo.mio>
```

| Opción | Descripción |
|---|---|
| `-o <archivo>` | Nombre del archivo de salida. Por defecto, el nombre del programa sin `.mio`. |
| `-S` | Solo genera el ensamblador (`.s`), sin crear el ejecutable. |
| `--tokens` | Muestra los tokens del análisis léxico. |
| `--ast` | Muestra el árbol sintáctico. |
| `-h`, `--ayuda` | Muestra la ayuda. |

Códigos de salida: `0` si todo salió bien, `1` si hay un error de compilación y `2` si los argumentos son incorrectos.

### Ver cada fase

```
java -jar target/compilador.jar --tokens ejemplos/factorial.mio -S -o salida/factorial.s   # tokens
java -jar target/compilador.jar --ast    ejemplos/factorial.mio -S -o salida/factorial.s   # árbol
cat salida/factorial.s                                                                     # ensamblador
```

El ensamblador generado incluye como comentario cada línea del programa original, para poder seguir
la traducción.

### Errores

Los errores indican la fase, el archivo, la línea y la columna, y señalan el lugar con una flecha:

```
programa.mio:6:22: error semántico: la variable 'listo' es de tipo booleano, pero se le asigna un valor entero
        booleano listo = doble(3);
                         ^
```

## El lenguaje

| Elemento | Ejemplo |
|---|---|
| Tipos | `entero` (64 bits con signo), `decimal` (como `double`), `booleano`, `texto` |
| Variables | `entero x = 10;` &nbsp; `decimal precio = 19.99;` &nbsp; `booleano listo;` (empieza en `falso`) &nbsp; `texto nombre = "Ana";` |
| Decimales | `7 / 2.0` da `3.5`; entero y decimal se mezclan solos (`7 / 2` sigue siendo división entera: `3`) |
| Conversiones | `entero(3.99)` → `3` &nbsp; `decimal(5)` → `5.0` &nbsp; `entero("42")` → `42` &nbsp; `texto(1.5)` → `"1.5"` |
| Textos | `"Hola"`, con escapes `\n` `\t` `\"` `\\` |
| Concatenación | `"Edad: " + edad` (los números y booleanos se convierten solos) |
| Aritmética | `+ - * / %` |
| Comparación | `== != < <= > >=` |
| Lógica | `y`, `o`, `no` (con corto circuito) |
| Condicional | `si (x > 0) { ... } sino si (x < 0) { ... } sino { ... }` |
| Ciclos | `mientras (i < 10) { ... }` &nbsp; `para (entero i = 0; i < 10; i = i + 1) { ... }` |
| Funciones | `funcion suma(entero a, entero b) : entero { regresa a + b; }` |
| Salida | `imprimir("x vale " + x);` (escribe una línea) &nbsp; `escribir("Edad: ");` (sin salto de línea) |
| Entrada (teclado) | `texto t = leerTexto();` &nbsp; `entero n = leerEntero();` &nbsp; `decimal d = leerDecimal();` |
| Comentarios | `// una línea` &nbsp; `/* varias líneas */` |

El programa empieza en `funcion principal()`. La especificación completa (gramática, reglas de tipos,
ámbitos y modelo de ejecución) está en [docs/gramatica.md](docs/gramatica.md).

## Estructura del proyecto

```
src/main/java/compilador/
├── Main.java              Línea de comandos
├── Opciones.java          Lectura de opciones (-o, -S, --tokens, --ast)
├── Compilador.java        Une las fases y llama a gcc
├── lexer/                 Fase 1: texto → tokens
├── parser/                Fase 2: tokens → árbol sintáctico
├── ast/                   Nodos del árbol sintáctico e impresor (--ast)
├── semantico/             Fase 3: tipos, variables, ámbitos y funciones
├── generador/             Fase 4: árbol → ensamblador x86-64
└── errores/               Errores de compilación y de ensamblado

src/test/java/compilador/          Pruebas unitarias de cada fase y pruebas de punta a punta
src/test/resources/programas/      Programas de prueba (.mio) con su salida esperada (.salida)
ejemplos/                          Programas de ejemplo
docs/gramatica.md                  Especificación del lenguaje
```

## Pruebas

```
mvn test
```

- **Unitarias**: cada fase tiene pruebas de sus casos válidos y de cada mensaje de error.
- **De punta a punta**: cada programa de `src/test/resources/programas/` se compila con gcc, se ejecuta,
  y su salida se compara con el archivo `.salida` correspondiente. Para agregar una prueba basta con
  crear `nombre.mio` y `nombre.salida`.

Las pruebas que necesitan gcc se omiten automáticamente si gcc no está instalado.
