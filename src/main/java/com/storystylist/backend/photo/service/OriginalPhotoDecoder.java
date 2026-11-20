package com.storystylist.backend.photo.service;
import com.storystylist.backend.story.dto.PhotoAssetMetadata;
import com.storystylist.backend.render.service.RenderRequestException;
import net.coobird.thumbnailator.util.exif.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
/** Internal selected-original decoder; caller must first run existing upload limits and asset verification. */
public final class OriginalPhotoDecoder {
    private OriginalPhotoDecoder(){}
    public static BufferedImage decode(MultipartFile file,PhotoAssetMetadata asset) {
        BufferedImage decoded=null,oriented=null,rgb=null;
        try(var source=file.getInputStream();var input=new MemoryCacheImageInputStream(source)) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IllegalArgumentException();
            var reader=readers.next();Orientation orientation;
            try {
                reader.setInput(input,false,false);
                if(reader.getWidth(0)!=asset.originalWidth() || reader.getHeight(0)!=asset.originalHeight()
                        || !("image/"+reader.getFormatName()).equalsIgnoreCase(asset.mediaType())) throw new IllegalArgumentException();
                orientation="image/jpeg".equals(asset.mediaType())?JpegExifOrientationReader.read(reader):null;
                boolean[] warning={false};reader.addIIOReadWarningListener((r,m)->warning[0]=true);
                decoded=reader.read(0);
                if(decoded==null || warning[0]) throw new IllegalArgumentException();
            } finally {reader.dispose();}
            oriented=orientation!=null && orientation!=Orientation.TOP_LEFT?ExifFilterUtils.getFilterForOrientation(orientation).apply(decoded):decoded;
            if(oriented.getWidth()!=asset.orientedWidth() || oriented.getHeight()!=asset.orientedHeight()) throw new IllegalArgumentException();
            rgb=new BufferedImage(oriented.getWidth(),oriented.getHeight(),BufferedImage.TYPE_INT_RGB);
            var g=rgb.createGraphics();try {g.setColor(Color.WHITE);g.fillRect(0,0,rgb.getWidth(),rgb.getHeight());g.drawImage(oriented,0,0,null);}finally{g.dispose();}
            return rgb;
        } catch(Exception e) {
            if(rgb!=null)rgb.flush();
            throw RenderRequestException.invalid("RENDER_IMAGE_INVALID","Selected original could not be decoded safely");
        } finally {if(oriented!=null && oriented!=decoded)oriented.flush();if(decoded!=null)decoded.flush();}
    }
}
