package com.storystylist.backend.photo.service;
import com.storystylist.backend.story.dto.PhotoAssetMetadata;
import com.storystylist.backend.photo.exception.PhotoProcessingException;
import net.coobird.thumbnailator.util.exif.Orientation;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.security.*;
import java.util.*;
/** Called only after existing upload validation/preprocessing; reuses the bounded EXIF reader. */
public final class PhotoAssetReader {
    private PhotoAssetReader() {}
    public static PhotoAssetMetadata read(MultipartFile upload, String id) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=upload.getInputStream()) {
                byte[] buffer=new byte[8192]; int n;
                while((n=input.read(buffer))!=-1) digest.update(buffer,0,n);
            }
            try(var source=upload.getInputStream(); var input=new MemoryCacheImageInputStream(source)) {
                var reader=ImageIO.getImageReaders(input).next();
                try {
                    reader.setInput(input,false,false);
                    int w=reader.getWidth(0), h=reader.getHeight(0);
                    String type="image/"+reader.getFormatName().toLowerCase(Locale.ROOT);
                    var o=type.equals("image/jpeg") ? JpegExifOrientationReader.read(reader) : null;
                    boolean swap=o==Orientation.LEFT_TOP || o==Orientation.RIGHT_TOP || o==Orientation.RIGHT_BOTTOM || o==Orientation.LEFT_BOTTOM;
                    return new PhotoAssetMetadata(id,HexFormat.of().formatHex(digest.digest()),w,h,type,swap?h:w,swap?w:h);
                } finally { reader.dispose(); }
            }
        } catch(java.io.IOException | NoSuchAlgorithmException e) { throw new PhotoProcessingException(e); }
    }
}
