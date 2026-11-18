# Ejecución y configuración

[← Volver al README](../README.md)

## Perfiles

| Perfil | Base de datos | Cómo se activa | Variables |
|---|---|---|---|
| `local` (**default**) | PostgreSQL local (Docker Compose o a mano) con `database.sql` + `db/migration/` (V2, V3, V4) | Sin perfil explícito | `LOCAL_DB_URL`, `LOCAL_DB_USERNAME`, `LOCAL_DB_PASSWORD` (opcionales; defaults de desarrollo `jdbc:postgresql://localhost:5432/cocos`, `cocos`/`cocos`) |
| `cocos` | Base externa provista por Cocos para el challenge (no necesariamente producción) | `SPRING_PROFILES_ACTIVE=cocos` | `COCOS_DB_URL`, `COCOS_DB_USERNAME`, `COCOS_DB_PASSWORD` (**obligatorias, sin defaults**); `COCOS_DB_READ_ONLY` (default `true`) |
| `test` | PostgreSQL efímero de Testcontainers, cargado con `database.sql` + `db/migration/` (V2, V3, V4) | Solo desde los tests (`@ActiveProfiles("test")`) | Ninguna |

Configuración común a todos los perfiles (`application.yml`):
- `ddl-auto: validate`, `open-in-view: false`, `sql.init.mode: never`.
- Sin Flyway ni Liquibase: la aplicación nunca ejecuta DDL ni scripts. Los cambios de esquema se aplican a mano,
  con scripts versionados (ver [Evolución del esquema](database.md#evolución-del-esquema)). Si falta alguno, `validate`
  impide el arranque.

Salvaguardas del perfil `cocos`:
- **Solo lectura por defecto:** `hikari.read-only=true` + `readOnlyMode=always` de pgjdbc. Cualquier escritura la
  rechaza el propio PostgreSQL ("cannot execute … in a read-only transaction"). Las escrituras se habilitan con
  `COCOS_DB_READ_ONLY=false`, solo cuando estén autorizadas.
- **Validación al arrancar** (`ExternalDatabaseGuard`, antes de crear el pool): falla si falta alguna variable o si
  la URL contiene credenciales (`user:pass@`, `user=`, `password=`). Nunca se loguean ni la URL ni las credenciales.
- Actuator expone solo `health`, `info` y `metrics` (no `env` ni `configprops`).
- Ningún test usa este perfil.

## Base de datos local sin Docker Compose (PowerShell, Windows)

```powershell
# 1. Base nueva: database.sql original (V1) + migraciones, en orden (se ejecutan solo en el primer arranque,
#    con ON_ERROR_STOP: si un script falla, el contenedor no queda a medio inicializar)
docker run -d --name cocos-db -p 5432:5432 `
  -e POSTGRES_DB=cocos -e POSTGRES_USER=cocos -e POSTGRES_PASSWORD=cocos `
  -v "${PWD}\database.sql:/docker-entrypoint-initdb.d/01-database.sql:ro" `
  -v "${PWD}\db\migration\V2__create_order_idempotency.sql:/docker-entrypoint-initdb.d/02-V2__create_order_idempotency.sql:ro" `
  -v "${PWD}\db\migration\V3__orders_integrity_constraints.sql:/docker-entrypoint-initdb.d/03-V3__orders_integrity_constraints.sql:ro" `
  -v "${PWD}\db\migration\V4__lookup_indexes.sql:/docker-entrypoint-initdb.d/04-V4__lookup_indexes.sql:ro" `
  postgres:16-alpine

# Alternativa: cargar los scripts en un PostgreSQL que ya está corriendo (base vacía)
Get-Content .\database.sql -Raw | docker exec -i cocos-db psql -v ON_ERROR_STOP=1 -U cocos -d cocos
Get-Content .\db\migration\V2__create_order_idempotency.sql -Raw | docker exec -i cocos-db psql -v ON_ERROR_STOP=1 -U cocos -d cocos
Get-Content .\db\migration\V3__orders_integrity_constraints.sql -Raw | docker exec -i cocos-db psql -v ON_ERROR_STOP=1 -U cocos -d cocos
Get-Content .\db\migration\V4__lookup_indexes.sql -Raw | docker exec -i cocos-db psql -v ON_ERROR_STOP=1 -U cocos -d cocos

# 2. Base existente: aplicar solo las migraciones que le falten, en orden y una sola vez
#    (V2 si se creó antes de la Fase D; V3 si se creó antes de V3; V4 si se creó antes de V4)
Get-Content .\db\migration\V2__create_order_idempotency.sql -Raw | docker exec -i cocos-db psql -v ON_ERROR_STOP=1 -U cocos -d cocos
Get-Content .\db\migration\V3__orders_integrity_constraints.sql -Raw | docker exec -i cocos-db psql -v ON_ERROR_STOP=1 -U cocos -d cocos
Get-Content .\db\migration\V4__lookup_indexes.sql -Raw | docker exec -i cocos-db psql -v ON_ERROR_STOP=1 -U cocos -d cocos

# Verificar
docker exec cocos-db psql -U cocos -d cocos -c "SELECT count(*) FROM orders;"              # 11
docker exec cocos-db psql -U cocos -d cocos -c "SELECT count(*) FROM order_idempotency;"   # 0
```

Para empezar de cero: `docker compose down -v; docker compose up -d` (o, sin Compose, `docker rm -f cocos-db` y
repetir el paso 1).

## Aplicación (PowerShell, Windows)
```powershell
# Perfil local (default). Las variables solo hacen falta si difieren de los defaults.
.\mvnw.cmd spring-boot:run

# Perfil local con otra base
$env:LOCAL_DB_URL = "jdbc:postgresql://localhost:5433/cocos"
.\mvnw.cmd spring-boot:run

# Perfil cocos (solo lectura). La URL no debe incluir credenciales.
$env:SPRING_PROFILES_ACTIVE = "cocos"
$env:COCOS_DB_URL      = "jdbc:postgresql://<host>:<port>/<database>"
$env:COCOS_DB_USERNAME = "<usuario>"
$env:COCOS_DB_PASSWORD = "<password>"
.\mvnw.cmd spring-boot:run
# Equivalente con el jar: java -jar target\cocos-challenge-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=cocos

# Limpiar las variables de la sesión
Remove-Item Env:SPRING_PROFILES_ACTIVE, Env:COCOS_DB_URL, Env:COCOS_DB_USERNAME, Env:COCOS_DB_PASSWORD
```

En Linux/macOS: `SPRING_PROFILES_ACTIVE=cocos COCOS_DB_URL=... ./mvnw spring-boot:run`.

| Otras variables (opcionales) | Default |
|---|---|
| `DB_POOL_SIZE` | `10` |
| `SERVER_PORT` | `8080` |
| `APP_TIME_ZONE` | `America/Argentina/Buenos_Aires` |
| `APP_ACCOUNT_LOCK_TIMEOUT` | `3s` |

| URL | Descripción |
|---|---|
| `/swagger-ui.html` | Swagger UI |
| `/v3/api-docs` | Especificación OpenAPI 3 |
| `/actuator/health` (`/liveness`, `/readiness`) | Health checks, incluida la DB |
| `/actuator/metrics` | Métricas (Micrometer) |
