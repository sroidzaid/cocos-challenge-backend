# API

[← Volver al README](../README.md)


## Buscar activos — `GET /api/v1/instruments?q={texto}&limit={n}`

| Parámetro | Regla |
|---|---|
| `q` | Obligatorio, 1..100 caracteres (no puede ser solo espacios). Coincidencia **parcial e insensible a mayúsculas en ticker o nombre**. `%` y `_` se toman literales |
| `limit` | Opcional, 1..100, default 20 |

```http
GET /api/v1/instruments?q=banco
```
```json
[
  { "id": 22, "ticker": "BBAR", "name": "Banco Frances",          "lastPrice": 1038.10, "dailyReturnPercent": -0.87 },
  { "id": 28, "ticker": "BHIP", "name": "Banco Hipotecario S.A.", "lastPrice": 33.40,   "dailyReturnPercent": -3.47 },
  { "id": 31, "ticker": "BMA",  "name": "Banco Macro S.A.",       "lastPrice": 1502.80, "dailyReturnPercent": -1.15 },
  { "id": 19, "ticker": "BPAT", "name": "Banco Patagonia",        "lastPrice": 279.00,  "dailyReturnPercent": -3.46 }
]
```

**Reglas:**
- **`lastPrice`** = `close` de la cotización más reciente del instrumento ("el close es el último precio"). Las
  cotizaciones se obtienen en batch con una sola query.
- **`dailyReturnPercent`** = `(close − previousClose) / previousClose × 100`, 2 decimales, `HALF_EVEN`.
- **Sin cotización** (por ejemplo PGR): `lastPrice` y `dailyReturnPercent` en `null`; no es un error. El retorno
  también es `null` si falta `previousClose` o si vale 0.
- **Orden por relevancia:** primero el ticker exacto, después los tickers que empiezan con `q`, después el resto;
  dentro de cada grupo, por ticker. Por ejemplo, `q=pa` → PAMP, PATA, BPAT, CGPA2, INTR.
- **Se excluyen los instrumentos `MONEDA`** (ARS): no son activos del mercado (D9).
- **Sin resultados:** `200 []`.
- **Parámetros inválidos:** `400 VALIDATION_FAILED`, con el campo en `errors[]`.

Solo se exponen los campos que justifica el enunciado: `id` (para enviar órdenes), `ticker` y `name` (criterios de
búsqueda), y el último precio y el retorno diario.

## Portfolio — `GET /api/v1/users/{userId}/portfolio`

- `userId` < 1 → `400 VALIDATION_FAILED`; no numérico → `400`.
- Usuario inexistente → `404 USER_NOT_FOUND`.

Respuesta con el seed original (usuario 1, cotizaciones del 2023-07-14):
```json
{
  "userId": 1,
  "totalAccountValue": 889756.00,
  "cashBalance": 753000.00,
  "availableCash": 627500.00,
  "valuationComplete": true,
  "positions": [
    { "instrumentId": 31, "ticker": "BMA", "name": "Banco Macro S.A.", "quantity": -10, "averageCost": null,
      "lastPrice": 1502.80, "marketValue": -15028.00, "totalReturnPercent": null, "valuationStatus": "NEGATIVE_QUANTITY" },
    { "instrumentId": 54, "ticker": "METR", "name": "MetroGAS S.A.", "quantity": 500, "averageCost": 250.00,
      "lastPrice": 229.50, "marketValue": 114750.00, "totalReturnPercent": -8.20, "valuationStatus": "OK" },
    { "instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.", "quantity": 40, "averageCost": 930.00,
      "lastPrice": 925.85, "marketValue": 37034.00, "totalReturnPercent": -0.45, "valuationStatus": "OK" }
  ],
  "warnings": [
    { "code": "NEGATIVE_QUANTITY", "instrumentId": 31,
      "message": "BMA has a negative quantity (more shares sold than bought in the order history): average cost and return are not computed" }
  ]
}
```

| Campo | Fórmula | Seed, usuario 1 |
|---|---|---|
| `cashBalance` | Saldo contable: Σ `±size × price` de las órdenes **FILLED** (CASH_IN/SELL suman, CASH_OUT/BUY restan) | 1.000.000 − 46.500 + 9.400 − 100.000 − 30.800 − 125.000 + 45.900 = 753.000,00 |
| `availableCash` | `cashBalance` − cash reservado por las **NEW BUY** (Σ `size × price`, D2) | 753.000 − (50×710 + 60×1.500) = 627.500,00 |
| `marketValue` | `quantity × lastPrice` (close más reciente), exacto | PAMP 40 × 925,85 = 37.034,00 |
| `averageCost` | Costo promedio ponderado (ver política abajo), expuesto con 2 decimales `HALF_EVEN` | PAMP 930,00 |
| `totalReturnPercent` | Rendimiento **no realizado** de la posición abierta: `(lastPrice − averageCost) / averageCost × 100` = `(marketValue − costo) / costo × 100`, 2 decimales `HALF_EVEN`. Pese al nombre, no incluye ganancias realizadas ni es una rentabilidad histórica total | PAMP −166 / 37.200 = −0,45 % |
| `totalAccountValue` | `cashBalance` + Σ `marketValue` de las posiciones **con precio**, incluidas las negativas | 753.000 + 37.034 + 114.750 − 15.028 = 889.756,00 |

**Reglas:**
- Las posiciones se reconstruyen desde las órdenes FILLED BUY/SELL. Las NEW, CANCELLED y REJECTED no cuentan, ni
  tampoco las transferencias. Las posiciones con cantidad 0 no se listan, y el resto se ordena por ticker.
- **El rendimiento es no realizado, sobre la posición actual.** La ganancia o pérdida de las ventas ya está en el
  cash (por ejemplo, las 10 PAMP vendidas a 940).
- **`valuationStatus`:**
  - `OK`.
  - `NO_MARKET_PRICE`: sin cotización. `marketValue` y `totalReturnPercent` en `null`, la posición queda fuera del
    total y `valuationComplete = false`.
  - `NEGATIVE_QUANTITY`: ver [Posiciones negativas](business-rules.md#posiciones-negativas).
  - Cada estado distinto de `OK` (y toda cantidad negativa) agrega un elemento a `warnings`.
- **Total parcial:** cuando `valuationComplete` es `false`, `totalAccountValue` es un **valor parcial conocido**
  (cash más las posiciones que sí tienen precio), **no una valuación completa** de la cuenta.
- **Consistencia:** todas las lecturas van en una única transacción `readOnly` + `REPEATABLE READ`, así cash,
  reservas, posiciones y precios salen del mismo snapshot de PostgreSQL. Ver
  [Consistencia de lectura del portfolio](idempotency-and-concurrency.md#consistencia-de-lectura-del-portfolio).

### Política de costo promedio (conservadora)
El historial se recorre por instrumento en orden **determinístico**: `datetime` ascendente y, ante empate, `id`.

| Evento | Costo promedio |
|---|---|
| BUY sobre una posición en 0 o positiva | `(avg × qty + price × size) / (qty + size)`, sin redondear (`DECIMAL128`) |
| SELL que deja la posición positiva (venta parcial) | Se mantiene |
| SELL que deja la posición en 0 | Se **reinicia**: la próxima compra arranca un promedio nuevo |
| SELL que deja la posición negativa (sobreventa) | Indefinido (`null`) |
| BUY sobre una posición negativa que no llega a positiva | Sigue indefinido |
| BUY que cruza de negativa a positiva | El precio de esa compra: equivale a cerrar en 0 y comprar el excedente, así que todas las acciones en cartera provienen de ella |

- **Nunca se inventa un costo.** Sin promedio definido, `averageCost` y `totalReturnPercent` son `null`. Así se evita
  mostrar un rendimiento engañoso.
- Una orden FILLED con precio nulo o ≤ 0 hace fallar el cálculo (500), en lugar de producir un costo incorrecto.
- **Control cruzado:** un test verifica que las cantidades del calculador coincidan con el agregado SQL
  `netPositions`.

## Enviar una orden — `POST /api/v1/users/{userId}/orders`

```http
POST /api/v1/users/1/orders
Content-Type: application/json
Idempotency-Key: 4f9c2a1e-8d7b-4c3a-9e6f-1b2d3c4e5f60

{ "instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900.50 }
```
El header `Idempotency-Key` (UUID) es **obligatorio**. Ver [Idempotencia](idempotency-and-concurrency.md#idempotencia-de-la-creación-de-órdenes).
```json
{ "id": 12, "userId": 1, "instrumentId": 47, "side": "BUY", "type": "LIMIT", "status": "NEW",
  "size": 10, "price": 900.50, "datetime": "2026-11-12T10:15:00", "rejectionReason": null }
```
Por monto, por ejemplo `{ "instrumentId": 47, "side": "SELL", "type": "MARKET", "amount": 10000 }`, la cantidad es
`floor(10000 / 925,85) = 10`. Un rechazo responde igual, con `201`:
```json
{ "id": 13, "userId": 1, "instrumentId": 47, "side": "SELL", "type": "MARKET", "status": "REJECTED",
  "size": 100, "price": 925.85, "datetime": "2026-11-12T10:16:00", "rejectionReason": "INSUFFICIENT_HOLDINGS" }
```

**Validación del request** (`400 VALIDATION_FAILED`, no se persiste nada):

| Campo | Regla |
|---|---|
| `instrumentId` | Obligatorio (BUY/SELL), ≥ 1 |
| `side` | `BUY` o `SELL` (para `CASH_IN`/`CASH_OUT` ver [Transferencias](#transferencias-cash_in--cash_out)); un valor desconocido → 400 `MALFORMED_REQUEST` |
| `type` | `MARKET` o `LIMIT` |
| `quantity` / `amount` | **Exactamente uno de los dos.** `quantity`: entero ≥ 1 (`1.5` → 400; nunca se trunca). `amount`: > 0, ≤ 2 decimales |
| `price` | **LIMIT:** obligatorio, > 0, ≤ 2 decimales, ≤ 99.999.999,99 (`NUMERIC(10,2)`). **MARKET:** no se admite |

**Reglas** (`p` = precio de ejecución, `q` = cantidad):
- `p`: MARKET usa el `close` más reciente; LIMIT usa el precio límite.
- `q`: el valor de `quantity`, o `floor(amount / p)` con `RoundingMode.DOWN`.
- `availableCash = cashBalance − reservas NEW BUY` y `availableQuantity = neto FILLED − reservas NEW SELL`: son las
  mismas consultas que usa el portfolio.

| Orden | Chequeo | Alcanza | No alcanza |
|---|---|---|---|
| BUY MARKET | `q × p ≤ availableCash` | `FILLED`: el cash baja y las acciones suben | `REJECTED` `INSUFFICIENT_FUNDS` |
| SELL MARKET | `q ≤ availableQuantity` | `FILLED`: el cash sube y las acciones bajan | `REJECTED` `INSUFFICIENT_HOLDINGS` |
| BUY LIMIT | `q × p ≤ availableCash` | `NEW`: reserva `q × p`, sin mover el saldo contable | `REJECTED` `INSUFFICIENT_FUNDS` |
| SELL LIMIT | `q ≤ availableQuantity` | `NEW`: reserva `q` acciones, sin mover las tenencias | `REJECTED` `INSUFFICIENT_HOLDINGS` |

- **Una SELL nunca abre una posición negativa.** BMA (−10 en el seed) no puede venderse, y la posición original se
  conserva tal cual.
- **Una LIMIT siempre queda `NEW`**, aunque su precio cruce el mercado: no hay matching ni ejecución posterior.
- **`REJECTED` es un resultado de negocio, no un error.** Se persiste con su `size` y su `price` en la misma
  transacción, sin rollback, y no cambia el patrimonio. `rejectionReason` solo viaja en la respuesta de creación,
  porque el esquema no tiene una columna para guardarlo.
- **Errores en los que no se persiste nada:**

| Caso | HTTP | `code` |
|---|---|---|
| Usuario inexistente | 404 | `USER_NOT_FOUND` |
| Instrumento inexistente | 404 | `INSTRUMENT_NOT_FOUND` |
| Instrumento `MONEDA` (ARS) | 422 | `INSTRUMENT_NOT_TRADABLE` |
| MARKET sin cotización | 422 | `MARKET_PRICE_UNAVAILABLE` |
| El monto no alcanza para 1 acción | 422 | `AMOUNT_TOO_LOW` |
| La cantidad calculada supera `INT` | 422 | `QUANTITY_OUT_OF_RANGE` |
| Cuenta ocupada (lock timeout) | 503 | `SERVICE_UNAVAILABLE` |

## Transferencias CASH_IN / CASH_OUT
Mismo endpoint `POST /api/v1/users/{userId}/orders`, con `Idempotency-Key` obligatorio. Solo se envían `side` y
`amount`:
```http
POST /api/v1/users/1/orders
Content-Type: application/json
Idempotency-Key: 7d3f1b2a-5c4e-4f6a-8b9c-0d1e2f3a4b5c

{ "side": "CASH_IN", "amount": 5000 }
```
```json
{ "id": 12, "userId": 1, "instrumentId": 66, "side": "CASH_IN", "type": "MARKET", "status": "FILLED",
  "size": 5000, "price": 1.00, "datetime": "2026-11-17T10:15:00", "rejectionReason": null }
```
Un retiro mayor a los pesos disponibles se persiste como `REJECTED` y responde `201` (con el seed, el usuario 1 tiene
`availableCash` 627.500):
```json
{ "id": 13, "userId": 1, "instrumentId": 66, "side": "CASH_OUT", "type": "MARKET", "status": "REJECTED",
  "size": 627501, "price": 1.00, "datetime": "2026-11-17T10:16:00", "rejectionReason": "INSUFFICIENT_FUNDS" }
```

**Validación** (`400 VALIDATION_FAILED`, no se persiste nada):

| Campo | Regla |
|---|---|
| `amount` | Obligatorio. Pesos enteros **sin decimales** (`100.50` y también `100.00` → 400), entre 1 y 2.147.483.647 (`size INT`) |
| `instrumentId`, `type`, `quantity`, `price` | No aplican: si se envían → 400 |

**Reglas:**

| Transferencia | Chequeo | Resultado | Si no alcanza |
|---|---|---|---|
| CASH_IN | — | `FILLED`: `cashBalance` y `availableCash` suben `amount` | — |
| CASH_OUT | `amount ≤ availableCash` | `FILLED`: `cashBalance` y `availableCash` bajan `amount` | `REJECTED` `INSUFFICIENT_FUNDS` |

- `availableCash = cashBalance − reservas NEW BUY`, el mismo valor contra el que se valida una BUY: el cash reservado
  por órdenes LIMIT pendientes no se puede retirar.
- Se persisten con la convención del seed: instrumento ARS (resuelto por `ticker = 'ARS'` y `type = 'MONEDA'`),
  `size = amount`, `price = 1`, `type = MARKET`. Las transferencias nunca quedan `NEW`.
- No modifican posiciones ni costo promedio; el valor total de la cuenta varía exactamente en el monto transferido.
- Comparten con las órdenes el lock por cuenta, la idempotencia y la transacción: dos CASH_OUT simultáneos, o un
  CASH_OUT y una BUY, nunca gastan el mismo saldo; repetir la misma key no acredita ni debita dos veces; la misma key
  con otro payload → `409 IDEMPOTENCY_KEY_REUSED`.
- Usuario inexistente → `404 USER_NOT_FOUND`.

## Cancelar una orden — `POST /api/v1/users/{userId}/orders/{orderId}/cancel`
Es un comando, no un `DELETE`: la orden cambia de estado y queda en el historial. Responde `200` con la orden en
`CANCELLED`:
```json
{ "id": 5, "userId": 1, "instrumentId": 45, "side": "BUY", "type": "LIMIT", "status": "CANCELLED",
  "size": 50, "price": 710.00, "datetime": "2023-07-12T15:14:20", "rejectionReason": null }
```
- **Solo se cancelan órdenes `NEW`.** `FILLED`, `REJECTED` o `CANCELLED` → `409 ORDER_NOT_CANCELLABLE`.
- Una orden inexistente o de otro usuario → `404 ORDER_NOT_FOUND`: no revela que la orden existe.
- **Cancelar libera la reserva automáticamente**, porque la orden deja de sumar como NEW. No se generan movimientos de
  cash ni de acciones. En el seed, cancelar la orden 5 sube `availableCash` de 627.500 a 663.000.

## Estados
```
(nueva) ── MARKET, alcanza ───────────► FILLED
(nueva) ── LIMIT, alcanza ────────────► NEW ── cancel ──► CANCELLED
(nueva) ── no alcanza (MARKET/LIMIT) ─► REJECTED
```
`FILLED`, `REJECTED` y `CANCELLED` son estados finales. No existe la transición `NEW → FILLED`, porque no hay
matching engine.

## Semántica HTTP
- `POST /orders` responde **201** con el status resultante (`FILLED`, `NEW` o `REJECTED` con motivo). Un rechazo
  por fondos o tenencia es un resultado de negocio persistido, no un error HTTP.
- Los errores siguen **RFC 9457** (`application/problem+json`) con `code` estable y `correlationId`:

| Situación | HTTP | `code` |
|---|---|---|
| Validación de request | 400 | `VALIDATION_FAILED` (+ `errors[]` por campo) |
| JSON inválido | 400 | `MALFORMED_REQUEST` |
| Recurso inexistente | 404 | p. ej. `ORDER_NOT_FOUND` |
| Transición de estado inválida | 409 | p. ej. `ORDER_NOT_CANCELLABLE` |
| Regla de negocio no ejecutable | 422 | p. ej. `MARKET_PRICE_UNAVAILABLE` |
| Falla transitoria de DB / lock timeout | 503 | `SERVICE_UNAVAILABLE` |
| Error inesperado (sin detalles internos) | 500 | `INTERNAL_ERROR` |
