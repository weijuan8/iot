package com.enjoyiot.module.ai.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 模块 DO 基类
 *
 * 本模块表结构简化：仅含 主键 / 创建时间 / 更新时间，
 * 无 creator / updater / deleted / tenant_id 等通用审计与租户字段。
 */
@Data
public abstract class YAiBaseDO implements Serializable {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 创建时间（由数据库 DEFAULT CURRENT_TIMESTAMP 生成，不加 fill 注解，
     * 否则 MyBatis-Plus 会将 create_time 显式写入 INSERT 并赋 null，覆盖数据库默认值）
     */
    private LocalDateTime createTime;

    /**
     * 更新时间（由数据库 ON UPDATE CURRENT_TIMESTAMP 生成，同上不加 fill 注解）
     */
    private LocalDateTime updateTime;

}
