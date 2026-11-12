package com.enjoyiot.eiot.component.modbusCustom.model;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * ============================================================================
 * 【Modbus 定时任务线程池配置】
 *
 * 【职责】
 *  为 ModbusComponent 的定时任务（readTask + offlineCheckTask）提供
 *  独立的线程池，避免与其他组件的定时任务竞争线程资源。
 *
 * ============================================================================
 * 【为什么需要独立的线程池？】
 *
 *  1. Modbus 轮询任务可能耗时较长（多个设备 × 多个属性 × 2 秒间隔）
 *  2. 如果使用公共线程池，Modbus 任务可能阻塞其他组件的定时任务
 *  3. 独立线程池可以精细控制并发度和资源隔离
 *
 * ============================================================================
 * 【线程池参数】
 *
 *  - Pool Size: CPU 核心数 × 2
 *    说明：Modbus 轮询是 I/O 密集型任务（网络读写），
 *    使用 CPU 核心数 × 2 可以充分利用 CPU 等待 I/O 的间隙
 *
 *  - Thread Name Prefix: "modbus-task-pool-"
 *    便于日志追踪和问题排查
 *
 *  - WaitForTasksToCompleteOnShutdown: true
 *    应用关闭时等待正在执行的任务完成，避免数据丢失
 *
 *  - AwaitTerminationSeconds: 30
 *    最多等待 30 秒，超时后强制终止
 *
 * ============================================================================
 * 【使用方式】
 *
 *  ModbusComponent 通过 @Qualifier("ModbusTaskScheduler") 注入：
 *
 *    taskScheduler.scheduleAtFixedRate(this::readTask, Duration.ofSeconds(timer));
 *    taskScheduler.scheduleAtFixedRate(this::offlineCheckTask, Duration.ofSeconds(40));
 *
 * @see com.enjoyiot.eiot.component.modbusCustom.service.ModbusComponent Modbus 核心组件
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@EnableAsync(proxyTargetClass = true)
public class TaskConfiguration {

    /**
     * 【创建 Modbus 专用线程池调度器】
     *
     * Bean 名称：ModbusTaskScheduler
     * 注入方式：@Qualifier("ModbusTaskScheduler") ThreadPoolTaskScheduler
     *
     * 调度能力：
     *  - scheduleAtFixedRate：固定频率执行（readTask、offlineCheckTask）
     *  - 支持任务取消（ScheduledFuture.cancel()）
     *
     * @return 配置好的 ThreadPoolTaskScheduler 实例
     */
    @Bean(name = "ModbusTaskScheduler")
    public ThreadPoolTaskScheduler setThreadPoolTaskScheduler(){
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

        // 线程池大小：CPU 核心数 × 2
        // Modbus 轮询是 I/O 密集型，更多线程可以并发处理多个设备的读取
        taskScheduler.setPoolSize(Runtime.getRuntime().availableProcessors() * 2);

        // 线程名前缀，便于日志追踪
        taskScheduler.setThreadNamePrefix("modbus-task-pool-");

        // 执行 shutdown 时，等待前一个任务执行完（优雅停机）
        // 避免正在发送的 Modbus 命令被中断
        taskScheduler.setWaitForTasksToCompleteOnShutdown(true);

        // 执行 shutdown 时，等待的超时时间（30 秒）
        // 超过此时间未完成的任务将被强制终止
        taskScheduler.setAwaitTerminationSeconds(30);

        // 自定义错误处理器（可选）
        // 当某个线程执行时出现错误，会跳过线程内部的 Try/catch，进入 ErrorHandler
        // 需要实现 ErrorHandler 接口
        // taskScheduler.setErrorHandler(new MyTaskErrorHandler());

        return taskScheduler;
    }
}
