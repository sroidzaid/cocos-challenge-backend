package com.cocos.challenge.instruments.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Mirror of the provided {@code instruments} table. {@code type} is kept as the raw string stored in the database
 * (no CHECK constraint exists); conversion to the domain enum happens in {@link InstrumentMapper}.
 */
@Entity
@Table(name = "instruments")
public class InstrumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "ticker", length = 10)
    private String ticker;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "type", length = 10)
    private String type;

    protected InstrumentEntity() {
        // JPA
    }

    public Integer getId() {
        return id;
    }

    public String getTicker() {
        return ticker;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }
}
