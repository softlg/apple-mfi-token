package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.TenantConfigPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户业务参数。
 */
@Mapper
public interface TenantConfigMapper extends BaseMapper<TenantConfigPO> {

    /**
     * 物理删除某租户的一个参数覆盖，用于"留空即回到全局默认"。
     *
     * <p>不用 {@code deleteById}：全局逻辑删除只把 deleted 置 1，而
     * {@code uk_config_tenant_key(tenant_id, config_key)} 不含 deleted，残留行会让下一次覆盖插入撞唯一键。
     * 这里保留拦截器改写，条件里的 tenant_id 与拦截器给出的同一个值，双保险。
     */
    @Delete("delete from t_tenant_config where tenant_id = #{tenantId} and config_key = #{configKey}")
    int deleteByKey(@Param("tenantId") long tenantId, @Param("configKey") String configKey);

}
