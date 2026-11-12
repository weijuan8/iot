
addr=1;fun=3;regAddr=1;regNum=1;dataType=1;json=PH;th1=0;th2=0;k=0.0100;b=0.0000;emg=0
addr=2;fun=3;regAddr=2;regNum=2;dataType=12;json=tem;th1=0;th2=0;k=1.0000;b=0.0000;emg=0
addr=2;fun=3;regAddr=0;regNum=2;dataType=12;json=DO;th1=0;th2=0;k=1.0000;b=0.0000;emg=0
addr=10;fun=3;regAddr=0;regNum=1;dataType=1;json=NH;th1=0;th2=0;k=0.0100;b=0.0000;emg=0
addr=17;fun=3;regAddr=12;regNum=1;dataType=1;json=pv_input_volt;th1=0;th2=0;k=0.1000;b=0.0000;emg=0
addr=17;fun=3;regAddr=16;regNum=1;dataType=1;json=pv_input_curr;th1=0;th2=0;k=0.0100;b=0.0000;emg=0
addr=17;fun=3;regAddr=13;regNum=1;dataType=1;json=bat_volt;th1=0;th2=0;k=0.1000;b=0.0000;emg=0
addr=17;fun=3;regAddr=15;regNum=1;dataType=1;json=bat_curr;th1=0;th2=0;k=0.0100;b=0.0000;emg=0
addr=17;fun=3;regAddr=21;regNum=1;dataType=1;json=bat_soc;th1=0;th2=0;k=1.0000;b=0.0000;emg=0
addr=17;fun=3;regAddr=24;regNum=1;dataType=1;json=batteryType;th1=0;th2=0;k=1.0000;b=0.0000;emg=0
addr=17;fun=3;regAddr=18;regNum=1;dataType=1;json=ctrl_temp;th1=0;th2=0;k=1.0000;b=-30.0000;emg=0
addr=17;fun=3;regAddr=20;regNum=1;dataType=1;json=charge_volt_target;th1=0;th2=0;k=0.1000;b=0.0000;emg=0
addr=17;fun=3;regAddr=14;regNum=1;dataType=1;json=load_curr;th1=0;th2=0;k=0.0100;b=0.0000;emg=0
addr=17;fun=3;regAddr=19;regNum=1;dataType=1;json=charge_curr_target;th1=0;th2=0;k=0.1000;b=0.0000;emg=0
addr=17;fun=3;regAddr=17;regNum=1;dataType=1;json=env_temp;th1=0;th2=0;k=1.0000;b=-30.0000;emg=0
addr=17;fun=3;regAddr=0;regNum=1;dataType=1;json=version;th1=0;th2=0;k=0.0000;b=0.0000;emg=0
addr=17;fun=3;regAddr=26;regNum=1;dataType=1;json=voltFloat;th1=0;th2=0;k=0.1000;b=0.0000;emg=0
addr=17;fun=3;regAddr=25;regNum=1;dataType=1;json=voltLevel;th1=0;th2=0;k=0.0000;b=0.0000;emg=0


{
"properties": [
{
"identifier": "bat_soc",
"dataType": {
"type": "float",
"specs": {}
},
"name": "电池电量",
"accessMode": "r",
"description": "电池电量",
"unit": "%",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "bat_volt",
"dataType": {
"type": "float",
"specs": {}
},
"name": "电池电压",
"accessMode": "r",
"description": "",
"unit": "V",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "bat_curr",
"dataType": {
"type": "float",
"specs": {}
},
"name": "电池电流",
"accessMode": "r",
"description": "",
"unit": "A",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "pv_input_volt",
"dataType": {
"type": "float",
"specs": {}
},
"name": "太阳能板输入电压",
"accessMode": "r",
"description": "",
"unit": "V",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "pv_input_curr",
"dataType": {
"type": "float",
"specs": {}
},
"name": "太阳能板输入电流",
"accessMode": "r",
"description": "",
"unit": "A",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "charge_volt_target",
"dataType": {
"type": "float",
"specs": {}
},
"name": "目标电压",
"accessMode": "r",
"description": "",
"unit": "V",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "charge_curr_target",
"dataType": {
"type": "float",
"specs": {}
},
"name": "目标电流",
"accessMode": "r",
"description": "",
"unit": "A",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "load_curr",
"dataType": {
"type": "float",
"specs": {}
},
"name": "负载电流",
"accessMode": "r",
"description": "",
"unit": "A",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "env_temp",
"dataType": {
"type": "float",
"specs": {}
},
"name": "环境温度",
"accessMode": "r",
"description": "",
"unit": "℃",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "ctrl_temp",
"dataType": {
"type": "float",
"specs": {}
},
"name": "控制器温度",
"accessMode": "r",
"description": "",
"unit": "℃",
"iconId": null,
"icon": null,
"proData": null
},
{
"identifier": "version",
"dataType": {
"type": "string",
"specs": {}
},
"name": "软件版本",
"accessMode": "r",
"description": "",
"unit": null,
"iconId": null,
"icon": null,
"proData": null
}
],
"services": [],
"events": []
}




# 20250530 提交内容






## 1. 修复物模型消息推送时，messageID 为空的问题；
```java
if (!StringUtils.hasText(action.getId())) {
message.setId(IdUtil.fastSimpleUUID());
}else {
message.setId(action.getId());
}
if (ObjectUtils.isEmpty(action.getTime())) {
message.setTime(System.currentTimeMillis());
}else {
message.setTime(action.getTime());
}
```


## 2. 增加UDP组件
### 2.1 UDP组件代码简单说明
#### 2.1.1 结构
和其他协议的组件一样：
UdpServer 服务启动入口；
UdpComponent 组件 判断数据类型以及格式，并调用对应的解析方法；
UdpVerticle 启动方法
UdpPacketDispatcher 基于策略模式，
将所有 PacketHandler 写成静态内部类，并用 list 固定顺序(按照性能优先级降序)
#### 2.2.2  接入方式
数据库执行sql

```mysql
INSERT INTO `eiot_component`( `name`, `type`, `config`, `status`, `remark`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES ('内置官方 UDP 组件', 'udp', '{\n     \"port\": 7101,\n     \"host\": \"0.0.0.0\"\n}', 1, '', NULL, '2025-02-19 01:01:54', '1', '2025-05-28 10:54:00', b'0');
```


