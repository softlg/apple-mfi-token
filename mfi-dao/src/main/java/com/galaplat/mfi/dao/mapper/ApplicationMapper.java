package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.ApplicationPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 申请单。这里只需要通用 CRUD：唯一的并发写点是状态聚合，而它必须带条件，因此写在
 * {@code ApplicationPpidMapper} 的聚合语句或本表的 lambdaUpdate 里，不额外开方法。
 */
@Mapper
public interface ApplicationMapper extends BaseMapper<ApplicationPO> {

}
