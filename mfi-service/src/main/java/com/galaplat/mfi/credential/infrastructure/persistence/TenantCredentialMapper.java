package com.galaplat.mfi.credential.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.credential.infrastructure.persistence.TenantCredentialPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户凭证。常规读写全部由租户拦截器改写，本类不提供任何跨租户方法。
 */
@Mapper
public interface TenantCredentialMapper extends BaseMapper<TenantCredentialPO> {

    /**
     * 物理删除某租户的凭证行，条件里已经定死 tenant_id，因此绕过改写以免依赖调用方的绑定上下文
     * （删除入口可能来自非 HTTP 线程）。
     *
     * <p>两个理由都必须物理删：一是 {@code uk_credential_tenant(tenant_id)} 不含 deleted，
     * 逻辑删除的残留行会让下一次上传撞唯一键；二是被删除的凭证仍是可解密的密文，
     * 留在库里只多一份密钥材料被误用的机会，换不到任何历史价值。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("delete from t_tenant_credential where tenant_id = #{tenantId}")
    int deleteByTenant(@Param("tenantId") long tenantId);

}
