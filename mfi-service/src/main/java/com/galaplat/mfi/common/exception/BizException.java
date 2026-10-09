package com.galaplat.mfi.common.exception;

import com.galaplat.mfi.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。消息会原样返回给前端，因此不得包含 SQL、内部地址、其他租户数据或任何凭证片段。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(String message) {
        this(ErrorCode.BIZ_REJECTED, message);
    }

    public static BizException of(String message) {
        return new BizException(message);
    }

}
