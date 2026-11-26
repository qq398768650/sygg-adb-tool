import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 生成「设备更新助手」图标：
 *   完整图标  -> res/mipmap-*dpi/ic_launcher.png
 *   前景层    -> res/mipmap-*dpi/ic_launcher_fg.png（自适应图标用，108dp 画布）
 * 图形：中式飞檐 + 向下箭头 + 托盘，深紫底白字，小尺寸也能认出来。
 */
public class MakeIcon {

    static final int S = 1024;
    /** 自适应图标前景内容占画布的比例（108dp 画布，安全区 72dp ≈ 66%） */
    static final float FG_SCALE = 0.66f;

    public static void main(String[] args) throws Exception {
        File res = new File(args.length > 0 ? args[0] : "res");

        BufferedImage full = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g0 = full.createGraphics();
        drawBackground(g0);
        drawGlyph(g0, 1.0f);
        g0.dispose();

        BufferedImage fg = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g1 = fg.createGraphics();
        drawGlyph(g1, FG_SCALE);
        g1.dispose();

        // 自适应图标的背景层：同样的渐变，但不做圆角（系统自己裁形状）
        BufferedImage bg = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bg.createGraphics();
        drawBackground(g2, false);
        g2.dispose();

        String[] names = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] legacy = {48, 72, 96, 144, 192};
        int[] fgSize = {108, 162, 216, 324, 432};
        for (int i = 0; i < names.length; i++) {
            File d = new File(res, "mipmap-" + names[i]);
            d.mkdirs();
            write(full, legacy[i], new File(d, "ic_launcher.png"));
            write(fg, fgSize[i], new File(d, "ic_launcher_fg.png"));
            write(bg, fgSize[i], new File(d, "ic_launcher_bg.png"));
        }
        System.out.println("ok icon");
    }

    // ---------- 背景：深紫渐变 + 圆角 ----------

    static void drawBackground(Graphics2D g) {
        drawBackground(g, true);
    }

    static void drawBackground(Graphics2D g, boolean rounded) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape shape = rounded
                ? new RoundRectangle2D.Float(0, 0, S, S, S * 0.22f, S * 0.22f)
                : new Rectangle2D.Float(0, 0, S, S);

        // 斜向渐变：左上亮紫，右下墨蓝
        LinearGradientPaint lg = new LinearGradientPaint(
                0, 0, S, S,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0x7C3AED), new Color(0x4C1D95), new Color(0x1E1B4B)});
        g.setPaint(lg);
        g.fill(shape);

        // 中心柔光，让图形区更亮一点
        RadialGradientPaint rg = new RadialGradientPaint(
                new Point2D.Float(S * 0.5f, S * 0.45f), S * 0.55f,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 46), new Color(255, 255, 255, 0)});
        g.setPaint(rg);
        g.fill(shape);
    }

    // ---------- 图形：飞檐 + 箭头 + 托盘 ----------

    static void drawGlyph(Graphics2D g, float scale) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 缩放到中心（尺寸按 scale）
        AffineTransform old = g.getTransform();
        if (scale != 1f) {
            double off = S * (1 - scale) / 2.0;
            g.translate(off, off);
            g.scale(scale, scale);
        }

        g.setColor(Color.WHITE);

        // 1) 中式飞檐：两端上翘的屋脊曲线 + 檐角竖钩
        Path2D roof = new Path2D.Float();
        roof.moveTo(206, 382);
        roof.quadTo(300, 294, 512, 258);
        roof.quadTo(724, 294, 818, 382);
        stroke(g, roof, 50f);

        Path2D ears = new Path2D.Float();
        ears.moveTo(206, 382);
        ears.lineTo(206, 336);
        ears.moveTo(818, 382);
        ears.lineTo(818, 336);
        stroke(g, ears, 44f);

        // 屋脊上的一点小装饰（短竖线），强化「建筑」识别
        Path2D ridge = new Path2D.Float();
        ridge.moveTo(512, 264);
        ridge.lineTo(512, 212);
        stroke(g, ridge, 38f);

        // 2) 向下箭头：竖杆 + 箭头尖
        Path2D shaft = new Path2D.Float();
        shaft.moveTo(512, 408);
        shaft.lineTo(512, 570);
        stroke(g, shaft, 56f);

        Path2D head = new Path2D.Float();
        head.moveTo(396, 548);
        head.lineTo(628, 548);
        head.lineTo(512, 672);
        head.closePath();
        g.fill(head);

        // 3) 托盘：U 形（左右两边 + 底）
        Path2D tray = new Path2D.Float();
        tray.moveTo(322, 618);
        tray.lineTo(322, 748);
        tray.quadTo(322, 788, 362, 788);
        tray.lineTo(662, 788);
        tray.quadTo(702, 788, 702, 748);
        tray.lineTo(702, 618);
        stroke(g, tray, 52f);

        g.setTransform(old);
    }

    static void stroke(Graphics2D g, Shape s, float width) {
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.setStroke(new BasicStroke(1));
    }

    static void write(BufferedImage src, int size, File out) throws Exception {
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        ImageIO.write(dst, "png", out);
    }
}
