package com.enjoyiot.module.ai.dal.dataobject.analysis;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.module.ai.dal.dataobject.YAiBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 农户习惯库 DO（逐条总结农户养殖习惯）
 *
 * 表：y_farmer_habit_library
 */
@TableName("y_farmer_habit_library")
@KeySequence("y_farmer_habit_library_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class YFarmerHabitLibraryDO extends YAiBaseDO {

    /**
     * 塘口ID（可空，空表示农户全局习惯）
     */
    private Long pondId;

    /**
     * 习惯标题（如：习惯在傍晚一次性多投喂）
     */
    private String habitTitle;

    /**
     * 习惯描述（完整习惯说明）
     */
    private String habitDetail;

    /**
     * 习惯倾向（偏保守/偏常规/偏激进/不规律）
     */
    private String habitTendency;

    /**
     * 出现频次（被观察到的累计次数）
     */
    private Integer occurrenceCount;

    /**
     * 可信度（0-100，观察越多次越可信）
     */
    private Integer credibility;

    /**
     * 对养殖影响（正面/中性/负面/待观察）
     */
    private String impact;

    /**
     * 影响说明（如：傍晚多投易导致夜间耗氧增加）
     */
    private String impactNote;

}
