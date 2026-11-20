package com.storystylist.backend.render.model;
import java.io.*;
/** Future renderer input. Caller supplies originals, not analysis thumbnails. Renderer closes each stream. */
@FunctionalInterface
public interface OriginalPhotoAssets {
    InputStream openOriginal(String photoId) throws IOException;
}
