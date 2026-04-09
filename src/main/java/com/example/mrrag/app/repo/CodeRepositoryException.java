package com.example.mrrag.app.repo;

public class CodeRepositoryException extends RuntimeException {

    public CodeRepositoryException(String message) {
        super(message);
    }

    public CodeRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public CodeRepositoryException(Throwable cause) {
        super(cause);
    }
}