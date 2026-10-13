package com.galaplat.mfi.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户 Mapper。
 */
@Mapper
public interface TenantMapper extends BaseMapper<TenantPO> {

}
