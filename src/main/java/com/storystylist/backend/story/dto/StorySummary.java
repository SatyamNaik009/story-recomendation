package com.storystylist.backend.story.dto;
public record StorySummary(String title, java.util.List<String> selectedPhotoIds, String concept, String selectionReason, String layoutSummary, CaptionSuggestion caption, String whyItWorks) {}
