package com.storystylist.backend.storage;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
/** In-memory adapter; its bytes never leave the internal rendering boundary. */
final class StoredPhotoFile implements MultipartFile {
 private final byte[] bytes; private final String mediaType;
 StoredPhotoFile(byte[] bytes,String mediaType){this.bytes=bytes;this.mediaType=mediaType;}
 public String getName(){return "photos";}
 public String getOriginalFilename(){return mediaType.equals("image/png")?"stored.png":"stored.jpg";}
 public String getContentType(){return mediaType;}
 public boolean isEmpty(){return bytes.length==0;}
 public long getSize(){return bytes.length;}
 public byte[] getBytes(){return bytes.clone();}
 public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
 public void transferTo(File dest){throw new UnsupportedOperationException("Stored images are rendered in memory");}
}
