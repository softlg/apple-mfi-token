package com.galaplat.mfi.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页壳。只暴露前端需要的四个字段，不把 MyBatis-Plus 的 Page 直接返回——它带着 orders、
 * optimizeCountSql 这些与调用方无关的内部状态，一旦出现在响应里就成了事实契约。
 */
@Data
public class PageResult<T> {

    private long pageNum;

    private long pageSize;

    private long total;

    private List<T> records;

    /**
     * 把实体分页映射成 VO 分页，分页元信息原样带过去。
     */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        PageResult<T> result = new PageResult<>();
        result.pageNum = page.getCurrent();
        result.pageSize = page.getSize();
        result.total = page.getTotal();
        result.records = page.getRecords().stream().map(mapper).toList();
        return result;
    }

}
