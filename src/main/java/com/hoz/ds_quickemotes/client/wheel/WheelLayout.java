package com.hoz.ds_quickemotes.client.wheel;

import net.minecraft.util.Mth;

/**
 * 轮盘几何（每页自适应扇区数）：
 * 0 号扇区（取消所有表情）固定存在，中心线朝正右（3 点钟方向）；
 * 其余表情扇区顺时针均分剩下的角度——满页为 pageSize+1 个扇区（默认 10 扇区 × 36°），
 * 不满页（如整个列表不足 9 个表情）则按实际数量均分，不留空扇区。
 * 扇区数不为 10 时不再保证分割线竖直朝上（按需求放弃该约束）。
 */
public final class WheelLayout {
    public static final int CANCEL_SECTOR = 0;
    public static final int MAX_SECTORS = 10;

    private static final double CENTER_HOLE_RATIO = 34.0 / 110.0;
    /** 外环指示灯带宽度（相对半径） */
    private static final double BAND_WIDTH_RATIO = 2.3 / 110.0;
    /** 悬浮排布下的窄圆环：内缘不变、外缘向内收（整体视觉变小）；环宽 = 半径 × 该比例 */
    private static final double FLOATING_RING_WIDTH_RATIO = 0.30;

    private final double radius;
    private final int sectorCount;
    private final boolean narrowRing;

    public WheelLayout(final double radius, final int sectorCount) {
        this(radius, sectorCount, false);
    }

    public WheelLayout(final double radius, final int sectorCount, final boolean narrowRing) {
        this.radius = radius;
        this.sectorCount = Mth.clamp(sectorCount, 1, MAX_SECTORS);
        this.narrowRing = narrowRing;
    }

    public double radius() {
        return radius;
    }

    public int sectorCount() {
        return sectorCount;
    }

    public double centerHoleRadius() {
        return radius * CENTER_HOLE_RATIO;
    }

    /** 绘制与判定共用的外缘半径：悬浮模式外缘内收为窄环（整体视觉变小），径向模式即名义半径 */
    public double outerRadius() {
        return narrowRing ? centerHoleRadius() + radius * FLOATING_RING_WIDTH_RATIO : radius;
    }

    public double bandWidth() {
        return radius * BAND_WIDTH_RATIO;
    }

    /** 外环指示灯带的内缘半径（指示灯位于 内缘→外缘 的薄环里） */
    public double bandInnerRadius() {
        return outerRadius() - bandWidth();
    }

    public double sectorDegrees() {
        return 360.0 / sectorCount;
    }

    /** 扇区中心线角度（度，屏幕坐标：0°=正右，顺时针增大） */
    public double centerAngleDeg(final int sector) {
        return sector * sectorDegrees();
    }

    public double startAngleDeg(final int sector) {
        return (sector - 0.5) * sectorDegrees();
    }

    public double endAngleDeg(final int sector) {
        return (sector + 0.5) * sectorDegrees();
    }

    /**
     * 鼠标相对轮盘中心的偏移落在哪个扇区。
     *
     * @return 0..sectorCount-1；中心空区或圆外返回 -1
     */
    public int sectorAt(final double dx, final double dy) {
        double distSq = dx * dx + dy * dy;
        double hole = centerHoleRadius();
        double outer = outerRadius();
        if (distSq < hole * hole || distSq > outer * outer) {
            return -1;
        }
        double degrees = Math.toDegrees(Math.atan2(dy, dx));
        return Math.floorMod((int) Math.round(degrees / sectorDegrees()), sectorCount);
    }
}
