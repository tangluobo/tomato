package test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PNG图标生成工具（带折痕和可调折角）
 */
public class PngIconGenerator {

    private static final int ICON_SIZE = 512;

    /**
     * 生成图标
     *
     * @param paperColor       纸张颜色
     * @param fileType         文件类型 "media" 或 "other"
     * @param fileTypeColor    符号颜色
     * @param foldColor        折角填充颜色
     * @param bottomRectColor  底部矩形颜色
     * @param labelText        底部文字（白色）
     * @param foldSize         折角边长（像素，建议 30~60）
     * @param outputPath       输出路径
     */
    public static void generateIcon(String paperColor, String fileType,
                                    String fileTypeColor, String foldColor,
                                    String bottomRectColor, String labelText,
                                    int foldSize, String outputPath) throws IOException {
        Color paper = Color.decode(paperColor);
        Color fTypeColor = Color.decode(fileTypeColor);
        Color fold = Color.decode(foldColor);
        Color bottom = Color.decode(bottomRectColor);

        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int W = ICON_SIZE, H = ICON_SIZE;

        int paperWidth=440;
        // 1. 纸张背景
        g2.setColor(paper);
        int x1 = (ICON_SIZE - paperWidth) / 2;
        g2.fillRect(x1, 0, paperWidth, H);

        // 2. 右上折角（三角形填充 + 折痕线）
        // 小正方形区域：x: W-foldSize 到 W, y: 0 到 foldSize
        // 对角线从 (W, 0) 到 (W-foldSize, foldSize)
        Path2D lowerTriangle = new Path2D.Double(); // 左下三角形（折角色）
        lowerTriangle.moveTo(W - foldSize-x1, 0);
        lowerTriangle.lineTo(W - foldSize-x1, foldSize);
        lowerTriangle.lineTo(W -x1, foldSize);
        lowerTriangle.closePath();
        g2.setColor(fold);
        g2.fill(lowerTriangle);


        // 右上三角形（透明清除）
        Path2D upperTriangle = new Path2D.Double();
        upperTriangle.moveTo(W - foldSize-x1, 0);                 // 右上顶点 (W-x1, 0)
        upperTriangle.lineTo(W  - x1, foldSize);          // 右下顶点 (W-x1, foldSize)
        upperTriangle.lineTo(W  - x1, 0); // 左下顶点 (W-foldSize-x1, foldSize)
        upperTriangle.lineTo(W - foldSize-x1, 0); // 左下顶点 (W-foldSize-x1, foldSize)
        upperTriangle.closePath();

        g2.setComposite(AlphaComposite.Clear);
        g2.fill(upperTriangle);
        g2.setComposite(AlphaComposite.SrcOver); // 恢复默认合成模式

        // 折痕线（对角线）
        g2.setColor(new Color(0, 0, 0, 80)); // 半透明黑
        g2.setStroke(new BasicStroke(2));
        g2.drawLine(W - foldSize-x1, 0, W-x1, foldSize);

        // 3. 底部矩形
        int rectY = 312;
        int rectHeight = 200;
        g2.setColor(bottom);
        g2.fillRect(0, rectY, W, rectHeight);

        // 4. 底部文字（白色）
        if (labelText != null && !labelText.isEmpty()) {
            g2.setColor(Color.WHITE);
            int fontSize = (int) (rectHeight * 0.6);
            Font font = new Font("SansSerif", Font.BOLD, fontSize);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(labelText);
            int x = (W - textWidth) / 2;
            int y = rectY + (rectHeight - fm.getAscent() - fm.getDescent()) / 2 + fm.getAscent();
            g2.drawString(labelText, x, y);
        }

        // 5. 文件类型符号（居中偏上）
        int cx = W / 2, cy = 180;
        int symbolSize = 130;
        g2.setColor(fTypeColor);
        g2.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if ("media".equalsIgnoreCase(fileType)) {
            Path2D play = new Path2D.Double();
            int half = symbolSize / 2;
            play.moveTo(cx - half+x1/2, cy - half);
            play.lineTo(cx - half+x1/2, cy + half);
            play.lineTo(cx + half-x1/2, cy);
            play.closePath();
            g2.fill(play);
        } else {
            int barWidth = 300;
            int barHeight = 20;
            int y1 = 160;
            int y2 = 230;
            g2.fillRect(cx - barWidth / 2, y1, barWidth, barHeight);
            g2.fillRect(cx - barWidth / 2, y2, barWidth, barHeight);
        }

        g2.dispose();
        ImageIO.write(image, "png", new File(outputPath));
        System.out.println("图标已生成: " + outputPath);
    }

    /**
     * 命令行参数：
     * 纸张颜色 文件类型(media/other) 符号颜色 折角颜色 底部颜色 文字 折角大小(像素) 输出路径
     */
    public static void main(String[] args) {
//        if (args.length < 8) {
//            System.err.println("用法: java PngIconGenerator <纸张颜色> <文件类型> <符号颜色> " +
//                    "<折角颜色> <底部颜色> <文字> <折角大小(像素)> <输出路径>");
//            System.err.println("示例: java PngIconGenerator #F5F5F5 media #FF0000 #CCCCCC #888888 HTML 50 icon.png");
//            System.exit(1);
//        }
        try {
//            int foldSize = Integer.parseInt(args[6]);
            String jsonPath="C:\\Users\\zoleet\\Desktop\\新建文件夹 (2)\\icon_params_extra.json";
            JSONArray objects = JSON.parseArray(Files.readString(Path.of(jsonPath)));
            for (Object object : objects) {
                JSONObject jsonObject = (JSONObject) object;
                String paperColor = jsonObject.getString("paperColor");
                String fileTypeColor = jsonObject.getString("fileTypeColor");
                String bottomRectColor = jsonObject.getString("bottomRectColor");
                String foldColor = jsonObject.getString("foldColor");
                String label = jsonObject.getString("label");
                generateIcon(paperColor,"other",fileTypeColor, foldColor, bottomRectColor, label,100,"C:\\Users\\zoleet\\Desktop\\新建文件夹 (2)\\"+ label +".png");

            }
//            generateIcon("#B3E5FC","other","#059BE5","#82D4F9","#82D4F9", "HTML" ,100,"d:\\png\\1.png");
//            generateIcon("#B3E5FC","media","#059BE5","#82D4F9","#82D4F9", "MP4" ,100,"d:\\png\\2.png");
        } catch (Exception e) {
            System.err.println("生成失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}