package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.ExchangeResponse;
import org.com.sharekhan.dto.InstrumentResponse;
import org.com.sharekhan.dto.StrikeResponse;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.ScriptMasterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/scripts")
@RequiredArgsConstructor
public class ScriptMasterController {

    private final ScriptMasterService service;

    @GetMapping("/exchanges")
    public ResponseEntity<ExchangeResponse> getExchanges() {
        return ResponseEntity.ok(new ExchangeResponse(service.getAllExchanges()));
    }

    @GetMapping("/instruments/{exchange}")
    public ResponseEntity<InstrumentResponse> getInstruments(@PathVariable String exchange) {
        return ResponseEntity.ok(new InstrumentResponse(service.getInstrumentsForExchange(exchange)));
    }

    @GetMapping("/strikes")
    public ResponseEntity<StrikeResponse> getStrikes(
            @RequestParam String exchange,
            @RequestParam String instrument) {
        return ResponseEntity.ok(new StrikeResponse(service.getStrikesForInstrument(exchange, instrument)));
    }

    @GetMapping("/expiries")
    public ResponseEntity<?> getExpiries(
            @RequestParam String exchange,
            @RequestParam String instrument,
            @RequestParam Double strikePrice
    ) {
        List<String> expiries = service.getExpiries(exchange, instrument, strikePrice);
        // Make sure they are sorted
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        expiries =  expiries.stream()
                .map(exp -> LocalDate.parse(exp,formatter)) // assuming format is DD-MM-YYYY
                .filter(expDate -> !expDate.isBefore(LocalDate.now(ZoneId.of("Asia/Kolkata")))) // future only
                .sorted()
                .map(date -> date.format(outputFormatter))
                .toList();

        return ResponseEntity.ok(Map.of("expiries", expiries));
    }

    /**
     * Resolve an option trading symbol for the given inputs. Returns { tradingSymbol: "...", scripCode: 123 }
     */
    @GetMapping("/option")
    public ResponseEntity<?> getOption(
            @RequestParam String exchange,
            @RequestParam String instrument,
            @RequestParam Double strikePrice,
            @RequestParam String optionType,
            @RequestParam String expiry
    ) {
        Optional<ScriptMasterEntity> opt = service.findOption(exchange, instrument, strikePrice, optionType, expiry);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "Option script not found"));
        }

        ScriptMasterEntity e = opt.get();
        Map<String, Object> resp = new HashMap<>();
        resp.put("tradingSymbol", e.getTradingSymbol());
        resp.put("scripCode", e.getScripCode());
        resp.put("exchange", e.getExchange());
        resp.put("optionType", e.getOptionType());
        resp.put("expiry", e.getExpiry());
        return ResponseEntity.ok(resp);
    }


//    @GetMapping("/lot-size")
//    public ResponseEntity<Map<String, Integer>> getLotSize(
//            @RequestParam String exchange,
//            @RequestParam String instrument) {
//        int lotSize = service.getLotSize(exchange, instrument);
//        return ResponseEntity.ok(Map.of("lotSize", lotSize));
//    }
}