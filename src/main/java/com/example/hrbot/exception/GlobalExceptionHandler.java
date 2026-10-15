package com.example.hrbot.exception;

import com.example.hrbot.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates exceptions from any controller into {@link ErrorResponse} bodies, so that
 * clients receive one error shape rather than a mix of framework defaults.
 *
 * <p>Request-binding and validation failures are handled explicitly and answered with
 * 400; the remaining handlers cover ingestion, upstream availability, and anything
 * unanticipated. The most specific {@code @ExceptionHandler} for a type wins.
 *
 * <p>Every body is sent with an explicit JSON content type. Without one, Spring would
 * negotiate the type against the request's {@code Accept} header, and a client of the
 * streaming endpoint that accepts only {@code text/event-stream} would receive the right
 * status with an empty body.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Request body failed bean validation; reports every field error. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("Validation failed for {}: {}", request.getRequestURI(), message);

        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                message,
                request.getRequestURI()
        ));
    }

    /** A constraint on a query parameter was violated; reports every violation. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolations(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        String message = ex.getConstraintViolations().stream()
                .map(violation -> parameterName(violation.getPropertyPath())
                        + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));

        return badRequest(request, "Validation Failed", message);
    }

    /** A required query parameter was absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request) {

        return badRequest(request, "Missing Parameter",
                "Required parameter '" + ex.getParameterName() + "' is missing.");
    }

    /** A parameter was present but could not be converted to its declared type. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String expected = ex.getRequiredType() == null
                ? "of the expected type"
                : "a valid " + ex.getRequiredType().getSimpleName();

        return badRequest(request, "Invalid Parameter",
                "Parameter '" + ex.getName() + "' must be " + expected + ".");
    }

    /** The request body was absent, truncated, or not valid JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        return badRequest(request, "Malformed Request Body",
                "The request body is missing or is not valid JSON.");
    }

    /** Ingestion failed: the upload was rejected, unparseable, or produced no chunks. */
    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<ErrorResponse> handleDocumentProcessingError(
            DocumentProcessingException ex,
            HttpServletRequest request) {

        log.error("Document processing failed: {}", ex.getMessage(), ex);

        return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Document Processing Error",
                ex.getMessage(),
                request.getRequestURI()
        ));
    }

    /** Upload exceeded the configured multipart size limit. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {

        log.warn("File upload too large: {}", ex.getMessage());

        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "File Too Large",
                "The uploaded file exceeds the maximum allowed size.",
                request.getRequestURI()
        ));
    }

    /**
     * Ollama could not be reached. Reported as 503 rather than 500, because the fault is
     * an unavailable dependency and the request may succeed on retry.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleOllamaConnectionError(
            ResourceAccessException ex,
            HttpServletRequest request) {

        log.error("Failed to connect to Ollama: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "AI Service Unavailable",
                "Cannot connect to Ollama. Ensure Ollama is running at http://localhost:11434 "
                        + "and the required models are pulled (ollama pull llama3, ollama pull nomic-embed-text).",
                request.getRequestURI()
        ));
    }

    /**
     * Fallback for unanticipated failures.
     *
     * <p>Exception types, messages and causes describe internal structure and are
     * confined to the log. Response and log share a generated reference, so a report of
     * a failed request can be matched to its stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericError(
            Exception ex,
            HttpServletRequest request) {

        // The client went away mid-response — typically a streamed answer abandoned by
        // closing the tab. Not a fault, and there is no one left to send a body to.
        // Checked first so that no other branch tries to write to the closed connection;
        // returning null tells Spring the exception is handled and nothing is written.
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.info("Client disconnected from {}: {}", request.getRequestURI(), ex.getMessage());
            return null;
        }

        // Spring's own request-handling exceptions already carry the status the request
        // deserves — an unsupported method, an unacceptable media type, an unmapped
        // path. Honour it, so a caller's mistake is not reported as a server fault.
        if (ex instanceof org.springframework.web.ErrorResponse carried) {
            HttpStatusCode status = carried.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(status.value());
            return clientError(request, status,
                    resolved != null ? resolved.getReasonPhrase() : "Request Error",
                    ex.getMessage());
        }

        String reference = UUID.randomUUID().toString().substring(0, 8);

        log.error("Unexpected error [{}] at {}: {}",
                reference, request.getRequestURI(), ex.getMessage(), ex);

        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred. Quote reference " + reference
                        + " when reporting this; the application log holds the detail.",
                request.getRequestURI()
        ));
    }

    /** Builds a 400 and logs it at warn level, since the fault lies with the request. */
    private ResponseEntity<ErrorResponse> badRequest(
            HttpServletRequest request, String error, String message) {

        return clientError(request, HttpStatus.BAD_REQUEST, error, message);
    }

    /**
     * Builds a client-error response and logs it at warn level. Warn rather than error,
     * and without a stack trace, because a malformed request is not an incident.
     */
    private ResponseEntity<ErrorResponse> clientError(
            HttpServletRequest request, HttpStatusCode status, String error, String message) {

        log.warn("{} {} for {}: {}", status.value(), error, request.getRequestURI(), message);

        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(
                status.value(),
                error,
                message,
                request.getRequestURI()
        ));
    }

    /**
     * The parameter name from a violation path, discarding the method prefix that
     * method-level validation prepends — {@code debugSearch.topK} becomes {@code topK}.
     */
    private String parameterName(Path propertyPath) {
        String name = null;
        for (Path.Node node : propertyPath) {
            name = node.getName();
        }
        return name == null ? "parameter" : name;
    }
}
