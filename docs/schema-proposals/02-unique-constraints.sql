-- PROPUESTA - NO APLICADA. Evaluar por separado (mejora futura).
-- Nada ejecuta este script. El esquema aplicado es database.sql (V1) + db/migration (V2, V3, V4).
-- Ver docs/database.md, seccion "Mejoras de esquema propuestas (no aplicadas)".
-- Si se adopta: verificar antes que no haya duplicados (el ALTER fallaria) y convertirlo en db/migration/V5__...
-- El indice orders(userid, status) que antes formaba parte de esta propuesta ya se aplico en V4.

-- One quote per instrument and day: makes "latest quote" deterministic by construction. Its backing index would
-- duplicate idx_marketdata_instrument_date (V4) for lookups; adopting it means evaluating whether to drop that one.
ALTER TABLE marketdata
    ADD CONSTRAINT uq_marketdata_instrument_date UNIQUE (instrumentid, date);

-- The ticker is the business key. Integrity only: orders use instrumentId and the search uses partial ILIKE, which
-- this index does not speed up. Today a duplicated ticker is detected (and reported) when read.
ALTER TABLE instruments
    ADD CONSTRAINT uq_instruments_ticker UNIQUE (ticker);
