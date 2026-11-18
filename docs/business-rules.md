# Reglas de negocio y decisiones funcionales

[← Volver al README](../README.md)

## Requisitos

**Obligatorios (enunciado):**
- Portfolio: valor total, pesos disponibles y posiciones con cantidad, valor y rendimiento %.
- Búsqueda por ticker y/o nombre.
- Órdenes BUY/SELL MARKET/LIMIT, por cantidad o por monto (acciones enteras), con estados NEW, FILLED, REJECTED
  (persistida) y CANCELLED (solo desde NEW).
- Transferencias CASH_IN/CASH_OUT: el enunciado indica que *se pueden* modelar como órdenes, y así se modelan. El cash
  las incluye y se crean por el mismo `POST /orders` (ver D12).
- Saldos derivados de `orders.size`.
- Test funcional del envío de órdenes.

**Opcional (enunciado):** colección de requests → `postman/cocos-challenge.postman_collection.json`.
- **Uso:** se importa en Postman (File → Import) y se ajusta `baseUrl` si la app no corre en `http://localhost:8080`.
- **Contenido:** health, búsqueda, portfolio, creación de órdenes (todas las combinaciones y errores),
  idempotencia y cancelación. Cada request tiene tests.
- **Corrida:** los valores esperados son los del seed. Conviene correrla en orden (Collection Runner) contra una base
  recién inicializada (`docker compose down -v` y luego `docker compose up -d --wait`).
- **`Idempotency-Key`:** la colección genera una key por request. La carpeta de idempotencia reutiliza una key fija
  a propósito.
- **Perfil `cocos`:** la base es de solo lectura, así que los POST fallan. Sirven Health, Instruments y Portfolio.
- No contiene credenciales.

**Fuera de alcance (por enunciado):** autenticación y simulación de mercado.

## Reservas de órdenes pendientes: regla funcional compartida (D2)
Las órdenes LIMIT que quedan en `NEW` **reservan**:
- una **BUY LIMIT NEW** reserva `size × price` de cash, de modo que `availableCash = cashBalance − Σ NEW BUY`;
- una **SELL LIMIT NEW** reserva `size` acciones del instrumento, de modo que la tenencia disponible es la cantidad
  neta FILLED menos Σ NEW SELL de ese instrumento.

Las reservas no modifican las posiciones ni el valor total: siguen siendo solo FILLED. Ya las expone
`LedgerQuery.reservations`, la única fuente que usan tanto el portfolio como la creación de órdenes:
- **Disponibilidad y validación:** una BUY requiere `size × precio ≤ availableCash`, y una SELL requiere
  `size ≤ tenencia disponible`. Si no alcanza, la orden queda REJECTED.
- **Cancelación:** pasar una orden de NEW a CANCELLED libera su reserva automáticamente, porque deja de sumarse.

No hace falta ninguna columna nueva: la reserva se deriva del estado de las órdenes.

## Decisiones funcionales

| # | Tema | Decisión | Estado |
|---|---|---|---|
| D1 | Costo base / rendimiento | **Costo promedio ponderado.** Las compras recalculan el promedio, las ventas reducen la cantidad sin cambiarlo y el promedio se reinicia al llegar a 0. Rendimiento % **solo no realizado** = (cotización − costo promedio) / costo promedio × 100, equivalente a (valor de mercado − costo restante) / costo restante. No se calcula rendimiento realizado, histórico ni FIFO. Ver [Política de costo promedio](api.md#política-de-costo-promedio-conservadora) | ✅ Implementada |
| D2 | LIMIT pendientes | Las órdenes NEW **reservan**: BUY reserva `size × price` de los pesos disponibles y SELL reserva acciones. No afectan las posiciones ni el valor total de la cuenta. Ver [Reservas](#reservas-de-órdenes-pendientes-regla-funcional-compartida-d2) | ✅ Implementada (portfolio y órdenes) |
| D3 | Concurrencia | Lock por cuenta (`users … FOR NO KEY UPDATE`) como primer paso de una transacción `READ COMMITTED`, con timeout → 503. Cancelación con update condicional. Ver [Estrategia de concurrencia](idempotency-and-concurrency.md#estrategia-de-concurrencia) | ✅ Implementada |
| D4 | Sin cotización | MARKET → 422 `MARKET_PRICE_UNAVAILABLE`, sin persistir. Portfolio: posición con valor `null`, excluida del total (valor parcial conocido), `valuationComplete = false` y aviso. Búsqueda: precio y retorno en `null` | ✅ Implementada |
| D5 | Precisión monetaria | Ver [Precisión monetaria](#precisión-monetaria) | ✅ Implementada (`MonetaryPolicy`) |
| D6 | Idempotencia | Header `Idempotency-Key` (UUID) **obligatorio** en `POST /orders` y tabla `order_idempotency` (V2) con `UNIQUE (user_id, idempotency_key)`, insertada en la misma transacción que la orden y después del lock por cuenta. El replay devuelve el **resultado original de la creación** (snapshot mínimo: estado inicial + motivo de rechazo). Un caché en memoria se descartó: no sobrevive reinicios ni múltiples instancias. Ver [Idempotencia](idempotency-and-concurrency.md#idempotencia-de-la-creación-de-órdenes) | ✅ Implementada |
| D7 | Posición negativa en los datos | Se muestra tal cual, sin asumir que sea un error; las ventas nuevas validan tenencia, así que la API no abre posiciones negativas. Ver [Posiciones negativas](#posiciones-negativas) | ✅ Implementada en el portfolio |
| D8 | Monto que alcanza para 0 acciones | 422 `AMOUNT_TOO_LOW` sin persistir: no existe una orden válida que grabar (distinto de fondos insuficientes, que sí se persiste) | ✅ Implementada |
| C1 | LIMIT sin fondos o acciones | `REJECTED` al crearla: nunca queda una orden NEW sin cobertura | ✅ Implementada |
| C2 | LIMIT con precio que cruza el mercado | Queda `NEW` igual: no hay matching engine | ✅ Implementada |
| C3 | Monto en una LIMIT | `floor(amount / precio límite)`. En MARKET, `floor(amount / close)` | ✅ Implementada |
| C4 | Código HTTP de una REJECTED | `201` con `status: REJECTED`, porque es un resultado persistido y no un error | ✅ Implementada |
| C5 | `datetime` de la orden | `LocalDateTime.now(clock)` en `APP_TIME_ZONE`, truncado a segundos como en el seed | ✅ Implementada |
| D9 | `MONEDA` en la búsqueda | Se excluye: ARS no es un activo del mercado ni se opera vía `/orders` | ✅ Implementada |
| D10 | Parámetros de búsqueda | Un único `q` con match parcial e insensible a mayúsculas en ticker **o** nombre, orden por relevancia y `limit` (1..100, default 20) | ✅ Confirmada e implementada |
| D11 | Identificación del usuario | `userId` en el path (`/api/v1/users/{userId}/…`). Sin autenticación, según el enunciado | ✅ Implementada |
| D12 | Transferencias | Mismo endpoint `POST /users/{userId}/orders` con `side` CASH_IN/CASH_OUT y solo `amount`; mismo lock, idempotencia y transacción que las órdenes. Detalle en [CASH_IN / CASH_OUT](#cash_in--cash_out) | ✅ Implementada |

## CASH_IN / CASH_OUT

Se crean por `POST /api/v1/users/{userId}/orders` con `{ "side": "CASH_IN" | "CASH_OUT", "amount": <pesos> }`.
Ejemplos y validaciones: [docs/api.md](api.md#transferencias-cash_in--cash_out).

- **Convención del seed** (`CASH_IN size=1000000, price=1`): `size` = monto en **pesos enteros**, `price = 1`,
  `type = 'MARKET'`, sobre el instrumento ARS. El instrumento se resuelve por `type = 'MONEDA'` y `ticker = 'ARS'`,
  no por un id fijo.
- **Limitación del esquema original:** `size INT` no admite centavos, y el monto máximo por transferencia es
  2.147.483.647. Un monto con decimales → 400 (no se redondea en silencio).
  - Alternativa descartada: `size = 1, price = monto`. Admitiría centavos, pero rompe la convención del seed y la
    indicación de usar la columna `size`.
- **Cálculo uniforme del cash:** cada orden FILLED aporta `± size × price`. CASH_IN y SELL suman; CASH_OUT y BUY
  restan. La fórmula no depende de que el `price` de las transferencias sea 1.
- **Estados:**
  - CASH_IN → FILLED.
  - CASH_OUT → FILLED si `monto ≤ pesos disponibles` (cash FILLED menos reservas de BUY NEW); si no, REJECTED y se
    persiste.

## Precisión monetaria
- **Entradas** (limitadas por el esquema):
  - Precio LIMIT > 0, con ≤ 2 decimales y ≤ 99.999.999,99 (`NUMERIC(10,2)`).
  - Cantidad entera entre 1 y 2.147.483.647 (`INT`).
  - Monto de inversión > 0 con ≤ 2 decimales.
  - Fuera de rango → 400; nunca se redondea en silencio.
- **Cantidad desde monto:** `floor(monto / precio)`. Si da 0 → 422 `AMOUNT_TOO_LOW` (D8).
- **Cálculos exactos:** todo monto es `entero × precio de 2 decimales`, así que el cash, el valor de la orden, el
  valor de mercado y los totales son exactos a escala 2, sin redondeo. Las validaciones de fondos comparan valores
  exactos.
- **Divisiones:**
  - El costo promedio se calcula internamente con `MathContext.DECIMAL128`.
  - El rendimiento no realizado (`totalReturnPercent`) y el retorno diario se redondean solo al exponerlos: porcentaje con 2 decimales,
    `HALF_EVEN`.
  - El costo promedio expuesto también lleva 2 decimales `HALF_EVEN`.
- Las reglas se centralizan en `shared/domain/MonetaryPolicy`, con tests de casos límite.

## Posiciones negativas
- **Caso real:** el usuario 1 tiene BMA con BUY 20 y SELL 30, ambas FILLED → −10. El SELL ya acreditó 45.900 de cash.
- **No se asume que sea un error.** Podría ser un dato de ejemplo inconsistente o una venta en corto registrada por
  otro canal; el enunciado no lo aclara. Lo que sí define es que las **ventas nuevas** deben validar que el usuario
  tenga las acciones suficientes, así que la API no permite abrir posiciones negativas (ver *Operaciones*).
- **Implementado:** la posición se muestra tal cual y los datos originales no se modifican. El tratamiento es válido
  con cualquiera de las dos interpretaciones.
  - `quantity = −10` y `marketValue = −10 × close = −15.028,00`, incluido en el valor total.
  - `averageCost = null` y `totalReturnPercent = null`: el costo promedio de compra no está definido para una
    posición negativa (una posición corta se valúa contra su precio de venta), y mostrar un número mal calculado
    sería peor que no mostrarlo.
  - `valuationStatus = NEGATIVE_QUANTITY`, con su warning en la respuesta.
- **Por qué se incluye en el total:** el cash ya recibió el ingreso de las 30 acciones. Excluir las −10 inflaría el
  valor de la cuenta en `10 × close`. Incluirlas mantiene coherente la ecuación cash + posiciones = valor total, sin
  esconder datos ni hacer clamp.
- **Operaciones:**
  - SELL: la tenencia disponible es ≤ 0, así que queda REJECTED (`INSUFFICIENT_HOLDINGS`), como exige el enunciado.
    Si el negocio permitiera ventas en corto, habría que cambiar esta validación y el cálculo del rendimiento: es
    una decisión de producto, fuera del alcance del enunciado.
  - BUY: reduce la posición negativa. Si cruza a positiva, el costo promedio de la parte positiva es el precio de esa
    compra.
- Las posiciones con cantidad 0 no se listan.
