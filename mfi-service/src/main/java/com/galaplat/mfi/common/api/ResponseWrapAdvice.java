package com.galaplat.mfi.common.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 只包装本仓库业务 Controller 的返回值。
 *
 * <p>限定包名而不是全局生效，是为了不误伤 springdoc 的 /v3/api-docs；排除 String 返回值是为了
 * 规避 StringHttpMessageConverter 拿到非 String 体时的 ClassCastException。
 */
@RestControllerAdvice(basePackages = "com.galaplat.mfi.controller")
public class ResponseWrapAdvice implements ResponseBodyAdvice<Object> {

    private static final String CONTROLLER_PACKAGE = "com.galaplat.mfi.controller";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (StringHttpMessageConverter.class.isAssignableFrom(converterType)) {
            return false;
        }
        Class<?> declaringClass = returnType.getContainingClass();
        return declaringClass.getName().startsWith(CONTROLLER_PACKAGE)
                && !R.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof R) {
            return body;
        }
        return R.ok(body);
    }

}
