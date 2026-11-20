package com.storystylist.backend.render.service;
public final class RenderRequestException extends RuntimeException {
    private final String code;
    private final int status;
    public RenderRequestException(String code,String message,int status) {super(message);this.code=code;this.status=status;}
    public String code(){return code;} public int status(){return status;}
    public static RenderRequestException invalid(String code,String message){return new RenderRequestException(code,message,400);}
}
