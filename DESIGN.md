---
version: alpha
name: "CarTunnel"
description: "面向 iCAR 横屏车机的一键式自建 VPN 控制面板，以仪表信息层级表达连接状态。"
colors:
  primary: "#167C80"
  primary-pressed: "#0F6265"
  success: "#287D55"
  warning: "#A66A12"
  danger: "#B33A3A"
  success-dark: "#70C79B"
  warning-dark: "#E9B95E"
  danger-dark: "#F08E8E"
  background-light: "#F3F6F7"
  surface-light: "#FFFFFF"
  text-light: "#172126"
  muted-light: "#5C6A70"
  border-light: "#CAD4D8"
  background-dark: "#11191D"
  surface-dark: "#19252A"
  text-dark: "#EDF4F5"
  muted-dark: "#A9B7BC"
  border-dark: "#3A4A50"
  focus: "#43B8C0"
typography:
  body:
    fontFamily: "sans-serif"
    fontSize: "1rem"
    lineHeight: "1.45"
  title:
    fontFamily: "sans-serif-medium"
    fontSize: "1.375rem"
    lineHeight: "1.25"
  data:
    fontFamily: "sans-serif-medium"
    fontSize: "1.125rem"
    lineHeight: "1.25"
  mono:
    fontFamily: "monospace"
    fontSize: "0.8125rem"
    lineHeight: "1.35"
rounded:
  DEFAULT: "0.625rem"
  sm: "0.375rem"
  md: "0.625rem"
  lg: "0.875rem"
spacing:
  control-gap: "0.625rem"
  section-gap: "1.125rem"
  page-padding: "1.5rem"
components:
  button:
    height: "3.5rem"
  primary-button:
    height: "4.25rem"
  card:
    padding: "1rem"
  touch-target:
    size: "3rem"
---

# CarTunnel Design System

## Overview

### Creative North Star

界面参考车机仪表的“状态优先”逻辑：驾驶舱只把当前是否联网、正在使用哪个节点以及下一步动作放在第一视觉层。它不是服务器管理后台，也不是消费级 VPN 的霓虹测速面板。

### Product context and register

- **Audience and primary job：** 有工程基础的车主在停车或调试场景下导入自己的节点，然后可靠地连接或断开全局 VPN。
- **Target market(s) and evidence：** 中国大陆自有设备侧载场景；以用户给出的 iCAR 03、Android 9/11 和实际车载流量验证为准。
- **Locale(s) and language policy：** 当前仅简体中文；协议名保留 VMess、WebSocket、VPN 等技术词。
- **Usage scene：** 横屏车机优先，普通 Android 手机用于基线测试；控件需适合触控、短时扫视和弱性能设备。
- **Register：** 产品工具。清晰、稳健和可诊断优先于品牌表达。
- **Memorable signature：** 顶部“连接状态轨”将状态、当前节点和最近延迟组成一个稳定区域，状态变化不推动主按钮位置。
- **Restraint：** 节点 CRUD、设置、确认和诊断使用熟悉的 Android 交互，不引入装饰动画、图表或隐藏手势。
- **Anti-references：** 不做商业机场控制台、广告首页、测速排行、霓虹圆环或密集服务器后台；这些都会掩盖唯一主任务。
- **Token ownership/runtime mapping：** Android `res/values` 与 `values-night` 是运行时唯一来源；本文件镜像其语义和批准值。YAML 中 `rem` 是 DESIGN.md 校验格式，运行时按正文对应为相同数值的 `sp/dp`。实施时颜色、尺寸和样式必须从资源引用，禁止在布局中继续散落十六进制值。

## Colors

主操作使用 `primary` 青绿色，连接成功使用 `success`，等待或重试使用 `warning`，失败及永久删除使用 `danger`。浅色和深色分别由 `background-*`、`surface-*`、`text-*`、`muted-*`、`border-*` 配对；不得用颜色作为唯一状态信息。焦点描边固定使用 `focus`，适应车机旋钮、键盘和触控焦点。

深色背景下语义正文通过 `values-night/colors.xml` 映射到 `success-dark`、`warning-dark`、`danger-dark`，保持同一含义并提高文字对比度。其余映射：短横线 token 对应下划线资源名；`type_body/title/data/mono` 为 16/22/18/13sp；`page_padding/section_gap/control_gap/card_padding/radius/button_height/primary_height` 为 24/18/10/16/10/56/68dp。原生 ScrollView 负责可见滚动条与焦点滚动；不映射 Web CSS scrollbar。

## Typography

不打包额外字体，沿用 Android 系统 `sans-serif`、`sans-serif-medium` 和诊断用 `monospace`，避免增加 APK 和车机字体兼容风险。正文不小于 16sp，关键状态 22sp，协议数据 18sp。按钮使用简短动词：连接、断开、导入配置、测试、删除。

## Layout

手机竖屏自然纵向滚动；车机横屏使用双栏：左侧固定连接状态与主按钮，右侧承载节点列表和低频入口。页面边距 24dp，分区间距 18dp，控件间距 10dp。主按钮高度至少 68dp，其他重要操作至少 56dp，任何触控目标不得小于 48dp。状态加载、错误和成功不改变主按钮几何位置。

## Elevation & Depth

层级主要依靠表面色、边框和留白，不堆叠阴影。状态卡与节点卡只允许轻微系统级 elevation；诊断和删除确认使用标准 AppCompat 对话框。静态正文、设置组和空状态禁止装饰性阴影。

## Shapes

卡片和主要控件使用 10dp 圆角，紧凑标签使用 6dp，对话框或大型状态面板可使用 14dp。选中节点通过左侧状态条、文本和边框共同表达，不只改变背景色。

## Components

### Foundational visual states

所有操作具备默认、按下、焦点、禁用和忙碌状态。连接中、停止中和导入处理中必须立即显示文本反馈并阻止重复动作；错误保留可恢复动作。列表包含空状态、未测试、测试中、可用、失败和当前连接状态。

### Buttons and actions

每个决策区只保留一个高强调主动作。主页“连接/断开”是唯一主按钮；“导入配置”为次要常驻动作；新增、粘贴导入、导出全部进入“配置”菜单。永久删除使用 danger，并与测试、编辑等日常操作分离。忙碌时按钮尺寸不变。

### Navigation and data display

主页直接显示节点列表，不再设置独立“节点管理”入口。点击节点卡只负责选择；卡内“测试”和“更多”是独立按钮，不触发父卡选择。节点“更多”只包含编辑、导出、删除，不重复“设为当前”和“测试”。

### Forms and overlays

节点编辑页只展示 VMess + WebSocket 所需字段。文件导入主按钮直接打开系统文件选择器；粘贴导入在配置菜单中。删除确认说明只删除本机配置、不影响服务器；运行节点使用“断开并删除”。诊断对话框内提供“复制诊断”和“关闭”，复制后给出不包含诊断正文的反馈。

### Iconography

优先文字标签，避免在低分辨率车机上依赖含义不明确的图标。若使用 Android 系统图标，必须同时保留可见文字或 contentDescription，尺寸与文字基线对齐。

### Motion

只使用 Android 控件默认按压反馈和 150–200ms 的状态淡变；不使用循环动画、入场编排或移动背景。系统减少动画时完全依从平台设置。

### Content and data visualization

文案从用户动作出发，错误说明原因和下一步，不显示异常类名或密钥。诊断保持等宽字体、最新日志在上，并保留版本与脱敏状态。

## Do's and Don'ts

- **Do：** 让一次点击立即产生可见状态，并让异步授权返回后自动继续。
- **Do：** 在横屏和竖屏都保持连接按钮、当前节点和故障摘要容易找到。
- **Don't：** 用二级菜单承载高频“导入文件”，或让父行点击吞掉子按钮动作。
- **Don't：** 为视觉效果增加第三方 UI 依赖、额外字体、大图、持续动画或不透明的自定义手势。

## 1.0.0 间距修正

节点卡之间、列表与下方按钮之间，以及导入按钮与配置/设置行之间统一使用 `control_gap`（10dp）。沿用既有颜色、字体、双栏结构与控件尺寸。
