package com.galaplat.mfi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 导出专用线程池。
 *
 * <p>不复用 {@code @Async} 的公共池：一个一万行的导出会占住线程几十秒，和它同池的其它异步任务
 * 会一起排队。这里独立成池、队列有界，队列满就直接拒绝并由受理方翻译成"稍后重试"。
 *
 * <p>拒绝策略保持默认的 Abort：绝不能用 CallerRuns，那等于把写盘跑回 HTTP 请求线程，
 * 正是 MES 同步导出把连接耗到超时的老路。
 */
@Configuration
public class ExportExecutorConfig {

    @Bean("exportExecutor")
    public ThreadPoolTaskExecutor exportExecutor(ExportProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerSize());
        executor.setMaxPoolSize(properties.getWorkerSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("mfi-export-");
        executor.initialize();
        return executor;
    }

}
