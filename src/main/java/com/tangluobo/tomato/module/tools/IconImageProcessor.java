package com.tangluobo.tomato.module.tools;

import java.awt.image.BufferedImage;
/**
 * 图标背景透明化的纯图像处理逻辑。
 *
 * <p>从图片四周识别占比最高的纯色背景，因此白色、黑色及彩色背景都可处理。JPG 的
 * 纯色背景通常仍包含压缩噪点；这里先稳定并收紧 alpha 遮罩，再只在边界附近做高斯
 * 羽化，最后把半透明像素中混入的背景色替换为邻近前景色。</p>
 */
final class IconImageProcessor {

    private static final int COLOR_TRANSITION_WIDTH = 36;
    private static final int BACKGROUND_HISTOGRAM_SIZE = 16 * 16 * 16;
    private static final double BACKGROUND_CLUSTER_RADIUS = 24.0;
    private static final int ALPHA_NOISE_FLOOR = 24;
    private static final int ALPHA_SOLID_CEILING = 232;
    // 去底色只采信几乎完全不透明的像素；较低阈值仍可能把白底混色当作前景色。
    private static final int COLOR_SEED_ALPHA = 250;

    private IconImageProcessor() {
    }

    static BufferedImage process(BufferedImage src, int backgroundTolerance, boolean crop,
                                 boolean smooth, int featherRadius) {
        if (src == null) {
            throw new IllegalArgumentException("源图片不能为空");
        }

        int w = src.getWidth();
        int h = src.getHeight();
        int size = w * h;

        // JPG 没有 alpha；透明 PNG 先合成到白底，随后与普通图片一样自动识别四周背景。
        BufferedImage normalized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = normalized.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, w, h);
        graphics.drawImage(src, 0, 0, null);
        graphics.dispose();

        int[] sourceArgb = new int[size];
        normalized.getRGB(0, 0, w, h, sourceArgb, 0, w);
        int[] background = detectDominantBorderColor(sourceArgb, w, h);
        int[] rawAlpha = createColorDistanceMatte(sourceArgb, background, backgroundTolerance);
        int[] outputAlpha = smooth
                ? refineAlphaMatte(rawAlpha, w, h, Math.max(1, featherRadius))
                : rawAlpha;

        int[] outputArgb = smooth
                ? decontaminateEdgeColors(sourceArgb, rawAlpha, outputAlpha, w, h,
                        Math.max(1, featherRadius), background)
                : combineArgb(sourceArgb, outputAlpha);

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, outputArgb, 0, w);

        if (crop) {
            BufferedImage cropped = cropTransparentBorder(result);
            if (cropped != null) {
                return cropped;
            }
        }
        return result;
    }

    /**
     * 按像素与背景色的 RGB 均方根距离创建遮罩。容差以内视为背景，之后留出固定的
     * 过渡带保存原图的抗锯齿覆盖信息。
     */
    private static int[] createColorDistanceMatte(int[] sourceArgb, int[] background,
                                                   int backgroundTolerance) {
        int[] alpha = new int[sourceArgb.length];
        int tolerance = clamp(backgroundTolerance, 0, 160);
        for (int i = 0; i < sourceArgb.length; i++) {
            int p = sourceArgb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            double distance = colorDistance(r, g, b, background[0], background[1], background[2]);
            if (distance <= tolerance) {
                alpha[i] = 0;
            } else if (distance >= tolerance + COLOR_TRANSITION_WIDTH) {
                alpha[i] = 255;
            } else {
                double coverage = (distance - tolerance) / COLOR_TRANSITION_WIDTH;
                alpha[i] = clamp((int) Math.round(coverage * 255.0), 0, 255);
            }
        }
        return alpha;
    }

    /**
     * 对四周像素做 4-bit RGB 直方图，选择数量最多的色簇，再在该色簇附近求均值。
     * 与直接取四角或 RGB 中位数相比，主体碰到某一条边时也不容易误判背景。
     */
    private static int[] detectDominantBorderColor(int[] sourceArgb, int w, int h) {
        int[] histogram = new int[BACKGROUND_HISTOGRAM_SIZE];
        int borderWidth = Math.max(1, Math.min(4, Math.min(w, h) / 100));

        forEachBorderPixel(w, h, borderWidth, idx -> {
            int p = sourceArgb[idx];
            int bin = (((p >> 16) & 0xFF) >> 4) << 8
                    | (((p >> 8) & 0xFF) >> 4) << 4
                    | (p & 0xFF) >> 4;
            histogram[bin]++;
        });

        int dominantBin = 0;
        for (int i = 1; i < histogram.length; i++) {
            if (histogram[i] > histogram[dominantBin]) dominantBin = i;
        }
        int centerR = ((dominantBin >> 8) & 0x0F) * 16 + 8;
        int centerG = ((dominantBin >> 4) & 0x0F) * 16 + 8;
        int centerB = (dominantBin & 0x0F) * 16 + 8;

        long[] sums = new long[4];
        forEachBorderPixel(w, h, borderWidth, idx -> {
            int p = sourceArgb[idx];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            if (colorDistance(r, g, b, centerR, centerG, centerB) <= BACKGROUND_CLUSTER_RADIUS) {
                sums[0] += r;
                sums[1] += g;
                sums[2] += b;
                sums[3]++;
            }
        });

        if (sums[3] == 0) return new int[]{centerR, centerG, centerB};
        return new int[]{
                (int) (sums[0] / sums[3]),
                (int) (sums[1] / sums[3]),
                (int) (sums[2] / sums[3])
        };
    }

    private static void forEachBorderPixel(int w, int h, int borderWidth,
                                           java.util.function.IntConsumer consumer) {
        int topEnd = Math.min(borderWidth, h);
        int bottomStart = Math.max(topEnd, h - borderWidth);
        int leftEnd = Math.min(borderWidth, w);
        int rightStart = Math.max(leftEnd, w - borderWidth);

        for (int y = 0; y < topEnd; y++) {
            for (int x = 0; x < w; x++) {
                consumer.accept(y * w + x);
            }
        }
        for (int y = bottomStart; y < h; y++) {
            for (int x = 0; x < w; x++) {
                consumer.accept(y * w + x);
            }
        }
        for (int y = topEnd; y < bottomStart; y++) {
            int row = y * w;
            for (int x = 0; x < leftEnd; x++) {
                consumer.accept(row + x);
            }
            for (int x = rightStart; x < w; x++) {
                consumer.accept(row + x);
            }
        }
    }

    /**
     * 先去掉 JPG 背景中的弱小 alpha 噪点，再用高斯核羽化边缘并恢复遮罩对比度。
     * 和直接盒式模糊相比，前景内部不会整体发虚，孤立噪点也不会向外扩散。
     */
    private static int[] refineAlphaMatte(int[] rawAlpha, int w, int h, int radius) {
        int[] stabilized = new int[rawAlpha.length];
        for (int i = 0; i < rawAlpha.length; i++) {
            stabilized[i] = remapMatteContrast(rawAlpha[i]);
        }

        // 3x3 邻域清除孤立/弱小噪点，同时填掉被 JPG 压缩制造的小针孔。
        int[] cleaned = stabilized.clone();
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - 1);
            int y1 = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int visibleCount = 0;
                int strongCount = 0;
                int sum = 0;
                int count = 0;
                for (int yy = y0; yy <= y1; yy++) {
                    int row = yy * w;
                    int x0 = Math.max(0, x - 1);
                    int x1 = Math.min(w - 1, x + 1);
                    for (int xx = x0; xx <= x1; xx++) {
                        int value = stabilized[row + xx];
                        if (value > ALPHA_NOISE_FLOOR) visibleCount++;
                        if (value >= 192) strongCount++;
                        sum += value;
                        count++;
                    }
                }

                int center = stabilized[idx];
                if (visibleCount <= 1 || (center <= 96 && visibleCount <= 2)) {
                    cleaned[idx] = 0;
                } else if (center < 192 && strongCount >= Math.max(5, count - 2)) {
                    cleaned[idx] = Math.max(center, sum / count);
                }
            }
        }

        int[] blurred = gaussianBlurAlpha(cleaned, w, h, radius);
        int[] result = new int[blurred.length];
        for (int i = 0; i < blurred.length; i++) {
            int value = smoothStepByte(blurred[i]);
            // 截掉肉眼不可见却会扩大自动裁切范围的 alpha 尾巴。
            result[i] = value <= 3 ? 0 : (value >= 252 ? 255 : value);
        }
        return result;
    }

    private static int remapMatteContrast(int alpha) {
        if (alpha <= ALPHA_NOISE_FLOOR) return 0;
        if (alpha >= ALPHA_SOLID_CEILING) return 255;
        double t = (double) (alpha - ALPHA_NOISE_FLOOR)
                / (ALPHA_SOLID_CEILING - ALPHA_NOISE_FLOOR);
        return clamp((int) Math.round(smoothStep(t) * 255.0), 0, 255);
    }

    private static int[] gaussianBlurAlpha(int[] alpha, int w, int h, int radius) {
        double sigma = Math.max(0.8, radius * 0.65);
        double[] kernel = new double[radius * 2 + 1];
        double kernelSum = 0;
        for (int i = -radius; i <= radius; i++) {
            double weight = Math.exp(-(i * i) / (2.0 * sigma * sigma));
            kernel[i + radius] = weight;
            kernelSum += weight;
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= kernelSum;
        }

        double[] horizontal = new double[alpha.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                double sum = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = clamp(x + k, 0, w - 1);
                    sum += alpha[row + xx] * kernel[k + radius];
                }
                horizontal[row + x] = sum;
            }
        }

        int[] result = new int[alpha.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = clamp(y + k, 0, h - 1);
                    sum += horizontal[yy * w + x] * kernel[k + radius];
                }
                result[y * w + x] = clamp((int) Math.round(sum), 0, 255);
            }
        }
        return result;
    }

    /**
     * 用邻近的可靠前景色给半透明边缘“续色”。这样 alpha 混合到深色背景时不会出现
     * JPG 原白底残留造成的白边/灰边。只有羽化带会进入邻域搜索，处理大图时仍保持线性级别。
     */
    private static int[] decontaminateEdgeColors(int[] sourceArgb, int[] rawAlpha, int[] outputAlpha,
                                                  int w, int h, int featherRadius, int[] background) {
        int searchRadius = Math.min(13, Math.max(3, featherRadius * 2 + 3));
        boolean[] colorSeedMask = createColorSeedMask(rawAlpha, w, h, Math.min(2, featherRadius));
        int[] output = new int[sourceArgb.length];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int alpha = outputAlpha[idx];
                if (alpha == 0) {
                    output[idx] = 0x00FFFFFF;
                    continue;
                }

                int source = sourceArgb[idx];
                int sourceR = (source >> 16) & 0xFF;
                int sourceG = (source >> 8) & 0xFF;
                int sourceB = source & 0xFF;
                if (alpha == 255 && colorSeedMask[idx]) {
                    output[idx] = 0xFF000000 | (sourceR << 16) | (sourceG << 8) | sourceB;
                    continue;
                }

                double sumWeight = 0;
                double sumR = 0;
                double sumG = 0;
                double sumB = 0;
                int x0 = Math.max(0, x - searchRadius);
                int x1 = Math.min(w - 1, x + searchRadius);
                int y0 = Math.max(0, y - searchRadius);
                int y1 = Math.min(h - 1, y + searchRadius);
                for (int yy = y0; yy <= y1; yy++) {
                    int dy = yy - y;
                    int row = yy * w;
                    for (int xx = x0; xx <= x1; xx++) {
                        int candidateIdx = row + xx;
                        int candidateAlpha = rawAlpha[candidateIdx];
                        if (!colorSeedMask[candidateIdx]) continue;
                        int dx = xx - x;
                        int distanceSquared = dx * dx + dy * dy;
                        if (distanceSquared > searchRadius * searchRadius) continue;

                        double weight = (candidateAlpha / 255.0) / (1.0 + distanceSquared);
                        int candidate = sourceArgb[candidateIdx];
                        sumR += ((candidate >> 16) & 0xFF) * weight;
                        sumG += ((candidate >> 8) & 0xFF) * weight;
                        sumB += (candidate & 0xFF) * weight;
                        sumWeight += weight;
                    }
                }

                int edgeR;
                int edgeG;
                int edgeB;
                boolean hasForegroundSeed = sumWeight > 0;
                if (hasForegroundSeed) {
                    edgeR = clamp((int) Math.round(sumR / sumWeight), 0, 255);
                    edgeG = clamp((int) Math.round(sumG / sumWeight), 0, 255);
                    edgeB = clamp((int) Math.round(sumB / sumWeight), 0, 255);
                } else {
                    // 极细图形可能没有足够不透明的颜色种子，此时按估计背景反解前景色。
                    int matteAlpha = Math.max(32, Math.max(rawAlpha[idx], alpha));
                    edgeR = unmixBackground(sourceR, background[0], matteAlpha);
                    edgeG = unmixBackground(sourceG, background[1], matteAlpha);
                    edgeB = unmixBackground(sourceB, background[2], matteAlpha);
                }

                int refinedAlpha = alpha;
                if (hasForegroundSeed) {
                    // 用“背景 -> 邻近实心前景”的颜色投影估算真实覆盖率。颜色距离只负责定位
                    // 边界，覆盖率由颜色混合关系决定，深色图标就不会带着一圈偏白的不透明边。
                    int coverageAlpha = estimateCoverageAlpha(sourceR, sourceG, sourceB,
                            edgeR, edgeG, edgeB, background[0], background[1], background[2]);
                    refinedAlpha = clamp((int) Math.round(coverageAlpha * 0.75 + alpha * 0.25), 0, 255);
                    if (refinedAlpha <= 3) refinedAlpha = 0;
                    if (refinedAlpha >= 252) refinedAlpha = 255;
                }

                // Straight-alpha PNG 的边缘 RGB 应是前景本色，透明度单独表达覆盖率；直接使用
                // 去底色后的邻近前景色，避免切换到深色背景时出现白色轮廓。
                double fallbackMix = smoothStep(clamp01((COLOR_SEED_ALPHA - rawAlpha[idx]) / 128.0));
                int r = hasForegroundSeed ? edgeR : mix(sourceR, edgeR, fallbackMix);
                int g = hasForegroundSeed ? edgeG : mix(sourceG, edgeG, fallbackMix);
                int b = hasForegroundSeed ? edgeB : mix(sourceB, edgeB, fallbackMix);
                output[idx] = (refinedAlpha << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return output;
    }

    /**
     * 将颜色种子向前景内部收缩，避免把 JPG 边界上已经混入背景色的像素当成前景本色。
     */
    private static boolean[] createColorSeedMask(int[] rawAlpha, int w, int h, int radius) {
        boolean[] seeds = new boolean[rawAlpha.length];
        int erosionRadius = Math.max(1, radius);
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - erosionRadius);
            int y1 = Math.min(h - 1, y + erosionRadius);
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (rawAlpha[idx] < COLOR_SEED_ALPHA) continue;
                int x0 = Math.max(0, x - erosionRadius);
                int x1 = Math.min(w - 1, x + erosionRadius);
                boolean reliable = true;
                for (int yy = y0; yy <= y1 && reliable; yy++) {
                    int row = yy * w;
                    for (int xx = x0; xx <= x1; xx++) {
                        if (rawAlpha[row + xx] < COLOR_SEED_ALPHA) {
                            reliable = false;
                            break;
                        }
                    }
                }
                seeds[idx] = reliable;
            }
        }
        return seeds;
    }

    private static int[] combineArgb(int[] sourceArgb, int[] alpha) {
        int[] output = new int[sourceArgb.length];
        for (int i = 0; i < sourceArgb.length; i++) {
            if (alpha[i] == 0) {
                output[i] = 0x00FFFFFF;
            } else {
                output[i] = (alpha[i] << 24) | (sourceArgb[i] & 0x00FFFFFF);
            }
        }
        return output;
    }

    private static BufferedImage cropTransparentBorder(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        final int alphaThreshold = 8;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha > alphaThreshold) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;

        int newW = maxX - minX + 1;
        int newH = maxY - minY + 1;
        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[newW * newH];
        img.getRGB(minX, minY, newW, newH, pixels, 0, newW);
        result.setRGB(0, 0, newW, newH, pixels, 0, newW);
        return result;
    }

    private static int unmixBackground(int observed, int background, int alpha) {
        double a = clamp(alpha, 1, 255) / 255.0;
        return clamp((int) Math.round((observed - background * (1.0 - a)) / a), 0, 255);
    }

    private static double colorDistance(int r1, int g1, int b1, int r2, int g2, int b2) {
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        return Math.sqrt((dr * dr + dg * dg + db * db) / 3.0);
    }

    private static int estimateCoverageAlpha(int observedR, int observedG, int observedB,
                                             int foregroundR, int foregroundG, int foregroundB,
                                             int backgroundR, int backgroundG, int backgroundB) {
        double vectorR = foregroundR - backgroundR;
        double vectorG = foregroundG - backgroundG;
        double vectorB = foregroundB - backgroundB;
        double denominator = vectorR * vectorR + vectorG * vectorG + vectorB * vectorB;
        if (denominator < 16.0) return 255;

        double projection = ((observedR - backgroundR) * vectorR
                + (observedG - backgroundG) * vectorG
                + (observedB - backgroundB) * vectorB) / denominator;
        return clamp((int) Math.round(clamp01(projection) * 255.0), 0, 255);
    }

    private static int mix(int from, int to, double amount) {
        return clamp((int) Math.round(from + (to - from) * amount), 0, 255);
    }

    private static int smoothStepByte(int value) {
        return clamp((int) Math.round(smoothStep(value / 255.0) * 255.0), 0, 255);
    }

    private static double smoothStep(double t) {
        double value = clamp01(t);
        return value * value * (3.0 - 2.0 * value);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
