package com.finmates.social.profile;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class NameDisplayPreferenceConverter implements AttributeConverter<NameDisplayPreference, String> {
    @Override
    public String convertToDatabaseColumn(NameDisplayPreference attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public NameDisplayPreference convertToEntityAttribute(String dbData) {
        return dbData == null ? null : NameDisplayPreference.fromValue(dbData);
    }
}
