# Arquitectura y persistencia

[← Volver al README](../README.md)

## Monolito modular, organizado por funcionalidad

| Módulo | Responsabilidad |
|---|---|
| `instruments` | Instrumentos y búsqueda por ticker y/o nombre |
| `marketdata` | Última cotización (`close`) y retorno diario (`close` vs `previousClose`) |
| `orders` | Creación, validación, ejecución y cancelación de órdenes; transferencias `CASH_IN`/`CASH_OUT` (mismo endpoint, ver D12); saldos derivados del ledger |
| `portfolio` | Valor de la cuenta, pesos disponibles, posiciones y rendimiento (solo lectura; sin persistencia propia) |
| `users` | Titulares de cuenta: búsqueda y **lock por cuenta** para serializar las escrituras del ledger |
| `shared` | Excepciones base, manejo de errores, correlation id, configuración transversal, guardas de persistencia |

Cada módulo se divide en capas:

```
<module>/
├── domain/                     modelos, enums, invariantes, servicios de dominio (Java puro)
├── application/                casos de uso (transacciones, orquestación)
│   └── port/in | port/out      puertos de entrada (API del módulo) / salida (I/O)
└── infrastructure/
    ├── web/                    controllers REST, DTOs, mappers
    └── persistence/            entidades JPA, repositorios Spring Data, adaptadores
```

Dependencias entre módulos (sin ciclos): `instruments → marketdata`; `orders → users, instruments, marketdata`;
`portfolio → users, orders, instruments, marketdata`.

## Reglas verificadas automáticamente (`ArchitectureTest`, ArchUnit)
- El dominio no depende de Spring, JPA, Hibernate ni infraestructura.
- `application` no depende de `infrastructure`.
- Un módulo usa otro **solo** a través de sus puertos de entrada (`application.port.in`) y de los tipos de dominio
  que estos exponen.
- No hay ciclos entre módulos.

## Trade-offs principales
- **Monolito modular vs. microservicios:** el dominio es chico y la consistencia entre órdenes y saldos es central.
  Un monolito da transacciones ACID sin coordinación distribuida. Los límites de módulos verificados dejan abierta
  una extracción futura.
- **Hexagonal pragmática:** hay puertos solo donde hay I/O o un límite entre módulos. No usamos interfaces para
  mappers, calculadores ni controllers. Las entidades JPA están separadas del dominio porque reflejan un esquema
  heredado (columnas en minúsculas, strings sin restricciones) que no queremos filtrar a las reglas de negocio.
- **Saldos calculados vs. materializados:** el enunciado pide derivar tenencias y pesos de la tabla `orders`.
  Siempre es consistente y no tiene drift, a cambio de un costo O(n órdenes por usuario). El índice
  `orders(userid, status)` (V4) lo mitiga. Snapshots o saldos materializados quedan como evolución si el volumen lo exige.
- **Esquema original sin herramienta de migraciones:** primero se implementan todos los requisitos sobre la base tal
  como la entrega Cocos. Las mejoras de esquema se evalúan por separado. Una herramienta como Flyway agregaría su
  propia tabla de historial a la base, y eso ya sería una modificación.
- **JPA + SQL nativo puntual:** JPA para el CRUD; SQL nativo donde es más claro o necesario (`DISTINCT ON` para
  la última cotización, `FOR UPDATE`, update condicional para cancelar).
- **Sin Lombok:** usamos records para el dominio y los DTOs; las pocas entidades JPA no lo justifican.

## Persistencia

### Arquitectura
```
caso de uso / otro módulo ──► puerto de entrada (application.port.in)   ← único punto de acceso entre módulos
                                   │  servicio de lectura @Transactional(readOnly = true)
                                   ▼
                              puerto de salida (application.port.out)    ← interfaz definida por la aplicación
                                   ▼
            adaptador (infrastructure.persistence) ──► Spring Data JPA ──► PostgreSQL (esquema original)
                    │  mapper entidad ↔ dominio (records puros)
```
- **Entidades JPA separadas del dominio:** las entidades son un espejo exacto de las tablas (nombres en minúsculas,
  strings sin restricciones, todo nullable). El dominio usa records y enums. La traducción vive en mappers dentro
  de cada adaptador.
- **Portfolio no tiene persistencia propia:** consume los puertos de entrada de `orders`, `instruments` y
  `marketdata`. Así la fórmula del cash existe una sola vez.

### Relaciones (FK existentes en `database.sql`)
```
users (id) 1 ──< orders (userid)
instruments (id) 1 ──< orders (instrumentid)
instruments (id) 1 ──< marketdata (instrumentid)
```
- **Las FK se mapean como IDs escalares, no como `@ManyToOne`.** La integridad la garantiza la base.
  - Sin asociaciones no hay carga lazy accidental, N+1 ni cascadas (no se usa `CascadeType`).
  - Es el patrón de "referencia por id entre agregados".
- **Los datos relacionados se cargan en batch con consultas explícitas:** `findAllByIds` para instrumentos y una
  sola query de últimas cotizaciones para N instrumentos.

### Entidades
| Entidad | Tabla | Mapeo |
|---|---|---|
| `UserEntity` | `users` | `id Integer` (SERIAL = int4), `email`, `accountnumber` |
| `InstrumentEntity` | `instruments` | `type` como `String`; el mapper lo convierte a `InstrumentType {ACCIONES, MONEDA}` |
| `MarketDataEntity` | `marketdata` | Todas las columnas; `NUMERIC(10,2)` → `BigDecimal(10,2)`; `date` → `LocalDate` |
| `OrderEntity` | `orders` | `side/type/status` como `String` → enums de dominio; `price` → `BigDecimal(10,2)`; `datetime` → `LocalDateTime` |

**Decisiones de mapeo:**
- **`Integer` para los ids:** las columnas son `int4`, y un `Long` haría fallar `ddl-auto=validate`.
- **Enums:**
  - la entidad guarda el string tal como está almacenado y el mapper lo convierte;
  - ante un valor desconocido (el esquema no tiene CHECKs) se lanza `PersistedDataIntegrityException` con el id de
    la fila, en vez de ignorarla o calcular un saldo incorrecto;
  - se descarta `@Enumerated`: produciría errores opacos de Hibernate.
- **Dinero:**
  - siempre `BigDecimal`, nunca `double` ni `float`;
  - al escribir, `MonetaryColumns` **rechaza** valores con más de 2 decimales o fuera de `NUMERIC(10,2)`. Nunca
    redondea, para no delegar el redondeo silencioso en PostgreSQL.

### Puertos y consultas
| Módulo | Puerto de salida | Consultas |
|---|---|---|
| users | `UserRepositoryPort` | `findById`; `lockById` → `SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE`, con `lock_timeout` local a la transacción |
| instruments | `InstrumentRepositoryPort` | Por id; batch por ids; ticker exacto case-insensitive; búsqueda parcial (`ILIKE … ESCAPE`) en ticker **o** nombre, sin `MONEDA`, por relevancia y con límite; `findLocalCurrency` (`MONEDA` + `ARS`) |
| marketdata | `MarketDataPort` | Última cotización por instrumento o batch (`DISTINCT ON (instrumentid) … ORDER BY date DESC, id DESC`); historial |
| orders | `OrderRepositoryPort` | `save`; por id **y** usuario; por usuario; por usuario y estados (FILLED = ejecutadas, NEW = pendientes); orden cronológico `datetime, id` |
| orders | `LedgerQueryPort` | `cashBalance`, `netPositions` y `reservations` agregados en SQL (ver abajo) |

**Puertos de entrada** (bridges de solo lectura entre módulos, no casos de uso):
- `UserLookup` y `AccountLock` (users);
- `InstrumentQuery` (instruments) y el caso de uso `SearchInstrumentsUseCase` (búsqueda + última cotización en batch);
- `MarketDataQuery` (marketdata);
- `LedgerQuery` (orders): cash, posiciones netas, reservas y órdenes FILLED;
- `GetPortfolioUseCase` (portfolio).

**Agregados del ledger:**
- El cash es la suma de `±size × price` sobre las órdenes FILLED: CASH_IN y SELL suman; CASH_OUT y BUY restan.
- Las cantidades netas son la suma de `±size` de BUY/SELL FILLED por instrumento.
- **Se calculan en la base:** la aritmética NUMERIC es exacta, viaja una fila por instrumento y la fórmula vive en un
  solo lugar, reutilizado por portfolio y por la validación de órdenes.
- **Defensa ante datos anómalos:** cada query cuenta las filas FILLED que no puede interpretar (side desconocido,
  size o price NULL), que `SUM` ignoraría en silencio. Si encuentra alguna, el adaptador falla en vez de devolver un
  saldo incorrecto.
- Las posiciones se devuelven **tal como están persistidas**, incluidas las negativas (BMA −10) y las que suman cero.
  El portfolio las presenta según [Posiciones negativas](business-rules.md#posiciones-negativas).
- **Reservas (D2):**
  - el cash reservado es la suma de `size × price` de las órdenes **NEW BUY**;
  - las acciones reservadas son la suma de `size` de las **NEW SELL**, por instrumento.
  - Una fila NEW que no se puede interpretar como reserva (side distinto de BUY/SELL, o size, price o instrumento
    NULL) hace fallar la consulta.
- Con el seed: usuario 1 → cash **753.000,00**, PAMP 40, METR 500, BMA −10, cash reservado 125.500,00. Estos valores
  son el oráculo de `LedgerQueryAdapterIT`.


## Observabilidad
- **Logs estructurados** JSON (ECS) nativos de Spring Boot. Usar el perfil `local` para leerlos como texto.
- **Correlation id:** se reutiliza el header `X-Correlation-Id` entrante (si es válido) o se genera uno. Se agrega
  al MDC (va en cada línea de log), se devuelve en la respuesta y se incluye en los errores.
- **Health checks:** `/actuator/health` con liveness y readiness, e incluye la DB.
- **Métricas:** Micrometer vía Actuator (latencia HTTP incluida). No hay métricas de negocio propias (por ejemplo, contadores de
  órdenes por `side`/`type`/`status`): queda como mejora.
