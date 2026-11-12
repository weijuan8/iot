package com.enjoyiot.module.eiot.service.iot;

import com.enjoyiot.eiot.message.event.MessageEvent;
import com.enjoyiot.eiot.message.listener.MessageEventListener;
import com.enjoyiot.framework.tenant.core.context.TenantContextHolder;
import com.enjoyiot.module.eiot.api.alert.dto.AlertConfig;
import com.enjoyiot.module.eiot.api.alert.dto.Message;
import com.enjoyiot.module.eiot.service.alert.AlertConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 【告警消息监听器】— 监听所有 MessageEvent，写入告警记录
 *
 * <h2>职责</h2>
 * 消费 MessageService 发布的 Spring ApplicationEvent，
 * 将告警消息转为告警记录写入 eiot_alert_record 表。
 *
 * <h2>与其他 Listener 的关系</h2>
 * <pre>
 * MessageService.sendMessage(message)
 *   → 发布 MessageEvent (Spring ApplicationEvent)
 *     → 多个 @EventListener 并行消费:
 *       ├── DingTalkEventListener    (condition: channelCode=='DingTalk')
 *       ├── EmailEventListener        (condition: channelCode=='Email')
 *       ├── SmsEventListener          (condition: channelCode=='SMS')
 *       ├── QyWechatEventListener     (condition: channelCode=='QyWechat')
 *       └── AlertMessageEventListener (无 condition — 所有消息都消费)
 *            ↑ 本类
 *            └── 写告警记录到 DB，用户在前端告警列表看到
 * </pre>
 *
 * <h2>告警记录内容</h2>
 * 告警记录包含：
 * <ul>
 *   <li>name — 告警配置名称</li>
 *   <li>level — 告警等级</li>
 *   <li>alertTime — 告警发生时间</li>
 *   <li>details — 模板渲染后的格式化内容</li>
 *   <li>readFlg — 是否已读（默认 false）</li>
 * </ul>
 */
@Slf4j
@Component
public class AlertMessageEventListener implements MessageEventListener {

    @Resource
    private AlertConfigService alertConfigService;

    /**
     * 消费所有 MessageEvent，写告警记录。
     *
     * <p>注意：无 @EventListener condition 过滤，
     * 所有消息（告警 + 恢复）都会被处理。
     *
     * @param event 消息事件，含 Message（模板渲染结果 + 告警配置 ID）
     */
    @Override
    @EventListener(classes = MessageEvent.class)
    public void doEvent(MessageEvent event) {
        Message message = event.getMessage();
        // 设置租户上下文（消息可能跨线程消费）
        TenantContextHolder.setTenantId(message.getTenantId());
        // 阈值告警不关联告警配置，alertConfigId 为 null，使用默认信息
        Long alertConfigId = message.getAlertConfigId();
        if (alertConfigId == null) {
            // 阈值告警：用 message 携带的 param 信息构建告警记录
            alertConfigService.addThresholdAlertRecord(message);
        } else {
            // 规则引擎告警：查告警配置获取 name + level
            AlertConfig alertConfig = alertConfigService.getAlertConfig(alertConfigId);
            alertConfigService.addAlertRecord(alertConfig, message.getFormatContent());
        }
    }
}
