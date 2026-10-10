package com.example.intelligent_cv_matching_ai_agent.exception.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static ProblemDetail problem(
        int status,
        String message) {
        return ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), message);
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail business(
        ApiException ex) {
        return problem(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(
        MethodArgumentNotValidException ex) {
        ProblemDetail detail = problem(400, "Request validation failed");
        detail.setProperty(
            "errors",
            ex.getBindingResult().getFieldErrors().stream().map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList()
        );
        return detail;
    }

    @ExceptionHandler(
        {
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            ConstraintViolationException.class}
    )
    ProblemDetail badRequest(
        Exception ex) {
        return problem(400, "Invalid request value or format");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(
        Exception ex) {
        return problem(409, "Operation conflicts with existing data or constraints");
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ProblemDetail unavailable(
        Exception ex) {
        return problem(503, "Database temporarily unavailable");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail upload(
        Exception ex) {
        return problem(413, "File exceeds the upload limit");
    }
}
