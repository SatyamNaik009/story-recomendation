package com.storystylist.backend.render.service;
import java.awt.*;
import java.awt.image.*;
/** A strongly muted, low-resolution blur of the real selected photo; no generated content. */
final class SinglePhotoBackdrop {
 private SinglePhotoBackdrop(){}
 static void paint(Graphics2D target,BufferedImage source) {
  BufferedImage small=new BufferedImage(54,96,BufferedImage.TYPE_INT_RGB),blur=null;
  var g=small.createGraphics();
  try {
   g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
   double scale=Math.max(54./source.getWidth(),96./source.getHeight());
   int w=(int)Math.ceil(source.getWidth()*scale),h=(int)Math.ceil(source.getHeight()*scale);
   g.drawImage(source,(54-w)/2,(96-h)/2,w,h,null);
  }finally{g.dispose();}
  try {
   float[] kernel=new float[81];java.util.Arrays.fill(kernel,1f/81);
   var filter=new ConvolveOp(new Kernel(9,9,kernel),ConvolveOp.EDGE_NO_OP,null);
   for(int i=0;i<3;i++){blur=filter.filter(small,null);small.flush();small=blur;}
   var out=(Graphics2D)target.create();
   try {
    out.setComposite(AlphaComposite.SrcOver.derive(.16f));
    out.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    out.drawImage(small,0,0,1080,1920,null);
   }finally{out.dispose();}
  }finally{small.flush();}
 }
}
