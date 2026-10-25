package com.aicopilot.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * AI 能力调用的统一计时与失败计数。
 *
 * <p>口径只有一个：{@code aicopilot.ai.request}，按 {@code capability}
 * （哪个能力）与 {@code outcome}（ok/error）两个标签聚合。Prometheus 里可以直接算出
 * 每个能力的 P95 耗时与失败率 —— 这正是「AI 服务挂了/变慢了」最先被看见的地方。
 *
 * <p>放在 {@link AiServiceConfiguration} 的每个 lambda 入口上而不是 HTTP 工具方法里，
 * 是因为「一次能力调用」的边界定义在函数式接口上；disabled 分支不算调用
 * （没有发生远程请求，计入反而会稀释真实延迟）。
 */
final class AiClientMetrics {

    private final MeterRegistry registry;

    AiClientMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    <T> T timed(String capability, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            T result = call.get();
            record(capability, "ok", System.nanoTime() - start);
            return result;
        } catch (RuntimeException exception) {
            record(capability, "error", System.nanoTime() - start);
            throw exception;
        }
    }

    void timed(String capability, Runnable call) {
        timed(capability, () -> {
            call.run();
            return null;
        });
    }

    private void record(String capability, String outcome, long elapsedNanos) {
        Timer.builder("aicopilot.ai.request")
                .description("AI service capability call duration")
                .tag("capability", capability)
                .tag("outcome", outcome)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
