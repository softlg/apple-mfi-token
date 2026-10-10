package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.OperationLogPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作留痕。只追加、不修改，所以只有通用插入，没有更新与删除方法。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogPO> {

}
