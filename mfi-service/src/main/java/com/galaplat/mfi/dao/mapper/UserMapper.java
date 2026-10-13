package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.UserPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户账号 Mapper。t_user 是全局表，查询不会被租户拦截器改写，
 * 因此这里绝不能出现"按 id 取用户后就直接当作已授权"的隐含假设。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserPO> {

}
