package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "shoonya_instrument_master", uniqueConstraints = @UniqueConstraint(columnNames = "instrumentKey"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoonyaInstrumentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String instrumentKey;
    @Column(nullable = false, length = 32)
    private String exchange;
    @Column(nullable = false, length = 64)
    private String token;
    @Column(nullable = false, length = 512)
    private String tradingSymbol;
    private String symbol;
    private String expiry;
    private String instrument;
    private String optionType;
    private Double strikePrice;
    private Integer lotSize;
    private Double tickSize;
    @Column(nullable = false)
    private LocalDateTime fetchedAt;
}
