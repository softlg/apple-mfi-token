package com.galaplat.mfi;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * apple-mfi-token 启动类。
 *
 * <p>{@code @EnableScheduling} 只为兜底扫描服务：流水线靠 MQ 推进，但"提交后投递失败""消费者崩溃"
 * 这两类只能靠周期性回看数据库来自愈，不引调度框架，Boot 自带的单线程足够。扫描是幂等的
 * （CAS 领取），多实例同时跑也不会重复调苹果。
 *
 * <p>Mapper 扫描按 {@code @Mapper} 注解筛而不是按包名：mapper 接口现在分散在五个上下文的
 * infrastructure.persistence 包里，用包名扫会把上下文里的普通接口一起注册成 Mapper。
 *
 * @author liaoguang
 * date: 2026-10-09
 * description: 多租户 MFI Token 申请 SaaS 服务入口
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = "com.galaplat.mfi", annotationClass = Mapper.class)
public class MfiTokenApplication {

    public static void main(String[] args) {
        SpringApplication.run(MfiTokenApplication.class, args);
    }

}
