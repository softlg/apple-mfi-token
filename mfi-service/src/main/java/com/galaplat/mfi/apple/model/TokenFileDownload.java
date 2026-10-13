package com.galaplat.mfi.apple.model;

import com.galaplat.mfi.apple.model.TokenFileRow;
import lombok.Data;

import java.util.List;

/**
 * token 文件下载结果。成功时给解析出的行，失败时给 HTTP 状态与苹果的错误体（若有）。
 *
 * <p>{@code rows} 含 token 原文，调用方只可入库或转存，不得序列化进日志与接口响应。
 */
@Data
public class TokenFileDownload {

    private boolean success;

    private int httpStatus;

    private List<TokenFileRow> rows;

    private MfiResponse errorBody;

    public static TokenFileDownload ok(int httpStatus, List<TokenFileRow> rows) {
        TokenFileDownload result = new TokenFileDownload();
        result.success = true;
        result.httpStatus = httpStatus;
        result.rows = rows;
        return result;
    }

    public static TokenFileDownload failed(int httpStatus, MfiResponse errorBody) {
        TokenFileDownload result = new TokenFileDownload();
        result.success = false;
        result.httpStatus = httpStatus;
        result.errorBody = errorBody;
        return result;
    }

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }

}
