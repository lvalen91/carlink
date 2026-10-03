package com.carlink.usbbridge;

interface IUsbBridgeCallback {
    void onState(int state, String detail);
    void onInboundChunk(long transferId, int messageType, int sourcePtsMs,
                        int chunkIndex, int chunkCount, int totalLength, in byte[] data);
}
