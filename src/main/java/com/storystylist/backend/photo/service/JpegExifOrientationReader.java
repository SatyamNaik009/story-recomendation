package com.storystylist.backend.photo.service;

import net.coobird.thumbnailator.util.exif.Orientation;

import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads only IFD0 orientation, with bounded offsets; never follows GPS or thumbnail IFDs. */
final class JpegExifOrientationReader {
    private JpegExifOrientationReader() {
    }

    static Orientation read(ImageReader reader) {
        try {
            var root = reader.getImageMetadata(0).getAsTree("javax_imageio_jpeg_image_1.0");
            for (var sequence = root.getFirstChild(); sequence != null; sequence = sequence.getNextSibling()) {
                if (!"markerSequence".equals(sequence.getNodeName())) continue;
                for (var node = sequence.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof IIOMetadataNode metadata && metadata.getUserObject() instanceof byte[] bytes
                            && "225".equals(metadata.getAttribute("MarkerTag")) && isExif(bytes)) {
                        return parse(bytes);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadableMetadata) {
            // Safe fallback: stored pixel order, orientationCorrected=false, no metadata logging.
        }
        return null;
    }

    private static boolean isExif(byte[] bytes) {
        return bytes.length >= 6 && bytes[0] == 'E' && bytes[1] == 'x' && bytes[2] == 'i'
                && bytes[3] == 'f' && bytes[4] == 0 && bytes[5] == 0;
    }

    private static Orientation parse(byte[] bytes) {
        if (bytes.length < 14) return null;
        ByteOrder order;
        if (bytes[6] == 'I' && bytes[7] == 'I') order = ByteOrder.LITTLE_ENDIAN;
        else if (bytes[6] == 'M' && bytes[7] == 'M') order = ByteOrder.BIG_ENDIAN;
        else return null;
        var data = ByteBuffer.wrap(bytes).order(order);
        if (data.getShort(8) != 42) return null;
        long offset = Integer.toUnsignedLong(data.getInt(10));
        if (offset < 8 || offset > bytes.length - 8L) return null;
        int ifd = (int) offset + 6;
        int entries = Short.toUnsignedInt(data.getShort(ifd));
        if (ifd + 2L + entries * 12L + 4 > bytes.length) return null;
        Orientation orientation = null;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (Short.toUnsignedInt(data.getShort(entry)) != 0x0112) continue;
            // Orientation is exactly one inline SHORT. Ambiguous/invalid declarations are ignored.
            if (orientation != null || data.getShort(entry + 2) != 3 || data.getInt(entry + 4) != 1) return null;
            orientation = Orientation.typeOf(Short.toUnsignedInt(data.getShort(entry + 8)));
            if (orientation == null) return null;
        }
        return orientation;
    }
}
