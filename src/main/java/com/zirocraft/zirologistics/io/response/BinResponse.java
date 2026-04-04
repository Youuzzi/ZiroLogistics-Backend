package com.zirocraft.zirologistics.io.response;

import lombok.Builder;

import lombok.Data;

@Data @Builder
public class BinResponse {
    private String publicId;
    private String warehouseName;
    private String zoneName;
    private String rackNumber;
    private String binCode;
}