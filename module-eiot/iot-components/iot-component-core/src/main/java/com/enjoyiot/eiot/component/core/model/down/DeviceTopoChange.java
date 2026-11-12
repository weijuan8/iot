
package com.enjoyiot.eiot.component.core.model.down;


import com.enjoyiot.eiot.component.core.model.AbstractAction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 【拓扑变更通知】- 平台通知网关设备拓扑关系已变更（下行消息）
 *
 * 当平台侧修改了网关-子设备的拓扑关系后（如管理员手动添加/删除子设备），
 * 通过此消息通知网关更新其本地拓扑信息。
 *
 * 使用泛型 T 使得 params 可以承载不同类型的拓扑变更数据。
 *
 * 【处理链路】
 *  1. 管理员在后台修改设备拓扑关系
 *  2. biz 层构造 ThingModelMessage（type="topo_change"）
 *  3. 消息通过组件路由下发给网关设备
 *  4. 网关更新本地子设备列表
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class DeviceTopoChange<T> extends AbstractAction {

    /**
     * 拓扑变更参数（泛型）
     * 包含拓扑变更的详细信息，如新增/删除的子设备列表
     */
    private T params;


}
