package com.mnyace.bank;

public record BankResponse(String code, String name, String colorLight, String colorDark) {
    public static BankResponse from(Bank bank) {
        return new BankResponse(bank.getCode(), bank.getName(), bank.getColorLight(), bank.getColorDark());
    }
}
