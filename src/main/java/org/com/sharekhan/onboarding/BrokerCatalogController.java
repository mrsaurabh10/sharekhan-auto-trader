package org.com.sharekhan.onboarding;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController @RequiredArgsConstructor
public class BrokerCatalogController {
    private final BrokerConnectionService connections;
    @GetMapping("/api/brokers") public List<BrokerCatalog.SupportedBroker> catalog() {
        return BrokerCatalog.list();
    }
    @PostMapping("/api/user/brokers/{id}/connect") public Map<String, Object> connect(@PathVariable Long id) { return connections.connect(id); }
    public record TradingConsent(boolean enabled) {}
    @PostMapping("/api/user/brokers/{id}/trading") public Map<String, String> trading(@PathVariable Long id, @RequestBody TradingConsent consent) {
        connections.setTrading(id, consent.enabled()); return Map.of("message", consent.enabled() ? "Trading enabled" : "New trading disabled");
    }
    @PostMapping("/api/user/brokers/{id}/disconnect") public Map<String, String> disconnect(@PathVariable Long id) {
        connections.disconnect(id); return Map.of("message", "Broker disconnected");
    }
}
