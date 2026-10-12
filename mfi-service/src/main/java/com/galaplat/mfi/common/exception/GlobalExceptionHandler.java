package com.galaplat.mfi.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.galaplat.mfi.common.api.ErrorCode;
import com.galaplat.mfi.common.api.R;
import com.galaplat.mfi.dao.tenant.TenantNotBoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 *
 * <p>对外消息一律是人能读懂的业务描述，且不得包含 SQL、内部地址、其他租户数据或凭证内容；
 * 真实堆栈只进日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> onBiz(BizException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("业务校验不通过: code={}, message={}", code.getCode(), e.getMessage());
        return status(httpStatusOf(code), R.fail(code, e.getMessage()));
    }

    /**
     * 同一套 BizException 按错误码分流 HTTP 状态：403 表示"知道是谁但没资格"，
     * 401 表示"连身份都不成立"，其余业务拒绝统一 400。
     */
    private static HttpStatus httpStatusOf(ErrorCode code) {
        return switch (code) {
            case NOT_OWNER -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED, BAD_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> onNotLogin(NotLoginException e) {
        log.info("未登录访问被拒绝: type={}", e.getLoginType());
        return status(HttpStatus.UNAUTHORIZED, R.fail(ErrorCode.UNAUTHORIZED, null));
    }

    @ExceptionHandler(TenantNotBoundException.class)
    public ResponseEntity<R<Void>> onTenantNotBound(TenantNotBoundException e) {
        // 走到这里说明有代码路径没绑定租户就访问数据，属编码缺陷，必须留全栈
        log.error("租户上下文缺失，数据访问被拒绝", e);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, R.fail(ErrorCode.TENANT_NOT_BOUND, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> onInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .reduce((a, b) -> a + "; " + b)
                .orElse(ErrorCode.INVALID_PARAM.getMessage());
        return status(HttpStatus.BAD_REQUEST, R.fail(ErrorCode.INVALID_PARAM, detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> onViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::describe)
                .reduce((a, b) -> a + "; " + b)
                .orElse(ErrorCode.INVALID_PARAM.getMessage());
        return status(HttpStatus.BAD_REQUEST, R.fail(ErrorCode.INVALID_PARAM, detail));
    }

    /**
     * 上传体积超过 servlet.multipart 上限。不接住会变成 500，而这是用户操作能直接造成的结果。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> onTooLarge(MaxUploadSizeExceededException e) {
        log.info("上传文件超出体积上限: maxBytes={}", e.getMaxUploadSize());
        return status(HttpStatus.BAD_REQUEST, R.fail(ErrorCode.INVALID_PARAM, "文件过大，请拆分后分批导入"));
    }

    /**
     * 浏览器会自动索要 favicon.ico 之类的静态资源，前端只打包了 static 目录里的文件。
     * 缺失按 404 处理即可，不该占用"未预期系统异常"的 ERROR 与堆栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> onMissingStaticResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: path={}", e.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> onUnexpected(Exception e) {
        log.error("未预期的系统异常", e);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, R.fail(ErrorCode.SYSTEM_ERROR, null));
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    private static ResponseEntity<R<Void>> status(HttpStatus httpStatus, R<Void> body) {
        return ResponseEntity.status(httpStatus).body(body);
    }

}
