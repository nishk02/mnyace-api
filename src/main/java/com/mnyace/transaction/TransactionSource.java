package com.mnyace.transaction;

/** How a transaction came to exist: typed in by the user, or created by the app from a bank SMS. */
public enum TransactionSource {
    MANUAL,
    SMS
}
