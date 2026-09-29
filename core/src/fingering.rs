//! 管乐器型号（调性/尺寸 × 孔制）→ 孔位指法表（数据见 `docs/spec-instruments.md` §4-§6）。
//!
//! 模型分两类，由 [`FingeringSource`] 区分：
//!
//! - **孔制类（笛/箫）**：每个孔制给出「相对筒音 0–11 半音」各自的孔位开闭组合
//!   （含半孔与叉口），音高只由孔位组合决定。因此可以从同一份孔位数据同时导出
//!   七声音阶表与十二音全表，也可以让筒音唱名在 12 个半音级上自由转调
//!   （筒音作 5 / 作 1 / 作 2 只是其中三档）。
//! - **固定音阶类（尺八）**：变化音靠俯仰口风（meri/kari）而非孔位组合，只给出
//!   固定音阶偏移与指法名，不提供孔位图与十二音展开。
//!
//! 预设表只存 MIDI 与孔位组合，频率按 A4 校准在使用时换算。

use crate::api::{FingeringKind, Instrument, InstrumentKind};

/// 七声音阶相对筒音的半音偏移（笛/箫，15 音，覆盖约两个八度）。
pub const SCALE_OFFSETS: [i32; 15] = [0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24];

/// 十二音全表相对筒音的半音偏移上限（0..=CHROMATIC_MAX，25 音）。
pub const CHROMATIC_MAX: i32 = 24;

/// 十二音全表的半音偏移（0..=[`CHROMATIC_MAX`]）。
pub const CHROMATIC_OFFSETS: [i32; (CHROMATIC_MAX + 1) as usize] = {
    let mut out = [0i32; (CHROMATIC_MAX + 1) as usize];
    let mut i = 0;
    while i <= CHROMATIC_MAX as usize {
        out[i] = i as i32;
        i += 1;
    }
    out
};

/// 孔制类（笛/箫）十二音详情的基础孔位。
pub const CHROMATIC_BASE_OFFSETS: [i32; 12] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];

/// 宫调式七声相对宫音的半音偏移（判定某音是否为当前调的正声）。
const GONG_SCALE_STEPS: [i32; 7] = [0, 2, 4, 5, 7, 9, 11];

/// 孔制类（笛/箫）按当前筒音唱名筛选可完整显示 `1 2 3 4 5 6 7` 的七个基础孔位。
///
/// 筒音作 5 得 `[0,2,4,5,7,9,10]`：第 7 个基础孔位是 10 半音的「4」，
/// 不是 11 半音全开的「#4」。
pub fn scale_base_offsets(tongyin_degree: u8) -> [i32; 7] {
    let mut offsets =
        GONG_SCALE_STEPS.map(|scale_step| (scale_step - i32::from(tongyin_degree)).rem_euclid(12));
    offsets.sort_unstable();
    offsets
}

/// 孔位状态编码（孔制表内使用）：`C` 闭孔、`O` 开孔、`H` 半开孔。
///
/// 字符顺序为「第一孔 → 第 N 孔」，即由下端（出音口一侧）向上端（吹口一侧）排列；
/// 末尾 `back_hole_count` 个孔位于背面（拇指孔）。
///
/// 八孔箫（前七后一）的常用基础指法。
///
/// 第 2、6 孔是相对六孔体系增加的辅助孔。参考常见八孔指法表时，标为「可开可闭」
/// 的辅助孔统一采用闭孔方案，因此多数自然音保持第 2、6 孔关闭，而不是八孔依次全开。
/// 字符仍按第一孔至第八孔排列，第八孔为背孔。
const XIAO8_PATTERNS: [&str; 12] = [
    "CCCCCCCC", // 0  筒音（全按）
    "HCCCCCCC", // 1  第一孔半开
    "OCCCCCCC", // 2  开第一孔
    "OOCCCCCC", // 3  开第一二孔
    "OCOCCCCC", // 4  第 2 孔保持关闭，开第一、三孔
    "OCOOCCCC", // 5  第 2 孔保持关闭，开第一、三、四孔
    "OCOOHCCC", // 6  同上，第五孔半开
    "OCOOOCCC", // 7  第 2、6 孔保持关闭
    "OCOOOOCC", // 8  第 2 孔关闭，第 6 孔打开
    "OCOOOCOC", // 9  第 2、6 孔关闭，第七孔打开
    "OCOOCCCO", // 10 叉口：闭第二、五、六、七孔
    "OCOOOCOO", // 11 第 2、6 孔关闭，第七、八孔打开
];

/// 八孔箫相对筒音 12–31 半音（中音区 12–23、高音区 24–31）的实测孔位。
///
/// 逐格抄录[八孔洞箫指法表](https://simumis.com/posts/xiao/)的 32 列：中音区除 22 半音
/// 换用另一种叉口外与低音区同孔位；高音区孔位与低、中音区完全不同。图中未给出的
/// 32 半音以上、以及高音区最后 4 个半音（`base` 8–11）没有可核对来源，保持 `None`，
/// 客户端留空。
const XIAO8_UPPER_PATTERNS: [Option<&str>; 20] = [
    Some("CCCCCCCC"), // 12 筒音·超吹
    Some("HCCCCCCC"), // 13
    Some("OCCCCCCC"), // 14
    Some("OOCCCCCC"), // 15
    Some("OCOCCCCC"), // 16
    Some("OCOOCCCC"), // 17
    Some("OCOOHCCC"), // 18
    Some("OCOOOCCC"), // 19
    Some("OCOOOOCC"), // 20
    Some("OCOOOCOC"), // 21
    Some("OOCCCCOC"), // 22 中音叉口：与低音区 10 半音的叉口不同
    Some("OCOOOCOO"), // 23
    Some("OCOOCCCO"), // 24 高音区起，孔位自成一套
    Some("CCOOHCCC"), // 25
    Some("OCCCOCCC"), // 26
    Some("CCOOOOCC"), // 27
    Some("COCCCOCC"), // 28
    Some("CCCOCCOC"), // 29
    Some("CCOOCCCO"), // 30
    Some("OCCCCCCO"), // 31
];

/// 六孔箫相对筒音 12–31 半音的实测孔位。
///
/// 抄录[洞簫指法](http://donsiau.net/notedr.htm)六孔表的五个筒音列并取列间一致项。
/// 该表自注「超高音指法因簫不同略有差異」，列间冲突（18、23、28、30 半音）一律保持
/// `None`，不做推算。
const XIAO6_UPPER_PATTERNS: [Option<&str>; 20] = [
    Some("CCCCCO"), // 12 背孔开、前孔全闭
    Some("HCCCCC"), // 13
    Some("OCCCCC"), // 14
    Some("COCCCC"), // 15
    Some("OOCCCC"), // 16
    Some("OOOCCC"), // 17
    None,           // 18 列间无一致来源
    Some("OOOOCC"), // 19
    Some("OOOCOC"), // 20
    Some("OOOOOC"), // 21
    Some("CCCOCO"), // 22
    None,           // 23 列间冲突
    Some("CCCCCO"), // 24 高音区
    Some("COOHCC"), // 25
    Some("COOOCC"), // 26
    Some("COOOHC"), // 27
    None,           // 28 列间冲突
    Some("COCCOC"), // 29
    None,           // 30 无来源
    Some("OOOCOO"), // 31
];

const XIAO8_LABELS: [&str; 12] = [
    "筒音",
    "第一孔半开",
    "开第一孔",
    "开第一二孔",
    "开第一三孔",
    "开第一三四孔",
    "开第一三四孔·第五孔半开",
    "开第一三四五孔",
    "开第一三四五六孔",
    "开第一三四五七孔",
    "闭第二五六七孔",
    "开第一三四五七八孔",
];

/// 六孔箫（前五后一）孔位组合。
///
/// 逐孔开放为七声音阶（0 2 4 5 7 9 11），五个变化音一律靠半孔取得。
const XIAO6_PATTERNS: [&str; 12] = [
    "CCCCCC", // 0  筒音（全按）
    "HCCCCC", // 1  第一孔半开
    "OCCCCC", // 2  开第一孔
    "OHCCCC", // 3  开第一孔 + 第二孔半开
    "OOCCCC", // 4  开第一二孔
    "OOOCCC", // 5  开第一二三孔
    "OOOHCC", // 6  开第一二三孔 + 第四孔半开
    "OOOOCC", // 7  开第一二三四孔
    "OOOOHC", // 8  开第一二三四孔 + 第五孔半开
    "OOOOOC", // 9  开第一…五孔
    "OOOOOH", // 10 开第一…五孔 + 第六孔半开
    "OOOOOO", // 11 全开
];

/// 六孔竹笛（六孔全在正面）孔位组合，第一孔为靠笛尾的一孔。
///
/// 与六孔箫逐孔开放一致；区别是 10 半音（筒音作 5 的「4」）用竹笛通行的叉口
/// 「开第一二三六孔」（吹口起记作 ○●●○○○），而不是六孔箫的背孔半开。
/// 参考：[Dizi fingerings](https://en.wikipedia.org/wiki/Dizi_(instrument)#Fingerings)。
const DIZI6_PATTERNS: [&str; 12] = [
    "CCCCCC", // 0  筒音（全按）
    "HCCCCC", // 1  第一孔半开
    "OCCCCC", // 2  开第一孔
    "OHCCCC", // 3  开第一孔 + 第二孔半开
    "OOCCCC", // 4  开第一二孔
    "OOOCCC", // 5  开第一二三孔
    "OOOHCC", // 6  开第一二三孔 + 第四孔半开
    "OOOOCC", // 7  开第一二三四孔
    "OOOOHC", // 8  开第一二三四孔 + 第五孔半开
    "OOOOOC", // 9  开第一…五孔
    "OOOCCO", // 10 叉口：闭第四五孔
    "OOOOOO", // 11 全开
];

/// 六孔竹笛相对筒音 12–31 半音的孔位。
///
/// 中音区（超吹）除 12 半音开第六孔作泛音孔、22 半音换叉口外沿用低音孔位；
/// 高音区只收录资料一致的 24 半音。其余高音格资料抄录互相矛盾，保持 `None`，
/// 客户端留空。参考同上。
const DIZI6_UPPER_PATTERNS: [Option<&str>; 20] = [
    Some("CCCCCO"), // 12 筒音·超吹：只开第六孔
    Some("HCCCCC"), // 13
    Some("OCCCCC"), // 14
    Some("OHCCCC"), // 15
    Some("OOCCCC"), // 16
    Some("OOOCCC"), // 17
    Some("OOOHCC"), // 18
    Some("OOOOCC"), // 19
    Some("OOOOHC"), // 20
    Some("OOOOOC"), // 21
    Some("CCCOCO"), // 22 中音叉口
    Some("OOOOOO"), // 23
    Some("CCCCCO"), // 24 高音区起
    None,           // 25
    None,           // 26
    None,           // 27
    None,           // 28
    None,           // 29
    None,           // 30
    None,           // 31
];

/// 一种孔制（孔数 + 前后分布 + 十二半音孔位组合 + 常用筒音唱名）。
pub struct HoleSystemDef {
    /// 孔制 id（型号 id 后缀），如 "x8"。
    pub id: &'static str,
    /// 显示名，如 "8孔"。
    pub display_name: &'static str,
    /// 孔数。
    pub hole_count: u8,
    /// 末尾若干孔位于背面（拇指孔）。
    pub back_hole_count: u8,
    /// 相对筒音 0–11 半音的孔位组合。
    pub patterns: &'static [&'static str; 12],
    /// 相对筒音 12–31 半音的孔位组合；无可核对来源的格为 `None`。
    pub upper_patterns: &'static [Option<&'static str>; 20],
    /// 该孔制常用的筒音唱名级（相对宫音的半音数）。
    pub common_tongyin: &'static [u8],
}

/// 八孔箫：前七后一。半音孔位齐备，故筒音作 5/1/2/3/6 都算常用。
pub const XIAO_8: HoleSystemDef = HoleSystemDef {
    id: "x8",
    display_name: "8孔",
    hole_count: 8,
    back_hole_count: 1,
    patterns: &XIAO8_PATTERNS,
    upper_patterns: &XIAO8_UPPER_PATTERNS,
    // 5=7 1=0 2=2 3=4 6=9 半音级
    common_tongyin: &[7, 0, 2, 4, 9],
};

/// 六孔箫：前五后一。变化音靠半孔，故常用筒音仍是作 5/1/2。
pub const XIAO_6: HoleSystemDef = HoleSystemDef {
    id: "x6",
    display_name: "6孔",
    hole_count: 6,
    back_hole_count: 1,
    patterns: &XIAO6_PATTERNS,
    upper_patterns: &XIAO6_UPPER_PATTERNS,
    common_tongyin: &[7, 0, 2],
};

/// 六孔竹笛：六孔全在正面。
pub const DIZI_6: HoleSystemDef = HoleSystemDef {
    id: "d6",
    display_name: "6孔",
    hole_count: 6,
    back_hole_count: 0,
    patterns: &DIZI6_PATTERNS,
    upper_patterns: &DIZI6_UPPER_PATTERNS,
    common_tongyin: &[7, 0, 2],
};

/// 尺八基本音阶相对筒音的半音偏移（ro tsu re chi ha 五声 × 2 八度 + 大甲，11 音）。
pub const SHAKUHACHI_OFFSETS: [i32; 11] = [0, 3, 5, 7, 10, 12, 15, 17, 19, 22, 24];

/// 尺八五孔（前四后一）孔数与背孔数。
pub const SHAKUHACHI_HOLE_COUNT: u8 = 5;
/// 尺八背孔（拇指孔）数。
pub const SHAKUHACHI_BACK_HOLE_COUNT: u8 = 1;

/// 尺八基本音阶孔位（与 [`SHAKUHACHI_OFFSETS`] 一一对应）。
///
/// 字符顺序为第一孔（最下、右手无名指）→ 第四孔 → 背孔。乙音与甲音同孔位，
/// 只有甲音ハ改为只按第一孔；大甲ロ全开。参考
/// [Alternate Fingering Chart for Five-Hole Shakuhachi](https://www.wfg.woodwind.org/shaku/index.html)
/// 各八度首选指法与 [JosenShakuhachi 基本音表](https://josenshakuhachi.com/shakuhachi-guides/shakuhachi-note-charts)。
/// 变化音靠俯仰（meri/kari）而非孔位，因此不提供十二音展开。
pub const SHAKUHACHI_PATTERNS: [&str; 11] = [
    "CCCCC", // ロ
    "OCCCC", // ツ：开第一孔
    "OOCCC", // レ：开第一二孔
    "OOOCC", // チ：开第一二三孔
    "CCOOC", // ハ：按第一二孔与背孔
    "CCCCC", // ロ·甲
    "OCCCC", // ツ·甲
    "OOCCC", // レ·甲
    "OOOCC", // チ·甲
    "COOOC", // ハ·甲：只按第一孔与背孔
    "OOOOO", // 大甲ロ：全开
];
/// 尺八指法名（1.8 寸 D 调：D F G A C）。
pub const SHAKUHACHI_LABELS: [&str; 11] = [
    "筒音(ro)",
    "ツ(tsu)",
    "レ(re)",
    "チ(chi)",
    "ハ(ha)",
    "ロ·甲(ro 高八度)",
    "ツ·甲(tsu 高八度)",
    "レ·甲(re 高八度)",
    "チ·甲(chi 高八度)",
    "ハ·甲(ha 高八度)",
    "大甲(ro 高二八度)",
];

/// 型号的指法来源。
pub enum FingeringSource {
    /// 孔制类：孔位组合决定音高，支持筒音唱名转调与十二音展开。
    Holes(&'static HoleSystemDef),
    /// 固定音阶类：音阶偏移、指法名与各音孔位（尺八变化音靠俯仰口风，不由孔位决定，
    /// 因此不支持筒音转调与十二音展开）。
    Scale {
        /// 相对筒音的半音偏移（升序）。
        offsets: &'static [i32],
        /// 指法名（与 offsets 一一对应）。
        labels: &'static [&'static str],
        /// 孔位组合（与 offsets 一一对应）。
        patterns: &'static [&'static str],
        /// 孔数。
        hole_count: u8,
        /// 末尾若干孔位于背面。
        back_hole_count: u8,
    },
}

/// 一个管乐器型号（调性/尺寸 × 孔制）。
pub struct WindVariantDef {
    /// 型号 id，如 "g_xiao_x8"、"shaku_1_8"。
    pub id: &'static str,
    /// 调性/尺寸 id（同一调性的不同孔制共享），如 "g_xiao"。
    pub key_id: &'static str,
    /// 调性/尺寸显示名，如 "G调洞箫"、"尺八 1.8寸（D调）"。
    pub key_name: &'static str,
    /// 筒音 MIDI（型号固有，与 A4 无关）。
    pub fundamental_midi: i32,
    /// 指法来源。
    pub source: FingeringSource,
}

/// 孔制类型号构造（型号 id = 调性 id + 孔制 id）。
const fn holed(
    id: &'static str,
    key_id: &'static str,
    key_name: &'static str,
    fundamental_midi: i32,
    system: &'static HoleSystemDef,
) -> WindVariantDef {
    WindVariantDef {
        id,
        key_id,
        key_name,
        fundamental_midi,
        source: FingeringSource::Holes(system),
    }
}

/// 尺八型号构造。
const fn shaku(id: &'static str, name: &'static str, fundamental_midi: i32) -> WindVariantDef {
    WindVariantDef {
        id,
        key_id: id,
        key_name: name,
        fundamental_midi,
        source: FingeringSource::Scale {
            offsets: &SHAKUHACHI_OFFSETS,
            labels: &SHAKUHACHI_LABELS,
            patterns: &SHAKUHACHI_PATTERNS,
            hole_count: SHAKUHACHI_HOLE_COUNT,
            back_hole_count: SHAKUHACHI_BACK_HOLE_COUNT,
        },
    }
}

// 竹笛按传统「小工调」以第三孔（唱名 do）定调，筒音作 5 时筒音 = 第三孔下方纯四度。
// 第三孔取 G 调梆笛 g2 = 784Hz（赵松庭《横笛的频率计算与应用》），即同名调比洞箫高一个八度：
// D 调曲笛筒音 A4=69（440Hz）；G 调梆笛筒音 D5=74（587Hz）；F 调筒音 C5=72；
// C 调筒音 G4=67（392Hz）；E 调（中音笛）筒音 B4=71。
/// 竹笛：D/G/F/C/E 五调，均为六孔。
pub const ZHUDI_VARIANTS: [WindVariantDef; 5] = [
    holed("d_qudi_d6", "d_qudi", "D调曲笛", 69, &DIZI_6),
    holed("g_bangdi_d6", "g_bangdi", "G调梆笛", 74, &DIZI_6),
    holed("f_dizi_d6", "f_dizi", "F调竹笛", 72, &DIZI_6),
    holed("c_dizi_d6", "c_dizi", "C调竹笛", 67, &DIZI_6),
    holed("e_dizi_d6", "e_dizi", "E调竹笛", 71, &DIZI_6),
];

// 洞箫筒音：G 调 d1 = D4 = 62；F 调 c1 = C4 = 60。
// 三音区覆盖筒音起 31 个半音，即 G 调 d1–a3（D4–A6），与洞箫实际音域一致。
/// 洞箫：G/F 两调 × 8 孔/6 孔（8 孔在前，为当前流行制式，作默认）。
pub const DONGXIAO_VARIANTS: [WindVariantDef; 4] = [
    holed("g_xiao_x8", "g_xiao", "G调洞箫", 62, &XIAO_8),
    holed("g_xiao_x6", "g_xiao", "G调洞箫", 62, &XIAO_6),
    holed("f_xiao_x8", "f_xiao", "F调洞箫", 60, &XIAO_8),
    holed("f_xiao_x6", "f_xiao", "F调洞箫", 60, &XIAO_6),
];

// 尺八筒音：1.8=D4=62，1.6=E4=64，2.0=C4=60，2.4=A3=57
/// 尺八：1.8 / 1.6 / 2.0 / 2.4 寸。
pub const SHAKUHACHI_VARIANTS: [WindVariantDef; 4] = [
    shaku("shaku_1_8", "尺八 1.8寸（D调）", 62),
    shaku("shaku_1_6", "尺八 1.6寸（E调）", 64),
    shaku("shaku_2_0", "尺八 2.0寸（C调）", 60),
    shaku("shaku_2_4", "尺八 2.4寸（A调）", 57),
];

/// 筒音作 5 的半音级（筒音 = 宫音上方纯五度，即宫音下方纯四度）。
pub const TONGYIN_SOL5: u8 = 7;
/// 筒音作 1 的半音级。
pub const TONGYIN_DO1: u8 = 0;
/// 筒音作 2 的半音级。
pub const TONGYIN_RE2: u8 = 2;
/// 按乐器 id 查全部型号。
pub fn wind_variants(instrument_id: &str) -> Option<&'static [WindVariantDef]> {
    match instrument_id {
        "zhudi" => Some(&ZHUDI_VARIANTS),
        "dongxiao" => Some(&DONGXIAO_VARIANTS),
        "shakuhachi" => Some(&SHAKUHACHI_VARIANTS),
        _ => None,
    }
}

/// 按型号 id 查型号定义。
pub fn find_wind_variant(variant_id: &str) -> Option<&'static WindVariantDef> {
    ["zhudi", "dongxiao", "shakuhachi"]
        .iter()
        .filter_map(|id| wind_variants(id))
        .flatten()
        .find(|v| v.id == variant_id)
}

/// 某调性/尺寸的默认型号（该调性下第一个孔制；洞箫即 8 孔）。
pub fn default_variant_of_key(
    instrument_id: &str,
    key_id: &str,
) -> Option<&'static WindVariantDef> {
    wind_variants(instrument_id)?
        .iter()
        .find(|v| v.key_id == key_id)
}

/// 管乐器显示名。
pub fn wind_display_name(id: &str) -> Option<&'static str> {
    match id {
        "zhudi" => Some("竹笛"),
        "dongxiao" => Some("洞箫"),
        "shakuhachi" => Some("尺八"),
        _ => None,
    }
}

/// 管乐器元数据。
pub fn wind_instrument_meta(id: &str) -> Option<Instrument> {
    wind_display_name(id).map(|name| Instrument {
        id: id.to_string(),
        display_name: name.to_string(),
        kind: InstrumentKind::Wind,
    })
}

impl WindVariantDef {
    /// 孔制（固定音阶类型号为 None）。
    pub fn hole_system(&self) -> Option<&'static HoleSystemDef> {
        match self.source {
            FingeringSource::Holes(s) => Some(s),
            FingeringSource::Scale { .. } => None,
        }
    }

    /// 是否支持筒音唱名转调（孔制类支持 12 个半音级）。
    pub fn supports_tongyin(&self) -> bool {
        self.hole_system().is_some()
    }

    /// 孔数（孔制类取孔制，固定音阶类取自带孔位表）。
    pub fn hole_count(&self) -> u8 {
        match self.source {
            FingeringSource::Holes(s) => s.hole_count,
            FingeringSource::Scale { hole_count, .. } => hole_count,
        }
    }

    /// 背孔数。
    pub fn back_hole_count(&self) -> u8 {
        match self.source {
            FingeringSource::Holes(s) => s.back_hole_count,
            FingeringSource::Scale {
                back_hole_count, ..
            } => back_hole_count,
        }
    }

    /// 宫音 pitch class：宫 = 筒音 − 筒音半音级。
    ///
    /// 筒音作 5 → 宫 = 筒音 − 7；作 1 → 宫 = 筒音；作 2 → 宫 = 筒音 − 2。
    /// 固定音阶类（尺八）以筒音为宫，忽略 `tongyin_degree`。
    pub fn tonic_pc(&self, tongyin_degree: u8) -> u8 {
        let degree = if self.supports_tongyin() {
            (tongyin_degree % 12) as i32
        } else {
            0
        };
        (self.fundamental_midi - degree).rem_euclid(12) as u8
    }

    /// 是否支持十二音展开（只有孔制类的音高完全由孔位组合决定）。
    pub fn supports_chromatic(&self) -> bool {
        self.hole_system().is_some()
    }

    /// 该型号在指定范围下的音阶半音偏移（升序）。
    ///
    /// 孔制类：七声取 15 音，十二音取 0..=24 共 25 音；固定音阶类忽略范围。
    pub fn offsets(&self, chromatic: bool) -> &'static [i32] {
        match self.source {
            FingeringSource::Holes(_) if chromatic => &CHROMATIC_OFFSETS,
            FingeringSource::Holes(_) => &SCALE_OFFSETS,
            FingeringSource::Scale { offsets, .. } => offsets,
        }
    }

    /// 第 `index` 个音（相对筒音 `semitones` 半音）的指法名。
    pub fn label_at(&self, index: usize, semitones: i32) -> String {
        match self.source {
            FingeringSource::Holes(system) => hole_label(system, semitones),
            FingeringSource::Scale { labels, .. } => {
                labels.get(index).copied().unwrap_or_default().to_string()
            }
        }
    }

    /// 第 `semitones` 半音的孔位组合（固定音阶类不在音阶内的半音返回空串）。
    pub fn pattern_at(&self, semitones: i32) -> &'static str {
        match self.source {
            FingeringSource::Holes(system) => hole_pattern(system, semitones),
            FingeringSource::Scale { .. } => self.pattern_at_pitch(semitones).unwrap_or(""),
        }
    }

    /// 某实际音高（相对筒音半音数）的实测孔位；资料未覆盖该音高时返回 `None`。
    pub fn pattern_at_pitch(&self, semitones: i32) -> Option<&'static str> {
        match self.source {
            FingeringSource::Holes(system) => hole_pattern_at_pitch(system, semitones),
            FingeringSource::Scale {
                offsets, patterns, ..
            } => offsets
                .iter()
                .position(|&offset| offset == semitones)
                .and_then(|index| patterns.get(index).copied()),
        }
    }
}

/// 判断某音是否为当前调（宫调式七声）的正声。
pub fn in_gong_scale(pc: u8, tonic_pc: u8) -> bool {
    let step = (pc as i32 - tonic_pc as i32).rem_euclid(12);
    GONG_SCALE_STEPS.contains(&step)
}

/// 孔序中文数字（第一孔…第八孔）。
const HOLE_NUMERALS: [&str; 8] = ["一", "二", "三", "四", "五", "六", "七", "八"];

/// 由孔位组合生成指法名（不含八度后缀）。
///
/// 规则：全闭 → 「全按」；全开 → 「全开」；开孔（含半孔）构成自下而上连续前缀 →
/// 「开第…孔」（半孔另注）；否则按叉口写法 → 「闭第…孔」。
pub fn pattern_label(pattern: &str) -> String {
    /// 孔序集合 → 中文孔序串（如 [0,1,2] → "一二三"）。
    fn numerals(list: &[usize]) -> String {
        list.iter()
            .filter_map(|&i| HOLE_NUMERALS.get(i).copied())
            .collect()
    }
    let indices = |want: char| -> Vec<usize> {
        pattern
            .chars()
            .enumerate()
            .filter(|(_, c)| *c == want)
            .map(|(i, _)| i)
            .collect()
    };
    let open = indices('O');
    let half = indices('H');
    let closed = indices('C');

    if open.is_empty() && half.is_empty() {
        return "全按".to_string();
    }
    if closed.is_empty() && half.is_empty() {
        return "全开".to_string();
    }
    // 开孔 + 半孔构成自下而上连续前缀时按「开第…孔」写，否则按叉口的「闭第…孔」写
    let mut touched: Vec<usize> = open.iter().chain(half.iter()).copied().collect();
    touched.sort_unstable();
    if !touched.iter().enumerate().all(|(k, &i)| k == i) {
        return format!("闭第{}孔", numerals(&closed));
    }
    match (open.is_empty(), half.is_empty()) {
        (false, true) => format!("开第{}孔", numerals(&open)),
        (true, false) => format!("第{}孔半开", numerals(&half)),
        (false, false) => format!("开第{}孔·第{}孔半开", numerals(&open), numerals(&half)),
        (true, true) => unreachable!("全闭已在前置分支返回"),
    }
}

/// 八度后缀：平吹（0–11）无后缀，超吹（12–23）「·超吹」，再八度（≥24）「·超吹二」。
pub fn octave_suffix(semitones: i32) -> &'static str {
    match semitones.div_euclid(12) {
        0 => "",
        1 => "·超吹",
        _ => "·超吹二",
    }
}

/// 孔制类型号某个半音的完整指法名（筒音特殊标注为「筒音」）。
///
/// 名称取自该音高的实测孔位：八孔箫沿用低音区的习惯叫法，中、高音区出现新孔位时
/// 按孔位自动生成名称，最后附八度后缀。
pub fn hole_label(system: &HoleSystemDef, semitones: i32) -> String {
    let pattern = hole_pattern(system, semitones);
    let base = if system.id == "x8" {
        match XIAO8_PATTERNS.iter().position(|known| *known == pattern) {
            Some(index) => XIAO8_LABELS[index].to_string(),
            None => pattern_label(pattern),
        }
    } else {
        let label = pattern_label(pattern);
        if label == "全按" {
            "筒音".to_string()
        } else {
            label
        }
    };
    format!("{base}{}", octave_suffix(semitones))
}

/// 将孔位分到「顺开」或「组合」展示列。
///
/// 顺开指从第一孔起连续开放若干孔、其余孔闭合；全闭筒音和全开也归入顺开。
/// 任意半孔或开闭交错均归入组合指法。
pub fn fingering_kind(pattern: &str) -> FingeringKind {
    if pattern.contains('H') {
        return FingeringKind::Combination;
    }
    let mut reached_closed = false;
    for mark in pattern.chars() {
        match mark {
            'O' if reached_closed => return FingeringKind::Combination,
            'O' => {}
            _ => reached_closed = true,
        }
    }
    FingeringKind::Sequential
}

/// 最上方开孔（含半孔）的孔序索引；全闭筒音/无孔位返回 `None`，由 UI 对齐到管底。
pub fn fingering_anchor_hole(pattern: &str) -> Option<u8> {
    pattern
        .bytes()
        .enumerate()
        .rfind(|(_, mark)| matches!(mark, b'O' | b'H'))
        .and_then(|(index, _)| u8::try_from(index).ok())
}

/// 孔制类型号某个半音的孔位组合字符串（`C`/`O`/`H`）。
///
/// 按八度取模，供旧接口与固定两八度音阶使用。三音区展开请用
/// [`hole_pattern_at_pitch`]，它区分低/中/高音区各自的实测孔位。
pub fn hole_pattern(system: &HoleSystemDef, semitones: i32) -> &'static str {
    hole_pattern_at_pitch(system, semitones)
        .unwrap_or_else(|| system.patterns[semitones.rem_euclid(12) as usize])
}

/// 某个实际音高（相对筒音半音数）的实测孔位；资料未覆盖时返回 `None`。
pub fn hole_pattern_at_pitch(system: &HoleSystemDef, semitones: i32) -> Option<&'static str> {
    match semitones {
        0..=11 => Some(system.patterns[semitones as usize]),
        12..=31 => system.upper_patterns[(semitones - 12) as usize],
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn variant_counts_and_unique_ids() {
        assert_eq!(ZHUDI_VARIANTS.len(), 5);
        assert_eq!(DONGXIAO_VARIANTS.len(), 4); // 2 调 × 2 孔制
        assert_eq!(SHAKUHACHI_VARIANTS.len(), 4);
        let mut ids: Vec<_> = ["zhudi", "dongxiao", "shakuhachi"]
            .iter()
            .filter_map(|id| wind_variants(id))
            .flatten()
            .map(|v| v.id)
            .collect();
        let total = ids.len();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), total, "型号 id 必须唯一");
    }

    #[test]
    fn dongxiao_defaults_to_eight_holes() {
        let first = &DONGXIAO_VARIANTS[0];
        assert_eq!(first.id, "g_xiao_x8");
        assert_eq!(first.hole_system().unwrap().hole_count, 8);
        assert_eq!(
            default_variant_of_key("dongxiao", "f_xiao").unwrap().id,
            "f_xiao_x8"
        );
    }

    #[test]
    fn hole_patterns_wellformed() {
        for system in [&XIAO_8, &XIAO_6, &DIZI_6] {
            for (semi, pattern) in system.patterns.iter().enumerate() {
                assert_eq!(
                    pattern.chars().count(),
                    system.hole_count as usize,
                    "{} 半音 {semi} 孔位长度不符",
                    system.id
                );
                assert!(
                    pattern.chars().all(|c| matches!(c, 'C' | 'O' | 'H')),
                    "{} 半音 {semi} 含非法孔位字符",
                    system.id
                );
            }
            assert_eq!(
                system.patterns[0].chars().filter(|&c| c == 'C').count(),
                system.hole_count as usize
            );
            assert!(system.patterns[11].contains('O'));
        }
    }

    #[test]
    fn eight_hole_scale_needs_no_half_hole() {
        // 八孔箫正声音阶采用第 2、6 辅助孔常闭方案，不需要半孔。
        for &off in SCALE_OFFSETS.iter() {
            let p = hole_pattern(&XIAO_8, off);
            assert!(!p.contains('H'), "8 孔七声 {off} 不应出现半孔：{p}");
        }
        for &off in &[4, 5, 7, 9, 11] {
            let p = hole_pattern(&XIAO_8, off);
            assert_eq!(p.as_bytes()[1], b'C', "第 2 孔应保持关闭：{p}");
            assert_eq!(p.as_bytes()[5], b'C', "第 6 孔应保持关闭：{p}");
        }
    }

    #[test]
    fn dongxiao_scale_offsets_follow_tongyin_and_cover_numbered_scale() {
        assert_eq!(scale_base_offsets(7), [0, 2, 4, 5, 7, 9, 10]);
        assert_eq!(scale_base_offsets(2), [0, 2, 3, 5, 7, 9, 10]);
    }

    #[test]
    fn eight_hole_labels() {
        assert_eq!(hole_label(&XIAO_8, 0), "筒音");
        assert_eq!(hole_label(&XIAO_8, 2), "开第一孔");
        assert_eq!(hole_label(&XIAO_8, 4), "开第一三孔");
        assert_eq!(hole_label(&XIAO_8, 9), "开第一三四五七孔");
        assert_eq!(hole_label(&XIAO_8, 10), "闭第二五六七孔");
        assert_eq!(hole_label(&XIAO_8, 11), "开第一三四五七八孔");
        assert_eq!(hole_label(&XIAO_8, 1), "第一孔半开");
        assert_eq!(hole_label(&XIAO_8, 6), "开第一三四孔·第五孔半开");
        // 中音区多数沿用低音孔位，故名称相同只加超吹后缀。
        assert_eq!(hole_label(&XIAO_8, 12), "筒音·超吹");
        assert_eq!(hole_label(&XIAO_8, 14), "开第一孔·超吹");
        // 22 半音换用另一种叉口，名称随实测孔位变化。
        assert_eq!(hole_label(&XIAO_8, 22), "闭第三四五六八孔·超吹");
        // 高音区孔位自成一套：24 半音用低音叉口，25 半音是全新组合。
        assert_eq!(hole_label(&XIAO_8, 24), "闭第二五六七孔·超吹二");
        assert_eq!(hole_label(&XIAO_8, 25), "闭第一二六七八孔·超吹二");
    }

    #[test]
    fn upper_register_patterns_come_from_charts_and_leave_gaps_absent() {
        // 中音区除 22 半音外与低音区同孔位；高音区孔位不同。
        for base in 0..12i32 {
            let low = hole_pattern_at_pitch(&XIAO_8, base).unwrap();
            let middle = hole_pattern_at_pitch(&XIAO_8, base + 12).unwrap();
            if base == 10 {
                assert_ne!(low, middle, "22 半音应为另一种叉口");
            } else {
                assert_eq!(low, middle, "中音 {} 半音应与低音同孔位", base + 12);
            }
        }
        assert_ne!(
            hole_pattern_at_pitch(&XIAO_8, 25),
            hole_pattern_at_pitch(&XIAO_8, 1)
        );
        // 八孔图只覆盖到 31 半音，六孔表在列间冲突处留空。
        assert!(hole_pattern_at_pitch(&XIAO_8, 31).is_some());
        assert!(hole_pattern_at_pitch(&XIAO_8, 32).is_none());
        assert!(hole_pattern_at_pitch(&XIAO_6, 18).is_none());
        assert!(hole_pattern_at_pitch(&XIAO_6, 23).is_none());
        assert!(hole_pattern_at_pitch(&XIAO_6, 28).is_none());
        assert!(hole_pattern_at_pitch(&XIAO_6, 30).is_none());
        for system in [&XIAO_8, &XIAO_6, &DIZI_6] {
            for (index, pattern) in system.upper_patterns.iter().enumerate() {
                let Some(pattern) = pattern else { continue };
                assert_eq!(
                    pattern.chars().count(),
                    system.hole_count as usize,
                    "{} 半音 {} 孔位长度不符",
                    system.id,
                    index + 12
                );
                assert!(
                    pattern.chars().all(|c| matches!(c, 'C' | 'O' | 'H')),
                    "{} 半音 {} 含非法孔位字符",
                    system.id,
                    index + 12
                );
            }
        }
    }

    #[test]
    fn fingering_lane_and_anchor_follow_hole_geometry() {
        assert_eq!(fingering_kind("CCCCCCCC"), FingeringKind::Sequential);
        assert_eq!(fingering_anchor_hole("CCCCCCCC"), None);
        assert_eq!(fingering_kind("OOOOCCCC"), FingeringKind::Sequential);
        assert_eq!(fingering_anchor_hole("OOOOCCCC"), Some(3));
        assert_eq!(fingering_kind("OOOOOOOO"), FingeringKind::Sequential);
        assert_eq!(fingering_anchor_hole("OOOOOOOO"), Some(7));
        assert_eq!(fingering_kind("HCCCCCCC"), FingeringKind::Combination);
        assert_eq!(fingering_anchor_hole("HCCCCCCC"), Some(0));
        assert_eq!(fingering_kind("OCOOCCCO"), FingeringKind::Combination);
        assert_eq!(fingering_anchor_hole("OCOOCCCO"), Some(7));
    }

    #[test]
    fn six_hole_labels_match_legacy_names() {
        // 六孔箫/竹笛的七声指法名与旧版静态标签一致
        let expected = [
            (0, "筒音"),
            (2, "开第一孔"),
            (4, "开第一二孔"),
            (5, "开第一二三孔"),
            (7, "开第一二三四孔"),
            (9, "开第一二三四五孔"),
            (11, "全开"),
            // 六孔表的中、高音区按图实测：高八度筒音改为开背孔。
            (14, "开第一孔·超吹"),
            (22, "闭第一二三五孔·超吹"),
        ];
        for (semi, name) in expected {
            assert_eq!(hole_label(&XIAO_6, semi), name, "半音 {semi}");
        }
        assert_eq!(hole_label(&XIAO_6, 10), "开第一二三四五孔·第六孔半开");
    }

    #[test]
    fn dizi_uses_its_own_fork_and_overblow_patterns() {
        for semi in [0, 2, 4, 5, 7, 9, 11] {
            assert_eq!(hole_pattern(&DIZI_6, semi), hole_pattern(&XIAO_6, semi));
        }
        // 4 用叉口而非半孔；中音筒音开第六孔；高音只收录 24 半音。
        assert_eq!(hole_pattern(&DIZI_6, 10), "OOOCCO");
        assert_eq!(hole_pattern_at_pitch(&DIZI_6, 12), Some("CCCCCO"));
        assert_eq!(hole_pattern_at_pitch(&DIZI_6, 22), Some("CCCOCO"));
        assert_eq!(hole_pattern_at_pitch(&DIZI_6, 24), Some("CCCCCO"));
        assert!(hole_pattern_at_pitch(&DIZI_6, 26).is_none());
    }

    #[test]
    fn shakuhachi_patterns_cover_every_scale_note() {
        assert_eq!(SHAKUHACHI_PATTERNS.len(), SHAKUHACHI_OFFSETS.len());
        for pattern in SHAKUHACHI_PATTERNS {
            assert_eq!(pattern.chars().count(), SHAKUHACHI_HOLE_COUNT as usize);
            assert!(pattern.chars().all(|c| matches!(c, 'C' | 'O')));
        }
        let v = find_wind_variant("shaku_1_8").unwrap();
        assert_eq!(v.hole_count(), 5);
        assert_eq!(v.back_hole_count(), 1);
        assert_eq!(v.pattern_at(3), "OCCCC");
        assert_eq!(v.pattern_at_pitch(10), Some("CCOOC"));
        assert_eq!(v.pattern_at_pitch(22), Some("COOOC"));
        assert_eq!(v.pattern_at_pitch(1), None);
    }

    #[test]
    fn scale_offsets_ascending() {
        for w in SCALE_OFFSETS.windows(2) {
            assert!(w[0] < w[1], "七声音阶非严格升序");
        }
        for w in SHAKUHACHI_OFFSETS.windows(2) {
            assert!(w[0] < w[1], "尺八音阶非严格升序");
        }
        assert_eq!(SHAKUHACHI_OFFSETS.len(), SHAKUHACHI_LABELS.len());
        assert_eq!(CHROMATIC_OFFSETS.len(), 25);
        assert_eq!(CHROMATIC_OFFSETS[24], 24);
    }

    #[test]
    fn variant_offsets_by_scope() {
        let xiao = find_wind_variant("g_xiao_x8").unwrap();
        assert_eq!(xiao.offsets(false).len(), 15);
        assert_eq!(xiao.offsets(true).len(), 25);
        assert!(xiao.supports_chromatic());
        // 尺八两种范围都是自带音阶
        let shaku = find_wind_variant("shaku_1_8").unwrap();
        assert_eq!(shaku.offsets(false).len(), 11);
        assert_eq!(shaku.offsets(true).len(), 11);
        assert!(!shaku.supports_chromatic());
        assert_eq!(shaku.label_at(0, 0), "筒音(ro)");
        assert_eq!(shaku.pattern_at(0), "CCCCC");
    }

    #[test]
    fn zhudi_fundamentals_sit_an_octave_above_same_key_xiao() {
        // D 调曲笛：第三孔 d2 下方纯四度 = A4 (MIDI 69) = 440Hz
        let v = find_wind_variant("d_qudi_d6").unwrap();
        assert_eq!(v.fundamental_midi, 69);
        let f = crate::note::midi_to_freq(69.0, 440.0);
        assert!((f - 440.0).abs() < 0.1);
        // G 调梆笛：筒音 d2 = D5 = 587Hz，第三孔即定调音 g2 = 784Hz
        let bangdi = find_wind_variant("g_bangdi_d6").unwrap();
        assert_eq!(bangdi.fundamental_midi, 74);
        assert!((crate::note::midi_to_freq(74.0, 440.0) - 587.33).abs() < 0.1);
        assert!((crate::note::midi_to_freq(79.0, 440.0) - 783.99).abs() < 0.1);
        // 同名调的笛比箫高一个八度：G 调梆笛筒音 = G 调洞箫筒音 + 12
        let xiao = find_wind_variant("g_xiao_x8").unwrap();
        assert_eq!(bangdi.fundamental_midi, xiao.fundamental_midi + 12);
        // 五调筒音都是该调 sol，且 C 调不再错取主音下方纯五度的 F
        for (id, expected) in [
            ("d_qudi_d6", 69),
            ("g_bangdi_d6", 74),
            ("f_dizi_d6", 72),
            ("c_dizi_d6", 67),
            ("e_dizi_d6", 71),
        ] {
            let variant = find_wind_variant(id).unwrap();
            assert_eq!(variant.fundamental_midi, expected, "{id}");
            // 筒音作 5 时宫音 = 筒音 + 5 个半音
            assert_eq!(
                variant.tonic_pc(7),
                ((expected + 5) % 12) as u8,
                "{id} 的筒音必须是该调 sol"
            );
        }
    }

    #[test]
    fn shakuhachi_1_8_scale() {
        // 1.8 寸：筒音 D4=62，ro tsu re chi ha = D F G A C
        let v = find_wind_variant("shaku_1_8").unwrap();
        let midis: Vec<i32> = SHAKUHACHI_OFFSETS
            .iter()
            .map(|o| v.fundamental_midi + o)
            .collect();
        assert_eq!(&midis[..5], &[62, 65, 67, 69, 72]); // D4 F4 G4 A4 C5
        assert!(!v.supports_tongyin());
        assert!(v.hole_system().is_none());
    }

    #[test]
    fn tonic_pc_mapping() {
        let qudi = find_wind_variant("d_qudi_d6").unwrap();
        // D 调曲笛作 5：宫 = D (pc 2)
        assert_eq!(qudi.tonic_pc(TONGYIN_SOL5), 2);
        // 作 1：宫 = A (pc 9)；作 2：宫 = G (pc 7)
        assert_eq!(qudi.tonic_pc(TONGYIN_DO1), 9);
        assert_eq!(qudi.tonic_pc(TONGYIN_RE2), 7);
        // 十二级连续转调：每升一级宫音降一个半音
        for degree in 0u8..12 {
            assert_eq!(
                qudi.tonic_pc(degree),
                (45 - degree as i32).rem_euclid(12) as u8
            );
        }
        // 尺八以筒音为宫，忽略筒音级
        let shaku = find_wind_variant("shaku_1_8").unwrap();
        assert_eq!(shaku.tonic_pc(TONGYIN_SOL5), 2);
    }

    #[test]
    fn gong_scale_membership() {
        // C 宫：C D E F G A B 为正声，C# 为偏音
        assert!(in_gong_scale(0, 0));
        assert!(in_gong_scale(11, 0));
        assert!(!in_gong_scale(1, 0));
        assert!(!in_gong_scale(6, 0));
    }

    #[test]
    fn unknown_wind_none() {
        assert!(wind_variants("suona").is_none());
        assert!(wind_display_name("suona").is_none());
        assert!(find_wind_variant("nope").is_none());
    }
}
