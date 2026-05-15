package org.com.sharekhan.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class CloseTradesResponse {
    private String instrument;
    private String optionType;
    private Double strikePrice;
    private String expiry;
    private int cancelledRequests;
    private int squareOffInitiated;
    private int skipped;
    private int errors;

    @Builder.Default
    private List<String> details = new ArrayList<>();
}
