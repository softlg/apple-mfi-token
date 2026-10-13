package com.galaplat.mfi.apple.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 苹果各接口的通用响应体：申请、状态、注册、销毁、token 信息都落在这几个字段上。
 *
 * <p>{@code ignoreUnknown} 必须为 true——苹果新增字段不能把已有链路打挂。MES 那版没有此项，
 * 是真实联调里最先炸的一处。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MfiResponse {

    @JsonProperty("code")
    private Integer code;

    @JsonProperty("error")
    private String error;

    @JsonProperty("error_code")
    private String errorCode;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("download_availability")
    private String downloadAvailability;

    @JsonProperty("success_count")
    private Integer successCount;

    @JsonProperty("request_status")
    private String requestStatus;

    /**
     * 以苹果的错误字段判定成功，而不是只看 HTTP 状态：HTTP 200 带 error_code 的情况确实存在。
     */
    public boolean isSuccess() {
        return error == null && errorCode == null && errorMessage == null;
    }

    /**
     * 可安全进日志与对外消息的摘要：只有错误码与苹果给的文案，不含 token 内容。
     */
    public String briefError() {
        return "errorCode=" + errorCode + ", code=" + code + ", message=" + errorMessage;
    }

}
