package org.com.sharekhan.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidTradeRequestException extends RuntimeException {
    public InvalidTradeRequestException(String message) {
        super(message);
    }
}

