package org.com.sharekhan.service;

class MStockLtpException extends RuntimeException {

    private final int httpStatus;
    private final String responseBody;

    MStockLtpException(int httpStatus, String responseBody) {
        super("MStock LTP request failed (http:" + httpStatus + "): " + responseBody);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    int getHttpStatus() {
        return httpStatus;
    }

    String getResponseBody() {
        return responseBody;
    }

    boolean isTransientFailure() {
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }
}
