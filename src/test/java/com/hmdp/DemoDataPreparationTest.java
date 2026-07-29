package com.hmdp;

import com.hmdp.config.AiEmbeddingProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopKnowledgeService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * 本地演示数据准备工具。
 *
 * 运行方式：
 * -Dseed.demo-data=true
 *
 * 可选：同时重建 Qdrant 知识库
 * -Dseed.rebuild-knowledge=true
 *
 * 生成规则：
 * 1. 固定准备 100 家演示店铺；
 * 2. 共 10 个店铺类型，每个类型正好 10 家；
 * 3. 共 20 个商圈，每个商圈分布 5 家店；
 * 4. 100 家店铺名称完全不同，不再使用“名称 + 编号”的方式；
 * 5. 每家店铺固定准备 3 篇不同角度的点评，共 300 篇；
 * 6. 店铺和点评均采用确定性数据，重复运行不会反复插入相同记录。
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "seed.demo-data", matches = "true")
class DemoDataPreparationTest {

    private static final int SHOP_TYPE_COUNT = 10;
    private static final int SHOPS_PER_TYPE = 10;
    private static final int DEMO_SHOP_COUNT = SHOP_TYPE_COUNT * SHOPS_PER_TYPE;
    private static final int BLOGS_PER_SHOP = 3;
    private static final int DEMO_BLOG_COUNT = DEMO_SHOP_COUNT * BLOGS_PER_SHOP;
    private static final int BATCH_SIZE = 200;
    private static final long BASELINE_MAX_SHOP_ID = 14L;

    private static final String DEFAULT_IMAGE = "/imgs/icons/default-icon.png";
    private static final String DEMO_BLOG_PREFIX = "【演示点评】";

    /**
     * 数组下标与 typeId 对应：
     * 0 -> typeId=1
     * 1 -> typeId=2
     * ...
     * 9 -> typeId=10
     *
     * 每种类型正好 10 个完全不同的店名。
     */
    private static final String[][] SHOP_NAMES_BY_TYPE = {
            {
                    "桂雨江南菜馆", "山岚炭火料理", "稻香里小厨", "椒遇川味馆", "海棠湾蒸鲜坊",
                    "云水谣茶餐厅", "禾木庭院餐厅", "南风巷面馆", "拾味本帮菜", "月白湖景餐厅"
            },
            {
                    "银河麦颂KTV", "青柠欢唱空间", "云端派对K歌城", "橙光量贩KTV", "潮声音乐会所",
                    "星河年代KTV", "悦唱盒子", "霓虹麦克风", "蓝鲸欢聚KTV", "夜莺派对KTV"
            },
            {
                    "木槿发型设计", "剪影造型沙龙", "青禾美发工作室", "云朵烫染中心", "原色造型",
                    "风向标美发", "鹿屿发艺", "镜界造型", "拾光理发馆", "栖木美发沙龙"
            },
            {
                    "凌跃健身中心", "燃点运动空间", "潮汐健身工场", "力行私教馆", "元气体能中心",
                    "启程健身俱乐部", "峰度训练营", "轻氧瑜伽健身", "逐风运动馆", "星野综合训练馆"
            },
            {
                    "松月足道", "听泉推拿馆", "暖石按摩院", "静川经络养生", "梧桐里足浴",
                    "云舒理疗馆", "和风盲人按摩", "澜庭足道会馆", "知足常乐养生馆", "栖云按摩坊"
            },
            {
                    "花漾肌肤管理", "澄境美容SPA", "白茶美学中心", "悦己皮肤管理", "兰汀美容院",
                    "云栖芳疗SPA", "木兰女子会所", "初颜抗衰中心", "水云间美肤馆", "蜜语美容空间"
            },
            {
                    "奇趣星球亲子乐园", "彩虹岛儿童成长馆", "小象探险营", "童梦森林乐园", "积木城堡亲子馆",
                    "飞鸟儿童运动馆", "泡泡海洋乐园", "向日葵亲子空间", "鲸鱼湾儿童乐园", "云朵王国成长中心"
            },
            {
                    "暮色音乐酒馆", "橡木桶精酿社", "蓝调码头酒吧", "微光鸡尾酒馆", "夜航Live House",
                    "旧巷威士忌吧", "月岛清吧", "南岸精酿工坊", "拾夜音乐餐吧", "星尘露台酒吧"
            },
            {
                    "云顶派对别墅", "热岛轰趴馆", "漫游星球派对屋", "松果团建空间", "白日梦轰趴馆",
                    "鲸屿派对中心", "橙堡桌游轰趴", "拾趣聚会馆", "夏夜派对工厂", "自由岛团建馆"
            },
            {
                    "指尖花园美甲", "琥珀美甲美睫", "月桂美甲工作室", "星芒指艺馆", "桃气美甲屋",
                    "云朵美睫沙龙", "初雪美甲", "森系指尖艺术", "小鹿美甲美睫", "晴空美甲馆"
            }
    };

    private static final String[] TYPE_LABELS = {
            "餐饮", "KTV", "美发", "健身", "按摩足浴",
            "美容SPA", "亲子乐园", "酒吧", "轰趴团建", "美甲美睫"
    };

    /**
     * 20 个商圈，每个商圈最终恰好分配 5 家店铺。
     * 经纬度为演示数据，只用于附近店铺和 Redis GEO 测试。
     */
    private static final AreaProfile[] AREA_PROFILES = {
            new AreaProfile("武林商圈", "体育场路", 120.1640D, 30.2740D),
            new AreaProfile("湖滨商圈", "平海路", 120.1705D, 30.2572D),
            new AreaProfile("钱江新城", "富春路", 120.2142D, 30.2448D),
            new AreaProfile("城西银泰", "丰潭路", 120.1087D, 30.2912D),
            new AreaProfile("运河上街", "台州路", 120.1414D, 30.3197D),
            new AreaProfile("滨江星光大道", "江南大道", 120.2112D, 30.2084D),
            new AreaProfile("下沙龙湖天街", "金沙大道", 120.3378D, 30.3065D),
            new AreaProfile("西溪印象城", "五常大道", 120.0647D, 30.2549D),
            new AreaProfile("未来科技城", "文一西路", 120.0126D, 30.2791D),
            new AreaProfile("良渚永旺商圈", "古墩路", 120.0888D, 30.3484D),
            new AreaProfile("拱宸桥商圈", "桥弄街", 120.1450D, 30.3218D),
            new AreaProfile("黄龙商圈", "曙光路", 120.1325D, 30.2707D),
            new AreaProfile("万象城商圈", "庆春东路", 120.2101D, 30.2593D),
            new AreaProfile("杭州东站商圈", "天城路", 120.2196D, 30.2911D),
            new AreaProfile("萧山万象汇", "金城路", 120.2641D, 30.1855D),
            new AreaProfile("临平银泰城", "迎宾路", 120.2976D, 30.4181D),
            new AreaProfile("之江转塘商圈", "美院南街", 120.0785D, 30.1577D),
            new AreaProfile("九堡商圈", "九沙大道", 120.2820D, 30.3146D),
            new AreaProfile("大悦城商圈", "莫干山路", 120.1505D, 30.3000D),
            new AreaProfile("奥体博览城", "飞虹路", 120.2376D, 30.2311D)
    };

    private static final long[] BASE_PRICES = {
            88L, 128L, 68L, 158L, 108L,
            218L, 98L, 168L, 188L, 88L
    };

    private static final String[] TYPE_OPEN_HOURS = {
            "10:30-21:30", "12:00-02:00", "09:30-21:00", "07:00-23:00", "11:00-01:00",
            "10:00-22:00", "09:30-21:00", "18:00-02:00", "10:00-24:00", "10:00-21:30"
    };

    private static final String[] BLOG_OPENINGS = {
            "这次是朋友推荐后第一次到店，实际体验比预期更完整。",
            "周末临时决定过来打卡，整个过程没有明显踩雷。",
            "之前路过几次，这次终于安排时间认真体验了一遍。",
            "下班后和同事一起过来，交通和时间安排都比较合适。",
            "本来只是随便看看，最后发现这家店有不少值得记录的细节。",
            "这次带家人一起到店，更关注环境、服务和整体舒适度。",
            "避开高峰时段来体验，现场节奏比较从容。",
            "在附近办事时顺便到店，整体表现有一些意外惊喜。",
            "第二次来这家店，相比第一次对服务细节观察得更仔细。",
            "朋友聚会选了这里，重点体验了空间、项目和接待效率。",
            "提前预约后按时到店，从进门到结束都比较顺畅。",
            "看了不少评价后决定亲自体验，实际感受与网上描述基本一致。"
    };

    private static final String[] TRAFFIC_DESCRIPTIONS = {
            "从商圈主入口步行几分钟就能到，附近公共交通比较方便。",
            "门店位置不难找，周边有停车场，自驾和打车都比较省心。",
            "店铺靠近主要道路，晚上离店时也比较容易叫车。",
            "周边餐饮和购物选择很多，适合把行程安排在同一天。",
            "位置在商圈相对安静的一侧，既方便到达又不会过于嘈杂。",
            "地铁或公交到达后步行距离适中，第一次来也不容易绕路。",
            "附近停车位在高峰期会紧张，建议提前一点到达。",
            "门店招牌比较醒目，根据导航基本可以直接找到。",
            "周边生活配套完善，体验结束后继续逛街也很方便。",
            "所在街区人流适中，工作日到店的整体体验更轻松。"
    };

    private static final String[] SERVICE_DESCRIPTIONS = {
            "工作人员会先确认需求，再介绍适合的项目，没有明显推销压力。",
            "接待流程比较清楚，等待时间控制得不错，问题也能及时回应。",
            "服务人员态度自然，遇到细节问题会主动说明处理方式。",
            "预约信息核对很快，到店后基本没有重复等待。",
            "现场人员分工明确，高峰期也没有出现明显混乱。",
            "第一次来的顾客也能听懂项目介绍，沟通过程比较轻松。",
            "服务节奏不会过快，重要环节都会提前提醒。",
            "店员对项目比较熟悉，给出的建议相对具体。",
            "整个过程没有频繁打断，体验连贯性比较好。",
            "结账和离店流程简洁，价格明细展示得比较清楚。"
    };

    private static final String[] BLOG_ENDINGS = {
            "综合价格、位置和体验来看，适合第一次尝试这类项目的人。",
            "整体表现比较稳定，之后有同类需求还会考虑再来。",
            "更推荐工作日或非高峰时段到店，体验会更加从容。",
            "如果是多人同行，建议提前预约并说明人数和具体需求。",
            "这家店的优势不是单一项目，而是各个环节比较均衡。",
            "对环境和服务细节比较在意的人，可以把它列入备选。",
            "价格不算最低，但服务和完成度与消费水平基本匹配。",
            "本次体验没有明显短板，适合作为商圈内的常规选择。",
            "更适合愿意提前规划时间的人，高峰期临时到店可能需要等待。",
            "总体属于愿意向朋友推荐的类型，但具体项目仍应按个人需求选择。",
            "如果住在附近，日常到店的便利性会进一步放大它的优势。",
            "从这次体验看，门店在同类型商家中有较好的辨识度。"
    };

    /**
     * 每种店铺类型各准备 8 条专属描述。
     * 每篇点评会从中选择两条不同描述，使 300 篇文本差异更明显。
     */
    private static final String[][] TYPE_REVIEW_DETAILS = {
            {
                    "招牌菜调味有层次，咸淡控制得比较稳，不会只靠重口味吸引注意。",
                    "上菜节奏安排合理，热菜温度保持得不错，多人用餐不用等太久。",
                    "菜单既有适合分享的菜品，也有适合一人食的选择，组合比较灵活。",
                    "食材新鲜度表现不错，蔬菜口感和肉类火候都比较自然。",
                    "座位间距比想象中宽，聊天时不会受到邻桌太多影响。",
                    "分量与标价基本匹配，三到四人点菜时比较容易控制预算。",
                    "餐具和桌面收拾得很及时，用餐高峰期环境仍然保持整洁。",
                    "甜品和饮品不是简单陪衬，完成度足以单独拿出来评价。"
            },
            {
                    "包厢隔音表现不错，正常音量唱歌时不会被走廊噪声干扰。",
                    "点歌系统反应较快，常见新歌和经典曲目的覆盖都比较完整。",
                    "麦克风没有明显杂音，人声效果对普通顾客比较友好。",
                    "房间灯光模式选择丰富，聚会拍照时能找到合适的氛围。",
                    "包厢大小与标注基本一致，多人入座后活动空间仍然够用。",
                    "饮品和小食补充速度较快，中途加单没有等待太久。",
                    "设备使用说明清楚，即使第一次操作也能很快上手。",
                    "空调和通风状态稳定，长时间唱歌不会觉得房间过于闷热。"
            },
            {
                    "发型师先根据脸型和日常打理习惯沟通方案，没有直接套用固定模板。",
                    "洗护过程比较细致，水温和力度会根据反馈及时调整。",
                    "剪发层次自然，回家后不需要复杂工具也能保持基本造型。",
                    "染发颜色与沟通时的色卡接近，室内外光线下都比较协调。",
                    "烫发后的卷度不会过分夸张，更适合日常通勤和简单打理。",
                    "使用产品时会说明用途和注意事项，不会让顾客一直被动等待。",
                    "工位和工具整理得比较干净，操作过程让人放心。",
                    "完成后会给出具体的居家护理建议，而不是只推荐购买产品。"
            },
            {
                    "器械种类覆盖力量和有氧训练，高峰期也能找到替代动作。",
                    "自由力量区空间足够，深蹲和硬拉时不会与其他人过度拥挤。",
                    "私教会先了解运动基础和旧伤情况，再安排体验强度。",
                    "更衣室和淋浴区域维护得不错，清洁频率能够看得出来。",
                    "跑步机、划船机等有氧设备状态稳定，没有明显异响。",
                    "团课节奏清晰，教练会照顾不同基础的参与者。",
                    "场馆通风和温度控制合理，训练时没有明显闷热感。",
                    "课程和会员价格说明比较透明，体验过程中没有强制办卡。"
            },
            {
                    "足浴水温保持稳定，开始前会主动询问力度和身体状况。",
                    "按摩师对肩颈和腰背紧张位置判断比较准确，手法有针对性。",
                    "房间光线柔和，背景声音不会过大，休息感比较明显。",
                    "使用的毛巾和一次性用品准备齐全，卫生细节处理得不错。",
                    "项目时长与页面说明一致，没有明显缩短服务时间。",
                    "力度调整沟通顺畅，提出偏轻或偏重后都能及时变化。",
                    "结束后身体放松感比较明显，适合久坐或运动后恢复。",
                    "茶水和休息区虽然简单，但整体环境保持得安静整洁。"
            },
            {
                    "体验前会做基础肤质沟通，项目选择并不是只看价格高低。",
                    "护理步骤说明比较清楚，使用产品前会确认是否存在敏感情况。",
                    "房间私密性不错，灯光和温度让整个过程比较放松。",
                    "清洁、补水和舒缓环节衔接自然，结束后皮肤没有明显紧绷感。",
                    "美容师操作力度稳定，不会频繁推销额外项目打断体验。",
                    "仪器和用品摆放整齐，卫生和消毒环节能够看到。",
                    "项目完成后会说明短期护理注意事项，建议比较容易执行。",
                    "整体更偏向长期维护而不是追求一次性夸张效果。"
            },
            {
                    "游乐区域按年龄和活动类型进行了区分，小朋友行动时更安全。",
                    "工作人员会及时整理散落玩具，场地秩序保持得不错。",
                    "互动项目既有体能活动，也有需要动手思考的内容。",
                    "家长休息区视野较好，可以比较方便地观察孩子状态。",
                    "入场和离场都有核验流程，对带孩子的家庭更安心。",
                    "场馆地面和软包维护得比较干净，边角防护也较完整。",
                    "活动老师带领节奏自然，比较能调动孩子参与。",
                    "周末人多但分区管理尚可，提前预约会更方便。"
            },
            {
                    "现场音乐音量控制得当，既有氛围又不影响正常聊天。",
                    "酒单分类清楚，经典款和低酒精选择都比较丰富。",
                    "调酒风味与菜单描述接近，不会只依赖甜味掩盖层次。",
                    "吧台位置适合独自到店，卡座则更适合朋友小聚。",
                    "驻唱或现场演出的节奏安排合理，不会从头到尾都过于吵闹。",
                    "服务人员能根据口味推荐酒款，对新手比较友好。",
                    "小食出品比普通酒吧更认真，适合边聊天边分享。",
                    "灯光和座位布局有辨识度，拍照效果自然但不过度商业化。"
            },
            {
                    "场地同时包含桌游、影音和休息区域，多人活动不容易无聊。",
                    "厨房和餐具配置比较齐全，自带食材时操作更方便。",
                    "活动区域动线清楚，不同小游戏同时进行也不会相互干扰。",
                    "工作人员会提前说明设备使用方法和退场整理要求。",
                    "投影、音响和游戏设备运行稳定，聚会过程中没有频繁故障。",
                    "空间能够根据人数调整，生日聚会和小型团建都比较合适。",
                    "卫生间和公共区域清洁情况不错，长时间停留也比较舒适。",
                    "收费项目列得比较清楚，押金和超时规则会提前说明。"
            },
            {
                    "美甲师会先确认甲型、长度和日常使用习惯，再推荐款式。",
                    "颜色试涂后再正式操作，成品与预期色差不大。",
                    "修型和死皮处理比较细致，没有为了赶时间省略步骤。",
                    "图案细节完成度不错，近距离看边缘也比较整齐。",
                    "美睫前会沟通卷翘度和浓密程度，效果不会过分夸张。",
                    "工具消毒和一次性用品使用比较规范，卫生感较好。",
                    "整个过程节奏稳定，长时间坐着也不会频繁被打断。",
                    "完成后会说明日常保护方式，收费项目也能对应到具体步骤。"
            }
    };

    private static final String[][] TYPE_SCENARIOS = {
            {"家庭聚餐", "朋友小聚", "工作简餐", "周末约会"},
            {"生日聚会", "同事团建", "朋友欢唱", "节日聚会"},
            {"日常剪发", "烫染换造型", "面试前整理", "重要活动造型"},
            {"日常训练", "减脂塑形", "力量提升", "团体课程"},
            {"久坐放松", "运动恢复", "朋友同行", "周末休息"},
            {"日常护肤", "重要活动前护理", "压力放松", "长期皮肤管理"},
            {"亲子陪伴", "周末遛娃", "生日活动", "儿童社交"},
            {"朋友聊天", "约会小酌", "下班放松", "现场音乐体验"},
            {"生日派对", "公司团建", "同学聚会", "家庭活动"},
            {"日常美甲", "节日款式", "婚礼造型", "美睫护理"}
    };

    @Resource
    private IShopService shopService;

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopKnowledgeService shopKnowledgeService;

    @Resource
    private AiEmbeddingProperties embeddingProperties;

    @Test
    void prepareShopsAndBlogs() {
        validateStaticData();

        // Only use the immutable seed shops as image sources. Otherwise, the first
        // execution (14 source shops) and later executions (including demo shops)
        // would assign different images to the same demo store.
        List<Shop> imageSources = shopService.list().stream()
                .filter(shop -> shop.getId() != null && shop.getId() <= BASELINE_MAX_SHOP_ID)
                .sorted(Comparator.comparing(Shop::getId))
                .collect(Collectors.toList());

        int shopsBefore = shopService.count();
        int blogsBefore = blogService.count();

        List<Shop> demoShops = ensureDemoShops(imageSources);
        int insertedBlogs = ensureDemoBlogs(demoShops);

        List<Shop> allShops = shopService.list().stream()
                .sorted(Comparator.comparing(Shop::getId))
                .collect(Collectors.toList());
        rebuildShopGeoIndex(allShops);

        int shopsAfter = shopService.count();
        int blogsAfter = blogService.count();

        if (demoShops.size() != DEMO_SHOP_COUNT) {
            throw new IllegalStateException(
                    "演示店铺数量不正确，expected=" + DEMO_SHOP_COUNT
                            + ", actual=" + demoShops.size());
        }
        verifyDemoBlogs(demoShops);

        System.out.printf(
                "演示数据准备完成：数据库店铺 %d -> %d，点评 %d -> %d。%n",
                shopsBefore, shopsAfter, blogsBefore, blogsAfter);
        System.out.printf(
                "本次演示数据包含 %d 家不同名称店铺、每种类型 %d 家、每家 %d 篇点评，共 %d 篇演示点评；本次新插入点评 %d 篇。%n",
                DEMO_SHOP_COUNT, SHOPS_PER_TYPE, BLOGS_PER_SHOP, DEMO_BLOG_COUNT, insertedBlogs);
        System.out.println("所有当前店铺的 Redis GEO 索引已重建。");

        if (Boolean.getBoolean("seed.rebuild-knowledge")) {
            if (!"openai-compatible".equalsIgnoreCase(embeddingProperties.getProvider())) {
                throw new IllegalStateException(
                        "Qdrant rebuild requires ai.embedding.provider=openai-compatible");
            }
            int indexed = shopKnowledgeService.rebuildShopKnowledge();
            System.out.println("Qdrant 店铺知识库已重建，索引店铺数=" + indexed);
        } else {
            System.out.println(
                    "本次未重建 Qdrant。如需重建，请在 Embedding 与 Qdrant 服务可用时添加 "
                            + "-Dseed.rebuild-knowledge=true");
        }
    }

    /**
     * 保证预设的 100 家演示店铺全部存在。
     *
     * 与原来的“数据库总数不足 100 才补齐”不同：
     * 即使数据库里已经存在其他店铺，这里仍会保证这 100 个指定名称都存在。
     */
    private List<Shop> ensureDemoShops(List<Shop> imageSources) {
        Map<String, Shop> existingByName = shopService.list().stream()
                .filter(shop -> shop.getName() != null)
                .collect(Collectors.toMap(
                        Shop::getName,
                        shop -> shop,
                        (left, right) -> left,
                        HashMap::new));

        List<Shop> toInsert = new ArrayList<>();
        List<Shop> toUpdate = new ArrayList<>();
        List<String> orderedNames = new ArrayList<>(DEMO_SHOP_COUNT);

        for (int typeIndex = 0; typeIndex < SHOP_TYPE_COUNT; typeIndex++) {
            for (int shopIndex = 0; shopIndex < SHOPS_PER_TYPE; shopIndex++) {
                int globalIndex = typeIndex * SHOPS_PER_TYPE + shopIndex;
                String name = SHOP_NAMES_BY_TYPE[typeIndex][shopIndex];
                orderedNames.add(name);

                // 这种计算能让每种类型分散到 10 个不同商圈，
                // 同时保证 20 个商圈最终各出现 5 家店。
                int areaIndex = (typeIndex + shopIndex * 2) % AREA_PROFILES.length;
                AreaProfile area = AREA_PROFILES[areaIndex];

                Shop shop = existingByName.get(name);
                boolean isNew = shop == null;
                if (isNew) {
                    shop = new Shop();
                    shop.setName(name);
                }

                fillShopData(
                        shop,
                        typeIndex,
                        shopIndex,
                        globalIndex,
                        area,
                        imageSources);

                if (isNew) {
                    toInsert.add(shop);
                } else {
                    toUpdate.add(shop);
                }
            }
        }

        if (!toInsert.isEmpty() && !shopService.saveBatch(toInsert, BATCH_SIZE)) {
            throw new IllegalStateException("插入演示店铺失败");
        }

        if (!toUpdate.isEmpty() && !shopService.updateBatchById(toUpdate, BATCH_SIZE)) {
            throw new IllegalStateException("更新演示店铺失败");
        }

        Map<String, Shop> preparedByName = shopService.list().stream()
                .filter(shop -> shop.getName() != null)
                .filter(shop -> orderedNames.contains(shop.getName()))
                .collect(Collectors.toMap(
                        Shop::getName,
                        shop -> shop,
                        (left, right) -> left));

        List<Shop> result = new ArrayList<>(DEMO_SHOP_COUNT);
        for (String name : orderedNames) {
            Shop shop = preparedByName.get(name);
            if (shop == null) {
                throw new IllegalStateException("店铺准备失败，未找到：" + name);
            }
            result.add(shop);
        }
        return result;
    }

    private void fillShopData(
            Shop shop,
            int typeIndex,
            int shopIndex,
            int globalIndex,
            AreaProfile area,
            List<Shop> imageSources) {

        // 在商圈中心坐标附近做微小偏移，避免 5 家店完全重叠。
        double longitudeOffset =
                ((typeIndex % 5) - 2) * 0.00055D + (shopIndex % 2) * 0.00025D;
        double latitudeOffset =
                ((shopIndex % 5) - 2) * 0.00045D + (typeIndex % 2) * 0.00020D;

        shop.setTypeId((long) typeIndex + 1L);
        shop.setImages(selectImage(imageSources, globalIndex));
        shop.setArea(area.name);
        shop.setAddress(
                area.street
                        + (88 + typeIndex * 23 + shopIndex * 7)
                        + "号"
                        + (1 + globalIndex % 8)
                        + "层");
        shop.setX(area.longitude + longitudeOffset);
        shop.setY(area.latitude + latitudeOffset);
        shop.setAvgPrice(BASE_PRICES[typeIndex] + (shopIndex * 19L) % 85L);
        shop.setSold(800 + (globalIndex * 137) % 18000);
        shop.setComments(120 + (globalIndex * 53) % 4500);
        shop.setScore(38 + globalIndex % 12);
        shop.setOpenHours(TYPE_OPEN_HOURS[typeIndex]);
    }

    /**
     * 每家店固定准备 3 篇点评。
     *
     * 标题是确定性的，因此重复运行时会跳过已经存在的演示点评，
     * 不会每运行一次就再插入 300 篇重复数据。
     */
    private int ensureDemoBlogs(List<Shop> demoShops) {
        List<User> users = userService.query()
                .select("id")
                .orderByAsc("id")
                .last("LIMIT 200")
                .list();

        if (users.isEmpty()) {
            throw new IllegalStateException("生成点评前至少需要一个用户");
        }

        Set<String> existingBlogKeys = blogService.list().stream()
                .filter(blog -> blog.getShopId() != null
                        && blog.getTitle() != null
                        && blog.getTitle().startsWith(DEMO_BLOG_PREFIX))
                .map(this::blogKey)
                .collect(Collectors.toCollection(HashSet::new));

        List<Blog> toInsert = new ArrayList<>(DEMO_BLOG_COUNT);

        for (int shopIndex = 0; shopIndex < demoShops.size(); shopIndex++) {
            Shop shop = demoShops.get(shopIndex);

            for (int reviewIndex = 0; reviewIndex < BLOGS_PER_SHOP; reviewIndex++) {
                int articleNumber = shopIndex * BLOGS_PER_SHOP + reviewIndex + 1;
                String title = buildBlogTitle(shop, reviewIndex, articleNumber);
                String key = blogKey(shop.getId(), title);

                if (!existingBlogKeys.add(key)) {
                    continue;
                }

                User user = users.get((shopIndex * 7 + reviewIndex * 13) % users.size());

                Blog blog = new Blog();
                blog.setShopId(shop.getId());
                blog.setUserId(user.getId());
                blog.setTitle(title);
                blog.setImages(firstImage(shop.getImages()));
                blog.setContent(buildBlogContent(shop, reviewIndex, articleNumber));
                blog.setLiked((articleNumber * 17 + shopIndex * 11) % 800);
                blog.setComments((articleNumber * 9 + reviewIndex * 7) % 180);
                blog.setCreateTime(
                        LocalDateTime.now()
                                .minusDays((articleNumber * 7L) % 120L)
                                .minusHours((shopIndex + reviewIndex * 3L) % 24L));
                blog.setUpdateTime(blog.getCreateTime());
                toInsert.add(blog);
            }
        }

        if (!toInsert.isEmpty() && !blogService.saveBatch(toInsert, BATCH_SIZE)) {
            throw new IllegalStateException("插入演示点评失败");
        }

        return toInsert.size();
    }

    private void verifyDemoBlogs(List<Shop> demoShops) {
        Set<String> expectedKeys = new HashSet<>(DEMO_BLOG_COUNT);
        for (int shopIndex = 0; shopIndex < demoShops.size(); shopIndex++) {
            Shop shop = demoShops.get(shopIndex);
            for (int reviewIndex = 0; reviewIndex < BLOGS_PER_SHOP; reviewIndex++) {
                int articleNumber = shopIndex * BLOGS_PER_SHOP + reviewIndex + 1;
                expectedKeys.add(blogKey(shop.getId(), buildBlogTitle(shop, reviewIndex, articleNumber)));
            }
        }

        Set<Long> shopIds = demoShops.stream().map(Shop::getId).collect(Collectors.toSet());
        Set<String> actualKeys = blogService.list().stream()
                .filter(blog -> shopIds.contains(blog.getShopId()))
                .filter(blog -> blog.getTitle() != null && blog.getTitle().startsWith(DEMO_BLOG_PREFIX))
                .map(this::blogKey)
                .collect(Collectors.toSet());

        if (!actualKeys.containsAll(expectedKeys)) {
            Set<String> missing = new HashSet<>(expectedKeys);
            missing.removeAll(actualKeys);
            throw new IllegalStateException("Demo blogs are incomplete, missing=" + missing.size());
        }
    }

    private String blogKey(Blog blog) {
        return blogKey(blog.getShopId(), blog.getTitle());
    }

    private String blogKey(Long shopId, String title) {
        return shopId + "::" + title;
    }

    private String buildBlogTitle(Shop shop, int reviewIndex, int articleNumber) {
        String[] perspectives = {
                "第一次到店的真实感受",
                "周末体验与细节记录",
                "从环境到服务的完整评价"
        };

        return DEMO_BLOG_PREFIX
                + shop.getName()
                + "｜"
                + perspectives[reviewIndex]
                + " #"
                + String.format("%03d", articleNumber);
    }

    /**
     * 点评差异来源：
     * 1. 不同开场；
     * 2. 不同商圈与地址；
     * 3. 不同店铺类型专属细节；
     * 4. 每篇选择两条不同专属描述；
     * 5. 不同交通、服务、适用场景和结尾。
     */
    private String buildBlogContent(Shop shop, int reviewIndex, int articleNumber) {
        int typeIndex = Math.toIntExact(shop.getTypeId() - 1L);
        int seed = articleNumber * 31 + reviewIndex * 17 + shop.getName().hashCode();

        String[] typeDetails = TYPE_REVIEW_DETAILS[typeIndex];
        String[] scenarios = TYPE_SCENARIOS[typeIndex];

        int detailIndex1 = floorMod(seed, typeDetails.length);
        int detailIndex2 = floorMod(seed + 3 + reviewIndex, typeDetails.length);
        if (detailIndex2 == detailIndex1) {
            detailIndex2 = (detailIndex2 + 1) % typeDetails.length;
        }

        String opening = BLOG_OPENINGS[floorMod(seed, BLOG_OPENINGS.length)];
        String traffic =
                TRAFFIC_DESCRIPTIONS[floorMod(seed / 3 + reviewIndex, TRAFFIC_DESCRIPTIONS.length)];
        String service =
                SERVICE_DESCRIPTIONS[floorMod(seed / 5 + articleNumber, SERVICE_DESCRIPTIONS.length)];
        String ending =
                BLOG_ENDINGS[floorMod(seed / 7 + typeIndex, BLOG_ENDINGS.length)];
        String scenario = scenarios[floorMod(seed / 11 + reviewIndex, scenarios.length)];

        return opening
                + "本次体验的是位于"
                + shop.getArea()
                + "的"
                + shop.getName()
                + "，属于"
                + TYPE_LABELS[typeIndex]
                + "类店铺，具体地址是"
                + shop.getAddress()
                + "。"
                + traffic
                + typeDetails[detailIndex1]
                + typeDetails[detailIndex2]
                + service
                + "这家店更适合"
                + scenario
                + "，参考人均消费约"
                + shop.getAvgPrice()
                + "元，营业时间为"
                + shop.getOpenHours()
                + "。"
                + ending;
    }

    private int floorMod(int value, int length) {
        return Math.floorMod(value, length);
    }

    private String selectImage(List<Shop> imageSources, int index) {
        if (imageSources == null || imageSources.isEmpty()) {
            return DEFAULT_IMAGE;
        }

        String images = imageSources.get(index % imageSources.size()).getImages();
        if (images == null || images.trim().isEmpty()) {
            return DEFAULT_IMAGE;
        }
        return images;
    }

    private String firstImage(String images) {
        if (images == null || images.trim().isEmpty()) {
            return DEFAULT_IMAGE;
        }

        int separator = images.indexOf(',');
        return separator < 0 ? images : images.substring(0, separator);
    }

    private void rebuildShopGeoIndex(List<Shop> shops) {
        for (int typeId = 1; typeId <= SHOP_TYPE_COUNT; typeId++) {
            String key = SHOP_GEO_KEY + typeId;
            stringRedisTemplate.delete(key);
            final long currentTypeId = typeId;

            List<RedisGeoCommands.GeoLocation<String>> locations = shops.stream()
                    .filter(shop ->
                            shop.getTypeId() != null
                                    && shop.getTypeId().longValue() == currentTypeId)
                    .filter(shop -> shop.getX() != null && shop.getY() != null)
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())))
                    .collect(Collectors.toList());

            if (!locations.isEmpty()) {
                stringRedisTemplate.opsForGeo().add(key, locations);
            }
        }
    }

    private void validateStaticData() {
        if (SHOP_NAMES_BY_TYPE.length != SHOP_TYPE_COUNT) {
            throw new IllegalStateException("店铺类型名称数组数量不正确");
        }

        Set<String> uniqueNames = new HashSet<>();
        for (int typeIndex = 0; typeIndex < SHOP_NAMES_BY_TYPE.length; typeIndex++) {
            if (SHOP_NAMES_BY_TYPE[typeIndex].length != SHOPS_PER_TYPE) {
                throw new IllegalStateException(
                        "typeId=" + (typeIndex + 1)
                                + " 的店铺数量不是 "
                                + SHOPS_PER_TYPE);
            }

            for (String name : SHOP_NAMES_BY_TYPE[typeIndex]) {
                if (!uniqueNames.add(name)) {
                    throw new IllegalStateException("存在重复店名：" + name);
                }
            }
        }

        if (uniqueNames.size() != DEMO_SHOP_COUNT) {
            throw new IllegalStateException(
                    "唯一店名数量不正确，expected="
                            + DEMO_SHOP_COUNT
                            + ", actual="
                            + uniqueNames.size());
        }

        if (AREA_PROFILES.length != 20) {
            throw new IllegalStateException("商圈数量应为 20");
        }

        int[] shopsPerArea = new int[AREA_PROFILES.length];
        for (int typeIndex = 0; typeIndex < SHOP_TYPE_COUNT; typeIndex++) {
            for (int shopIndex = 0; shopIndex < SHOPS_PER_TYPE; shopIndex++) {
                shopsPerArea[(typeIndex + shopIndex * 2) % AREA_PROFILES.length]++;
            }
        }
        for (int areaIndex = 0; areaIndex < shopsPerArea.length; areaIndex++) {
            if (shopsPerArea[areaIndex] != 5) {
                throw new IllegalStateException("商圈分布不正确, areaIndex=" + areaIndex
                        + ", expected=5, actual=" + shopsPerArea[areaIndex]);
            }
        }

        if (TYPE_LABELS.length != SHOP_TYPE_COUNT
                || BASE_PRICES.length != SHOP_TYPE_COUNT
                || TYPE_OPEN_HOURS.length != SHOP_TYPE_COUNT
                || TYPE_REVIEW_DETAILS.length != SHOP_TYPE_COUNT
                || TYPE_SCENARIOS.length != SHOP_TYPE_COUNT) {
            throw new IllegalStateException("类型相关配置数组长度不一致");
        }
    }

    static Set<String> demoShopNames() {
        Set<String> names = new HashSet<>(DEMO_SHOP_COUNT);
        for (String[] namesByType : SHOP_NAMES_BY_TYPE) {
            for (String name : namesByType) {
                names.add(name);
            }
        }
        return names;
    }

    private static class AreaProfile {
        private final String name;
        private final String street;
        private final double longitude;
        private final double latitude;

        private AreaProfile(
                String name,
                String street,
                double longitude,
                double latitude) {
            this.name = name;
            this.street = street;
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }
}
