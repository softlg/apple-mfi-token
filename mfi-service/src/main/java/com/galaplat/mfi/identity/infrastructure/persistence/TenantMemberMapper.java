package com.galaplat.mfi.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.TenantMemberPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 成员关系 Mapper。
 *
 * <p>常规方法都受租户拦截器保护；下面两个带 {@link InterceptorIgnore} 的方法是本产品**唯一**
 * 允许跨租户读成员表的入口，各自只按 user_id 收敛结果，不接受调用方传入的任意条件。
 * 新增绕过方法必须同时给出等价的 user_id 约束，否则等于打开跨租户读口子。
 */
@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMemberPO> {

    /**
     * 查某个账号加入了哪些租户。登录与切换租户时还没有生效租户，无法被拦截器改写，
     * 因此必须显式绕过，并靠 user_id 自身圈定范围。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, user_id, owner_flag, status, join_time, create_time, update_time, deleted
            from t_tenant_member
            where user_id = #{userId} and deleted = 0
            order by owner_flag desc, join_time asc
            """)
    List<TenantMemberPO> selectByUserId(@Param("userId") long userId);

    /**
     * 校验某账号是否属于某租户。切换租户时上下文里仍是旧租户，同样必须绕过改写，
     * 由 tenant_id 与 user_id 两个条件共同定死唯一行。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, user_id, owner_flag, status, join_time, create_time, update_time, deleted
            from t_tenant_member
            where tenant_id = #{tenantId} and user_id = #{userId} and deleted = 0
            limit 1
            """)
    TenantMemberPO selectByTenantAndUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

    /**
     * 物理移除成员关系。用 {@code deleteById} 只会把 deleted 置 1，而
     * {@code uk_member_tenant_user(tenant_id, user_id)} 不含 deleted：被移出的人再次邀请时
     * 读不到旧行（查询都带 deleted = 0）却插不进新行，直接撞唯一键。
     * 条件已定死 tenant_id + user_id，仍保留拦截器改写做双保险。
     */
    @Delete("delete from t_tenant_member where tenant_id = #{tenantId} and user_id = #{userId}")
    int deleteByTenantAndUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

}
