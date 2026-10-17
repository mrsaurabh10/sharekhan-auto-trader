package org.com.sharekhan.onboarding;

import java.util.List;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Only integrations approved for customer onboarding belong in this catalog. */
public final class BrokerCatalog {
    private BrokerCatalog() {}
    public record SupportedBroker(String id, String name, boolean enabled, String description,
                                  List<String> requiredFields) {}

    public static List<SupportedBroker> list() {
        return List.of(new SupportedBroker("Sharekhan", "Sharekhan", true,
                "Connect your Sharekhan trading account using your API credentials.",
                List.of("customerId", "clientCode", "apiKey", "brokerPassword", "totpSecret", "secretKey")),
                new SupportedBroker("Dhan", "Dhan", true,
                    "Generate a Dhan access token using PIN + TOTP or API key + secret browser login. Supports cash and option limit orders with explicit trading consent; whitelist your server static IP in Dhan.",
                    List.of("clientCode")));
    }

    public static boolean tradingSupported(String name) {
        return "Sharekhan".equalsIgnoreCase(name) || "Dhan".equalsIgnoreCase(name);
    }

    public static boolean hasTradingIdentity(org.com.sharekhan.entity.BrokerCredentialsEntity broker) {
        return "Dhan".equalsIgnoreCase(broker.getBrokerName())
            ? broker.getClientCode() != null && !broker.getClientCode().isBlank()
            : "Sharekhan".equalsIgnoreCase(broker.getBrokerName()) && broker.getCustomerId() != null && broker.getCustomerId() > 0;
    }

    public static boolean supports(String name) {
        return list().stream().anyMatch(b -> b.enabled() && b.id().equalsIgnoreCase(name));
    }

    public static List<String> requiredFields(String name) {
        String id = requireSupported(name);
        return list().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow().requiredFields();
    }

    public static String requireSupported(String name) {
        return list().stream().filter(b -> b.enabled() && b.id().equalsIgnoreCase(name))
                .map(SupportedBroker::id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Select a supported broker"));
    }
}
