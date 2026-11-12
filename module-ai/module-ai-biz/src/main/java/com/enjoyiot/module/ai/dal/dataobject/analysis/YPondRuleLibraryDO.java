package com.enjoyiot.module.ai.dal.dataobject.analysis;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.module.ai.dal.dataobject.YAiBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 塘口规律库 DO（AI复盘沉淀的可复用经验）
 *
 * 表：y_pond_rule_library
 */
@TableName("y_pond_rule_library")
@KeySequence("y_pond_rule_library_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class YPondRuleLibraryDO extends YAiBaseDO {

    /**
     * 塘口ID
     */
    private Long pondId;

    /**
     * 规律标题（如：连续3天凌晨溶氧<4mg/L易浮头）
     */
    private String ruleTitle;

    /**
     * 规律内容（完整规律描述）
     */
    private String ruleContent;

    /**
     * 可信度（0-100，初始值由AI评定）
     */
    private Integer credibility;

    /**
     * 验证次数（被多次验证可行的累计次数）
     */
    private Integer verifyCount;

    /**
     * 失效次数（与事实不符的次数）
     */
    private Integer invalidCount;

    /**
     * 最近验证时间
     */
    private LocalDateTime lastVerifiedAt;

}
