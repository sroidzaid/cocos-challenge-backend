# Idempotencia, concurrencia y consistencia

[← Volver al README](../README.md)

## Idempotencia de la creación de órdenes

**Motivación.** Un cliente que no recibe la respuesta (timeout, corte de red) no sabe si la orden se creó, y lo
seguro es reintentar. Sin idempotencia, cada reintento crea otra orden: dos compras ejecutadas, o el doble de
efectivo reservado.

**Idempotencia y control de concurrencia son cosas distintas, y se complementan:**
- **El lock por cuenta y las reservas** evitan comprometer más efectivo o acciones de los disponibles, sea cual
  sea la combinación de órdenes. No distinguen un reintento de una orden nueva.
- **La idempotencia** asegura que **una misma intención**, identificada por la key, se ejecute **una sola vez**,
  aunque llegue repetida, en paralelo o después de un reinicio. No reemplaza al lock: se apoya en él.

**Contrato:** `POST /api/v1/users/{userId}/orders` con `Idempotency-Key: <UUID>` (obligatorio).

| Caso | Respuesta |
|---|---|
| Key nueva | Procesamiento normal → `201` con la orden (`FILLED`, `NEW` o `REJECTED`) |
| Misma key y mismo request (reintento) | `201` con **el resultado original de la creación**, más el header `Idempotent-Replayed: true`. No se crea ninguna orden |
| Misma key con otro request | `409 IDEMPOTENCY_KEY_REUSED` |
| La misma key usada por otro usuario | Es independiente: las keys son por usuario |
| Header ausente o que no es un UUID | `400 VALIDATION_FAILED` (`errors[].field = "Idempotency-Key"`) |
| Error de negocio (404/422), lock timeout (503) o error técnico | No queda registro: se puede reintentar con la misma key |
| `409 IDEMPOTENCY_KEY_CONFLICT` | Solo si un escritor que no toma el lock usa la misma key a la vez. Al reintentar se obtiene el resultado original |

**Ejemplo:**
```http
POST /api/v1/users/1/orders
Idempotency-Key: 4f9c2a1e-8d7b-4c3a-9e6f-1b2d3c4e5f60

{ "instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900.50 }
```
- **Primera vez:** `201`, y la orden queda `NEW` con `id: 12`.
- **Mismo request y misma key:** `201` + `Idempotent-Replayed: true`, con el mismo body (id 12, `NEW`). El efectivo no
  se reserva dos veces.
- **Misma key con `"quantity": 11`:** `409 IDEMPOTENCY_KEY_REUSED`.

**El replay devuelve el resultado de la creación, no el estado actual.** Si la orden 12 se cancela después, reintentar
el POST original sigue devolviendo `NEW`, que es lo que respondió la creación. El estado actual (`CANCELLED`) es el de
la orden en `orders`, el que muestran la cancelación y el portfolio. Una `REJECTED` se reproduce con su
`rejectionReason`.

**Request equivalente.** La comparación usa un SHA-256 de una forma canónica del request, no el JSON crudo:
`v1|user=1|instrument=47|side=BUY|type=LIMIT|quantity=10|price=900.5`.
- `900.50`, `900.5` y `900.500` son la misma intención. Tampoco importa el orden de los campos.
- `quantity=10` y `amount=10` son distintos.
- No incluye nada generado por el servidor: ni la fecha ni el precio de ejecución MARKET. Un reintento después de
  que cambió el `close` sigue siendo la misma intención y devuelve el precio original.

**Cómo se garantiza, todo en una transacción `READ COMMITTED`:**
1. Lock de la cuenta (`AccountLock`, primer paso, sin cambios).
2. Búsqueda de la key `(user, key)`. Como el lock serializa todas las órdenes del usuario y las keys son por usuario,
   dos requests con la misma key nunca se procesan a la vez. El segundo espera y, al tomar el lock, ve la key que
   commiteó el primero.
   - Si la key ya existe y el hash coincide → replay.
   - Si existe y el hash es distinto → `409`.
3. Validación, disponibilidad y decisión, como en la Fase C.
4. `INSERT` de la orden y `INSERT` de la key, en la misma transacción. Hay commit de los dos o rollback de los dos:
   nunca queda una orden sin key ni una key sin orden.

**Red de seguridad en la base:** `UNIQUE (user_id, idempotency_key)`. Si la viola un escritor que no respeta el
lock, PostgreSQL aborta la transacción.
- Se traduce a `409 IDEMPOTENCY_KEY_CONFLICT` **solo si la constraint violada es esa**: se verifica el SQLState
  `23505` y el nombre `uq_order_idempotency_user_key`.
- Cualquier otra violación (FK, CHECK, el otro UNIQUE) se propaga sin cambios.
- No se reintenta nada dentro de la transacción abortada.

**Garantías:**
- **Como máximo una orden por (usuario, key)**, aun con reintentos simultáneos.
- **Sobrevive a reinicios:** todo está en PostgreSQL, nada en memoria.
- **Atomicidad entre la orden y la key.**
- **Una `REJECTED` también queda asociada a su key.**

**Limitaciones:**
- La garantía depende de que el cliente reutilice la misma key en cada reintento.
- Las keys no expiran. Hay como máximo una fila por orden, así que crecen al ritmo del ledger.
- El replay arma la respuesta con las columnas de `orders` que no cambian después de la creación: instrumento, lado,
  tipo, cantidad, precio y fecha. Un escritor externo que las modifique cambiaría el replay.
- La cancelación (`POST …/cancel`) no usa key. No le hace falta: cancelar dos veces deja el mismo estado y la segunda
  llamada responde `409`.

## Consistencia de lectura del portfolio
- **Cómo se lee:** `PortfolioService` lee en una transacción `@Transactional(readOnly = true, isolation =
  REPEATABLE_READ)`.
  - En PostgreSQL, `REPEATABLE READ` toma un snapshot en la primera sentencia, y todas las lecturas siguientes (cash,
    reservas, órdenes, instrumentos, precios) ven ese mismo estado. Una orden que se commitea en el medio no puede
    hacer que el cash y las posiciones queden desalineados.
  - Los servicios de los otros módulos usan propagación `REQUIRED`, así que se unen a esta transacción.
  - `HibernateJpaDialect` aplica el nivel sobre la conexión y lo restaura al devolverla al pool.
  - Una transacción de solo lectura en `REPEATABLE READ` nunca falla por serialización (eso solo afecta a escrituras
    concurrentes) y no toma locks de fila.
- **Consistencia de lectura no es bloqueo:** el portfolio no toma `AccountLock` y no espera a las operaciones de
  escritura, ni las bloquea. Las escrituras, en cambio, usan `READ COMMITTED` + lock de cuenta (ver la sección
  siguiente).
- **Verificación:** `PortfolioTransactionIT` comprueba en PostgreSQL que la transacción es `repeatable read` y
  `read only`, que la conexión vuelve a `read committed`, y que el portfolio responde mientras otra transacción
  retiene el lock de la cuenta.

## Estrategia de concurrencia

**El problema:** "validar saldo e insertar la orden" es *check-then-act* sobre un **agregado** (la suma de filas de
`orders`).

**Escenarios:** dos compras que usan el mismo saldo, dos ventas del mismo instrumento, una compra y un retiro
simultáneos. Todos son *write skew*:
- cada transacción inserta una fila **nueva**, así que bloquear las órdenes existentes no lo impide (phantoms);
- `REPEATABLE READ` en PostgreSQL (snapshot isolation) tampoco lo detecta.

| Alternativa | ¿Resuelve? | Por qué no se elige / limitaciones |
|---|---|---|
| `@Version` (optimista) | No | Protege una fila, no una suma; además requiere una columna nueva |
| `SERIALIZABLE` (SSI) | Sí, abortando con 40001 | Necesita reintentos. **Sin índice en `orders.userid`, los predicate locks pasan a nivel tabla y aparecen conflictos entre usuarios distintos** |
| `pg_advisory_xact_lock(userId)` | Sí | Invisible en el modelo; espacio de claves compartido con cualquier otro uso |
| **Lock de la fila padre en `users`** | **Sí**, si todos los escritores del ledger lo toman | Elegida: compatible con el esquema original, explícita y testeable |

**Estrategia** (implementada en `PlaceOrderService` y `CancelOrderService`):
1. **Lock primero:** toda operación que escribe el ledger de un usuario (crear orden o transferencia CASH_IN/CASH_OUT, cancelar) abre
   una transacción `READ COMMITTED` y su **primer paso** es `AccountLock.lock(userId)`. Después lee los saldos,
   valida y escribe.
2. **`FOR NO KEY UPDATE`:**
   - es mutuamente excluyente entre los escritores del mismo usuario;
   - no bloquea los `FOR KEY SHARE` que toman los chequeos de FK al insertar órdenes.
3. **`READ COMMITTED` explícito:**
   - cada statement toma un snapshot nuevo, así que la lectura de saldos *posterior* al lock ve lo que commiteó el
     titular anterior;
   - en `REPEATABLE READ` el snapshot se tomaría antes de esperar el lock, la lectura quedaría vieja y el
     sobregiro seguiría siendo posible.
4. **`Propagation.MANDATORY`:** fuera de una transacción el lock no protegería nada, así que llamarlo así falla.
5. **Timeout** (`APP_ACCOUNT_LOCK_TIMEOUT`, default 3 s):
   - se aplica con `set_config('lock_timeout', …, true)`, local a la transacción;
   - al vencer se lanza `PessimisticLockingFailureException`, que el handler traduce a **503**;
   - así una conexión del pool nunca queda retenida indefinidamente.
6. **Cancelación concurrente:** lock de usuario + `UPDATE … SET status='CANCELLED' WHERE id=? AND userid=? AND
   status='NEW'`. Si afecta 0 filas → 409. También protege frente a un proceso externo que pase la orden a FILLED,
   porque PostgreSQL reevalúa el `WHERE` sobre la fila bloqueada.
7. **Deadlocks:** cada transacción toma un único lock (la fila de un usuario), así que no puede haber ciclos. Si en
   el futuro una operación involucra a dos usuarios, los locks se toman en orden ascendente de id.

**Limitaciones:**
- El lock es **cooperativo**: un escritor externo que inserte en `orders` sin tomarlo no queda protegido. La defensa
  en la base no lo reemplaza: V3 (NOT NULL y CHECK) evita filas inválidas, pero no el sobregiro, y los
  índices de V4 solo aceleran las lecturas.
- Serializa las operaciones del mismo usuario (aceptable: son pocas y cortas).

`AccountLockIT` demuestra con transacciones reales en hilos distintos que:
- una segunda transacción espera hasta que la primera commitea;
- vence el timeout con un error transitorio;
- las cuentas distintas no se bloquean entre sí;
- `MANDATORY` rechaza la llamada sin transacción.

**Pruebas de concurrencia con órdenes reales** (`OrderConcurrencyIT`):
- dos operaciones del mismo usuario arrancan juntas (`CyclicBarrier`), cada una en su propio hilo, transacción y
  conexión;
- casos: dos BUY MARKET y dos BUY LIMIT por el mismo cash; dos SELL MARKET y dos SELL LIMIT por las mismas acciones;
  dos cancelaciones de la misma orden;
- siempre gana exactamente una, y el cash y las acciones disponibles nunca quedan negativos;
- **verificación de que los tests detectan el problema:** con el lock desactivado a propósito, fallan los 4 tests de
  BUY/SELL (se gasta dos veces el mismo saldo). La doble cancelación sigue protegida por el `UPDATE` condicional.
- **Rollback técnico:** `OrderRollbackIT` simula una falla después del `INSERT`. La respuesta es 503 y no queda
  ninguna fila nueva.
