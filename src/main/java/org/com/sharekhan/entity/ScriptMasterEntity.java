// ScriptMasterEntity.java
package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "script_master")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptMasterEntity {

    @Id
    private Integer scripCode;

    @Column(nullable = false)
    private String tradingSymbol;

    private String exchange;
    private String instrumentType;
    private Double strikePrice;
    private String expiry;
    private Integer lotSize;
    private String optionType;

    // NOTE: TriggeredTradeSetupEntity is defined as a standalone entity in
    // src/main/java/org/com/sharekhan/entity/TriggeredTradeSetupEntity.java
    // Any previous inner-class duplicate has been removed to avoid conflicts.
}


