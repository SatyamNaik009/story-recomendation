package com.storystylist.backend.photo.dto;

import java.util.List;

public record PhotoInspectionResponse(String status, int photoCount, boolean promptProvided,
                                      List<PhotoMetadata> photos) {
    public PhotoInspectionResponse {
        photos = List.copyOf(photos);
    }
}
