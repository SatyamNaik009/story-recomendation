package com.storystylist.backend.story.dto;
public record StoryRenderPlan(String schemaVersion, StoryCanvas canvas, StoryBackground background, StoryLayoutType layoutType, StoryLayers layers, RenderingOptions renderingOptions) {}
