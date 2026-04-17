package com.finmates.social.profile;

public enum NameDisplayPreference {
    FULL_NAME("full_name"),
    DISPLAY_NAME("display_name");

    private final String value;

    NameDisplayPreference(String value) { this.value = value; }

    public String getValue() { return value; }

    public static NameDisplayPreference fromValue(String value) {
        for (NameDisplayPreference v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown NameDisplayPreference: " + value);
    }
}
