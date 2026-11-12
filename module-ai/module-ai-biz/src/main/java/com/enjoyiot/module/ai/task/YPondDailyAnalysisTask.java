package com.enjoyiot.module.ai.task;

import static com.enjoyiot.module.ai.enums.YAiAnalysisConstants.DAILY_ANALYSIS_CRON;

import com.enjoyiot.module.ai.service.analysis.YPondAnalysisService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 塘口每日 AI 分析定时任务
 *
 * 每天 06:00 查询出所有塘口，逐个分析：
 * 结合近24小时数据（传感器/增氧机）与昨日+今日上报、塘口画像，生成当天的风险总结、投喂建议、溶氧/氧机建议、水质建议，
 * 并将新规律/新习惯沉淀到塘口画像，为次日复盘提供依据。
 */
@Slf4j
@Component
public class YPondDailyAnalysisTask {

    @Resource
    private YPondAnalysisService pondAnalysisService;

    /**
     * 每天 06:00 执行
     */
    @Scheduled(cron = DAILY_ANALYSIS_CRON)
    public void dailyAnalysis() {
        log.info("[YPondDailyAnalysisTask][dailyAnalysis] 开始每日塘口 AI 分析");
        try {
            int success = pondAnalysisService.analyzeAllPond();
            log.info("[YPondDailyAnalysisTask][dailyAnalysis] 每日分析完成，成功 {} 个塘口", success);
        } catch (Exception e) {
            log.error("[YPondDailyAnalysisTask][dailyAnalysis] 每日分析异常", e);
        }
    }

}
