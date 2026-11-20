package com.storystylist.backend.photo;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

public final class ProcessingTestImages {
    public static final Color[] COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};

    private ProcessingTestImages() {
    }

    public static byte[] pattern(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    graphics.setColor(COLORS[y * 2 + x]);
                    graphics.fillRect(x * width / 2, y * height / 2, width / 2, height / 2);
                }
            }
            return encode(image, "jpeg");
        } finally {
            graphics.dispose();
            image.flush();
        }
    }

    public static byte[] orientedJpeg(int width, int height, int orientation) throws IOException {
        return withExif(pattern(width, height), exif(orientation));
    }

    public static byte[] exif(int orientation) {
        // APP1 payload: Exif identifier, little-endian TIFF, one SHORT orientation entry.
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{'E', 'x', 'i', 'f', 0, 0}).put((byte) 'I').put((byte) 'I')
                .putShort((short) 42).putInt(8).putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1)
                .putShort((short) orientation).putShort((short) 0).putInt(0).array();
    }

    public static byte[] withExif(byte[] jpeg, byte[] exif) throws IOException {
        // Insert after the generated JFIF APP0 segment so it remains first after SOI.
        int offset = 4 + ((jpeg[4] & 255) << 8 | jpeg[5] & 255);
        var output = new ByteArrayOutputStream();
        output.write(jpeg, 0, offset);
        output.write(0xff);
        output.write(0xe1);
        int length = exif.length + 2;
        output.write(length >> 8);
        output.write(length & 255);
        output.write(exif);
        output.write(jpeg, offset, jpeg.length - offset);
        return output.toByteArray();
    }

    public static byte[] transparentPng() throws IOException {
        var image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 80; y++) {
            for (int x = 60; x < 120; x++) {
                image.setRGB(x, y, new Color(255, 0, 0, 128).getRGB());
            }
        }
        try {
            return encode(image, "png");
        } finally {
            image.flush();
        }
    }

    public static byte[] noisyPng() throws IOException {
        var image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        try {
            return encode(image, "png");
        } finally {
            image.flush();
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("Test image writer missing");
        }
        return output.toByteArray();
    }
}
