package com.galaplat.mfi.identity.application;

import com.galaplat.mfi.identity.domain.TenantProfile;

/**
 * 租户苹果调用参数的只读入口。
 *
 * <p>申请受理要把 technology 写进申请单快照，客户端装配要按租户决定出口地址，两者都只读这几列，
 * 所以没必要把租户表和 Mapper 暴露到身份上下文之外。
 */
public interface TenantProfileReader {

    /**
     * 读租户的苹果调用参数。租户不存在或已删除返回 null，由调用方决定拒绝还是回退。
     */
    TenantProfile find(long tenantId);

}
