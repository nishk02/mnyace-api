package com.mnyace.transaction;

public record TransactionResult(TransactionResponse response, String warning, String info) {
}
