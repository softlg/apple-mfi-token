package com.galaplat.mfi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * apple-mfi-token 启动类。
 *
 * @author liaoguang
 * date: 2026-10-09
 * description: 多租户 MFI Token 申请 SaaS 服务入口
 */
@SpringBootApplication
@MapperScan("com.galaplat.mfi.dao.mapper")
public class MfiTokenApplication {

    public static void main(String[] args) {
        SpringApplication.run(MfiTokenApplication.class, args);
    }

}
