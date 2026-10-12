package com.galaplat.mfi.common.api;

import com.galaplat.mfi.controller.ExportTaskController;
import com.galaplat.mfi.controller.TokenController;
import com.galaplat.mfi.model.request.TokenPageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 返回值包装的边界。第二轮 P6 活体栽过一次：导出下载声明 {@code ResponseEntity<Resource>}，
 * 被包装成 {@code R} 之后按 Resource 选中的转换器直接抛 ClassCastException，附件下载到库里都写好了却拿不出来。
 */
class ResponseWrapAdviceTest {

    private final ResponseWrapAdvice advice = new ResponseWrapAdvice();

    @Test
    void plainVoIsWrapped() throws Exception {
        assertTrue(advice.supports(parameter(TokenController.class, "page", TokenPageRequest.class),
                MappingJackson2HttpMessageConverter.class), "普通业务返回值必须包装");
    }

    @Test
    void declaredResponseEntityIsLeftAlone() throws Exception {
        assertFalse(advice.supports(parameter(ExportTaskController.class, "download", String.class),
                MappingJackson2HttpMessageConverter.class), "自己声明状态码与媒体类型的返回值不能再包一层");
    }

    private static MethodParameter parameter(Class<?> controller, String methodName, Class<?> parameterType)
            throws NoSuchMethodException {
        Method method = controller.getDeclaredMethod(methodName, parameterType);
        // 索引 -1 表示返回值而不是入参
        return new MethodParameter(method, -1);
    }

}
