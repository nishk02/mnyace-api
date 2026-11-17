package com.mnyace.account;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;

/** Stores the card endings as one comma-separated column; they are digits only, so the comma never clashes. */
@Converter
public class CardSuffixesConverter implements AttributeConverter<List<String>, String> {
    @Override
    public String convertToDatabaseColumn(List<String> suffixes) {
        return suffixes == null ? "" : String.join(",", suffixes);
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        return column == null || column.isEmpty() ? List.of() : Arrays.asList(column.split(","));
    }
}
