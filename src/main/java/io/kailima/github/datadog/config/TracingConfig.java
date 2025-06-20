package io.kailima.github.datadog.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.kailima.github.datadog.aspect.DataDogTracingAspect;

@Configuration
@EnableAspectJAutoProxy
public class TracingConfig {

    @Bean(name = "datadogAsyncExecutor")
    public ThreadPoolTaskExecutor datadogAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("dd-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }

    @Bean
    public DataDogTracingAspect dataDogTracingAspect(Executor datadogAsyncExecutor) {
        DataDogTracingAspect aspect = new DataDogTracingAspect();
        aspect.setExecutor(datadogAsyncExecutor);
        return aspect;
    }
}
