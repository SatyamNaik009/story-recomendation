package com.storystylist.backend.story.dto;
public record ResolvedTextLayout(String measurementVersion, double paddingPixels, double lineSpacing,
                                 java.util.List<TextLine> lines) {}
