package likelion.mlb.backendProject.global.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulerConfig {

    /** WebSocket/STOMP와 분리된 match 전용 스케줄러 */
    @Bean(name = "matchTaskScheduler")
    public ThreadPoolTaskScheduler matchTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("match-scheduler-");
        s.setRemoveOnCancelPolicy(true);
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.initialize();
        return s;
    }
}
