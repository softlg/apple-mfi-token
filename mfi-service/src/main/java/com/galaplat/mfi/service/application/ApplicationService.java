package com.galaplat.mfi.service.application;

import com.galaplat.mfi.common.api.PageResult;
import com.galaplat.mfi.model.request.ApplyCreateRequest;
import com.galaplat.mfi.model.request.ApplicationPageRequest;
import com.galaplat.mfi.model.vo.ApplicationRetryVo;
import com.galaplat.mfi.model.vo.ApplicationVo;
import com.galaplat.mfi.model.vo.ApplySubmitVo;

/**
 * 申请链路的同步段：校验、扣额度、落单、排分片，并保证消息只在提交成功后才离开进程。
 *
 * <p>苹果调用不在这里发生。五个阶段的推进属于 P5 的流水线，人工重放是流水线唯一的出口外按钮。
 */
public interface ApplicationService {

    /**
     * 受理一次申请。
     */
    ApplySubmitVo submit(ApplyCreateRequest request);

    /**
     * 申请单分页，默认按创建时间倒序。
     */
    PageResult<ApplicationVo> page(ApplicationPageRequest request);

    /**
     * 人工重放：只把终态失败（DEAD）的分片按当前阶段放回流水线，替代 MES 的 11 个人工补偿端点。
     */
    ApplicationRetryVo retry(String applicationNo);

}
