package com.galaplat.mfi.common.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
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
 *
 * <p>{@code ResponseEntity} 返回值同样不包装：声明它的接口自己拥有状态码、响应头与媒体类型
 * （导出下载就是二进制附件），再套一层 {@code R} 会让按 Resource 选中的转换器拿到 R 而抛错。
 */
@RestControllerAdvice(basePackages = "com.galaplat.mfi.controller")
public class ResponseWrapAdvice implements ResponseBodyAdvice<Object> {

    private static final String CONTROLLER_PACKAGE = "com.galaplat.mfi.controller";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (StringHttpMessageConverter.class.isAssignableFrom(converterType)) {
            return false;
        }
        Class<?> returnTypeClass = returnType.getParameterType();
        if (HttpEntity.class.isAssignableFrom(returnTypeClass)) {
            return false;
        }
        Class<?> declaringClass = returnType.getContainingClass();
        return declaringClass.getName().startsWith(CONTROLLER_PACKAGE)
                && !R.class.isAssignableFrom(returnTypeClass);
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
