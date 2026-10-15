package com.driving.hud.usbcamera.camera.usbiso;

import com.sun.jna.ptr.IntByReference;

import java.io.IOException;

import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.USBDEVFS_GET_CAPABILITIES;
import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.USBDEVFS_RELEASEINTERFACE;
import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.USBDEVFS_SETINTERFACE;

/**
 * Raw usbfs ioctl helpers (set interface alt-setting, release interface, caps).
 *
 * Ported from Peter-St/Android-UVC-Camera (LGPL-2.1):
 * https://github.com/Peter-St/Android-UVC-Camera/blob/master/app/src/main/java/humer/UvcCamera/UsbIso64/usbdevice_fs_util.java
 */
public class UsbDeviceFsUtil {

    /** Sets the interface to the given alternate setting via USBDEVFS_SETINTERFACE. */
    public static void setInterface(int fileDescriptor, int interfaceId, int altSetting) throws IOException {
        UsbDeviceFs.usbdevfs_setinterface p = new UsbDeviceFs.usbdevfs_setinterface();
        p.interfaceId = interfaceId;
        p.altsetting = altSetting;
        Libc.INSTANCE.ioctl(fileDescriptor, USBDEVFS_SETINTERFACE, p);
    }

    public static int releaseInterface(int fileDescriptor, int interfaceId) throws IOException {
        return Libc.INSTANCE.ioctl(fileDescriptor, USBDEVFS_RELEASEINTERFACE, interfaceId);
    }

    public static int getCapabilities(int fileDescriptor) throws IOException {
        IntByReference resBuffer = new IntByReference();
        Libc.INSTANCE.ioctl(fileDescriptor, USBDEVFS_GET_CAPABILITIES, resBuffer);
        return resBuffer.getValue();
    }
}
