# Base de datos

[← Volver al README](../README.md)

El enunciado permite modificar, agregar o ajustar tablas si se justifica. Todos los requisitos funcionales se cumplen
con el modelo provisto: no se renombra ni se agrega ninguna columna a las tablas originales, y no se modifica ningún
dato. Se aplicaron tres cambios, justificados (ver [Evolución del esquema](#evolución-del-esquema)):
- **V2: tabla nueva `order_idempotency`.** La idempotencia durable no se puede lograr de otra forma.
- **V3: NOT NULL y CHECK sobre `orders`.** La base rechaza filas que corromperían los saldos, también cuando se
  escriben por fuera de la API.
- **V4: índices `orders(userid, status)` y `marketdata(instrumentid, date DESC, id DESC)`.** Los saldos y la
  última cotización se calculan en cada request; sin índices, cada consulta recorre la tabla completa.

- **Fuentes del esquema:** `database.sql` (V1, sin cambios) más `db/migration/` (V2, V3, V4).
- **La aplicación no ejecuta DDL:** no hay herramienta de migraciones, `spring.sql.init.mode=never` y Hibernate corre
  con `ddl-auto=validate`, que solo verifica que el mapeo coincida con el esquema.
- **`SchemaIntegrityIT`** verifica, con la aplicación levantada, que el esquema sea exactamente V1 + V2 + V3 + V4:
  - las tablas originales, con los mismos nombres y tipos de columna, PK, FK, índices y secuencias;
  - la nulabilidad y los 5 CHECK que V3 agrega a `orders`;
  - la tabla nueva, tal como la define V2;
  - los dos índices de V4, con sus columnas y direcciones exactas.

## Evolución del esquema

**Mecanismo:** scripts SQL versionados en `db/migration/`, aplicados explícitamente. La aplicación nunca los
ejecuta.
- **Nombres compatibles con Flyway** (`V2__…`, donde V1 es `database.sql`). Adoptar Flyway más adelante es
  directo (`baselineVersion=1`), pero se descartó por ahora para que ningún arranque modifique el esquema.
- **Cada script es transaccional (`BEGIN … COMMIT`) y se corre con `ON_ERROR_STOP=1`.**
- **No se usa `IF NOT EXISTS`:** si un objeto ya existe (porque se aplicó dos veces o hay un conflicto), el script
  falla y hace rollback, en lugar de aceptar en silencio un esquema que podría no coincidir.
- **Arranque sin V2:** `ddl-auto: validate` lo impide (`Schema validation: missing table [order_idempotency]`). Es un
  fail-fast explícito.
- **Arranque sin V3:** la aplicación arranca igual, porque `validate` no revisa NOT NULL ni CHECK. V3 es un
  endurecimiento: la aplicación ya valida todo lo que escribe y falla explícitamente ante valores que no puede
  interpretar.
- **Arranque sin V4:** la aplicación arranca igual (`validate` no revisa índices). Las mismas consultas corren con
  seq scans, que con el volumen del seed no se notan, pero degradan a medida que crece el ledger.

| Entorno | Cómo se aplica |
|---|---|
| Tests | Testcontainers copia `database.sql`, `V2`, `V3` y `V4` al init del contenedor, así que el entorno es reproducible en cada corrida |
| Local, base nueva | `docker compose up -d` (o `docker run`) montando `01-database.sql`, `02-V2…`, `03-V3…` y `04-V4…` (ver [Base de datos local](../README.md#quick-start)) |
| Local, base existente | `psql -v ON_ERROR_STOP=1 … -f db/migration/<script>` con cada migración que falte, en orden y una sola vez |
| Base externa (`cocos`) | Procedimiento manual de abajo. La aplicación nunca ejecuta DDL ahí, y nada de esto se ejecutó sobre esa base |

**Actualizar la base externa del perfil `cocos`:**
1. **Verificar el estado** con un usuario de solo lectura:
   - **V2:** `SELECT to_regclass('public.order_idempotency');`. `NULL` significa que falta aplicarla.
   - **V3:** `SELECT count(*) FROM pg_constraint WHERE conrelid = 'orders'::regclass AND contype = 'c';`. `0` significa
     que falta aplicarla; `5` significa que ya se aplicó.
   - **V4:** `SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_orders_user_status',
     'idx_marketdata_instrument_date');`. `0` significa que falta aplicarla; `2` significa que ya se aplicó.
2. **Prevalidar V3** con la consulta que está en el encabezado de
   `db/migration/V3__orders_integrity_constraints.sql`. Cuenta las filas que violarían cada regla (NULL, `side`, `type`
   o `status` desconocidos, `size ≤ 0`, `price ≤ 0`).
   - **Todo en 0:** V3 se puede aplicar.
   - **Algo distinto de 0:** no se aplica, y **no se modifican ni se borran datos** para que pase. Se analizan las
     filas y se decide aparte. Mientras tanto la aplicación sigue funcionando con sus defensas propias.
3. **Aplicar**, en orden (V2, V3 y V4), con un usuario con permiso de DDL, no el de la aplicación, dentro de una
   ventana acordada:
   `psql -v ON_ERROR_STOP=1 -h <host> -U <dba> -d <db> -f db/migration/V2__create_order_idempotency.sql` y lo mismo
   con `V3__orders_integrity_constraints.sql`.
   - Las credenciales se ingresan por prompt o `PGPASSWORD` en la sesión, nunca en archivos versionados.
   - V3 toma un lock exclusivo sobre `orders` mientras verifica las filas, que es breve con este volumen.
   - V4 bloquea las escrituras sobre `orders` y `marketdata` mientras construye los índices. En una base con tráfico,
     ejecutar cada sentencia como `CREATE INDEX CONCURRENTLY`, fuera de una transacción.
4. **Permisos:** si la aplicación usa un rol distinto, darle `SELECT, INSERT` sobre `order_idempotency` y `USAGE`
   sobre `order_idempotency_id_seq`.
5. **Verificar** que `\d order_idempotency` y `\d orders` coincidan con los scripts, y arrancar la aplicación.

**Qué cambia cada migración:**
- **V2 es aditiva:** no toca ninguna tabla ni dato existente. No necesita backfill, porque las órdenes anteriores no
  tienen key: no se crearon con una.
- **V3 solo agrega restricciones a `orders`:** no cambia columnas, tipos ni datos. Los datos originales del seed las
  cumplen todas: el contenedor de tests aplica V3 sobre ellos con `ON_ERROR_STOP` en cada corrida.

## Integridad de `orders` (V3)

| Regla | Qué evita | Compatibilidad con el dominio |
|---|---|---|
| `NOT NULL` en `instrumentid`, `userid`, `size`, `side`, `type`, `status`, `datetime` | Filas que los agregados de saldo no pueden interpretar | Toda orden tiene instrumento: BUY/SELL el activo, CASH_IN/CASH_OUT el ARS |
| `side IN ('BUY','SELL','CASH_IN','CASH_OUT')` | Movimientos desconocidos, que `SUM` ignoraría en silencio | Los 4 lados que existen |
| `type IN ('MARKET','LIMIT')` | Tipos desconocidos | Las transferencias usan `MARKET`, por la convención del seed |
| `status IN ('NEW','FILLED','REJECTED','CANCELLED')` | Estados desconocidos | Una REJECTED también se guarda |
| `size > 0` | Cantidades nulas o negativas que inviertan un movimiento | Acciones enteras ≥ 1. En transferencias, el monto ≥ 1 |
| `price IS NULL OR price > 0` | Precios negativos o en cero | MARKET usa el close, LIMIT el precio límite y las transferencias `price = 1`. `price` sigue siendo nullable, como en el modelo provisto |

- **No agrega reglas de negocio nuevas:** formaliza en la base lo que la aplicación ya exigía.
- **Defensas en Java:** se mantienen porque una base sin V3, como la de cocos mientras no se aplique, puede tener
  datos inválidos. Sus tests simulan esa base quitando la constraint dentro de una transacción que se revierte
  (`PreV3Schema`).
- **`OrderPersistenceAdapterIT` verifica las dos caras:** que la base rechaza cada violación (14 casos) y que acepta
  cada tipo de orden que escribe la aplicación.

## Tabla `order_idempotency` (V2)

| Columna | Tipo | Por qué |
|---|---|---|
| `id` | `BIGSERIAL` PK | Identificador interno |
| `user_id` | `INT NOT NULL` FK → `users` | Las keys son por usuario |
| `idempotency_key` | `UUID NOT NULL` | La key del cliente. El tipo nativo valida el formato y ocupa 16 bytes |
| `request_hash` | `VARCHAR(64) NOT NULL`, CHECK hex | SHA-256 de la forma canónica del request. Distingue un reintento de un reuso de la key |
| `order_id` | `INT NOT NULL` FK → `orders`, UNIQUE | La orden creada. No hay estado "en proceso", porque se inserta junto con la orden |
| `order_status` | `VARCHAR(20) NOT NULL`, CHECK `NEW`/`FILLED`/`REJECTED` | **Snapshot mínimo del resultado:** el único campo de la respuesta que cambia después (`NEW → CANCELLED`) |
| `rejection_reason` | `VARCHAR(30)`, CHECK coherente con `order_status` | La otra parte del snapshot: `orders` no tiene dónde guardarlo |
| `created_at` | `TIMESTAMP NOT NULL` | Trazabilidad. Mismo `Clock` y la misma zona horaria que `orders.datetime` |

- **Constraints:**
  - `UNIQUE (user_id, idempotency_key)`: es la garantía en la base;
  - `UNIQUE (order_id)`: una key por orden;
  - 3 CHECK de coherencia.
- **Snapshot mínimo:** el resto de la respuesta original (instrumento, lado, tipo, cantidad, precio, fecha) no cambia
  después de la creación y se lee de `orders`. Así no se duplica ningún dato.

## Hallazgos del DDL provisto (`database.sql`)

| Hallazgo | Consecuencia |
|---|---|
| PostgreSQL. Las columnas camelCase no están entre comillas, así que se guardan en minúsculas (`instrumentid`, `userid`, `previousclose`, `accountnumber`) | `PhysicalNamingStrategyStandardImpl` + nombres de columna explícitos. La estrategia por defecto de Spring generaría `instrument_id` |
| `marketdata` tiene la columna **`date`** (`DATE`), no `datetime` como dice el enunciado | Prevalece el DDL. Última cotización = fila con mayor `date` por instrumento |
| `orders.size INT`, `orders.price NUMERIC(10,2)` | Acciones enteras. Precio con ≤ 2 decimales y ≤ 99.999.999,99. Las transferencias se registran con `size` = monto en pesos enteros y `price = 1` |
| `orders.datetime TIMESTAMP` sin zona horaria | Se usa un `Clock` inyectable con zona de negocio configurable |
| No hay CHECKs, NOT NULL, UNIQUE ni índices secundarios; las FK no están indexadas | La aplicación escribe solo valores válidos (enums, rangos). Al leer, **falla explícitamente** ante valores que no puede interpretar, en vez de ignorarlos (ver [Persistencia](architecture.md#persistencia)). V3 agrega NOT NULL y CHECK a `orders` y V4 agrega los índices de consulta. Los UNIQUE quedan propuestos (ver abajo) |
| ARS = instrumento `MONEDA` (id 66 en el seed solo por el orden de inserción), sin marketdata | El cash se identifica por `type='MONEDA'` + `ticker='ARS'`, nunca por id. Vale 1 y no se lista como posición |
| Hay instrumentos sin marketdata (p. ej. PGR id 3, IRCP id 10) y filas con `open/high/low` NULL | La ausencia de cotización es un caso real |
| `previousclose` del id 22 se insertó como `1047.16600000` y PostgreSQL lo guardó como **1047.17** | PG redondea en silencio al escribir en `NUMERIC(10,2)`. Por eso la aplicación rechaza los precios con más de 2 decimales **antes** de escribir |
| Existe una orden `LIMIT` en estado `FILLED` (id 9) y una REJECTED guardada con price (id 6) | Para los saldos solo importa el `status` |
| El usuario 1 tiene BMA (id 31): compra 20 FILLED (12:51) y venta 30 FILLED (15:13) del 13/07 → **posición −10**; la venta acreditó 45.900 | No se asume que sea un error (podría ser una venta en corto registrada por otro canal; el enunciado no lo aclara). No se corrige ni se oculta: las consultas la devuelven tal como está persistida. La API no permite abrir posiciones negativas nuevas (ver D7) |

## Mejoras de esquema propuestas (no aplicadas)

Están en `docs/schema-proposals/`, fuera del classpath, y nada las ejecuta. La implementación **no depende** de
ellas. La antigua propuesta 01 (integridad de `orders`) ya se aplicó como `V3`, y los índices de la propuesta 02 como
`V4`.

| Propuesta | Qué agrega | Problema que resolvería | Alternativa |
|---|---|---|---|
| `02-unique-constraints.sql` | `UNIQUE(instrumentid, date)` en `marketdata`; `UNIQUE(ticker)` en `instruments` | Con el UNIQUE, la "última cotización" sería determinista por construcción. El ticker es la clave de negocio | Índice trigram (`pg_trgm`) para `ILIKE`: con 66 instrumentos no se justifica |

**Por qué los UNIQUE quedaron como propuesta** (los índices se aplicaron en V4, de cara a pruebas de carga):
- **Los dos UNIQUE:** pueden fallar con los datos de una base externa (duplicados), y hoy esos casos ya se detectan
  al leer: ticker duplicado → error de integridad; cotizaciones repetidas → desempate determinista por `id`.
- **Cómo adoptarla:** se convierte en `db/migration/V5__…` con el mismo procedimiento.

**Qué no se propone, y por qué:**
- **Saldos o posiciones materializadas:** el enunciado pide calcular desde `orders`, y una segunda fuente de verdad
  introduce riesgo de drift.
- **Renombrar columnas camelCase:** rompería la compatibilidad con `database.sql` y no aporta nada funcional.
- **Columna `version` para locking optimista:** no protege, porque el invariante de fondos depende de la suma de
  muchas filas (ver D3).

**Datos existentes:** se respetan tal cual, incluida la posición negativa de BMA (ver D7).

## Limitaciones conocidas del esquema original
- **Transferencias sin centavos:** `size INT` obliga a montos enteros. El seed usa `price = 1` y `size` = monto
  (convención observada, no regla del enunciado).
- **Precio:** `NUMERIC(10,2)` limita el precio a 99.999.999,99.
- **Duplicados posibles:**
  - no hay UNIQUE en `instruments.ticker`: si un ticker se repite, la búsqueda por ticker falla con un error de
    integridad en vez de elegir uno al azar;
  - no hay UNIQUE en `marketdata (instrumentid, date)`: ante dos cotizaciones del mismo día se toma la de mayor `id`.
- **FK sin índice propio:** `orders.instrumentid` no está indexada. Las consultas por usuario usan
  `idx_orders_user_status` y las de cotizaciones `idx_marketdata_instrument_date` (V4).
- **Valores fuera del seed** en la base de Cocos (otros `type`, `side` o `status`) harían fallar las lecturas de
  forma explícita. Conviene verificarlos antes en modo solo lectura:
  `SELECT DISTINCT side, type, status FROM orders; SELECT DISTINCT type FROM instruments;`
- **Idempotencia:** resuelta con la tabla `order_idempotency` (V2, D6). Un reintento con la misma
  `Idempotency-Key` no crea otra orden.
- **Motivo del rechazo:** `orders` no tiene una columna para guardarlo. Se guarda en `order_idempotency`, que es lo
  que permite reproducir la respuesta original de un replay.
- **Sin matching engine:** las órdenes LIMIT quedan `NEW` hasta que se cancelan.
