package com.enjoyiot.module.system.api.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户简易 Response DTO
 *
 * @author EnjoyIot
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantSimpleRespDTO {

    /**
     * 租户ID
     */
    private Long id;

    /**
     * 租户名称
     */
    private String name;
}
