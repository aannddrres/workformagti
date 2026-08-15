package ge.magti.portal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounds the pool that builds export files (audit PR-12).
 *
 * <p>{@code ExportJobWorker.buildAndStore} is {@code @Async} with no
 * qualifier, so it ran on Spring Boot's default {@code applicationTaskExecutor}
 * -- which has an <b>unbounded queue</b>. Every queued task holds the full
 * row set for its export in memory until a thread picks it up, so a burst of
 * requests (or one manager clicking the button repeatedly while nothing
 * appears to happen -- see FE-03, where the spinner never resolved) could
 * queue work faster than it drains and push the JVM toward an
 * OutOfMemoryError. Nothing would have refused the work; it would simply
 * accumulate.
 *
 * <p>Bounded on both axes, and the queue is deliberately shallow. An export
 * has a one-hour TTL ({@code ExportJobWorker.EXPORT_JOB_TTL_SECONDS}) and a
 * user watching a spinner, so a job that sits behind fifty others is useless
 * by the time it runs. Better to refuse it now, visibly, than to build it
 * for nobody.
 *
 * <p>{@link ThreadPoolExecutor.CallerRunsPolicy} on saturation: the request
 * thread that submitted the work builds it itself. That is slow -- and that
 * is the point. It applies backpressure to the source of the load instead of
 * dropping a job the user believes was accepted, and it cannot lose work.
 * The alternative, {@code AbortPolicy}, would surface as a rejected task the
 * caller would have to translate into a user-visible failure; there is no
 * such path today, so silently dropping is the more likely outcome of
 * choosing it.
 */
@Configuration
public class ExportExecutorConfig {

    private static final Logger logger = LoggerFactory.getLogger(ExportExecutorConfig.class);

    /**
     * Must be named {@code applicationTaskExecutor} to REPLACE Boot's
     * auto-configured bean rather than sit alongside it -- {@code @Async}
     * with no qualifier resolves by that name, and adding a second executor
     * under a different name would leave the unbounded one in use while
     * looking fixed.
     */
    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("export-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let an in-flight export finish on shutdown rather than leaving a
        // job stuck at "processing" forever with its row already committed.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        logger.info("Export task executor: core={}, max={}, queue={}, saturation=caller-runs",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 20);
        return executor;
    }
}
