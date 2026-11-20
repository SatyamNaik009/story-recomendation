package com.storystylist.backend.render.service;
import org.springframework.stereotype.Service;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
@Service
public class StoryImageEncodingService {
    public byte[] png(BufferedImage image) {
        if(image.getWidth()!=1080 || image.getHeight()!=1920) throw new RenderRequestException("RENDER_ENCODING_FAILED","Could not encode the Story image",500);
        var writers=ImageIO.getImageWritersByFormatName("png");
        if(!writers.hasNext()) throw new RenderRequestException("RENDER_ENCODING_FAILED","PNG encoding is unavailable",500);
        var writer=writers.next();
        try(var bytes=new ByteArrayOutputStream();var output=new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);writer.write(null,new IIOImage(image,null,null),writer.getDefaultWriteParam());output.flush();
            var result=bytes.toByteArray();
            if(result.length<8 || !java.util.Arrays.equals(java.util.Arrays.copyOf(result,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) throw new IOException();
            return result;
        } catch(IOException | RuntimeException e){throw new RenderRequestException("RENDER_ENCODING_FAILED","Could not encode the Story image",500);}
        finally{writer.dispose();}
    }
}
