package com.nexusai.kb.app.graph;

/**
 * 图谱抽取提示词（中文）· 对齐 WeKnora
 * {@code config/prompt_templates/graph_extraction.yaml}
 * （{@code default_extract_entities} / {@code default_extract_relationships}）。
 *
 * <p><b>⭐⭐ 三处不能改的字面量</b>：
 * <ol>
 *   <li><b>实体类型清单必须与解析端一致</b>。提示词给了 11 种（{@link #ENTITY_TYPES}），
 *       解析端只接受这 11 种（大小写不敏感），其余一律回落 {@code Concept}。
 *       两边不同步的后果：模型按 A 写、代码按 B 认 ⇒ 全部实体变成 Concept，
 *       图谱按类型着色时全是一个颜色，<b>不报错</b>。</li>
 *   <li><b>强度档位 5..10 是固定语义</b>（10 = 直接从属 / 9 = 同一实体的不同称呼 /
 *       8 = 密切相关 / 7 = 明确但间接 / 6 = 间接关联 / 5 = 松散相关）。
 *       解析端把 &lt;5 或 &gt;10 的值夹回区间，所以档位改动要两侧一起改。</li>
 *   <li><b>「只抽取文本里明确出现的关系」</b>。模型很爱"补"常识关系
 *       （比如看到两个地名就自动连「相邻」）。这类关系在检索时会把不相干的证据串起来，
 *       且语气上完全像真的一样。</li>
 * </ol>
 */
public final class KbGraphPrompts {

    private KbGraphPrompts() {
    }

    /**
     * 合法实体类型（与提示词里列举的 11 种逐字一致）。
     *
     * <p>⚠️ 顺序影响归一化的兜底选择，别随意调整。
     */
    public static final java.util.List<String> ENTITY_TYPES = java.util.List.of(
        "Person", "Organization", "Location", "Product", "Event",
        "Date", "Work", "Concept", "Resource", "Category", "Operation");

    /** 未知类型回落值。 */
    public static final String DEFAULT_ENTITY_TYPE = "Concept";

    /** 强度合法区间下界。 */
    public static final int MIN_STRENGTH = 5;
    /** 强度合法区间上界。 */
    public static final int MAX_STRENGTH = 10;

    /** 实体抽取系统提示词。 */
    public static final String EXTRACT_ENTITIES = """
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
        """;

    /** 关系抽取系统提示词。 */
    public static final String EXTRACT_RELATIONSHIPS = """
        ## 任务
        从用户提供的实体数组出发，抽取实体之间**明确存在**的关系，构成结构化的关系网络。

        ## 要求
        1. 关系抽取必须基于提供的文本内容，不得编造不存在的关系
        2. 输出必须是 JSON 数组格式，每个关系是数组中的一个对象
        3. 每个关系对象必须包含 source、target、type、description、strength 五个字段
        4. 不要输出任何解释或额外内容，只输出 JSON 数组
        5. 如果没有找到关系，返回空数组 []

        ## 关系抽取规则
        - 只抽取文本中明确出现的关系
        - source 与 target 必须是实体数组中已有的实体（用它的 title）
        - type 是关系的**类型短语**（2-6 个字），用文本里出现的动词或结构来概括，
          例如「制定」「负责运维」「监测」「依据」「考核」「配备」「属于」。
          ⚠️ 要短、要能重复（同类关系用同一个词），不要写成整句话 ——
          星图会按它给边分类着色，写成长句等于每条边都不一样、无法分类。
        - description 是关系的**具体说明**（可以长一些，写清依据哪条事实）
        - 关系强度按以下档位判定：
          * 10：直接的创造/从属关系（如作者与作品、发明者与发明、母公司与子公司）
          * 9：同一实体的不同表现（如别名、曾用名）
          * 8：密切相关且相互影响（如紧密合作方、家庭成员）
          * 7：明确但较间接（如作品中的角色、组织中的成员）
          * 6：有明确关联的间接关系（如同事、同类产品）
          * 5：相关但联系较松（如同一领域的不同概念）

        ## 抽取步骤
        1. 仔细分析文本内容，判断哪些实体之间存在明确关系
        2. 只考虑文本中明确提到的关系，不要编造
        3. 对每个找到的关系确定：
           - source：起点实体的 title（必须已在实体列表中）
           - target：终点实体的 title（必须已在实体列表中）
           - description：简明准确的关系描述
           - strength：按上述档位给出的整数（5 到 10 之间）
        4. 判断关系是否双向：
           - 双向关系（如「A 是 B 的朋友」隐含「B 也是 A 的朋友」）考虑是否要建反向关系
           - 单向关系（如「A 创建了 B」）只保留单向
        5. 校验所有关系的一致性与合理性：
           - 不得出现互相矛盾的关系（如 A 同时是 B 的父亲和兄弟）
           - 关系描述要与关系强度相符
        6. 把所有有效关系组织成 JSON 数组

        ## 语言规则
        - 关系描述用中文书写

        ## 示例
        [输入]
        实体：[{"title":"农村生活污水处理设施运维考核实施细则","type":"Work"},{"title":"区生态环境局","type":"Organization"}]
        文本：本细则由区生态环境局制定并负责监督执行。

        [输出]
        [
          {"source": "区生态环境局", "target": "农村生活污水处理设施运维考核实施细则", "type": "制定", "description": "区生态环境局制定并监督执行该细则", "strength": 10}
        ]
        """;

    /**
     * 渲染关系抽取的 user 消息。
     *
     * <p>⚠️ 实体只传 {@code title/type/description} 三个字段：属性与 chunk 列表
     * 既占 token 又与「判关系」无关（源侧传了全量 JSON，但那是因为它序列化的是整个对象）。
     *
     * @param entitiesJson 实体 JSON 数组（调用方负责裁剪字段）
     * @param content      文本内容
     * @return user 消息
     */
    public static String relationshipUserMessage(String entitiesJson, String content) {
        return "实体：" + (entitiesJson == null ? "[]" : entitiesJson)
            + "\n\n文本：" + (content == null ? "" : content);
    }
}
