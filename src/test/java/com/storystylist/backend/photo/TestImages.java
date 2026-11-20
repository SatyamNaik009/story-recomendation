package com.storystylist.backend.photo;

import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class TestImages {
    private TestImages() {
    }

    public static byte[] image(String format, int width, int height) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output)) {
            throw new IllegalArgumentException("No test image writer for " + format);
        }
        return output.toByteArray();
    }

    public static MockMultipartFile photo(String format, int width, int height) throws IOException {
        return new MockMultipartFile("photos", "photo." + format, "image/" + format, image(format, width, height));
    }
}
