package com.galaplat.mfi.common.api;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 统一响应包装，由 {@link ResponseWrapAdvice} 自动套上，Controller 直接返回业务对象即可。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class R<T> {

    private String code;
    private String message;
    private T data;

    public static <T> R<T> ok(T data) {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return build(errorCode.getCode(), message == null ? errorCode.getMessage() : message, null);
    }

    private static <T> R<T> build(String code, String message, T data) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.data = data;
        return r;
    }

}
