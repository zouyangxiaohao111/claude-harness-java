<!-- 来源：WeKnora internal/application/service/image_multimodal.go:535 buildImageAttrsPrompt
     + internal/types/image_attrs.go ImageAttrRegistry（字段名与取值逐字照抄） -->
先观察这张图片的属性，然后**只输出下面三行**，格式逐字如下：

contain.text: <none | sparse | block>
contain.data_visual: <true | false>
DESCRIPTION: 一句话描述

要求：
1) contain.text 只能是 none / sparse / block 之一，全部小写：
   none = 图里完全没有文字；
   sparse = 只有几个词（logo、路牌、单个标签）；
   block = 有成段正文（截图、表格、文档页）。
2) contain.data_visual 只能是 true / false 之一，全部小写：
   true = 用图表、曲线、示意图或信息图表达数据（有坐标轴、图例、数值标注）；
   false = 照片、绘画、图标、装饰图。
3) DESCRIPTION 是一句中文说明，40 字以内，只描述图片里**看得见**的内容：主体是什么、
   在做什么、有什么关键对象或数据。不加「这张图片」「图中」这类前缀，不评价、不推测、
   不编造。若图片完全空白或无法辨认，DESCRIPTION 这一行只写 NO_CONTENT。
4) 除这三行外不要输出任何内容：不要解释、不要 Markdown 表格、不要代码围栏。
