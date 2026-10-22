# Especificación del lenguaje `.mio`

Este documento define el lenguaje que acepta el compilador: sus tokens, su gramática,
sus reglas semánticas y cómo se traduce a ensamblador x86-64.

## 1. Léxico

El análisis léxico (`compilador.lexer.Lexer`) convierte el texto en tokens.

| Categoría | Tokens |
|---|---|
| Palabras reservadas | `entero` `decimal` `booleano` `texto` `verdadero` `falso` `si` `sino` `mientras` `para` `funcion` `regresa` `imprimir` `escribir` `y` `o` `no` |
| Identificadores | Una letra o `_`, seguida de letras, dígitos o `_`. Se aceptan acentos y `ñ` (`año`, `número`). |
| Números enteros | Uno o más dígitos, de `0` a `9223372036854775807` (enteros con signo de 64 bits). |
| Números decimales | Dígitos, un punto y más dígitos: `3.14`, `0.5`, `2.0`. Escribir `5.` o `.5` es un error. |
| Textos | Caracteres entre comillas dobles, en una sola línea: `"¡Hola, mundo!"`. Escapes: `\n` (salto de línea), `\t` (tabulador), `\"` (comillas) y `\\` (diagonal). |
| Operadores | `+` `-` `*` `/` `%` `==` `!=` `<` `<=` `>` `>=` `=` |
| Puntuación | `(` `)` `{` `}` `,` `;` `:` |
| Comentarios | `// hasta el final de la línea` y `/* de bloque, pueden ocupar varias líneas */` |

- Los espacios, tabuladores y saltos de línea separan tokens y se ignoran.
- Las palabras reservadas no se pueden usar como nombres. Ojo: `y`, `o` y `no` son palabras reservadas.
- Un número no puede ir pegado a letras (`12abc` es un error).
- Para negar se usa `no`; el símbolo `!` solo existe como parte de `!=`.

## 2. Gramática

Notación EBNF: `*` = cero o más veces, `?` = opcional, `|` = alternativa.
Los símbolos terminales van entre comillas. Cada regla corresponde a un método
del analizador sintáctico descendente recursivo (`compilador.parser.Parser`).

```ebnf
programa     = funcion* FIN ;

funcion      = "funcion" IDENTIFICADOR "(" parametros? ")" ( ":" tipo )? bloque ;
parametros   = parametro ( "," parametro )* ;
parametro    = tipo IDENTIFICADOR ;
tipo         = "entero" | "decimal" | "booleano" | "texto" ;

bloque       = "{" sentencia* "}" ;

sentencia    = declaracion ";"
             | asignacion ";"
             | llamada ";"
             | si
             | mientras
             | para
             | regresa
             | imprimir
             | bloque ;

declaracion  = tipo IDENTIFICADOR ( "=" expresion )? ;
asignacion   = IDENTIFICADOR "=" expresion ;
si           = "si" "(" expresion ")" bloque ( "sino" ( si | bloque ) )? ;
mientras     = "mientras" "(" expresion ")" bloque ;
para         = "para" "(" ( declaracion | asignacion )? ";" expresion? ";" asignacion? ")" bloque ;
regresa      = "regresa" expresion? ";" ;
imprimir     = ( "imprimir" | "escribir" ) "(" expresion ")" ";" ;

expresion    = o ;
o            = y ( "o" y )* ;
y            = igualdad ( "y" igualdad )* ;
igualdad     = comparacion ( ( "==" | "!=" ) comparacion )* ;
comparacion  = suma ( ( "<" | "<=" | ">" | ">=" ) suma )* ;
suma         = producto ( ( "+" | "-" ) producto )* ;
producto     = unario ( ( "*" | "/" | "%" ) unario )* ;
unario       = ( "-" | "no" ) unario | primario ;
primario     = NUMERO | NUMERO_DECIMAL | CADENA | "verdadero" | "falso"
             | conversion | llamada | IDENTIFICADOR | "(" expresion ")" ;
conversion   = ( "entero" | "decimal" | "texto" ) "(" expresion ")" ;
llamada      = IDENTIFICADOR "(" ( expresion ( "," expresion )* )? ")" ;
```

### Precedencia de operadores

De mayor a menor. Todos los operadores binarios asocian a la izquierda
(`10 - 3 - 2` es `(10 - 3) - 2`).

| Nivel | Operadores | Ejemplo |
|---|---|---|
| 1 (mayor) | `-` (negativo), `no` | `-x`, `no listo` |
| 2 | `*` `/` `%` | `a * b` |
| 3 | `+` `-` | `a + b` |
| 4 | `<` `<=` `>` `>=` | `a < b` |
| 5 | `==` `!=` | `a == b` |
| 6 | `y` | `a y b` |
| 7 (menor) | `o` | `a o b` |

### Decisiones de diseño

- Las variables solo pueden declararse dentro de funciones (no hay variables globales).
- Las únicas expresiones que pueden usarse como sentencia son las llamadas (`saludar();`).
  Una sentencia como `x + 1;` es un error, porque no tendría ningún efecto.
- `sino si` se representa como un `si` dentro del `sino`, así que no necesita una regla propia.
- Las conversiones usan el nombre del tipo como si fuera una función (`entero(3.7)`), así que
  `entero` seguido de `(` es una conversión y `entero` seguido de un nombre es una declaración.

## 3. Reglas semánticas

El análisis semántico (`compilador.semantico.AnalizadorSemantico`) revisa que el programa
tenga sentido. Se reporta el primer error encontrado.

### Programa y funciones

1. Debe existir una función `principal`, sin parámetros y sin tipo de retorno. Ahí empieza la ejecución.
2. No puede haber dos funciones con el mismo nombre, ni una con el nombre de una función predefinida.
3. Una función puede llamarse antes de su definición (esto permite la recursión mutua).
4. Una llamada debe pasar tantos argumentos como parámetros tiene la función, y cada uno de un tipo aceptable.
5. Una función sin `: tipo` no regresa valor. Su `regresa` va sin valor, y no puede usarse dentro de una expresión.
6. Una función con `: tipo` debe terminar con `regresa <valor del tipo>` en todos los caminos posibles.
   El análisis es conservador: un `regresa` dentro de un `mientras` o un `para` no cuenta,
   porque el ciclo podría no ejecutarse nunca.

### Funciones predefinidas

| Función | Regresa | Qué hace |
|---|---|---|
| `leerTexto()` | `texto` | Lee una línea del teclado, sin el salto de línea. |
| `leerEntero()` | `entero` | Lee una línea y la convierte a entero. Se permiten espacios antes y después del número. |
| `leerDecimal()` | `decimal` | Lee una línea y la convierte a decimal. |

Antes de esperar los datos, se muestra todo lo que estaba pendiente de imprimirse (por ejemplo,
la pregunta que se hizo con `escribir`).

### Variables y ámbitos

7. Una variable debe declararse antes de usarse. En `entero x = x;`, la `x` de la derecha todavía no existe.
8. Cada bloque `{ }` crea un ámbito. Una variable solo existe desde su declaración hasta el final de su bloque.
   La variable declarada en un `para` solo existe dentro del ciclo.
9. No se puede declarar una variable con el nombre de otra que sigue visible, aunque sea en un bloque interior
   (igual que en Java). Sí se puede repetir el nombre en bloques separados.
10. Los parámetros se comportan como variables declaradas al inicio de la función.
11. Una variable sin valor inicial empieza en `0` (entero), `0.0` (decimal), `falso` (booleano) o `""` (texto).
    Si se declara dentro de un ciclo, vuelve a ese valor en cada vuelta.

### Tipos

Hay cuatro tipos de valor: `entero` (con signo, 64 bits), `decimal` (punto flotante de 64 bits,
como `double` en Java), `booleano` y `texto`.

| Operación | Operandos | Resultado |
|---|---|---|
| `+` | texto y cualquier valor (en cualquier orden) | texto (concatenación) |
| `+ - * /` | entero, entero | entero |
| `+ - * /` | dos números, al menos uno decimal | decimal |
| `%` | entero, entero | entero |
| `-x` | entero o decimal | el mismo tipo |
| `< <= > >=` | dos números (entero o decimal) | booleano |
| `== !=` | dos valores del mismo tipo, o dos números | booleano |
| `y o` | booleano, booleano | booleano |
| `no x` | booleano | booleano |

12. Las condiciones de `si`, `mientras` y `para` deben ser booleanas.
13. El valor guardado en una variable, pasado como argumento o regresado por una función debe ser de su tipo.
    La única excepción es que **un entero se acepta donde se espera un decimal** (se convierte solo, como en Java):
    `decimal d = 5;` guarda `5.0`. Al revés no: `entero e = 2.5;` es un error.
14. `imprimir` acepta cualquier valor y lo muestra seguido de un salto de línea: los enteros en decimal,
    los decimales con la menor cantidad de dígitos que los representa exactamente (siempre con punto: `2.0`),
    los booleanos como `verdadero` o `falso` y los textos tal cual. `escribir` hace lo mismo sin el salto de línea.
15. La concatenación asocia a la izquierda, igual que en Java: `1 + 2 + "a"` es `"3a"`, pero `"a" + 1 + 2` es `"a12"`.
16. `==` y `!=` entre textos comparan el contenido, no la ubicación en memoria: `"hola" == "hola"` es `verdadero`.

### Conversiones

| Conversión | Acepta | Resultado |
|---|---|---|
| `entero(x)` | decimal | Quita la parte decimal (trunca hacia cero): `entero(3.99)` es `3`, `entero(-3.99)` es `-3`. |
| `entero(x)` | texto | El número que contiene el texto: `entero("42")` es `42`. |
| `decimal(x)` | entero o texto | `decimal(5)` es `5.0`, `decimal("2.5")` es `2.5`. |
| `texto(x)` | cualquier valor | El mismo texto que mostraría `imprimir`: `texto(1.5)` es `"1.5"`. |

Convertir un booleano a número es un error de compilación.

### Comportamiento en tiempo de ejecución

- La aritmética de enteros es de 64 bits en complemento a dos: si una operación se desborda, el resultado
  da la vuelta (`9223372036854775807 + 1` da `-9223372036854775808`).
- La división de enteros trunca hacia cero y `%` lleva el signo del dividendo, igual que en C y Java
  (`-7 / 2 = -3`, `-7 % 2 = -1`). Para obtener decimales hay que usar al menos un decimal: `7 / 2.0 = 3.5`.
- Dividir un entero entre cero (con `/` o `%`) termina el programa con el código de salida 1 y el mensaje
  `Error en tiempo de ejecución (línea N): división entre cero`.
- Los decimales siguen el estándar IEEE 754, igual que `double` en Java: `0.1 + 0.2` es `0.30000000000000004`;
  dividir un decimal entre cero no es error y da `infinito`, `-infinito` o `NaN` ("no es un número");
  y `NaN` no es igual a nada, ni a sí mismo.
- `entero(x)` con un decimal que no cabe en un entero (o con `NaN`) da `-9223372036854775808`.
- Si una conversión desde texto o una lectura reciben algo que no es un número válido, el programa termina
  con el código 1 y el mensaje `Error en tiempo de ejecución (línea N): "abc" no es un número entero válido`.
- Si se intenta leer y ya no hay datos (por ejemplo, porque la entrada viene de un archivo que se terminó),
  el programa termina con `Error en tiempo de ejecución (línea N): no hay más datos para leer`.
- `y` y `o` usan corto circuito: si el lado izquierdo ya decide el resultado, el derecho no se evalúa.
- Cada concatenación, conversión a texto o lectura crea un texto nuevo en memoria dinámica. Esa memoria no se
  libera mientras el programa corre (el lenguaje no tiene recolector de basura); el sistema operativo la
  recupera al terminar. Si se acaba la memoria, el programa termina con
  `Error en tiempo de ejecución: no hay memoria suficiente`.
- Los argumentos se evalúan de izquierda a derecha.

## 4. Modelo de ejecución (generación de código)

El generador (`compilador.generador.GeneradorX86`) produce ensamblador x86-64 en sintaxis Intel
para el ensamblador de GNU. gcc lo ensambla y lo enlaza con la biblioteca de C.

### Evaluación de expresiones

Toda expresión deja su resultado en `rax`. En una operación binaria se evalúa el lado izquierdo,
se guarda en la pila (`push rax`), se evalúa el derecho, se pasa a `rcx` y se recupera el izquierdo
(`pop rax`). Al final se opera `rax ← rax OP rcx`.

| Tipo | Cómo se guarda en 64 bits |
|---|---|
| `entero` | El número en complemento a dos |
| `decimal` | Los 64 bits del número en formato IEEE 754 (`double`) |
| `booleano` | `1` (verdadero) o `0` (falso) |
| `texto` | La dirección de memoria de sus bytes en UTF-8, terminados en un byte `0` (como en C) |

Los textos literales se guardan una sola vez en la sección de datos de solo lectura (`.rodata`),
aunque aparezcan varias veces en el programa.

### Decimales

Los decimales viajan en `rax` y en la pila como cualquier otro valor, pero para operar se pasan a los
registros de punto flotante de la extensión SSE2 del procesador:

| Operación | Instrucciones |
|---|---|
| Pasar entre registros | `movq xmm0, rax` / `movq rax, xmm0` |
| `+ - * /` | `addsd`, `subsd`, `mulsd`, `divsd` |
| Comparaciones | `ucomisd` seguida de `seta`/`setae` (que dan falso si hay un `NaN`) |
| `==` / `!=` | `ucomisd` con `sete`/`setne` combinadas con la bandera de paridad, que indica `NaN` |
| `-x` | `btc rax, 63` (cambia el bit de signo) |
| entero → decimal | `cvtsi2sd` |
| decimal → entero | `cvttsd2si` (trunca) |

Cuando una operación mezcla un entero con un decimal, el entero se convierte con `cvtsi2sd` antes de operar.
Lo mismo pasa al guardar un entero en una variable decimal, al pasarlo como argumento decimal y al regresarlo
de una función decimal.

### Marco de una función

```
        ┌──────────────────────┐  direcciones altas
        │ argumento 1          │  [rbp + 16 + 8·(n-1)]
        │ ...                  │
        │ argumento n          │  [rbp + 16]
        │ dirección de retorno │  [rbp + 8]
rbp ──► │ rbp anterior         │  [rbp]
        │ ranura 0             │  [rbp - 8]
        │ ranura 1             │  [rbp - 16]
        │ ...                  │
rsp ──► └──────────────────────┘  direcciones bajas
```

- El análisis semántico asigna a cada parámetro y variable una ranura: primero los parámetros
  (0, 1, ...) y luego las variables locales en el orden en que se declaran.
- La ranura `k` está en `[rbp - 8·(k+1)]`.

### Convención de llamada

1. Quien llama evalúa los argumentos de izquierda a derecha y mete cada uno a la pila (`push rax`).
2. `call fn_<nombre>`.
3. La función crea su marco (`push rbp`, `mov rbp, rsp`, `sub rsp, 8·ranuras`) y copia los argumentos a sus ranuras.
4. El valor de retorno se deja en `rax`, se destruye el marco (`mov rsp, rbp`, `pop rbp`) y se ejecuta `ret`.
5. Quien llamó retira los argumentos (`add rsp, 8·n`).

Las funciones predefinidas (`leerTexto`, `leerEntero`, `leerDecimal`) no siguen esta convención:
reciben en `rdi` el número de línea de la llamada, para poder reportar errores, y llaman directamente
a su rutina de soporte.

### Nombres en ensamblador

Cada función del lenguaje se llama `fn_<nombre>` en ensamblador, para que no choque con registros
(`rax`), con `main` o con funciones de C. Los caracteres que no son ASCII se codifican: `ñ` se convierte
en `_u0000f1`, y el `_` se duplica para que dos nombres distintos nunca produzcan la misma etiqueta.

### Programa completo

- `main` es la función que llama la biblioteca de C al iniciar. Llama a `fn_principal` y regresa 0.
- Las operaciones que no son instrucciones simples del procesador usan rutinas de soporte escritas
  en ensamblador, que llaman a la biblioteca de C. Antes de llamar a C alinean la pila a 16 bytes,
  como exige el ABI System V:

  | Rutina | Qué hace | Usa de C |
  |---|---|---|
  | `_rt_imprimir_entero` / `_rt_escribir_entero` | `imprimir` / `escribir` de un entero | `printf` |
  | `_rt_imprimir_texto` / `_rt_escribir_texto` | `imprimir` / `escribir` de un texto | `puts`, `printf` |
  | `_rt_entero_a_texto` | Convierte un entero a texto | `malloc`, `snprintf` |
  | `_rt_decimal_a_texto` | Convierte un decimal a texto con 15, 16 o 17 dígitos, los mínimos que lo representan exactamente | `malloc`, `snprintf`, `strtod`, `strpbrk` |
  | `_rt_booleano_a_texto` | Da `"verdadero"` o `"falso"` | — |
  | `_rt_texto_a_entero` | Convierte un texto a entero y valida el formato | `strtol`, `__errno_location` |
  | `_rt_texto_a_decimal` | Convierte un texto a decimal y valida el formato | `strtod` |
  | `_rt_leer_texto` | `leerTexto()`: lee una línea de la entrada estándar | `fflush`, `getline` |
  | `_rt_leer_entero` / `_rt_leer_decimal` | `leerEntero()` / `leerDecimal()` | (las dos anteriores) |
  | `_rt_concatenar` | Une dos textos en uno nuevo | `strlen`, `malloc`, `memcpy` |
  | `_rt_textos_iguales` | `==` entre textos | `strcmp` |
  | `_rt_error_division`, `_rt_error_conversion`, `_rt_error_sin_datos`, `_rt_error_memoria` | Errores en tiempo de ejecución | `fflush`, `dprintf`, `exit` |

- La división de enteros revisa el divisor: si es 0, llama a `_rt_error_division`. Si es -1, calcula el
  resultado sin `idiv`, porque `idiv` falla con el mínimo entero entre -1.
