package com.cocos.challenge.marketdata.infrastructure.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MarketDataJpaRepository extends JpaRepository<MarketDataEntity, Integer> {

    /**
     * Latest quote per instrument in one round trip. {@code id DESC} breaks ties deterministically because the
     * schema does not prevent two rows for the same instrument and date.
     */
    @Query(value = """
            SELECT DISTINCT ON (instrumentid) *
            FROM marketdata
            WHERE instrumentid IN (:instrumentIds)
            ORDER BY instrumentid, date DESC, id DESC""", nativeQuery = true)
    List<MarketDataEntity> findLatestByInstrumentIds(@Param("instrumentIds") Collection<Integer> instrumentIds);

    List<MarketDataEntity> findByInstrumentIdOrderByDateDescIdDesc(Integer instrumentId);
}
