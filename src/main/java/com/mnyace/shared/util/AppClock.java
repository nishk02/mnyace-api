package com.mnyace.shared.util;

import java.time.LocalDate;
import java.time.ZoneId;

/** "Today" for the whole app, pinned to IST regardless of the server's own JVM/host timezone. */
public class AppClock {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private AppClock() {
        // Utility class - not instantiable
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}