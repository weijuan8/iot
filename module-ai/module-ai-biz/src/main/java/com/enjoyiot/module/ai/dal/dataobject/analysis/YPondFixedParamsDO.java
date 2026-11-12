package com.enjoyiot.module.ai.dal.dataobject.analysis;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enjoyiot.module.ai.dal.dataobject.YAiBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 塘口固定参数 DO（池塘安全红线/基准参数）
 *
 * 表：y_pond_fixed_params
 */
@TableName("y_pond_fixed_params")
@KeySequence("y_pond_fixed_params_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class YPondFixedParamsDO extends YAiBaseDO {

    /**
     * 塘口ID
     */
    private Long pondId;

    /**
     * 塘口最大负载量（池塘最大承载产量kg）
     */
    private BigDecimal maxLoadKg;

    /**
     * pH范围下限（适宜下限，如7.5）
     */
    private BigDecimal phMin;

    /**
     * pH范围上限（适宜上限，如8.5）
     */
    private BigDecimal phMax;

    /**
     * 溶氧下限（mg/L，低于则预警）
     */
    private BigDecimal dissolvedOxygenMin;

    /**
     * 溶氧上限（mg/L，过高警惕）
     */
    private BigDecimal dissolvedOxygenMax;

    /**
     * 氨氮上限（mg/L）
     */
    private BigDecimal ammoniaNitrogenMax;

    /**
     * 亚硝酸盐上限（mg/L）
     */
    private BigDecimal nitriteMax;

    /**
     * 水温适宜下限（℃）
     */
    private BigDecimal tempMin;

    /**
     * 水温适宜上限（℃）
     */
    private BigDecimal tempMax;

    /**
     * 透明度适宜范围（cm），如：30-40
     */
    private String transparencyRange;

    /**
     * 投喂策略基准（保守/常规/激进）
     */
    private String feedingStrategyBase;

    /**
     * pH日最大波动
     */
    private BigDecimal phDailyFluctMax;

    /**
     * 叶绿素a范围（μg/L），如：10-50
     */
    private String chlARange;

    /**
     * 电导率范围（μS/cm），如：300-800
     */
    private String conductivityRange;

}
