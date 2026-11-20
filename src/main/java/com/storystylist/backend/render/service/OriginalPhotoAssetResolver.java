package com.storystylist.backend.render.service;
import com.storystylist.backend.photo.service.*;
import com.storystylist.backend.story.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@Service
public class OriginalPhotoAssetResolver {
    private final PhotoValidationService validation;
    public OriginalPhotoAssetResolver(PhotoValidationService validation){this.validation=validation;}
    public Map<String,MultipartFile> verify(List<MultipartFile> uploads,List<PhotoAssetMetadata> assets) {
        validation.inspect(uploads,null); // Headers/limits only; no pixel decoding of unused photos.
        if(assets==null || assets.size()!=uploads.size()) throw RenderRequestException.invalid("RENDER_ASSETS_MISSING","Supply the complete original ordered upload set and matching asset metadata");
        var matched=new LinkedHashMap<String,MultipartFile>();
        for(int i=0;i<uploads.size();i++) {
            String id="photo-"+(i+1);var expected=assets.get(i);
            if(expected==null || !id.equals(expected.photoId())) throw RenderRequestException.invalid("RENDER_ASSET_ORDER","Asset IDs must match the original upload order");
            var actual=PhotoAssetReader.read(uploads.get(i),id);
            if(expected.sha256()==null || !actual.sha256().equalsIgnoreCase(expected.sha256())) throw RenderRequestException.invalid("RENDER_ASSET_MISMATCH","Original photographs do not match the recorded assets or upload order");
            if(!Objects.equals(actual.originalWidth(),expected.originalWidth()) || !Objects.equals(actual.originalHeight(),expected.originalHeight())
                    || !Objects.equals(actual.orientedWidth(),expected.orientedWidth()) || !Objects.equals(actual.orientedHeight(),expected.orientedHeight())
                    || !Objects.equals(actual.mediaType(),expected.mediaType())) throw RenderRequestException.invalid("RENDER_ASSET_METADATA","Original photo metadata does not match");
            matched.put(id,uploads.get(i));
        }
        return Collections.unmodifiableMap(matched);
    }
}
