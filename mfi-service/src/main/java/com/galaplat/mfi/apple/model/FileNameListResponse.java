package com.galaplat.mfi.apple.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 文件名列表响应。苹果用 {@code retry_download_after} 表达"文件还在生成，稍后再来"，
 * 它是流水线延迟重试的时间依据。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileNameListResponse extends MfiResponse {

    @JsonProperty("file_count")
    private Integer fileCount;

    /** 苹果这里的字段名是单数 file_name，值却是数组，照原样映射 */
    @JsonProperty("file_name")
    private List<String> fileNames;

    @JsonProperty("retry_download_after")
    private String retryDownloadAfter;

    public boolean needRetry() {
        return retryDownloadAfter != null && !retryDownloadAfter.isBlank();
    }

}
