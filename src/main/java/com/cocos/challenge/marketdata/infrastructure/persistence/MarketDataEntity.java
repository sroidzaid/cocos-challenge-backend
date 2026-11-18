package com.cocos.challenge.marketdata.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Mirror of the provided {@code marketdata} table (all columns, NUMERIC(10,2) as BigDecimal).
 * The foreign key to {@code instruments} is mapped as a scalar id, not as an association: queries only need the id,
 * and avoiding the association rules out lazy loading surprises and N+1 queries.
 * Read-only for the application.
 */
@Entity
@Table(name = "marketdata")
public class MarketDataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "instrumentid")
    private Integer instrumentId;

    @Column(name = "high", precision = 10, scale = 2)
    private BigDecimal high;

    @Column(name = "low", precision = 10, scale = 2)
    private BigDecimal low;

    @Column(name = "open", precision = 10, scale = 2)
    private BigDecimal open;

    @Column(name = "close", precision = 10, scale = 2)
    private BigDecimal close;

    @Column(name = "previousclose", precision = 10, scale = 2)
    private BigDecimal previousClose;

    @Column(name = "date")
    private LocalDate date;

    protected MarketDataEntity() {
        // JPA
    }

    public Integer getId() {
        return id;
    }

    public Integer getInstrumentId() {
        return instrumentId;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public BigDecimal getClose() {
        return close;
    }

    public BigDecimal getPreviousClose() {
        return previousClose;
    }

    public LocalDate getDate() {
        return date;
    }
}
