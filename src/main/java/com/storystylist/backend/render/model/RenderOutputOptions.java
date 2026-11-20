package com.storystylist.backend.render.model;
public record RenderOutputOptions(Format format,String contentType,ColorModel colorModel) {
    public enum Format { PNG }
    public enum ColorModel { RGB }
    public static RenderOutputOptions png() { return new RenderOutputOptions(Format.PNG,"image/png",ColorModel.RGB); }
}
