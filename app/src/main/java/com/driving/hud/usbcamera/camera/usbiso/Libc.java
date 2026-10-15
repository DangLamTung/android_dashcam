package com.driving.hud.usbcamera.camera.usbiso;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA binding to libc for the raw usbfs ioctls used by isochronous UVC capture.
 *
 * Ported from Peter-St/Android-UVC-Camera (LGPL-2.1):
 * https://github.com/Peter-St/Android-UVC-Camera/blob/master/app/src/main/java/humer/UvcCamera/UsbIso64/Libc.java
 */
public interface Libc extends Library {

    Libc INSTANCE = Native.load("c", Libc.class);

    int O_RDONLY = 00;
    int O_WRONLY = 01;
    int O_RDWR = 02;

    int open(String path, int flags) throws LastErrorException;

    int close(int fd) throws LastErrorException;

    int ioctl(int fileHandle, int request, PointerByReference p) throws LastErrorException;

    int ioctl(int fileHandle, int request, Pointer p) throws LastErrorException;

    int ioctl(int fileHandle, int request, long l) throws LastErrorException;

    int ioctl(int fileHandle, int request, IntByReference l) throws LastErrorException;

    int ioctl(int fileHandle, int request, Structure s) throws LastErrorException;
}
