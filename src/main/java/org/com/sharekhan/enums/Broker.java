package org.com.sharekhan.enums;

public enum Broker {
    SHAREKHAN("Sharekhan"),
    MSTOCK("MStock");

    private final String displayName;

    Broker(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static Broker fromDisplayName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Display name must not be null");
        }
        for (Broker b : values()) {
            if (b.displayName.equalsIgnoreCase(name.trim())) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown Broker: " + name);
    }
}
