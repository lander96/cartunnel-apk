# CarTunnel 文档索引

当前产品与使用入口为根 [README](../README.md)，版本 1.1.0-test。
只支持 VMess AEAD + WebSocket + 无 TLS + 全局 VPN。

## 当前合同与交付

- [DESIGN.md](../DESIGN.md)：Android resources token、车机视觉与横屏/竖屏布局。
- [UX-CONTRACT.md](../UX-CONTRACT.md)：原生交互、节点 CRUD、权限、单次动作、迁移与验收。
- [VMess-only 产品与实施计划](12-vmess-only-product-ui-plan.md)：冻结范围。
- [实施交接](13-vmess-only-agent-handoff.md)：基线与验证要求。
- [唯一服务端部署脚本](../deploy/server/deploy.sh)：单文件生成 Xray 配置与 Docker Compose 并启动服务；使用说明见根 README。

## 历史资料

01–06 记录最初 MVP，包括已经移除的 VLESS/REALITY、本地代理与旧部署方式。
07–08 为之前的单 VPN 兼容重构；保留其中已经实现的 VPN 生命周期事实，协议与 UI 范围以 12/13 为准。
09–10 与 verification 下保留的 1.0.x 文档为历史故障与候选版证据，不能作为当前部署/验收结论。

包含真实服务器标识、拓扑、路径或运行日志的历史资料已从公开仓库及 Git 历史中移除。其余历史资料仅用于代码演进追溯。
