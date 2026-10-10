package com.galaplat.mfi.apple.model;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 苹果返回的 token 文件（xlsx）行。表头固定为 PPID / Token ID / Base64-encoded Token / CRC32 in HEX。
 *
 * <p>{@code base64EncodedToken} 与 {@code tokenId} 属凭证材料，禁止进日志与错误消息；
 * 需要定位问题时只打印 CRC32 与行号。
 */
@Data
public class TokenFileRow {

    @ExcelProperty(value = "PPID", index = 0)
    private String ppid;

    @ExcelProperty(value = "Token ID", index = 1)
    private String tokenId;

    @ExcelProperty(value = "Base64-encoded Token", index = 2)
    private String base64EncodedToken;

    @ExcelProperty(value = "CRC32 in HEX", index = 3)
    private String crc32;

}
