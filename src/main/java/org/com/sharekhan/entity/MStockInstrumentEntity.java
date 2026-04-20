package org.com.sharekhan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Stores instrument metadata fetched from the MStock script master API.
 */
@Entity
@Table(name = "mstock_instrument_master")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MStockInstrumentEntity {

    @Id
    private Long instrumentToken;

    @Column(nullable = false, unique = true)
    private String instrumentKey;

    @Column(nullable = false)
    private String tradingSymbol;

    private String name;
    private String exchange;
    private String segment;
    private String instrumentType;
    private String exchangeToken;
    private Double lastPrice;
    private String expiry;
    private Double strike;
    private Double tickSize;
    private Integer lotSize;

    @Column(nullable = false)
    private LocalDateTime fetchedAt;
}
