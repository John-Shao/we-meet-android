package com.we.meet.ui.theme

import androidx.compose.ui.unit.dp

/**
 * App-wide spacing and sizing tokens.
 *
 * Unlike [WeMeetExtras] (which varies by light/dark and therefore lives in a
 * CompositionLocal), these values are theme-independent constants, so a plain
 * object is the simplest correct home. Prefer these over inline `.dp` literals
 * so button heights, screen padding, and the 8dp spacing grid stay consistent
 * across screens.
 */
object Dimens {

    // ---- Spacing (8dp grid; xs/xxs are the sanctioned sub-grid exceptions
    // for tight icon–text gaps) ----
    /** 显式的「无间距」。动画目标值收起到 0 时用它,别写裸 `0.dp`。 */
    val SpaceNone = 0.dp
    val SpaceXxs = 2.dp
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 12.dp
    val SpaceL = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp
    /** 撑开整屏留白用(空态上下、预览页两侧),不用于常规排版。 */
    val SpaceXxxl = 48.dp

    /**
     * Standard horizontal content inset (screen bodies, dialogs, sheets, list
     * rows). Prefer this over inline `horizontal = 16.dp`.
     */
    val ScreenPadding = 16.dp

    // ---- Controls ----
    /** Shared height for primary/full-width buttons across the app. */
    val ButtonHeight = 52.dp

    /** Google/Material minimum interactive target for accessibility. */
    val MinTouchTarget = 48.dp

    /** 底部弹层顶部标题栏的高度(消息、AI 等各 sheet 共用)。 */
    val SheetHeaderHeight = 40.dp

    /**
     * 密集列表行里被压小的图标按钮。
     *
     * ⚠️ 32dp **低于** [MinTouchTarget],不满足 WCAG 2.2 AA。收在这里是为了
     * 让这笔欠账只有一处、可一次改掉,不是为它背书。修的方向是热区回到 48dp
     * 而视觉图标保持小(`IconButton(Modifier.size(MinTouchTarget))` + 小 Icon),
     * 会让参会人列表行变高,属于布局改动,应单独评估。
     *
     * 新代码不要用它。
     */
    val IconButtonCompact = 32.dp

    // ---- Icons ----
    /** 徽标/角标内的小图标(画面块上的麦克风、举手)。 */
    val IconTiny = 16.dp
    val IconSmall = 18.dp
    val IconMedium = 24.dp
    val IconLarge = 28.dp
    /** 功能入口方块里那种大一号的图标。 */
    val IconXl = 32.dp

    // ---- 圆角 ----
    /** 极小的标签底(如消息里的「主持人」角标)。 */
    val CornerXs = 4.dp
    /** 小卡片、徽标底、聊天气泡。 */
    val CornerS = 8.dp
    /** 卡片、面板、画面块。会中界面的默认圆角。 */
    val CornerM = 12.dp
    /** 大面板、底部弹层。 */
    val CornerL = 16.dp

    // ---- 描边 ----
    /** 列表行之间的细分隔线。比 [BorderThin] 更轻,免得长列表显得像表格。 */
    val DividerThin = 0.5.dp
    /** 普通描边/分隔线。 */
    val BorderThin = 1.dp
    /** 需要强调的描边(如「正在说话」的画面块外框)。 */
    val BorderEmphasis = 2.dp

    // ---- 高程 ----
    /** 卡片与背景之间最轻微的一档色调抬升。 */
    val ElevationSubtle = 1.dp
    /** 吸顶/吸底条压在滚动内容之上的投影。 */
    val ElevationSticky = 8.dp

    // ---- 头像 ----
    /** 内联在文字流里的极小头像(已选参会人的小胶囊)。 */
    val AvatarXs = 24.dp
    val AvatarS = 36.dp
    /** 列表行主位头像。 */
    val AvatarM = 40.dp
    /** 详情页/资料页的大头像。 */
    val AvatarXl = 88.dp
    /** 个人资料页顶部的最大头像,以及启动页的 App 图标。 */
    val AvatarXxl = 96.dp

    /** 列表行左侧的图标容器(圆角方块,内含一个居中图标)。 */
    val ListLeadingIcon = 44.dp

    /** 空态/错误态里那个大图标。比正文图标大一档,用来撑起整屏的视觉重心。 */
    val IconIllustration = 48.dp
    /** 占据整屏的状态图标(等候室、被移出会议等只有一个图标 + 一句话的页面)。 */
    val IconIllustrationLarge = 64.dp

    /** 列表行左侧的方形缩略图(历史会议、文件等)。 */
    val ListThumbnail = 56.dp
    /** 首页那种大号功能入口方块。 */
    val ActionTile = 72.dp

    /** 键值行左侧标签列的宽度,让冒号后的值对齐成一列。 */
    val LabelColumnWidth = 88.dp

    /**
     * 隐藏的取焦锚点。
     *
     * 验证码那种「看着是 N 个格子、实际是一个不可见输入框」的做法需要一个
     * 有尺寸才能取焦、又不能被看见的元素。1dp 是这个 hack 的一部分,不是
     * 排版尺寸 —— 别拿它当间距用。
     */
    val HiddenFocusAnchor = 1.dp

    /**
     * 列表分隔线的左缩进 —— 让线从文字左缘起,不横穿头像/图标。
     *
     * 两档对应两种行:[DividerIndent] 用于图标行(部门、入口行),
     * [DividerIndentAvatar] 用于头像行(成员),后者的头像更宽。
     * 值要和对应行的实际左侧元素宽度对得上,改行内布局时记得一起改。
     */
    val DividerIndent = 56.dp
    val DividerIndentThumbnail = 68.dp
    val DividerIndentAvatar = 72.dp

    /** 按钮内联转圈的描边粗细。 */
    val ProgressStroke = 2.dp

    /**
     * 会中界面的布局常量。
     *
     * 这些值**不是** 8dp 栅格的倍数,也不该是 —— 它们是被具体内容和手感定死
     * 的尺寸(画中画的 3:4、工具栏高度、二维码边长)。收进来是为了「改一处
     * 生效」,不是为了对齐栅格。
     */
    object Room {
        /** 顶部/底部工具栏的图标按钮边长。 */
        val ToolbarIconButton = 40.dp
        /** 顶栏高度,参与工具栏收起动画的位移计算。 */
        val TopToolbarHeight = 56.dp
        /** 底栏高度,同上。比顶栏高 2dp 是为了容纳按钮下方的文字标签。 */
        val BottomToolbarHeight = 58.dp
        /** 无摄像头时圆形头像的边长范围(按画面块短边的 30% 取,再夹到这个区间)。 */
        val AvatarMin = 48.dp
        val AvatarMax = 120.dp
        /**
         * 焦点模式下缩略图轮播里的小画面块,3:4 竖版。
         *
         * 竖屏时轮播在底部(高 [CarouselTileHeight]、每块宽 [CarouselTileWidth]),
         * 横屏时在右侧(宽高对调),所以两个值两种朝向共用。
         */
        val CarouselTileWidth = 120.dp
        val CarouselTileHeight = 160.dp
        /** 画廊分页的页码圆点,当前页略大。 */
        val PageDotActive = 8.dp
        val PageDotInactive = 6.dp
        /** 字幕条距底的避让量,让它落在底部工具栏之上。 */
        val SubtitleBottomInset = 96.dp
        /**
         * 顶栏居中标题两侧的留白。
         *
         * 取这么大是为了给两侧的图标按钮让位 —— 标题过长时先被这个留白挤到
         * 省略号,而不是压到按钮底下。
         */
        val TitleSideInset = 110.dp
        /** 邀请弹层里的二维码边长。 */
        val QrSize = 200.dp
    }

    /** 个人资料页的头图布局。 */
    object Profile {
        /** 顶部封面图高度。 */
        val HeaderHeight = 160.dp
        /** 头像相对封面底边的下探量,让它压在封面与内容的交界上。 */
        val AvatarOverlap = 44.dp
        /** 封面下方给下探的头像预留的垂直空间,免得头像压住下面第一行内容。 */
        val AvatarReserve = 56.dp
    }

    /**
     * 日历时间网格的几何常量。
     *
     * 和 [Room] 同理:这些值由内容和手感定死,不是 8dp 栅格的倍数,也不该硬凑。
     * 收进来是为了「改一处生效」。
     */
    object Calendar {
        /**
         * 一小时对应的网格高度。
         *
         * 日视图、周视图、忙闲对比、TimeGrid 默认参数原先各写一遍 56.dp ——
         * 改一处漏三处就会错位。所有网格必须取同一个值。
         */
        val HourHeight = 56.dp
        /** 左侧小时刻度栏宽度。日/周视图共用,改了两边一起动。 */
        val HourRailWidth = 44.dp
        /** 忙闲对比里每人一列的最小宽度,再窄名字就挤没了。 */
        val MinColumnWidth = 76.dp

        /** 新建日程草稿块上下的拖拽手柄:热区、内缩、可见尺寸。 */
        val DraftHandleTouch = 26.dp
        val DraftHandleInset = 18.dp
        val DraftHandleSize = 13.dp

        /** 「当前时间」虚线的线宽与虚线间隔;横线本身的粗细与左端圆点。 */
        val NowLineStroke = 1.2.dp
        val NowLineDashGap = 7.dp
        val NowLineThickness = 2.dp
        val NowLineDotSize = 6.dp

        /**
         * 日程块的最小高度 —— 时长再短也得留得下这么高,否则 15 分钟的日程
         * 会缩成一条看不见的线。三种块各有下限:普通日程、拖拽预览、新建选区。
         */
        val BlockMinHeight = 10.dp
        val MovePreviewMinHeight = 14.dp
        val SelectionMinHeight = 6.dp
        /** 日程块左侧那条表示表态的实心竖条。 */
        val BlockAccentBarWidth = 3.dp

        /**
         * 日程小块的水平内缩。
         *
         * 1.5dp 看着离谱,但网格里相邻两列小块之间只有这么点缝 —— 再大就把
         * 15 分钟的短块挤成一条线了。
         */
        val ChipInset = 1.5.dp

        /** 月视图里日期数字的圆形选中底。 */
        val DateCellSize = 34.dp
        /** 月视图日期下方「这天有日程」的小圆点。 */
        val EventDotSize = 4.dp
        /** 议程行左侧那条表示表态的竖色条。 */
        val RsvpAccentBarWidth = 4.dp
        /** 议程行左侧时间列的宽度,要放得下「全天」和「23:59」。 */
        val TimeLabelWidth = 52.dp
        /** 忙闲对比里压在头像右上角的冲突红点,及其与头像隔开的描边环。 */
        val ConflictDotSize = 11.dp
        val ConflictDotRing = 1.5.dp
        /** 周视图里「某天有日程」的圆点。 */
        val WeekDotSize = 26.dp
        /** 议程/网格底部留白,免得浮动按钮盖住最后一条。 */
        val FabClearance = 88.dp
    }
}
