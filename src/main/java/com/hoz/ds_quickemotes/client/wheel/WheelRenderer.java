package com.hoz.ds_quickemotes.client.wheel;

import com.hoz.ds_quickemotes.client.anim.WheelAnim;
import com.hoz.ds_quickemotes.config.QuickEmotesConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 轮盘矢量渲染（无贴图，纯三角网格）。
 * 图层自底向顶：扇区背景（径向排布=均匀暗色；悬浮排布=每扇区角向浅→深渐变）
 * → 悬停高亮扇区 → 外环指示灯弧 → 文字。
 * 所有角度为屏幕坐标角度（0°=正右，顺时针），与 {@link WheelLayout#sectorAt} 一致。
 */
public final class WheelRenderer {
    /** 均匀暗色底（径向排布使用） */
    public static final int COLOR_BACKGROUND = 0x80101010;
    /** 指示灯亮起的扇区底色更暗（径向排布，更不透明 + 更黑） */
    public static final int COLOR_BACKGROUND_ACTIVE = 0xB00A0A0A;
    /** 「取消所有表情」扇区底色单独调白一点点（叠在暗色底上） */
    public static final int COLOR_CANCEL_TINT = 0x20FFFFFF;
    /** 悬浮排布下每扇区背景的角向渐变：起始边（浅）→ 终到边（深） */
    public static final int COLOR_SECTOR_GRADIENT_LIGHT = 0x80262626;
    public static final int COLOR_SECTOR_GRADIENT_DARK = 0x800C0C0C;
    /** 悬浮排布下亮灯扇区：取消渐变，统一用比渐变暗端更深的实色 */
    public static final int COLOR_SECTOR_ACTIVE_FLOATING = 0x90040404;
    /** 悬停高亮：浅蓝 */
    public static final int COLOR_HIGHLIGHT = 0xFF00B2FF;
    /** 「取消所有表情」扇区高亮：红 */
    public static final int COLOR_CANCEL = 0xFFCE3535;
    /** 外环指示灯（条目全部在播）：亮黄 */
    public static final int COLOR_INDICATOR = 0xFFFFD84D;
    /** 外环指示灯（条目部分在播，如组合里有一次性表情播完退出了）：橙色 */
    public static final int COLOR_INDICATOR_PARTIAL = 0xFFFF9800;
    /** 外环指示灯（强制循环中）：红色 */
    public static final int COLOR_INDICATOR_FORCED = 0xFFFF3B30;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final float TEXT_MIN_SCALE = 0.4f;
    private static final float TEXT_MAX_SCALE = 1.2f;
    /** 达到该缩放即视为「够读」，不再尝试更多行 */
    private static final float TEXT_COMFORT_SCALE = 0.9f;
    private static final int TEXT_MAX_LINES = 3;
    /** 断行拆进词中间时的代价（让拆行优先落在词与词之间） */
    private static final double TEXT_BREAK_PENALTY = 8;
    /** 文字与内外边缘的径向留白 */
    private static final double TEXT_PADDING = 6;

    /** 指示灯状态：熄灭 / 条目全部在播（黄） / 部分在播（橙） / 强制循环（红） */
    public enum IndicatorState { OFF, FULL, PARTIAL, FORCED }

    /**
     * 页面上一个扇区的内容：标签 + 指示灯动画值（由屏幕逐帧平滑后传入）。
     *
     * @param indicatorColor     指示灯 ARGB（alpha 通道 = 强度 × 255，熄灭时为 0）
     * @param indicatorIntensity 指示灯强度 0~1（驱动底色变暗的过渡）
     * @param labelGrow          横版悬浮标签放大强度 0~1（选中放大动画）
     */
    public record Slot(String label, int indicatorColor, float indicatorIntensity, float labelGrow) { }

    private WheelRenderer() { }

    public static void render(final GuiGraphics graphics, final WheelLayout layout, final int centerX, final int centerY,
                              final List<Slot> slots, final QuickEmotesConfig.LabelStyle labelStyle,
                              final int labelLeftBound, final int labelRightBound, final float alpha,
                              final float geometryScale, final float hoverPosDeg, final float hoverAlpha,
                              final float hoverRedness) {
        if (alpha <= 0.004f) {
            return;
        }

        // 开屏/关屏的整体不透明度：所有几何与文字统一乘进各自的 alpha
        final int textColor = WheelAnim.mulAlpha(TEXT_COLOR, alpha);
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f matrix = graphics.pose().last().pose();

        // 扇区背景（逐扇区绘制：指示灯强度越高底色越暗，平滑过渡）
        for (int sector = 1; sector < layout.sectorCount(); sector++) {
            float intensity = slots.size() > sector ? slots.get(sector).indicatorIntensity() : 0f;
            double startDeg = layout.startAngleDeg(sector);
            double endDeg = layout.endAngleDeg(sector);

            if (labelStyle == QuickEmotesConfig.LabelStyle.FLOATING) {
                // 角向浅→深渐变；亮灯时两停靠点都向「更暗更实」的实色收敛（强度 1 = 完全实色）
                int light = FastColor.ARGB32.lerp(intensity, COLOR_SECTOR_GRADIENT_LIGHT, COLOR_SECTOR_ACTIVE_FLOATING);
                int dark = FastColor.ARGB32.lerp(intensity, COLOR_SECTOR_GRADIENT_DARK, COLOR_SECTOR_ACTIVE_FLOATING);
                emitGradientAnnulus(vertices, matrix, centerX, centerY, layout.centerHoleRadius(), layout.outerRadius(),
                        startDeg, endDeg, WheelAnim.mulAlpha(light, alpha), WheelAnim.mulAlpha(dark, alpha));
            } else {
                int background = FastColor.ARGB32.lerp(intensity, COLOR_BACKGROUND, COLOR_BACKGROUND_ACTIVE);
                emitAnnulus(vertices, matrix, centerX, centerY, layout.centerHoleRadius(), layout.outerRadius(),
                        startDeg, endDeg, WheelAnim.mulAlpha(background, alpha));
            }
        }

        // 取消扇区保持平坦暗底（随后叠加白提亮）
        emitAnnulus(vertices, matrix, centerX, centerY, layout.centerHoleRadius(), layout.outerRadius(),
                layout.startAngleDeg(WheelLayout.CANCEL_SECTOR), layout.endAngleDeg(WheelLayout.CANCEL_SECTOR),
                WheelAnim.mulAlpha(COLOR_BACKGROUND, alpha));

        emitAnnulus(vertices, matrix, centerX, centerY, layout.centerHoleRadius(), layout.outerRadius(),
                layout.startAngleDeg(WheelLayout.CANCEL_SECTOR), layout.endAngleDeg(WheelLayout.CANCEL_SECTOR),
                WheelAnim.mulAlpha(COLOR_CANCEL_TINT, alpha));

        // 悬停高亮：单个楔形平滑滑向目标扇区（角度由屏幕动画驱动），颜色蓝↔红平滑过渡
        if (hoverAlpha > 0.004f) {
            int color = FastColor.ARGB32.lerp(hoverRedness, COLOR_HIGHLIGHT, COLOR_CANCEL);
            double half = layout.sectorDegrees() / 2.0;
            emitAnnulus(vertices, matrix, centerX, centerY, layout.centerHoleRadius(), layout.outerRadius(),
                    hoverPosDeg - half, hoverPosDeg + half, WheelAnim.mulAlpha(color, alpha * hoverAlpha));
        }

        // 外环指示灯：取消扇区没有指示灯（颜色与强度由屏幕平滑后传入）
        for (int sector = 1; sector < layout.sectorCount(); sector++) {
            if (slots.size() <= sector || slots.get(sector).indicatorIntensity() <= 0.004f) {
                continue;
            }
            emitAnnulus(vertices, matrix, centerX, centerY, layout.bandInnerRadius(), layout.outerRadius(),
                    layout.startAngleDeg(sector), layout.endAngleDeg(sector),
                    WheelAnim.mulAlpha(slots.get(sector).indicatorColor(), alpha));
        }

        // 先冲刷几何再画文字，避免批次顺序不确定
        graphics.flush();

        for (int sector = 0; sector < layout.sectorCount() && sector < slots.size(); sector++) {
            String label = slots.get(sector).label();
            if (label.isEmpty()) {
                continue;
            }

            boolean floating = labelStyle == QuickEmotesConfig.LabelStyle.FLOATING;

            if (floating) {
                drawFloatingLabel(graphics, layout, centerX, centerY, sector, label, labelLeftBound, labelRightBound,
                        textColor, slots.get(sector).labelGrow());
            } else {
                drawSectorText(graphics, layout, centerX, centerY, sector, label, textColor, geometryScale);
            }
        }
    }

    // ------------------------------------------------------------ 几何网格

    /** 环形扇区网格（单色；三角化：每步一个环形四边形，最多 4° 一步） */
    private static void emitAnnulus(final VertexConsumer vertices, final Matrix4f matrix,
                                    final int centerX, final int centerY,
                                    final double innerRadius, final double outerRadius,
                                    final double startDeg, final double endDeg, final int argb) {
        double span = endDeg - startDeg;
        int steps = Math.max(1, (int) Math.ceil(span / 4.0));
        double step = Math.toRadians(span / steps);
        double angle = Math.toRadians(startDeg);

        for (int i = 0; i < steps; i++) {
            double next = angle + step;
            emitQuad(vertices, matrix, centerX, centerY, innerRadius, outerRadius,
                    (float) Math.cos(angle), (float) Math.sin(angle),
                    (float) Math.cos(next), (float) Math.sin(next), argb, argb);
            angle = next;
        }
    }

    /** 环形扇区网格（角向渐变：起始边色 → 终到边色） */
    private static void emitGradientAnnulus(final VertexConsumer vertices, final Matrix4f matrix,
                                            final int centerX, final int centerY,
                                            final double innerRadius, final double outerRadius,
                                            final double startDeg, final double endDeg,
                                            final int fromArgb, final int toArgb) {
        double span = endDeg - startDeg;
        int steps = Math.max(1, (int) Math.ceil(span / 4.0));
        double step = Math.toRadians(span / steps);
        double angle = Math.toRadians(startDeg);

        for (int i = 0; i < steps; i++) {
            double next = angle + step;
            float tA = i / (float) steps;
            float tB = (i + 1) / (float) steps;
            emitQuad(vertices, matrix, centerX, centerY, innerRadius, outerRadius,
                    (float) Math.cos(angle), (float) Math.sin(angle),
                    (float) Math.cos(next), (float) Math.sin(next),
                    FastColor.ARGB32.lerp(tA, fromArgb, toArgb),
                    FastColor.ARGB32.lerp(tB, fromArgb, toArgb));
            angle = next;
        }
    }

    /**
     * 一段环形四边形。顶点绕序必须与 RenderType.gui() 的背面剔除匹配：
     * 参考 GuiGraphics.fill —— u 轴(径向)与 v 轴(角度)下的 (+v,+u,...) 序；写反整圈都会被剔除。
     */
    private static void emitQuad(final VertexConsumer vertices, final Matrix4f matrix,
                                 final int centerX, final int centerY,
                                 final double innerRadius, final double outerRadius,
                                 final float cosA, final float sinA, final float cosB, final float sinB,
                                 final int colorA, final int colorB) {
        vertex(vertices, matrix, centerX + innerRadius * cosA, centerY + innerRadius * sinA, colorA);
        vertex(vertices, matrix, centerX + innerRadius * cosB, centerY + innerRadius * sinB, colorB);
        vertex(vertices, matrix, centerX + outerRadius * cosB, centerY + outerRadius * sinB, colorB);
        vertex(vertices, matrix, centerX + outerRadius * cosA, centerY + outerRadius * sinA, colorA);
    }

    private static void vertex(final VertexConsumer vertices, final Matrix4f matrix,
                               final double x, final double y, final int argb) {
        float alpha = ((argb >> 24) & 0xFF) / 255f;
        float red = ((argb >> 16) & 0xFF) / 255f;
        float green = ((argb >> 8) & 0xFF) / 255f;
        float blue = (argb & 0xFF) / 255f;
        vertices.addVertex(matrix, (float) x, (float) y, 0).setColor(red, green, blue, alpha);
    }

    // ------------------------------------------------------------ 标签排布

    /**
     * 横版悬浮标签：
     * 在扇区中心角方向、轮盘外缘之外取一个锚点，标签水平摆放——
     * 右半圈以锚点为左端向外展开，左半圈以锚点为右端向内收拢，使文字保持横向可读；
     * 摆放结果整体限制在安全放置区内：屏幕边距之内，且不进入顶部名称栏与底部页码行。
     */
    private static void drawFloatingLabel(final GuiGraphics graphics, final WheelLayout layout,
                                          final int centerX, final int centerY, final int sector, final String label,
                                          final int leftBound, final int rightBound, final int textColor,
                                          final float labelGrow) {
        Font font = Minecraft.getInstance().font;
        double angle = Math.toRadians(layout.centerAngleDeg(sector));
        double anchorDistance = layout.outerRadius() + 4;
        double anchorX = centerX + Math.cos(angle) * anchorDistance;
        double anchorY = centerY + Math.sin(angle) * anchorDistance;

        // 走廊内超宽时截断（走廊由屏幕两侧元素夹出，避免与属性面板/预览区重叠）
        int corridorWidth = Math.max(20, rightBound - leftBound);
        String text = label;
        if (font.width(text) > corridorWidth) {
            text = font.plainSubstrByWidth(text, corridorWidth - 6) + "…";
        }

        int textWidth = font.width(text);
        int textHeight = font.lineHeight;

        // 选中标签放大：摆放与夹取都按缩放后尺寸算，放大后不越走廊/安全带
        float scale = 1f + WheelAnim.LABEL_GROW * labelGrow;
        double scaledWidth = textWidth * scale;
        double scaledHeight = textHeight * scale;

        // 先求出标签矩形，再统一夹取，避免逐轴分支
        double left = anchorX >= centerX ? anchorX : anchorX - scaledWidth;
        double top = anchorY - scaledHeight / 2.0;

        left = Mth.clamp(left, (double) leftBound, rightBound - scaledWidth);
        top = Mth.clamp(top, centerY - layout.outerRadius() + 1.0, centerY + layout.outerRadius() + 8.0 - scaledHeight);

        // 以「靠轮盘的锚边 + 行垂直中心」为原点缩放 → 向外扩张，不向轮盘扑
        boolean rightHalf = anchorX >= centerX;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(rightHalf ? left : left + scaledWidth, top + scaledHeight / 2.0, 0);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, text, (int) (rightHalf ? 0 : -textWidth), (int) (-textHeight / 2.0), textColor, true);
        pose.popPose();
    }

    /**
     * 径向文字：
     * 依次尝试 1→2→3 行——拆行时最小化「最长行宽度」并优先在词间断行；
     * 行数增加后若字号达到「够读」阈值即停止，最终取所有可行候选中字号最大者；
     * 候选还需通过切向堆叠空间检查（行数×行高×字号 不超过扇区内端弦长）。
     * 右半圈（|中心角|<90°）从内向外读、正立；左半圈整体旋转 180°，从外向内读。
     */
    private static void drawSectorText(final GuiGraphics graphics, final WheelLayout layout,
                                       final int centerX, final int centerY,
                                       final int sector, final String label, final int textColor,
                                       final float geometryScale) {
        Font font = Minecraft.getInstance().font;
        double centerRad = Math.toRadians(layout.centerAngleDeg(sector));
        boolean flipped = Math.cos(centerRad) < 0;

        double innerRadius = layout.centerHoleRadius() + TEXT_PADDING;
        double outerRadius = layout.bandInnerRadius() - TEXT_PADDING;
        double available = outerRadius - innerRadius;
        if (available <= 0) {
            return;
        }

        // 排版决策用名义几何（开/关屏动画缩放半径时，行数与字号不跳变），绘制时再乘回动画缩放。
        // 注意：TEXT_PADDING 是固定值，不能跟着半径一起除以缩放，否则名义可用宽度会随动画漂移。
        float fitFactor = Math.max(geometryScale, 0.05f);
        double nominalInner = layout.centerHoleRadius() / fitFactor + TEXT_PADDING;
        double nominalOuter = layout.bandInnerRadius() / fitFactor - TEXT_PADDING;
        double nominalAvailable = nominalOuter - nominalInner;
        if (nominalAvailable <= 0) {
            return;
        }
        FittedText fitted = fitText(font, label, nominalAvailable, nominalInner, layout.sectorDegrees());
        List<String> lines = fitted.lines();
        float scale = fitted.scale() * fitFactor;
        double blockHeight = lines.size() * font.lineHeight * scale;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0);
        pose.mulPose(new Quaternionf().rotationZ(flipped ? (float) (centerRad + Math.PI) : (float) centerRad));

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            // 截断判定用名义几何（与 fitText 一致），避免开/关屏动画期间正常标签短暂省略
            if (font.width(line) * fitted.scale() > nominalAvailable + 1.0e-3) {
                String ellipsis = "…";
                int maxWidth = (int) Math.floor(nominalAvailable / fitted.scale()) - font.width(ellipsis);
                if (maxWidth > 0) {
                    line = font.plainSubstrByWidth(line, maxWidth) + ellipsis;
                }
            }

            double lineWidth = font.width(line) * scale;
            double halfGap = (available - lineWidth) / 2.0;

            // 未翻转：笔向朝外，起点 = 内缘 + 居中偏移；翻转：笔向朝内（坐标为负半径）
            double along = flipped ? -(outerRadius - halfGap) : innerRadius + halfGap;
            double across = -blockHeight / 2.0 + i * font.lineHeight * scale;

            pose.pushPose();
            pose.translate(along, across, 0);
            pose.scale(scale, scale, 1);
            graphics.drawString(font, line, 0, 0, textColor, true);
            pose.popPose();
        }

        pose.popPose();
    }

    private record FittedText(List<String> lines, float scale) { }

    /** 选出字号最大（同时满足空间约束）的行数方案 */
    private static FittedText fitText(final Font font, final String label, final double available,
                                      final double innerRadius, final double sectorDegrees) {
        FittedText best = null;
        // 含空白的短语优先尝试换行：不同字体（如 ModernUI 的 TTF）字宽不同，单行可能刚好达到
        // 舒适阈值而跳过换行，导致同一名字在不同字体下排版不一致。单行已达最大字号时仍不拆。
        boolean preferWrap = label.chars().anyMatch(Character::isWhitespace);

        for (int lineCount = 1; lineCount <= TEXT_MAX_LINES; lineCount++) {
            List<String> lines = splitBalanced(font, label, lineCount);
            if (lines.size() != lineCount) {
                break; // 文本太短，拆不出这么多行
            }

            int widest = 1;
            for (String line : lines) {
                widest = Math.max(widest, font.width(line));
            }

            float scale = (float) Mth.clamp(available / widest, TEXT_MIN_SCALE, TEXT_MAX_SCALE);

            // 切向堆叠空间检查（按内端弦长：扇区内缘最窄处，行堆叠不能越过相邻扇区）；
            // 扇区数=1（空轮盘）时角度为 360°，sin(180°)=0 会让弦长退化为 0 → 钳到 ≤180°
            double chordDegrees = Math.min(sectorDegrees, 180.0);
            double chord = 2 * innerRadius * Math.sin(Math.toRadians(chordDegrees) / 2);
            if (lineCount * font.lineHeight * scale > chord) {
                continue;
            }

            if (best == null || scale > best.scale()) {
                best = new FittedText(lines, scale);
            }

            if (scale >= TEXT_MAX_SCALE) {
                break; // 已到最大字号，不需要再拆
            }
            // 含空白的短语至少尝试一次换行，避免不同字体字宽差异导致排版不一致
            if ((!preferWrap || lineCount > 1) && scale >= TEXT_COMFORT_SCALE) {
                break; // 已经够读了
            }
        }

        return best != null ? best : new FittedText(List.of(label), TEXT_MIN_SCALE);
    }

    /**
     * 把文本拆成 lineCount 行：最小化「最长行宽度 + 断词惩罚」（DP，文本很短，代价可忽略）。
     * 无法合理拆分时返回单行。
     */
    private static List<String> splitBalanced(final Font font, final String text, final int lineCount) {
        int length = text.length();
        if (lineCount <= 1 || length < lineCount) {
            return List.of(text);
        }

        double[][] best = new double[lineCount + 1][length + 1];
        int[][] cut = new int[lineCount + 1][length + 1];
        for (double[] row : best) {
            java.util.Arrays.fill(row, Double.MAX_VALUE);
        }
        best[0][0] = 0;

        for (int k = 1; k <= lineCount; k++) {
            for (int end = k; end <= length; end++) {
                for (int start = k - 1; start < end; start++) {
                    if (best[k - 1][start] == Double.MAX_VALUE || !canBreakAt(text, start)) {
                        continue;
                    }

                    double cost = Math.max(best[k - 1][start],
                            font.width(text.substring(start, end)) + breakPenalty(text, start));
                    if (cost < best[k][end]) {
                        best[k][end] = cost;
                        cut[k][end] = start;
                    }
                }
            }
        }

        if (best[lineCount][length] == Double.MAX_VALUE) {
            return List.of(text);
        }

        List<String> lines = new ArrayList<>(lineCount);
        int end = length;
        for (int k = lineCount; k >= 1; k--) {
            int start = cut[k][end];
            String line = text.substring(start, end).trim();
            if (line.isEmpty()) {
                return List.of(text); // 拆分结果退化（例如空格过多），放弃拆行
            }
            lines.add(line);
            end = start;
        }
        Collections.reverse(lines);
        return lines;
    }

    /** 只在词间（空格）或 CJK 字符间断行；拉丁单词内部禁止切开。 */
    private static boolean canBreakAt(final String text, final int index) {
        if (index <= 0 || index >= text.length()) {
            return true;
        }
        char before = text.charAt(index - 1);
        char after = text.charAt(index);
        if (before == ' ' || after == ' ') {
            return true;
        }
        return isCjk(before) || isCjk(after);
    }

    private static boolean isCjk(final char c) {
        return switch (Character.UnicodeScript.of(c)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        };
    }

    /** 空格断行无惩罚；CJK 字符间断行有小惩罚，让词间断行优先 */
    private static double breakPenalty(final String text, final int cutIndex) {
        if (cutIndex <= 0 || cutIndex >= text.length()) {
            return 0;
        }
        char before = text.charAt(cutIndex - 1);
        char after = text.charAt(cutIndex);
        return (before == ' ' || after == ' ') ? 0 : TEXT_BREAK_PENALTY;
    }
}
