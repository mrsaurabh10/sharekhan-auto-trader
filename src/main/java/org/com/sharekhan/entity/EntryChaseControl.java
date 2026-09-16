package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Kept separate from the trade so status polling cannot overwrite a user's decision. */
@Entity
@Table(name = "entry_chase_control")
@Getter
@Setter
public class EntryChaseControl {
    public enum State { RUNNING, PAUSED, ACTION_PENDING, CANCEL_PENDING, CLOSED }

    @Id
    private Long tradeId;
    private String orderId;
    @Enumerated(EnumType.STRING)
    private State state;
    private String decisionToken;
    private int attempts;
    private boolean waitForBrokerTrigger;
    private Double lastPrice;
    private String reason;
    private Long telegramMessageId;
}
