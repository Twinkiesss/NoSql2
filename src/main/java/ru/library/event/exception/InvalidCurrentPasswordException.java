package ru.library.event.exception;

public class InvalidCurrentPasswordException extends RuntimeException {
    public InvalidCurrentPasswordException() {
        super("Неверный текущий пароль");
    }
}
