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
                List.of("customerId", "clientCode", "apiKey", "brokerPassword", "totpSecret", "secretKey")));
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
