package com.zirocraft.zirologistics.wms.warehouse.application;

public class WmsWarehouseNotFoundException extends RuntimeException {
    public WmsWarehouseNotFoundException() {
        super("The requested warehouse resource was not found");
    }
}
