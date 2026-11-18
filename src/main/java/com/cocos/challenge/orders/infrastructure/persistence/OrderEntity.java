package com.cocos.challenge.orders.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Mirror of the provided {@code orders} table.
 * <ul>
 *     <li>Foreign keys ({@code instrumentid}, {@code userid}) are scalar ids, not associations: referential
 *     integrity is enforced by the database, and there is no lazy loading, cascading or N+1 to worry about.</li>
 *     <li>{@code side}, {@code type} and {@code status} keep the raw stored strings (no CHECK constraints exist);
 *     {@link OrderMapper} converts them to domain enums and fails on unknown values.</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "instrumentid")
    private Integer instrumentId;

    @Column(name = "userid")
    private Integer userId;

    @Column(name = "size")
    private Integer size;

    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "type", length = 10)
    private String type;

    @Column(name = "side", length = 10)
    private String side;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "datetime")
    private LocalDateTime datetime;

    protected OrderEntity() {
        // JPA
    }

    OrderEntity(Integer instrumentId, Integer userId, Integer size, BigDecimal price, String type, String side,
            String status, LocalDateTime datetime) {
        this.instrumentId = instrumentId;
        this.userId = userId;
        this.size = size;
        this.price = price;
        this.type = type;
        this.side = side;
        this.status = status;
        this.datetime = datetime;
    }

    public Integer getId() {
        return id;
    }

    public Integer getInstrumentId() {
        return instrumentId;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getSize() {
        return size;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getType() {
        return type;
    }

    public String getSide() {
        return side;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getDatetime() {
        return datetime;
    }
}
