package org.com.sharekhan.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BrokerContext {
    private Long customerId;
    private String apiKey;
    private String clientCode;
    private String brokerName;
}
