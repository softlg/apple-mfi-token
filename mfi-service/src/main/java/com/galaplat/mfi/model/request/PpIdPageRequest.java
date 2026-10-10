package com.galaplat.mfi.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * PPID 池分页查询条件。产品型号与 SPU 是选池维度，按精确匹配；PPID 长且容易只记得片段，按模糊匹配。
 *
 * <p>租户维度不在这里：条件由租户拦截器注入，请求里传任何租户字段都不生效。
 */
@Data
public class PpIdPageRequest {

    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 200, message = "每页最多 200 条")
    private Integer pageSize = 20;

    /** PPID 片段，忽略大小写 */
    private String ppid;

    private String spu;

    private String productModel;

    /** 1=可用 0=停用，留空表示不限 */
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;

}
