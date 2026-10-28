package dev.queryguard.common.cost;

public class CostUnavailableException extends Exception {

    public CostUnavailableException(String message) {
        super(message);
    }

    public CostUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
