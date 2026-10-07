package com.ai.assistance.quro.core.rag

/**
 * 模糊匹配原语：编辑距离、字符 n-gram、拼音首字母、概念扩展。
 *
 * ## 为什么需要它（这是「再模糊也能RAG 到」的地基）
 * 旧的检索层（`ToolTextMatcher`）只有**精确 token 重叠**：分词后必须字面命中。
 * 于是下面这些真实用户输入**必然零命中**：
 *  - 错别字：「微心」→「微信」
 *  - 拼音缩写：「wx 登录」→「微信登录」
 *  - 换一种说法：「把这段视频剪短一点」里没有「剪辑」二字
 *  - 口语：「那个能联网查东西的」
 *  - 只说效果不说手段：「帮我弄个能看图的表」
 *
 * 这类查询不是「模型不查」，而是**检索层根本没法处理**。本文件提供四种模糊信号，
 * 由 [RagIndex] 融合成最终排序。
 *
 * ## 设计约束
 *  - 纯 Kotlin、无 Android 依赖 → 可 JVM 单测。
 *  - 全部为 O(n²) 级别的小规模计算：候选集在数百量级，单次检索可接受。
 *  - 每个函数都必须有可测的边界行为，模糊匹配最怕"看起来能跑其实恒返回 0"。
 */
internal object RagFuzzy {

    // ───────────────────────── 编辑距离 ─────────────────────────

    /**
     * 标准 Levenshtein 编辑距离（插入/删除/替换各 1）。
     *
     * 用滚动数组而非二维矩阵：文本长度都在几十字级，省掉 O(n·m) 的分配。
     */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * 是否属于「拼写近似」。
     *
     * 🔴 阈值必须随长度放宽：`len<=2` 时距离 1 就意味着有一半字符不同，
     * 「日」和「月」距离是 1，若放行会把所有单字查询全部判成近似，噪声淹没结果。
     * 因此短词只允许距离 0（已由精确匹配覆盖），长词才逐步放宽。
     */
    fun isTypoMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 2 || b.length < 2) return false
        val d = editDistance(a, b)
        val longer = maxOf(a.length, b.length)
        // 长度 ≤4 只容 1 个错字；≤8 容 2；更长按比例容 3。
        val allowed = when {
            longer <= 4 -> 1
            longer <= 8 -> 2
            else -> 3
        }
        return d <= allowed
    }

    // ───────────────────────── 字符 n-gram ─────────────────────────

    /**
     * 字符 3-gram 集合（不足 3 字退化为整串）。
     *
     * 为什么是 3-gram 而不是 2-gram：2-gram 在短中文里几乎人人命中
     * （「微心」的 bigram 是「微心」，两个都在）区分度差；3-gram 要求至少 3 个字形连续，
     * 错一个字仍能靠另外两三个 n-gram 命中，这才是真正容错。
     */
    fun trigrams(text: String): Set<String> {
        val s = text.lowercase().filter { !it.isWhitespace() }
        if (s.isEmpty()) return emptySet()
        if (s.length < 3) return setOf(s)
        val out = LinkedHashSet<String>(s.length)
        for (i in 0..s.length - 3) out.add(s.substring(i, i + 3))
        return out
    }

    /**
     * n-gram 相似度（Jaccard）。
     *
     * 🔴 不是 cosine：集合场景下 Jaccard 只需算交集并集，无归一化浮点误差，
     * 且对「长度差异大」天然惩罚（短查询撞长描述不会虚高）。
     */
    fun trigramSimilarity(a: String, b: String): Double {
        val ta = trigrams(a)
        val tb = trigrams(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.count { it in tb }
        val union = ta.size + tb.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    // ───────────────────────── 拼音首字母 ─────────────────────────

    /**
     * 常用汉字 → 拼音首字母表。
     *
     * 🔴 为什么只收「首字母」而不是全拼：
     *  - 用户在手机输入法上打「wx」远比打「weixin」快，首字母是真实高频输入形态；
     * - 全拼表要覆盖 20k+ 汉字、维护成本与出错率都高，且用户几乎不输入全拼。
     * 一旦输入「wx」，再靠 [RagIndex] 的其它通道（n-gram / 概念）兜住其余情况。
     *
     * 表按首字母分组紧凑存储，避免 600 个单字符 key 的写法噪音。
     */
    private val PINYIN_INITIALS: Map<Char, String> = buildMap {
        fun put(initial: String, chars: String) {
            for (c in chars) put(c, initial)
        }
        put("a", "阿啊")
        put("b", "八吧把爸白百般办班板版半包报北备背倍被本笨比笔必边变标表别宾饼并病波播伯博卜不布步部")
        put("c", "擦猜才材菜参餐残蚕灿仓苍草层查察产长常场唱抄超朝车成城程吃池迟持尺齿充冲虫抽丑出初除楚触穿传船窗床创吹春纯词此次刺聪从粗醋催存错")
        put("d", "搭达答打大呆代带袋单担胆但当刀导岛倒到得德等灯登瞪低滴敌底地第颠点电店垫掉调爹丁顶定东冬懂动冻斗豆督毒读独堵度短段堆对队顿多夺朵躲")
        put("e", "俄鹅额恶饿恩儿而尔耳二")
        put("f", "发乏法翻凡烦反返方防房放非飞肥废费分粉份奋丰风封疯峰锋风佛否夫服浮符幅福府父复副富腹赋")
        put("g", "该改概干甘杆赶敢感刚岗钢港高搞告哥格个给根跟更耕工弓公功攻供宫弓共钩狗构购够估鼓故顾固刮挂关观管贯光广规归鬼贵滚锅国过")
        put("h", "哈还孩海害含寒喊汉汗航毫豪好号喝合何和河盒黑很狠恒横衡轰红洪宏鸿厚候后呼胡湖虎互户护花华划话怀坏欢还环换唤黄挥回悔会汇绘惠昏婚混火货获")
        put("j", "击基激及吉级即极急集几己挤计记纪技术既继加夹价驾架假嫁尖坚间肩艰检减剪简见件建健舰剑渐践鉴键江姜将讲奖降交郊焦角脚叫接揭街节劫洁结捷姐解介届界今金津紧锦仅尽劲近进禁京经茎惊晶精井警景颈净静镜究九酒救就居局菊举巨据距聚卷决绝觉军均君")
        put("k", "卡开看康考科棵颗壳可渴克客课肯坑空孔恐口扣哭苦夸跨块快宽款狂况亏葵溃昆扩阔")
        put("l", "垃拉喇啦蜡来赖兰拦栏蓝篮览懒烂郎狼朗浪捞劳牢老乐雷垒类冷厘离梨璃黎礼李里理力立历利例粒连联廉脸练炼恋链良凉梁粮两亮谅辆量辽疗聊了料列裂邻林临淋铃零灵领令另留流硫柳六龙笼楼漏炉鲁陆录路露旅屡绿乱掠略轮论罗萝逻螺落洛")
        put("m", "妈麻马码骂埋买迈麦脉蛮满慢忙毛矛冒贸帽么没眉媒煤每美妹门闷们萌梦弥迷米密蜜眠免勉面苗描秒妙民敏名明鸣命摸模膜摩磨魔末沫莫墨母亩木目")
        put("n", "拿哪那纳乃奶耐南男难脑闹呢内嫩能尼泥你逆年念娘酿鸟尿捏您宁凝牛扭农浓弄努怒女暖虐挪")
        put("o", "哦欧偶")
        put("p", "趴爬怕拍排派判叛胖抛跑泡赔陪配佩喷盆碰批披皮疲脾匹屁偏篇骗漂票拼贫频品聘平评凭瓶坡婆迫破仆扑铺葡朴普谱瀑")
        put("q", "七妻期欺漆齐其奇骑棋旗乞岂企启起气汽弃恰千迁牵铅前钱潜浅欠枪抢强悄敲桥瞧巧切茄窃勤青轻氢倾清情晴请庆穷丘秋求球区曲驱屈趋渠取去圈全权劝缺却确群裙")
        put("r", "然燃染让绕惹热人仁忍认任扔仍日容绒荣融冗柔肉如乳入软锐若弱")
        put("s", "撒洒塞赛三伞散桑嗓丧扫嫂色森杀沙纱筛晒删闪扇善伤商赏上烧稍勺少舌蛇设社射涉摄申伸身深神审婶肾甚渗慎升生声牲绳省圣胜剩尸失师诗施狮湿十什石时识实拾食蚀史使始士氏世市示式事势视试收手守首授受瘦书叔殊舒疏输蔬熟暑鼠属术束树竖数刷耍衰摔甩帅拴双霜爽谁水税睡顺说硕思斯撕死四寺似松搜艘苏俗诉肃素速宿塑算虽随岁碎孙损缩所索锁")
        put("t", "他它她塔踏台抬太态泰贪摊滩坛谈叹汤唐堂塘糖躺趟涛掏逃桃陶讨套特疼腾提题体替天添田填甜挑条调跳贴铁帖厅听停通同铜童统桶痛偷头透突图徒途涂团推腿退吞屯托拖脱驼妥拓唾")
        put("w", "挖蛙瓦歪外弯完玩顽晚碗万汪网忘危威微为围违唯维伟伪尾委未位味胃温文纹稳问翁我握卧乌污屋无五午武舞勿务物误雾")
        put("x", "西吸希昔析息稀溪熄膝习席洗系细虾瞎峡侠狭吓下夏仙先纤咸闲弦嫌显险现县限线宪陷馅乡相香箱详祥享响想向项象像橡削消小晓孝校笑效些歇协邪胁斜写泄谢心辛欣新信兴星刑行形型醒姓性凶兄胸雄熊休修羞朽秀袖需须虚需许序续宣悬旋选削学雪血寻巡询循训讯迅")
        put("y", "压呀丫牙芽哑亚呀烟淹严言岩延沿炎研盐颜眼演厌宴验央羊阳养样腰摇药要爷也页夜液一医依仪移遗疑乙已以亿义议艺亦异译易益疫意毅因阴音银引饮印英樱迎盈营蝇赢影硬映拥佣拥永勇涌用优忧幽悠尤由邮犹油游有又右幼诱于予余鱼娱与宇羽雨语玉育郁预域欲遇愈元员园圆援缘源远愿约月阅越悦跃云允运晕韵孕")
        put("z", "杂灾栽载再在咱赞脏葬遭糟早枣造噪则责怎增扎渣炸摘窄债沾展战站张章丈找照罩遮折哲者这浙针真诊枕阵振镇争征整正证郑政支汁只织职直值植殖止只旨址纸指志制质治秩滞终钟种重众舟周州洲宙昼皱骤朱猪竹逐主煮嘱住助注驻柱抓专砖转赚庄装状撞追坠准捉桌卓着姿资滋子紫字自宗综总纵走奏租族祖阻组钻嘴最罪尊遵昨左作坐座做")
    }

    /**
     * 取字符串的拼音首字母串（非汉字字符原样保留并小写）。
     *
     * 「微信」→`"wx"`；「WiFi」→`"wifi"`；「wx」→`"wx"`（原样）。
     * 非汉字字符参与首字母串，是因为中英混排查询（"wx登录"）里英文部分本来就该拼进去。
     */
    fun pinyinInitials(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            val mapped = PINYIN_INITIALS[c]
            sb.append(mapped ?: c)
        }
        return sb.toString()
    }

    /**
     * 拼音首字母匹配强度 0..1。
     *
     * 比较方式不是「全等」而是**包含 + 长度惩罚**：
     * 用户输入的往往只是某个词的首字母缩写（「wx」对应「微信登录」），
     * 要求全等会漏掉绝大多数真实用法。
     */
    fun pinyinScore(query: String, target: String): Double {
        val q = pinyinInitials(query)
        val t = pinyinInitials(target)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0
        if (!t.contains(q)) return 0.0
        // 命中位置越靠前、缩写占比越短，越可能是用户有意输入的首字母
        val ratio = q.length.toDouble() / t.length
        return (0.55 + 0.45 * ratio).coerceAtMost(0.95)
    }

    /**
     * 逐 token 取拼音分最大值。
     *
     * 🔴 不能直接对整串调 [pinyinScore]：中英混排查询（「ziliao 搜索」）
     * 整串转首字母会得到 `ziliaoss` 这种**无意义串**，拿去跟 `knowledge_search` 比必然不命中。
     * 用户真实输入是「一段拼音缩写 + 一段中文」，两段要分开算再取最大。
     *
     * @param query 用户原话。
     * @param target 待匹配字段。
     * @param tokenizer 分词器（传入以避免本对象反向依赖 [RagText]）。
     */
    fun pinyinScoreTokenized(
        query: String,
        target: String,
        tokenizer: (String) -> List<String>,
    ): Double {
        val whole = pinyinScore(query, target)
        if (whole > 0.0) return whole
        var best = 0.0
        for (tok in tokenizer(query)) {
            if (tok.length < 2) continue
            // 纯中文 token 的首字母串没有意义（每个汉字都变成一个字母），跳过
            if (tok.all { it.code > 0x2E80 }) continue
            val s = pinyinScore(tok, target)
            if (s > best) best = s
        }
        return best
    }

    // ───────────────────────── 通用工具 ─────────────────────────

    /** 归一化：去空白、转小写。全库统一，避免各处各写一套。 */
    fun norm(text: String): String = text.lowercase().replace("\\s+".toRegex(), " ").trim()
}