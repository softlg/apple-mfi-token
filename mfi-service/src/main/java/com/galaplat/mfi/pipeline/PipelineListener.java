package com.galaplat.mfi.pipeline;

import com.galaplat.mfi.mq.RabbitTopology;
import com.galaplat.mfi.mq.StageMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 五个阶段的入口。每个方法只做一件事：把消息交给 {@link StageRunner}。
 *
 * <p>刻意薄到没有逻辑——阶段队列与处理器的对应关系一旦散进监听方法，"哪个阶段在哪个队列上跑"
 * 就只能靠读注解猜。所有跃迁、重试、投递语义都在 StageRunner 里。
 *
 * <p>容器参数沿用全局配置：{@code prefetch=1} 且关闭 Spring 层重试。一个阶段可能几十秒（下载大文件），
 * 预取多条只会让消息堆在某个消费者内存里，而兜底扫描根本看不见它们。
 */
@Component
public class PipelineListener {

    private final StageRunner runner;

    public PipelineListener(StageRunner runner) {
        this.runner = runner;
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_APPLY)
    public void onApply(StageMessage message) {
        runner.run(message);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_NAME)
    public void onFileName(StageMessage message) {
        runner.run(message);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CONTENT)
    public void onFileContent(StageMessage message) {
        runner.run(message);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_REGISTER)
    public void onRegister(StageMessage message) {
        runner.run(message);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_FINISH)
    public void onFinish(StageMessage message) {
        runner.run(message);
    }

}
