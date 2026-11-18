# Estrategia de testing

[← Volver al README](../README.md)

```powershell
.\mvnw.cmd test            # unit tests + ArchUnit (no requiere Docker)
.\mvnw.cmd clean verify    # + tests de integración (*IT) con Testcontainers (requiere Docker en ejecución)
```
Los tests de integración nunca se conectan a la base local ni a la de Cocos: usan su propio contenedor y el perfil
`test`, y `ProfileIsolationIT` lo verifica.

| Tipo | Herramienta | Alcance |
|---|---|---|
| Unitarios | JUnit 5 + AssertJ (+ Mockito en los casos de uso) | Reglas de dominio y cálculos financieros; configuración de perfiles (`ProfileConfigurationTest`); guarda del perfil cocos (`ExternalDatabaseGuardTest`) |
| Web slice | `@WebMvcTest` + `MockMvcTester` | Contrato HTTP y de errores |
| Persistencia | `@PersistenceIT` = `@DataJpaTest` + Testcontainers, **rollback por test** | Adaptadores de users, instruments, marketdata, orders, ledger e idempotencia contra el DDL (V1 + V2 + V3 + V4) y los datos reales. Incluye cómo se traduce cada constraint, y qué rechaza y qué acepta V3 |
| Aplicación | `@ApplicationIT` = `@SpringBootTest` + Testcontainers | Esquema intacto (`SchemaIntegrityIT`), aislamiento de perfiles (`ProfileIsolationIT`), solo lectura del perfil cocos aplicado al contenedor (`ReadOnlyDataSourceIT`), concurrencia del lock (`AccountLockIT`), API de punta a punta con el seed como oráculo (`InstrumentSearchApiIT`, `PortfolioApiIT`), transacción del portfolio (`PortfolioTransactionIT`), test funcional de órdenes (`OrderApiIT`), transferencias CASH_IN/CASH_OUT con su impacto en el cash (`CashTransferApiIT`), concurrencia real de órdenes y retiros (`OrderConcurrencyIT`), rollback técnico (`OrderRollbackIT`), idempotencia por HTTP, con concurrencia y entre instancias (`OrderIdempotencyApiIT`, `OrderIdempotencyConcurrencyIT`, `OrderIdempotencyRestartIT`) |
| Arquitectura | ArchUnit | Capas hexagonales y límites de módulos |

**Aislamiento de datos:**
- **Un único contenedor PostgreSQL**, cargado con `database.sql` + V2 + V3 + V4, compartido por todos los contextos de test. Nunca
  se usa H2, la base local ni la de Cocos.
- **Tests de repositorio:** transacción con rollback, así que nada persiste.
- **Tests que necesitan commits reales** (HTTP en otros hilos, concurrencia con escritura): restauran la tabla con
  `SeedDataRestorer`.
  - Toma un snapshot en memoria de `orders` al iniciar el contexto.
  - Para restaurar hace `TRUNCATE orders RESTART IDENTITY`, reinserta las filas del seed con sus ids y ajusta la
    secuencia.
  - A diferencia de borrar `id > max`, también deshace los **updates** sobre filas del seed (por ejemplo, una
    cancelación).
  - No crea objetos en la base. `SeedDataRestorerIT` lo verifica.
