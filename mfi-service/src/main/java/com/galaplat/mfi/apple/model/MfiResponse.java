package com.galaplat.mfi.apple.model;

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

    /**
     * 这个失败是否"再试也不会变好"，调用方据此决定终态失败还是退避重试。
     *
     * <p>{@code code} 是 HTTP 状态码（由客户端在解析后统一覆盖），不是苹果错误体里的同名字段。
     * 4xx 里绝大多数确实是证书权限、PPID 无效这类配置问题，重试只会白占出口 IP 配额；但 429 是苹果
     * 对同源 IP 的限流、408 是它前面网关的超时，两者都属于"稍后再来"。把它们判成终态失败，一次限流
     * 就会打死整片已扣额度的 token，只能人工重放。
     */
    public boolean isPermanentRejection() {
        return code != null && code >= 400 && code < 500 && code != 408 && code != 429;
    }

}
