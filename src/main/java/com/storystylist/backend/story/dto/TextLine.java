package com.storystylist.backend.story.dto;
/** Pixel offsets relative to text-box top-left; draw text at (xOffset, baseline). */
public record TextLine(String text, double xOffset, double baseline, double width) {}
