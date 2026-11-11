<!-- 源侧 config/prompt_templates/graph_extraction.yaml · default_extract_entities -->
## 任务
从用户提供的文本中抽取所有符合下列类型的实体：
实体类型：[Person, Organization, Location, Product, Event, Date, Work, Concept, Resource, Category, Operation]

## 要求
1. 输出必须是 JSON 数组格式
2. 每个实体必须包含 title 与 type 字段；description 字段可选但强烈建议给出
3. type 字段的值必须严格从上述实体类型列表中选取，不要创造新类型
4. 如果无法确定一个实体的类型，不要强行分类，宁可跳过该实体
5. 不要输出任何解释或额外内容，只输出 JSON 数组
6. 所有字段值都不得包含 HTML 标签或其它代码
7. 如果实体有歧义，在 description 中说明具体指代
8. 如果没有找到任何实体，返回空数组 []

## 实体类型说明
- Person：真实或虚构的人物，包括历史人物、现代人物、文学角色等
- Organization：公司、政府机构、团队、学校等组织实体
- Location：地理位置、地标、国家、城市等
- Product：商品、服务、品牌等商业产品
- Event：事件、会议、节庆、历史事件等
- Date：日期、时间段、年代等时间信息
- Work：书籍、电影、音乐、艺术作品等创作成果
- Concept：抽象概念、思想、理论等
- Resource：自然资源、信息资源、工具等
- Category：分类、类别、领域等
- Operation：操作、动作、方法、流程等

## 抽取步骤
1. 仔细阅读文本，识别潜在实体
2. 为每个识别出的实体确定最合适的类型（必须来自上述类型列表）
3. 为每个实体创建 JSON 对象，字段如下：
   - title：实体的标准名称，不要带引号等修饰符
   - type：从类型列表中选取的实体类型
   - description：基于文本内容的简要描述，使用与原文相同的语言
4. 检查每个实体的所有字段是否正确、格式是否规范
5. 把全部实体对象合并为一个 JSON 数组
6. 检查最终 JSON 合法且满足要求

## 语言规则
- 实体 title 必须与原文中的写法完全一致
- description 用中文书写

## 示例
[输入]
文本："农村生活污水处理设施运维考核实施细则"适用于本区农村污水处理设施的运行维护，由区生态环境局负责监督。

[输出]
[
  {"title": "农村生活污水处理设施运维考核实施细则", "type": "Work", "description": "规范本区农村污水处理设施运行维护的考核文件"},
  {"title": "区生态环境局", "type": "Organization", "description": "负责监督农村污水处理设施运行维护的主管部门"},
  {"title": "农村污水处理设施", "type": "Resource", "description": "本区农村地区用于处理生活污水的设施"}
]
