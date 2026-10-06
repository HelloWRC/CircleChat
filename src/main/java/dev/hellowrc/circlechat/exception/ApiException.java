package dev.hellowrc.circlechat.exception;

public class ApiException extends RuntimeException {
    private final Integer statusCode;

    public ApiException() {
        this(400, "请求失败");
    }

    public ApiException(String message) {
        this(400, message);
    }

    public ApiException(Integer statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

}
