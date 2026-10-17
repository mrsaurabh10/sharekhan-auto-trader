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
        if ("Dhan".equalsIgnoreCase(brokerName)) {
            if (clientCode != null && !clientCode.isBlank() && !clientCode.trim().matches("[0-9]{1,20}"))
                throw new ResponseStatusException(BAD_REQUEST, "Dhan Client ID must contain digits only");
            if (brokerPassword != null && !brokerPassword.isBlank() && !brokerPassword.matches("[0-9]{6}"))
                throw new ResponseStatusException(BAD_REQUEST, "Dhan PIN must contain six digits");
            if (totpSecret != null && !totpSecret.isBlank() && !totpSecret.matches("(?i)[A-Z2-7]{16,128}=*"))
                throw new ResponseStatusException(BAD_REQUEST, "Enter the authenticator setup secret, not a one-time TOTP code");
            if (creating && !((result.containsKey("brokerPassword") && result.containsKey("totpSecret"))
                    || (result.containsKey("apiKey") && result.containsKey("secretKey"))))
                throw new ResponseStatusException(BAD_REQUEST, "Dhan requires PIN and TOTP secret, or API key and API secret");
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
