package com.storystylist.backend.render.model;
/** Subpixel canvas coordinates preserve the exact box used for Java2D measurement. */
public record TextPixelBox(double x,double y,double width,double height) {}
