package dev.queryguard.common.masking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public enum MaskStrategy {

    /** {@code ****} */
    REDACT {
        @Override
        public String apply(String value) {
            return "****";
        }
    },

    /** {@code user42@example.com -> u***@***.com}. Keeps the TLD, which is sometimes useful and rarely identifying. */
    PARTIAL_EMAIL {
        @Override
        public String apply(String value) {
            int at = value.indexOf('@');
            int dot = value.lastIndexOf('.');
            if (at <= 0 || dot < at) {
                return "****";
            }
            return value.charAt(0) + "***@***" + value.substring(dot);
        }
    },

    /** Everything but the last four characters becomes {@code *}. Phone numbers, mostly. */
    LAST4 {
        @Override
        public String apply(String value) {
            if (value.length() <= 4) {
                return "*".repeat(value.length());
            }
            return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
        }
    },

    /**
     * Stable pseudonym. Analysts can't read the value but can still COUNT(DISTINCT)
     * and group by it, which is usually what they actually wanted.
     */
    HASH {
        @Override
        public String apply(String value) {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
                return "h_" + HexFormat.of().formatHex(d, 0, 6);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    },

    /** Value comes back as SQL NULL. Also what binary-format columns get, since we can't rewrite those safely. */
    NULLIFY {
        @Override
        public String apply(String value) {
            return null;
        }
    };

    /** Returns the masked text, or null for SQL NULL. */
    public abstract String apply(String value);
}
