package com.avangrid.gui.avangrid_backend.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class VpiSearchResponse {
    private List<Map<String, Object>> data;
    private PaginationResponse pagination;
    private String status;
    private String message;
}

