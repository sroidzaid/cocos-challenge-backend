# cocos-challenge-backend

API REST de **portfolio**, **búsqueda de instrumentos** y **gestión de órdenes** (BUY/SELL, MARKET/LIMIT, y
transferencias CASH_IN/CASH_OUT) para el challenge técnico de Cocos. Está construida con **Java 21 + Spring Boot 4** como **monolito modular con arquitectura
hexagonal pragmática**, sobre el esquema PostgreSQL provisto (`database.sql`).

> El enunciado original pide Node.js; durante la entrevista técnica se autorizó resolverlo en Java.

**Qué resuelve:**
- **Portfolio:** valor total de la cuenta, pesos disponibles y posiciones con cantidad, valor de mercado y rendimiento.
- **Búsqueda** de activos por ticker y/o nombre.
- **Órdenes** MARKET y LIMIT, por cantidad o por monto, con estados `NEW`, `FILLED`, `REJECTED` y `CANCELLED`.
- **Transferencias** CASH_IN (ingreso) y CASH_OUT (retiro) de pesos, por el mismo endpoint de órdenes.
- **Saldos derivados del ledger** (`orders`), con reservas para las LIMIT pendientes.
- **Idempotencia persistente** en la creación de órdenes y **control de concurrencia** por cuenta.

---

## Contenido

- [Stack tecnológico](#stack-tecnológico)
- [Requisitos previos](#requisitos-previos)
- [Quick Start](#quick-start)
- [API y Swagger](#api-y-swagger)
- [Tests](#tests)
- [Colección de Postman](#colección-de-postman)
- [Arquitectura](#arquitectura)
- [Reglas de negocio y supuestos financieros](#reglas-de-negocio-y-supuestos-financieros)
- [Órdenes MARKET y LIMIT](#órdenes-market-y-limit)
- [Transferencias CASH_IN y CASH_OUT](#transferencias-cash_in-y-cash_out)
- [Reservas de efectivo e instrumentos](#reservas-de-efectivo-e-instrumentos)
- [Idempotencia y concurrencia](#idempotencia-y-concurrencia)
- [Base de datos y migraciones V2 a V4](#base-de-datos-y-migraciones-v2-a-v4)
- [Limitaciones conocidas y posibles mejoras](#limitaciones-conocidas-y-posibles-mejoras)
- [Documentación detallada](#documentación-detallada)

---

## Stack tecnológico

| Área | Tecnología |
|---|---|
| Lenguaje / runtime | Java 21 |
| Framework | Spring Boot 4.1 (Web MVC, Data JPA, Validation, Actuator) |
| Base de datos | PostgreSQL 16 (driver pgjdbc) |
| Documentación de la API | springdoc-openapi (OpenAPI 3 + Swagger UI) |
| Build | Maven, mediante el Maven Wrapper incluido (`mvnw` / `mvnw.cmd`) |
| Tests | JUnit 5, AssertJ, Mockito, MockMvcTester, Testcontainers (PostgreSQL), ArchUnit |
| Entorno local | Docker Compose (`compose.yml`) |
| Observabilidad | Logs estructurados ECS, correlation id, Actuator health y métricas Micrometer |

## Requisitos previos

- **JDK 21.** No hace falta instalar Maven: el Maven Wrapper lo descarga en la primera ejecución.
- **Docker** (con Docker Compose v2) en ejecución: para la base local y para los tests de integración
  (Testcontainers).
- Puerto **5432** libre para PostgreSQL y **8080** para la aplicación (ambos configurables, ver
  [Ejecución y configuración](docs/running.md)).

## Quick Start

```bash
git clone https://github.com/sroidzaid/cocos-challenge-backend.git
cd cocos-challenge-backend

# 1. Levantar PostgreSQL con el esquema y el seed (database.sql + V2 + V3 + V4)
docker compose up -d --wait        # espera a que la base quede "healthy"

# 2. Ejecutar la aplicación (perfil "local", el default; no requiere variables de entorno)
./mvnw spring-boot:run             # Linux / macOS / Git Bash
.\mvnw.cmd spring-boot:run         # Windows (PowerShell / cmd)
```

Verificar que responde:

```bash
curl http://localhost:8080/actuator/health                       # {"status":"UP",...}
curl "http://localhost:8080/api/v1/instruments?q=banco"
curl http://localhost:8080/api/v1/users/1/portfolio              # totalAccountValue 889756.00 con el seed
```

**Sobre la base local:**
- `compose.yml` levanta `postgres:16-alpine` (contenedor `cocos-db`, base/usuario/password `cocos`) y monta en el init
  del contenedor, en este orden: `database.sql` (V1, original sin cambios),
  `db/migration/V2__create_order_idempotency.sql`, `db/migration/V3__orders_integrity_constraints.sql` y
  `db/migration/V4__lookup_indexes.sql`.
- Los scripts se ejecutan **solo la primera vez**, cuando el volumen `cocos_pgdata` está vacío. En los arranques
  siguientes PostgreSQL detecta la base existente y no los vuelve a correr.
- Para volver al seed original: `docker compose down -v` y luego `docker compose up -d --wait` (borra los datos).
- `docker compose down` detiene la base y conserva los datos.
- Si ya existe un contenedor `cocos-db` creado con `docker run`, eliminarlo antes con `docker rm -f cocos-db`.

Alternativa sin Compose, perfil `cocos` (base externa, solo lectura) y variables opcionales:
[docs/running.md](docs/running.md).

## API y Swagger

Con la aplicación levantada:

| URL | Descripción |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI (probar los endpoints desde el navegador) |
| http://localhost:8080/v3/api-docs | Especificación OpenAPI 3 (JSON) |
| http://localhost:8080/actuator/health | Health check, incluida la base (`/liveness`, `/readiness`) |
| http://localhost:8080/actuator/metrics | Métricas (Micrometer) |

| Método y ruta | Descripción |
|---|---|
| `GET /api/v1/instruments?q={texto}&limit={n}` | Búsqueda parcial e insensible a mayúsculas por ticker o nombre, con último precio y retorno diario |
| `GET /api/v1/users/{userId}/portfolio` | Valor total, pesos disponibles y posiciones con rendimiento |
| `POST /api/v1/users/{userId}/orders` | Crea una orden BUY/SELL o una transferencia CASH_IN/CASH_OUT. Header `Idempotency-Key` (UUID) **obligatorio** |
| `POST /api/v1/users/{userId}/orders/{orderId}/cancel` | Cancela una orden `NEW` |

Ejemplo de creación de orden:

```http
POST /api/v1/users/1/orders
Content-Type: application/json
Idempotency-Key: 4f9c2a1e-8d7b-4c3a-9e6f-1b2d3c4e5f60

{ "instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900.50 }
```
```json
{ "id": 12, "userId": 1, "instrumentId": 47, "side": "BUY", "type": "LIMIT", "status": "NEW",
  "size": 10, "price": 900.50, "datetime": "2026-11-12T10:15:00", "rejectionReason": null }
```

Los errores siguen **RFC 9457** (`application/problem+json`) con un `code` estable y `correlationId`.
Contrato completo, validaciones, ejemplos y códigos de error: **[docs/api.md](docs/api.md)**.

## Tests

```bash
./mvnw test             # unit tests, web slice y ArchUnit (no requiere Docker)
./mvnw clean verify     # + tests de integración (*IT) con Testcontainers (requiere Docker en ejecución)
```
En Windows: `.\mvnw.cmd test` / `.\mvnw.cmd clean verify`.

- **Resultado de referencia de `clean verify`:** 150 tests unitarios (Surefire) y 128 de integración (Failsafe),
  sin fallos.
- Los tests de integración levantan **su propio** PostgreSQL (`postgres:16-alpine`) con Testcontainers, inicializado
  con `database.sql` + V2 + V3 + V4. Nunca usan H2, la base local ni la de Cocos (`ProfileIsolationIT` lo verifica).
- Durante los IT aparecen en el log algunos `WARN … ERROR: duplicate key…`, `lock timeout` o `read-only
  transaction`: son esperados, porque hay tests que provocan esas violaciones a propósito.

Tipos de test, aislamiento de datos y qué cubre cada suite: **[docs/testing.md](docs/testing.md)**.

## Colección de Postman

`postman/cocos-challenge.postman_collection.json`

1. En Postman: **File → Import** y seleccionar el archivo.
2. La variable `baseUrl` es `http://localhost:8080`; ajustarla si la aplicación corre en otro puerto.
3. Correrla **en orden** con el *Collection Runner* contra una base recién inicializada
   (`docker compose down -v` y luego `docker compose up -d --wait`): los valores esperados son los del seed.

Carpetas: Health, Instruments, Portfolio, Orders - create (todas las combinaciones y errores), Idempotency y
Orders - cancel. Cada request tiene tests. La colección genera una `Idempotency-Key` por request; la carpeta de
idempotencia reutiliza una key fija a propósito. No contiene credenciales.

---

## Arquitectura

**Monolito modular, organizado por funcionalidad**, con capas hexagonales dentro de cada módulo:

| Módulo | Responsabilidad |
|---|---|
| `instruments` | Instrumentos y búsqueda por ticker y/o nombre |
| `marketdata` | Última cotización (`close`) y retorno diario |
| `orders` | Creación, validación, ejecución y cancelación de órdenes; saldos derivados del ledger |
| `portfolio` | Valor de la cuenta, posiciones y rendimiento (solo lectura, sin persistencia propia) |
| `users` | Titulares de cuenta y **lock por cuenta** para serializar las escrituras |
| `shared` | Excepciones base, manejo de errores, correlation id, configuración transversal |

```
<module>/
├── domain/                     modelos, enums, invariantes, servicios de dominio (Java puro)
├── application/                casos de uso (transacciones, orquestación)
│   └── port/in | port/out      puertos de entrada (API del módulo) / salida (I/O)
└── infrastructure/
    ├── web/                    controllers REST, DTOs, mappers
    └── persistence/            entidades JPA, repositorios Spring Data, adaptadores
```

- **Reglas verificadas con ArchUnit** (`ArchitectureTest`): el dominio no depende de Spring/JPA; `application` no
  depende de `infrastructure`; un módulo usa otro solo a través de sus puertos de entrada; no hay ciclos.
- **Hexagonal pragmática:** hay puertos solo donde hay I/O o un límite entre módulos. Las entidades JPA están
  separadas del dominio porque reflejan un esquema heredado.
- **Saldos calculados, no materializados:** se derivan siempre de `orders`, como pide el enunciado. No hay drift.
- **JPA + SQL nativo puntual** (`DISTINCT ON`, `FOR NO KEY UPDATE`, update condicional). Sin Lombok.

Trade-offs, persistencia, entidades, puertos y observabilidad: **[docs/architecture.md](docs/architecture.md)**.

## Reglas de negocio y supuestos financieros

- **Cash (`cashBalance`):** Σ `±size × price` de las órdenes `FILLED` (CASH_IN y SELL suman; CASH_OUT y BUY restan).
- **Pesos disponibles (`availableCash`):** `cashBalance` menos el cash reservado por las BUY `NEW`.
- **Posiciones:** se reconstruyen desde las BUY/SELL `FILLED`. `NEW`, `CANCELLED` y `REJECTED` no cuentan.
  CASH_IN/CASH_OUT solo mueven el cash, nunca las posiciones.
- **Último precio:** el `close` de la cotización más reciente (`marketdata.date`).
- **Rendimiento:** solo **no realizado**, contra el **costo promedio ponderado** de la posición actual:
  `(lastPrice − averageCost) / averageCost × 100`. No se calcula rendimiento realizado, histórico ni FIFO: la ganancia
  o pérdida de las ventas ya queda reflejada en el cash. Si el costo no está definido (por ejemplo, una posición
  negativa), `averageCost` y `totalReturnPercent` son `null`: nunca se inventa un costo.
- **Precisión monetaria:** siempre `BigDecimal`. Precios con ≤ 2 decimales (`NUMERIC(10,2)`), cantidades enteras.
  Nada se redondea en silencio: los valores fuera de rango se rechazan con 400. Los porcentajes se exponen con 2
  decimales `HALF_EVEN`.
- **Datos del seed:** se respetan tal cual, incluida la posición negativa de BMA (−10) del usuario 1, que se muestra
  con `valuationStatus = NEGATIVE_QUANTITY` y un warning.
- **Sin cotización:** la búsqueda devuelve precio `null`; el portfolio excluye la posición del total y marca
  `valuationComplete = false`; una orden MARKET responde 422.

Decisiones funcionales numeradas (D1–D12, C1–C5), precisión monetaria y posiciones negativas:
**[docs/business-rules.md](docs/business-rules.md)**.

## Órdenes MARKET y LIMIT

`p` = precio de ejecución (MARKET: `close` más reciente; LIMIT: precio límite). `q` = `quantity`, o
`floor(amount / p)` si la orden es por monto.

| Orden | Chequeo | Alcanza | No alcanza |
|---|---|---|---|
| BUY MARKET | `q × p ≤ availableCash` | `FILLED` | `REJECTED` `INSUFFICIENT_FUNDS` |
| SELL MARKET | `q ≤ availableQuantity` | `FILLED` | `REJECTED` `INSUFFICIENT_HOLDINGS` |
| BUY LIMIT | `q × p ≤ availableCash` | `NEW`, reserva `q × p` de cash | `REJECTED` `INSUFFICIENT_FUNDS` |
| SELL LIMIT | `q ≤ availableQuantity` | `NEW`, reserva `q` acciones | `REJECTED` `INSUFFICIENT_HOLDINGS` |

- **`REJECTED` es un resultado de negocio, no un error:** se persiste y responde `201` con `rejectionReason`.
- **Una LIMIT siempre queda `NEW`**, aunque cruce el mercado: no hay matching engine, así que no existe `NEW → FILLED`.
- **Una SELL nunca abre una posición negativa.**
- **Cancelar** (`POST …/cancel`) solo es válido desde `NEW` (si no, `409 ORDER_NOT_CANCELLABLE`) y libera la reserva.
- Errores en los que no se persiste nada: usuario o instrumento inexistente (404), instrumento `MONEDA`, MARKET sin
  cotización o monto que no alcanza para 1 acción (422), cuenta ocupada (503).

```
(nueva) ── MARKET, alcanza ───────────► FILLED
(nueva) ── LIMIT, alcanza ────────────► NEW ── cancel ──► CANCELLED
(nueva) ── no alcanza (MARKET/LIMIT) ─► REJECTED
```

Detalle completo: [docs/api.md](docs/api.md#enviar-una-orden--post-apiv1usersuseridorders).

## Transferencias CASH_IN y CASH_OUT

Se crean con el mismo `POST /api/v1/users/{userId}/orders` (mismo lock, idempotencia y transacción que las órdenes),
enviando solo `side` y `amount` en **pesos enteros**:

```http
POST /api/v1/users/1/orders
Content-Type: application/json
Idempotency-Key: 0b8e7c4a-2f61-4d3e-9a57-6c1d2e3f4a5b

{ "side": "CASH_OUT", "amount": 3000 }
```
```json
{ "id": 12, "userId": 1, "instrumentId": 66, "side": "CASH_OUT", "type": "MARKET", "status": "FILLED",
  "size": 3000, "price": 1.00, "datetime": "2026-11-17T10:15:00", "rejectionReason": null }
```

| Transferencia | Chequeo | Alcanza | No alcanza |
|---|---|---|---|
| CASH_IN | — | `FILLED`: el cash sube `amount` | — |
| CASH_OUT | `amount ≤ availableCash` | `FILLED`: el cash baja `amount` | `REJECTED` `INSUFFICIENT_FUNDS` (201, se persiste) |

- Se registran como en el seed: instrumento ARS, `size = amount`, `price = 1`, `type = MARKET`. Sin columnas nuevas.
- El retiro se valida contra `availableCash`: **no se puede retirar el cash reservado por BUY LIMIT `NEW`**.
- `instrumentId`, `type`, `quantity` y `price` no aplican (400). `amount` es obligatorio, entero sin decimales,
  entre 1 y 2.147.483.647 (`size INT`); si no, 400. No se admiten centavos.

Detalle: [docs/api.md](docs/api.md#transferencias-cash_in--cash_out).

## Reservas de efectivo e instrumentos

- Una **BUY LIMIT `NEW`** reserva `size × price` de cash: `availableCash = cashBalance − Σ NEW BUY`.
- Una **SELL LIMIT `NEW`** reserva `size` acciones: `availableQuantity = neto FILLED − Σ NEW SELL` del instrumento.
- Las reservas **no modifican** el saldo contable, las posiciones ni el valor total (siguen siendo solo `FILLED`).
- **Cancelar libera la reserva automáticamente**, porque la orden deja de sumar como `NEW`. No hace falta ninguna
  columna nueva: la reserva se deriva del estado de las órdenes.
- Portfolio y creación de órdenes usan **la misma consulta** (`LedgerQuery.reservations`).

Con el seed, el usuario 1 tiene `cashBalance` 753.000,00 y `availableCash` 627.500,00. Detalle:
[docs/business-rules.md](docs/business-rules.md#reservas-de-órdenes-pendientes-regla-funcional-compartida-d2).

## Idempotencia y concurrencia

**Idempotencia** (`Idempotency-Key`, UUID obligatorio en `POST /orders`):
- Misma key + mismo request → `201` con **el resultado original de la creación** y header `Idempotent-Replayed: true`.
  No se crea otra orden.
- Misma key + otro request → `409 IDEMPOTENCY_KEY_REUSED`. Las keys son por usuario.
- La key se guarda en `order_idempotency` (V2) **en la misma transacción** que la orden, con
  `UNIQUE (user_id, idempotency_key)` como red de seguridad. Sobrevive a reinicios y a múltiples instancias.

**Concurrencia** (evita gastar dos veces el mismo saldo o vender dos veces las mismas acciones):
- Toda escritura del ledger abre una transacción `READ COMMITTED` cuyo **primer paso** es lockear la fila del usuario
  (`SELECT … FROM users … FOR NO KEY UPDATE`), con timeout configurable → `503`.
- La cancelación usa un `UPDATE … WHERE status = 'NEW'` condicional.
- El portfolio lee en una transacción `readOnly` + `REPEATABLE READ` (snapshot consistente) y no toma el lock.
- Verificado con transacciones reales en paralelo (`AccountLockIT`, `OrderConcurrencyIT`,
  `OrderIdempotencyConcurrencyIT`, `OrderIdempotencyRestartIT`).

Alternativas evaluadas (`@Version`, `SERIALIZABLE`, advisory locks), garantías y limitaciones:
**[docs/idempotency-and-concurrency.md](docs/idempotency-and-concurrency.md)**.

## Base de datos y migraciones V2 a V4

El esquema original (`database.sql`) se usa **sin cambios** en columnas ni datos. Se agregaron tres scripts
versionados en `db/migration/`, con nombres compatibles con Flyway:

| Script | Cambio | Por qué |
|---|---|---|
| `V2__create_order_idempotency.sql` | Tabla nueva `order_idempotency` (aditiva) | La idempotencia durable no se puede lograr de otra forma |
| `V3__orders_integrity_constraints.sql` | `NOT NULL` y 5 `CHECK` sobre `orders` | La base rechaza filas que corromperían los saldos, también si se escriben por fuera de la API |
| `V4__lookup_indexes.sql` | Índices `orders(userid, status)` y `marketdata(instrumentid, date DESC, id DESC)` | Los saldos y la última cotización se calculan en cada request; sin índices, el costo crece con el total de la tabla |

- **La aplicación nunca ejecuta DDL:** no hay Flyway/Liquibase, `spring.sql.init.mode=never` y Hibernate corre con
  `ddl-auto: validate`. Si falta V2, la aplicación no arranca (fail-fast).
- Cada script es transaccional (`BEGIN … COMMIT`), se corre con `ON_ERROR_STOP` y no usa `IF NOT EXISTS`.
- Se aplican automáticamente en una base nueva (Docker Compose y Testcontainers). En una base existente se aplican a
  mano, en orden y una sola vez.
- `SchemaIntegrityIT` verifica que el esquema sea exactamente V1 + V2 + V3 + V4.
- **Base externa (perfil `cocos`):** solo lectura por defecto. Nada de esto se ejecutó sobre esa base; el
  procedimiento manual está documentado.

Hallazgos del DDL provisto, procedimiento para la base externa y propuestas no aplicadas:
**[docs/database.md](docs/database.md)**.

## Limitaciones conocidas y posibles mejoras

**Limitaciones:**
- **Transferencias en pesos enteros:** sin centavos ni otras monedas, y sin conciliación con un medio de pago real:
  un CASH_IN se acredita al registrarse.
- **Sin matching engine:** las LIMIT quedan `NEW` hasta que se cancelan (fuera de alcance según el enunciado).
- **Sin autenticación:** el usuario se identifica por `userId` en el path (fuera de alcance según el enunciado).
- **Esquema original:** transferencias sin centavos (`size INT`), precio máximo 99.999.999,99 (`NUMERIC(10,2)`),
  `rejectionReason` no se persiste en `orders` (se guarda en `order_idempotency`).
- **Lock cooperativo:** un escritor externo que inserte en `orders` sin tomar el lock de la cuenta no queda
  protegido.
- **Keys de idempotencia sin expiración:** crecen al ritmo del ledger (una fila por orden).
- **Saldos O(n órdenes del usuario):** se recalculan desde `orders` en cada request. V4 indexa `orders(userid, status)`,
  así que el costo depende de las órdenes del usuario y no del total de la tabla.

**Posibles mejoras:**
- `UNIQUE` en `marketdata(instrumentid, date)` e `instruments(ticker)`: propuestos en
  `docs/schema-proposals/02-unique-constraints.sql`, **no aplicados**.
- Métricas de negocio (contadores de órdenes por `side`/`type`/`status`).
- Adoptar Flyway con `baselineVersion=1` si se acepta que la aplicación gestione el esquema.
- Snapshots o saldos materializados si el volumen lo exigiera.

---

## Documentación detallada

| Documento | Contenido |
|---|---|
| [docs/api.md](docs/api.md) | Contrato de cada endpoint, validaciones, fórmulas del portfolio, costo promedio, estados y semántica HTTP |
| [docs/business-rules.md](docs/business-rules.md) | Requisitos, reservas, decisiones funcionales, transferencias, precisión monetaria y posiciones negativas |
| [docs/idempotency-and-concurrency.md](docs/idempotency-and-concurrency.md) | Idempotencia, consistencia de lectura del portfolio y estrategia de concurrencia |
| [docs/architecture.md](docs/architecture.md) | Módulos, reglas de arquitectura, trade-offs, persistencia y observabilidad |
| [docs/database.md](docs/database.md) | Esquema, migraciones V2 a V4, hallazgos del DDL, propuestas y limitaciones |
| [docs/running.md](docs/running.md) | Perfiles (`local`, `cocos`, `test`), base sin Compose, variables de entorno |
| [docs/testing.md](docs/testing.md) | Estrategia de testing y aislamiento de datos |

### Estructura del repositorio

```
├── compose.yml                 PostgreSQL local (database.sql + V2 + V3 + V4)
├── database.sql                Esquema y seed originales provistos por Cocos (V1, sin cambios)
├── db/migration/               V2 (idempotencia), V3 (integridad de orders) y V4 (índices)
├── docs/                       Documentación detallada y propuestas de esquema no aplicadas
├── postman/                    Colección de requests con tests
├── src/main/java/…             Código fuente (módulos instruments, marketdata, orders, portfolio, users, shared)
├── src/test/java/…             Tests unitarios, de integración y de arquitectura
├── .env.example                Variables de entorno de referencia (sin secretos)
└── mvnw, mvnw.cmd, .mvn/       Maven Wrapper
```
