package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.BrokerAuthProvider;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.service.MStockInstrumentCacheService;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.service.MStockHistoricalService;
import org.com.sharekhan.service.MStockLtpService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/mstock")
@RequiredArgsConstructor
public class MStockController {

    private final MStockLtpService mStockLtpService;
    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final MStockInstrumentResolver instrumentResolver;
    private final MStockInstrumentCacheService instrumentCacheService;
    private final MStockHistoricalService mStockHistoricalService;

    @GetMapping("/ltp")
    public ResponseEntity<Map<String, Object>> getLtp(@RequestParam(name = "i") List<String> instruments) {
        if (instruments == null || instruments.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "At least one 'i' query parameter is required, e.g. ?i=NSE:ACC");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            Map<String, Map<String, Object>> data = mStockLtpService.fetchLtp(instruments);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "success");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Failed to fetch MStock LTP", e);
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @GetMapping("/ltp/by-script")
    public ResponseEntity<Map<String, Object>> getLtpByScript(
            @RequestParam(name = "scripCode", required = false) Integer scripCode,
            @RequestParam(name = "exchange", required = false) String exchange,
            @RequestParam(name = "instrument", required = false) String instrument,
            @RequestParam(name = "strikePrice", required = false) Double strikePrice,
            @RequestParam(name = "optionType", required = false) String optionType,
            @RequestParam(name = "expiry", required = false) String expiry) {

        if (scripCode == null && (!StringUtils.hasText(exchange) || !StringUtils.hasText(instrument))) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "Provide either scripCode or exchange + instrument to resolve the script.");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            Optional<ScriptMasterEntity> scriptOpt = instrumentResolver.resolveScript(
                    scripCode, exchange, instrument, strikePrice, optionType, expiry);

            if (scriptOpt.isEmpty()) {
                Map<String, Object> err = new HashMap<>();
                err.put("status", "error");
                err.put("message", "Unable to locate script in cache.");
                return ResponseEntity.status(404).body(err);
            }

            ScriptMasterEntity script = scriptOpt.get();
            Optional<String> instrumentKeyOpt = instrumentResolver.resolveInstrumentKey(script);
            if (instrumentKeyOpt.isEmpty()) {
                Map<String, Object> err = new HashMap<>();
                err.put("status", "error");
                err.put("message", "Unable to resolve MStock instrument key for provided script.");
                return ResponseEntity.status(422).body(err);
            }

            String instrumentKey = instrumentKeyOpt.get();
            Map<String, Object> ltpData = mStockLtpService.fetchLtpForInstrument(instrumentKey);
            Double lastPrice = null;
            if (ltpData != null) {
                Object lastPriceObj = ltpData.get("last_price");
                if (lastPriceObj instanceof Number) {
                    lastPrice = ((Number) lastPriceObj).doubleValue();
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "success");
            resp.put("instrument", instrumentKey);
            resp.put("scripCode", script.getScripCode());
            resp.put("last_price", lastPrice);
            resp.put("data", ltpData);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Failed to fetch MStock LTP by script", e);
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @GetMapping("/historical/candles")
    public ResponseEntity<Map<String, Object>> getHistoricalCandles(
            @RequestParam(name = "scripCode", required = false) Integer scripCode,
            @RequestParam(name = "mstockExchange", required = false) String mstockExchange,
            @RequestParam(name = "instrumentToken", required = false) Long instrumentToken,
            @RequestParam(name = "exchange", required = false) String exchange,
            @RequestParam(name = "instrument", required = false) String instrument,
            @RequestParam(name = "strikePrice", required = false) Double strikePrice,
            @RequestParam(name = "optionType", required = false) String optionType,
            @RequestParam(name = "expiry", required = false) String expiry,
            @RequestParam(name = "interval", defaultValue = "minute") String interval,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to) {
        if (instrumentToken == null && scripCode == null && (!StringUtils.hasText(exchange) || !StringUtils.hasText(instrument))) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("message", "Provide instrumentToken + mstockExchange, scripCode, or exchange + instrument.");
            return ResponseEntity.badRequest().body(err);
        }
        if (instrumentToken != null && !StringUtils.hasText(mstockExchange)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("message", "mstockExchange is required when instrumentToken is provided.");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            MStockHistoricalService.HistoricalResponse response = instrumentToken != null
                    ? mStockHistoricalService.getHistoricalCandlesByToken(mstockExchange, instrumentToken, interval, from, to)
                    : mStockHistoricalService.getHistoricalCandles(
                            scripCode, exchange, instrument, strikePrice, optionType, expiry, interval, from, to);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", response.status());
            body.put("exchange", response.exchange());
            body.put("instrumentToken", response.instrumentToken());
            body.put("instrumentKey", response.instrumentKey());
            body.put("tradingSymbol", response.tradingSymbol());
            body.put("interval", response.interval());
            body.put("from", response.from());
            body.put("to", response.to());
            body.put("count", response.count());
            body.put("candles", response.candles());
            body.put("raw", response.raw());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception ex) {
            log.error("Failed to fetch MStock historical candles", ex);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("message", ex.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> setToken(@RequestBody Map<String, Object> body) {
        // Accept JSON: { "accessToken": "...", "expiresIn": 28800 }
        String accessToken = (body.containsKey("accessToken") && body.get("accessToken") != null)
                ? body.get("accessToken").toString() : null;
        Number expiresInNum = body.containsKey("expiresIn") && body.get("expiresIn") instanceof Number
                ? (Number) body.get("expiresIn") : null;

        if (accessToken == null || accessToken.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "accessToken is required in request body");
            return ResponseEntity.badRequest().body(err);
        }

        long expiresIn = (expiresInNum != null) ? expiresInNum.longValue() : 8L * 60 * 60; // default 8h

        try {
            tokenStoreService.updateToken(Broker.MSTOCK, accessToken, expiresIn);
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("message", "token stored");
            resp.put("expiresIn", expiresIn);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Failed to store MStock token", e);
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyAndStore() {
        BrokerAuthProvider provider = providerRegistry.getProvider(Broker.MSTOCK);
        if (provider == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "No MStock auth provider registered");
            return ResponseEntity.status(404).body(err);
        }

        try {
            AuthTokenResult result = provider.loginAndFetchToken();
            if (result == null || result.token() == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("status", "error");
                err.put("message", "Provider returned no token");
                return ResponseEntity.status(500).body(err);
            }

            // persist token in DB and cache
            tokenStoreService.updateToken(Broker.MSTOCK, result.token(), result.expiresIn());

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("maskedToken", mask(result.token()));
            resp.put("expiresIn", result.expiresIn());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("MStock verify failed", e);
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/instruments/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> refreshInstrumentMaster() {
        try {
            boolean refreshed = instrumentCacheService.refreshInstrumentMaster();
            long rows = instrumentCacheService.instrumentCount();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", refreshed ? "success" : "error");
            resp.put("message", refreshed
                    ? "MStock script master refreshed"
                    : "MStock script master refresh did not complete. Check credentials/token and server logs.");
            resp.put("rows", rows);
            return refreshed
                    ? ResponseEntity.ok(resp)
                    : ResponseEntity.status(500).body(resp);
        } catch (Exception e) {
            log.error("Failed to refresh MStock instrument master", e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    private String mask(String token) {
        if (token == null) return null;
        int len = token.length();
        if (len <= 8) return "****";
        return token.substring(0, 4) + "..." + token.substring(len - 4);
    }
}
