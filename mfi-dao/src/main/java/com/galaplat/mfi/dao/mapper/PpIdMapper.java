package com.galaplat.mfi.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.galaplat.mfi.dao.entity.PpIdPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * PPID 池。这里的三条写方法是"不超卖"的唯一防线，条件更新由数据库判定，不依赖应用层读后写。
 */
@Mapper
public interface PpIdMapper extends BaseMapper<PpIdPO> {

    /**
     * 扣减一份额度的余量。{@code remaining_quantity >= quantity} 进 WHERE，
     * 返回 0 就是"不够"，返回 1 才是扣到了；并发下同一行只有一个赢家，因此不会超卖。
     *
     * <p>赋值顺序有硬约束：MySQL 单表 UPDATE 的 SET 从左到右求值，后面的表达式会读到前面列的新值。
     * 所以 {@code status} 必须排在最前，它判断的才是扣减之前的余量。
     */
    @Update("""
            update t_ppid
            set status = case when remaining_quantity <= #{quantity} then 0 else status end,
                applied_quantity = applied_quantity + #{quantity},
                remaining_quantity = remaining_quantity - #{quantity}
            where tenant_id = #{tenantId} and id = #{id} and deleted = 0 and status = 1
              and remaining_quantity >= #{quantity}
            """)
    int deduct(@Param("tenantId") long tenantId, @Param("id") long id, @Param("quantity") int quantity);

    /**
     * 退回扣减。默认不改动 status：人工停用的行不该因为一次回滚就变回可分配。
     * 只有调用方明确知道该行是被自己刚才的扣减自动停用时，才传 reactivate=true。
     */
    @Update("""
            update t_ppid
            set applied_quantity = applied_quantity - #{quantity},
                remaining_quantity = remaining_quantity + #{quantity},
                status = case when #{reactivate} = true then 1 else status end
            where tenant_id = #{tenantId} and id = #{id} and deleted = 0
              and applied_quantity >= #{quantity}
            """)
    int restore(@Param("tenantId") long tenantId, @Param("id") long id,
                @Param("quantity") int quantity, @Param("reactivate") boolean reactivate);

    /**
     * 按 产品型号 + SPU 取可分配额度，余量多的优先——与 MES 既有分配顺序一致，
     * 让大额先用、零头留后，减少同一次申请拆出的分片数。
     */
    @Select("""
            select * from t_ppid
            where tenant_id = #{tenantId} and product_model = #{productModel} and spu = #{spu}
              and status = 1 and deleted = 0 and remaining_quantity > 0
            order by remaining_quantity desc, id asc
            """)
    List<PpIdPO> selectAllocatable(@Param("tenantId") long tenantId,
                                   @Param("productModel") String productModel,
                                   @Param("spu") String spu);

    /**
     * 池子总览，按 产品型号 + SPU 聚合。用 Map 而不建投影类，与 {@code MemberServiceImpl}
     * 里跨租户查租户名的既有做法一致。
     */
    @Select("""
            select product_model, spu,
                   count(*) as ppid_rows,
                   sum(status = 1) as enabled_rows,
                   sum(total_quantity) as total_quantity,
                   sum(applied_quantity) as applied_quantity,
                   sum(remaining_quantity) as remaining_quantity
            from t_ppid
            where tenant_id = #{tenantId} and deleted = 0
            group by product_model, spu
            order by product_model asc, spu asc
            """)
    List<Map<String, Object>> selectPoolStats(@Param("tenantId") long tenantId);

    /**
     * 批量导入用的多值插入。导入一万行时逐条 insert 会把连接占满，这里按分片调用。
     * 列清单里显式写了 tenant_id，拦截器便不会再重复追加。
     */
    @Insert("""
            <script>
            insert into t_ppid (tenant_id, ppid, spu, product_model, total_quantity,
                                applied_quantity, remaining_quantity, status, remark, create_by)
            values
            <foreach collection="rows" item="r" separator=",">
            (#{r.tenantId}, #{r.ppid}, #{r.spu}, #{r.productModel}, #{r.totalQuantity},
             #{r.appliedQuantity}, #{r.remainingQuantity}, #{r.status}, #{r.remark}, #{r.createBy})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<PpIdPO> rows);

}
