package org.com.sharekhan.onboarding;

import java.util.HashMap;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Ownership, connection state and trading flags are never accepted from credential forms. */
public record BrokerCredentialRequest(String brokerName, String customerId, String apiKey,
        String brokerUsername, String brokerPassword, String clientCode, String totpSecret,
        String secretKey, Boolean defaultForOrders) {

    public Map<String, Object> fields(boolean creating) {
        Map<String, Object> result = new HashMap<>();
        if (brokerName != null) result.put("brokerName", brokerName.trim());
        put(result, "clientCode", clientCode, 128);
        put(result, "apiKey", apiKey, 512);
        put(result, "brokerUsername", brokerUsername, 256);
        put(result, "brokerPassword", brokerPassword, 512);
        put(result, "totpSecret", totpSecret, 512);
        put(result, "secretKey", secretKey, 512);
        if (customerId != null && !customerId.isBlank()) {
            try {
                long value = Long.parseLong(customerId.trim());
                if (value <= 0) throw new NumberFormatException();
                result.put("customerId", value);
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(BAD_REQUEST, "Customer ID must be a positive whole number");
            }
        }
        if (creating) {
            for (String field : BrokerCatalog.requiredFields(brokerName)) {
                if (!result.containsKey(field))
                    throw new ResponseStatusException(BAD_REQUEST, "Complete all required broker credentials, including customer ID");
            }
        }
        if (defaultForOrders != null) result.put("defaultForOrders", defaultForOrders);
        return result;
    }

    private void put(Map<String, Object> fields, String name, String value, int maxBytes) {
        if (value == null || value.isBlank()) return; // Blank edits preserve existing secrets.
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes)
            throw new ResponseStatusException(BAD_REQUEST, "Broker credential field is too long: " + name);
        fields.put(name, value);
    }

    @Override public String toString() { return "BrokerCredentialRequest[redacted]"; }
}
