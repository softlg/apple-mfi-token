package com.galaplat.mfi.common.enums;

/**
 * Token 生命周期状态。
 */
public enum TokenStatus {

    /** 苹果已下发、本系统已落库 */
    ISSUED,
    /** 已作为 used auth entity 注册回苹果 */
    REGISTERED,
    /** 本系统标记报废，苹果侧状态不一定同步 */
    SCRAPPED,
    /** 已调用苹果 destroyUnusedAuthEntities 销毁，不可逆 */
    DESTROYED;

    /**
     * 仅未使用的实体可被苹果销毁。
     */
    public boolean destroyable() {
        return this == ISSUED || this == SCRAPPED;
    }

}
