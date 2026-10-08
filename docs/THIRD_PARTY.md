# 第三方来源与许可范围

Android 中转 Java 实现为本项目独立编写；协议兼容工作参考公开的字段、线路格式和 API 说明。未将 AstroBox 安装包、Rust 实现或其生成的 Protobuf 源码打包进 APK。参考资料各自的许可证继续适用，本项目不将其重新声明为 MIT。

| 来源 | 使用范围 |
| --- | --- |
| [AstroBox Core · 0bc0e7a](https://github.com/AstralSightStudios/AstroBox-NG-Module-Core/tree/0bc0e7a) | SAR v2、认证、MASS、安装／应用响应的公开线路事实；上游代码遵循其 AGPL 许可 |
| [AstroBox Protobuf · 03a9201](https://github.com/AstralSightStudios/AstroBox-NG-Module-Pb/tree/03a9201) | 消息类型和字段编号 |
| [AstroBox Classic SPP · b7ca7b3](https://github.com/AstralSightStudios/AstroBox-NG-Plugin-BtClassicSpp/tree/b7ca7b3) | 标准 SPP UUID 与公开连接行为参考 |
| [minstall · 56f74ec](https://github.com/HyperionD/minstall/tree/56f74ec801e5b1674d0f45ef49333f5cba9d985d) | 运动健康导出入口和认证日志字段的公开记录；其他型号的实测不当作 Q63 证明 |
| [Android 蓝牙连接](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)、[权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) | Android 官方公开 API 和权限模型 |
| [band10-toolkit · 549abf5](https://github.com/utsabfdahal/band10-toolkit/tree/549abf5046ce207327ca82fdc20b0320c27a3830) | 主表盘项目的原生打包器基线，MIT；该表盘工具链没有作为 Android 中转依赖打包 |
| [EasyFace 图片列表说明](https://github.com/m0tral/EasyFace/wiki/ImageListCN)、[格式模板](https://gist.github.com/Doliman100/c4d22766c4288ad0e025fb3eba066289)、[Mi8WfBinTool](https://github.com/zhy8388608/Mi8WfBinTool) | 格式研究及能力限制的依据，不据其他型号推断 Q63 条件动画支持 |

R13／R14 的人物与 Q 版背屏素材沿用 [commonApp/xiaomi_desk_app](https://github.com/jsczymm1015/commonApp/tree/main/xiaomi_desk_app) 中的 L1D-02 素材，原始来源提交为 `a4e463c55c7ba6bb2197ab897ae5ee040f7affaf`。后续全身构图、局部修改、背景分离及动画合成在用户确认下制作；R14 使用内置 imagegen 局部编辑，将熄屏 Q 版人物左腿（观看者右侧）改为白色过膝袜。素材整理与生成式修改不新增第三方授权。

仓库里的 R13／R14 PNG 是从原生资源回读得到的排版预览，原始素材、角色及相关权利由其权利人保留。**代码的 MIT 许可不覆盖角色素材，不构成另行商业再分发授权。** 该说明不宣称小米、AstroBox 或素材权利人对本项目作出认可。

仓库与 Release 不包含用户原始连接日志、认证信息、健康数据、账号数据、SDK、签名私钥或私人实验记录。公开验证记录只保留版本、公开文件摘要及测试结果。
