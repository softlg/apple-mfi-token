package com.galaplat.mfi.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PPID 池的一份额度：同一 PPID + SPU 下的可申请总量与余量。
 *
 * <p>一行不是一个 token，而是"苹果授予的 quantity 个"，所以扣减走 remaining_quantity 的条件更新，
 * 由数据库保证并发下不会超卖（{@code PpIdMapper.deduct}）。
 */
@Data
@TableName("t_ppid")
public class PpIdPO {

    /** 可用：可被分配 */
    public static final int STATUS_ENABLED = 1;
    /** 停用：余量归零自动置此，或人工停用，都不参与分配 */
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 苹果产品计划 ID */
    private String ppid;

    private String spu;

    /** 选池维度，与 spu 共同决定一次申请能用哪些额度 */
    private String productModel;

    private Integer totalQuantity;

    private Integer appliedQuantity;

    private Integer remainingQuantity;

    private Integer status;

    private String remark;

    private Long createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除位。本表只在"从未用过"时物理删，用过的一律停用，因此不会留下占唯一键的残行 */
    private Integer deleted;

}
