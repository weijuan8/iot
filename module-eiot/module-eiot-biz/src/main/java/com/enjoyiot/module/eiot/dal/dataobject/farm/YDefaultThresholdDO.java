package com.enjoyiot.module.eiot.dal.dataobject.farm;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 默认告警阈值模板 DO
 *
 * @author EnjoyIot
 */
@TableName("y_default_threshold")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YDefaultThresholdDO {

    @TableId
    private Long id;

    /** 默认阈值JSON模板 */
    private String thresholdsJson;

    /** 创建人 */
    private String creator;

    /** 更新人 */
    private String updater;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
