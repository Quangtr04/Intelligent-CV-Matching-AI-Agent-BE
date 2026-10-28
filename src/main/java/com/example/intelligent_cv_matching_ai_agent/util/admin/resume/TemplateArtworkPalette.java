package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A bounded adaptive PNG palette preserves flat design colors and avoids a fixed web palette on portraits. */
final class TemplateArtworkPalette {

    private TemplateArtworkPalette() {
    }

    static BufferedImage indexed(
        BufferedImage source) {
        int sampleStep = Math.max(1, (int) Math.ceil(Math.sqrt(source.getWidth() * source.getHeight() / 65536.0)));
        Map<Integer, Integer> frequency = new HashMap<>();
        for (int y = 0; y < source.getHeight(); y += sampleStep) {
            for (int x = 0; x < source.getWidth(); x += sampleStep) {
                frequency.merge(source.getRGB(x, y), 1, Integer::sum);
            }
        }
        int[] palette = new int[256];
        Map<Integer, Integer> exact = new HashMap<>();
        var dominant = frequency.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(40).toList();
        int next = 216;
        for (var color : dominant) {
            exact.put(color.getKey(), next);
            palette[next++] = color.getKey();
        }
        Map<Integer, long[]> histogram = new HashMap<>();
        for (var entry : frequency.entrySet()) {
            if (exact.containsKey(entry.getKey())) {
                continue;
            }
            int rgb = entry.getKey();
            int count = entry.getValue();
            long[] bin = histogram.computeIfAbsent(key(rgb), ignored -> new long[4]);
            bin[0] += count;
            bin[1] += (long) (rgb >> 16 & 255) * count;
            bin[2] += (long) (rgb >> 8 & 255) * count;
            bin[3] += (long) (rgb & 255) * count;
        }
        List<ColorBin> bins = new ArrayList<>();
        for (long[] bin : histogram.values()) {
            bins.add(
                new ColorBin((int) (bin[1] / bin[0]), (int) (bin[2] / bin[0]), (int) (bin[3] / bin[0]), (int) bin[0])
            );
        }
        List<ColorBox> boxes = new ArrayList<>();
        if (!bins.isEmpty()) {
            boxes.add(new ColorBox(bins));
        }
        while (boxes.size() < 216) {
            ColorBox selected = boxes.stream().filter(box -> box.bins.size() > 1)
                .max(Comparator.comparingLong(ColorBox::priority)).orElse(null);
            if (selected == null) {
                break;
            }
            boxes.remove(selected);
            boxes.addAll(selected.split());
        }
        for (int index = 0; index < boxes.size(); index++) {
            palette[index] = boxes.get(index).average();
        }
        // Quantized lookup is bounded at 32768 x 256 comparisons, independent of the page's pixel count.
        byte[] closest = new byte[32768];
        for (int key = 0; key < closest.length; key++) {
            int red = (key >> 10 & 31) * 8 + 4;
            int green = (key >> 5 & 31) * 8 + 4;
            int blue = (key & 31) * 8 + 4;
            int best = 0;
            int distance = Integer.MAX_VALUE;
            for (int index = 0; index < palette.length; index++) {
                if (index >= boxes.size() && index < 216 || index >= next) {
                    continue;
                }
                int rgb = palette[index];
                int r = red - (rgb >> 16 & 255);
                int g = green - (rgb >> 8 & 255);
                int b = blue - (rgb & 255);
                int candidate = r * r + g * g + b * b;
                if (candidate < distance) {
                    best = index;
                    distance = candidate;
                }
            }
            closest[key] = (byte) best;
        }
        IndexColorModel colors = new IndexColorModel(8, 256, palette, 0, false, -1, DataBuffer.TYPE_BYTE);
        BufferedImage result =
            new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_INDEXED, colors);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                Integer known = exact.get(rgb);
                result.getRaster().setSample(x, y, 0, known == null ? closest[key(rgb)] & 255 : known);
            }
        }
        return result;
    }

    private static int key(
        int rgb) {
        return (rgb >> 19 & 31) << 10 | (rgb >> 11 & 31) << 5 | (rgb >> 3 & 31);
    }

    private record ColorBin(
        int red,
        int green,
        int blue,
        int count
    ) {

        private int channel(
            int channel) {
            return channel == 0 ? red : channel == 1 ? green : blue;
        }
    }

    private static final class ColorBox {

        private final List<ColorBin> bins;

        private final int channel;

        private final int range;

        private final long count;

        private ColorBox(
            List<ColorBin> bins) {
            this.bins = bins;
            int widest = 0;
            int chosen = 0;
            for (int channel = 0; channel < 3; channel++) {
                int minimum = 255;
                int maximum = 0;
                for (ColorBin bin : bins) {
                    minimum = Math.min(minimum, bin.channel(channel));
                    maximum = Math.max(maximum, bin.channel(channel));
                }
                if (maximum - minimum > widest) {
                    widest = maximum - minimum;
                    chosen = channel;
                }
            }
            this.channel = chosen;
            this.range = widest;
            this.count = bins.stream().mapToLong(ColorBin::count).sum();
        }

        private long priority() {
            return range * count;
        }

        private List<ColorBox> split() {
            bins.sort(Comparator.comparingInt(bin -> bin.channel(channel)));
            long weight = 0;
            int split = 1;
            for (int index = 0; index < bins.size() - 1; index++) {
                weight += bins.get(index).count();
                split = index + 1;
                if (weight >= count / 2) {
                    break;
                }
            }
            return List.of(
                new ColorBox(new ArrayList<>(bins.subList(0, split))),
                new ColorBox(new ArrayList<>(bins.subList(split, bins.size())))
            );
        }

        private int average() {
            long red = 0;
            long green = 0;
            long blue = 0;
            for (ColorBin bin : bins) {
                red += (long) bin.red() * bin.count();
                green += (long) bin.green() * bin.count();
                blue += (long) bin.blue() * bin.count();
            }
            return 0xff000000 | (int) (red / count) << 16 | (int) (green / count) << 8 | (int) (blue / count);
        }
    }
}
