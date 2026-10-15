package com.driving.hud.usbcamera.camera.usbiso;

import android.util.Log;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.util.ArrayList;

import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.USBDEVFS_DISCARDURB;
import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.USBDEVFS_REAPURB;
import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.USBDEVFS_REAPURBNDELAY;
import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.USBDEVFS_SUBMITURB;
import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.UsbdevfsUrbFlag;
import static com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFs.UsbdevfsUrbType;

/**
 * An USB isochronous transfer controller using raw usbfs ioctls
 * (USBDEVFS_SUBMITURB / USBDEVFS_REAPURB*) directly on the device fd.
 *
 * This is the proven Android approach (works incl. MediaTek devices) used by
 * Peter-St/Android-UVC-Camera — it bypasses both libusb's broken Android
 * wrap_sys_device and Android's UsbRequest (which returns no iso data on many
 * devices).
 *
 * Ported from Peter-St/Android-UVC-Camera (LGPL-2.1):
 * https://github.com/Peter-St/Android-UVC-Camera/blob/master/app/src/main/java/humer/UvcCamera/UsbIso64/USBIso.java
 */
public class UsbIso {

    private static final String TAG = "UsbIso";

    private static final int EAGAIN = 11;
    private static final int ENODEV = 19;
    private static final int EINVAL = 22;

    private final ArrayList<Request> requests = new ArrayList<>();
    private final int fileDescriptor;
    private final int maxPacketsPerRequest;
    private final int maxPacketSize;
    private final byte endpointAddress;

    /**
     * @param fileDescriptor       value returned by UsbDeviceConnection.getFileDescriptor()
     * @param maxPacketsPerRequest maximum number of packets per request
     * @param maxPacketSize        maximum packet size
     * @param endpointAddress      isochronous endpoint address (IN, e.g. 0x82)
     */
    public UsbIso(int fileDescriptor, int maxPacketsPerRequest, int maxPacketSize, byte endpointAddress) {
        this.fileDescriptor = fileDescriptor;
        this.maxPacketsPerRequest = maxPacketsPerRequest;
        this.maxPacketSize = maxPacketSize;
        this.endpointAddress = endpointAddress;
    }

    public void preallocateRequests(int n) {
        while (requests.size() < n) {
            allocateRequest();
        }
    }

    public void submitUrbs() throws IOException {
        for (Request req : requests) {
            req.initialize();
            int rc = req.submit();
            if (rc != 0) {
                Log.e(TAG, "submitUrbs: USBDEVFS_SUBMITURB failed rc=" + rc);
                throw new IOException("USBDEVFS_SUBMITURB failed, rc=" + rc + ".");
            }
        }
    }

    public void dispose() throws IOException {
        try {
            flushRequests();
        } catch (LastErrorException e) {
            // Happens when the device has been disconnected.
            if (e.getErrorCode() != ENODEV) {
                throw e;
            }
        }
    }

    public void flushRequests() throws IOException {
        cancelAllRequests();
        discardAllPendingRequests();
        int queuedRequests = countQueuedRequests();
        if (queuedRequests > 0) {
            throw new IOException("The number of queued requests after flushRequests() is " + queuedRequests + ".");
        }
    }

    private void cancelAllRequests() throws IOException {
        for (Request req : requests) {
            if (req.queued) {
                req.cancel();
            }
        }
    }

    public Request getRequest() {
        for (Request req : requests) {
            if (!req.queued) {
                return req;
            }
        }
        return allocateRequest();
    }

    private Request allocateRequest() {
        Request request = new Request();
        request.setUserContext(requests.size());
        requests.add(request);
        return request;
    }

    public Request reapRequest(boolean wait) throws IOException {
        PointerByReference urbPointer = new PointerByReference();
        int func = wait ? USBDEVFS_REAPURB : USBDEVFS_REAPURBNDELAY;
        int rc = Libc.INSTANCE.ioctl(fileDescriptor, func, urbPointer);
        if (rc != 0) {
            throw new IOException("ioctl(USBDEVFS_REAPURB*) failed, rc=" + rc + ".");
        }
        UsbDeviceFs.Urb urb = new UsbDeviceFs.Urb(urbPointer.getValue());
        int urbNdx = urb.getUserContext();
        if (urbNdx < 0 || urbNdx >= requests.size()) {
            throw new IOException("URB.userContext returned by ioctl(USBDEVFS_REAPURB*) is out of range.");
        }
        Request req = requests.get(urbNdx);
        if (!req.getNativeUrbAddr().equals(urbPointer.getValue())) {
            throw new IOException("Address of URB returned by ioctl(USBDEVFS_REAPURB*) does not match.");
        }
        if (!req.queued) {
            throw new IOException("URB returned by ioctl(USBDEVFS_REAPURB*) was not queued.");
        }
        req.queued = false;
        req.initialized = false;
        return req;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private void discardAllPendingRequests() throws IOException {
        if (requests.size() == 0) {
            return;
        }
        // Drain any URBs that already completed (also catches URBs submitted by
        // Android's own UsbRequest machinery).
        while (reapRequest(false) != null) ;
    }

    private int countQueuedRequests() {
        int ctr = 0;
        for (Request req : requests) {
            if (req.queued) {
                ctr++;
            }
        }
        return ctr;
    }

    public class Request extends UsbDeviceFs.Urb {
        private boolean initialized;
        private boolean queued;
        private Memory buffer;

        private Request() {
            super(maxPacketsPerRequest);
            int bufSize = maxPacketsPerRequest * maxPacketSize;
            buffer = new Memory(bufSize);
        }

        public void initialize() {
            if (queued) {
                throw new IllegalStateException();
            }
            setEndpoint(endpointAddress);
            setType(UsbdevfsUrbType.ISO.getValue());
            setFlags(UsbdevfsUrbFlag.ISO_ASAP.getValue());
            setBuffer(buffer);
            setBufferLength((int) buffer.size());
            setActualLength(0);
            setStartFrame(0);
            setNumberOfPackets(maxPacketsPerRequest);
            setErrorCount(0);
            setSigNr(0);
            setStatus(-1);
            for (int packetNo = 0; packetNo < maxPacketsPerRequest; packetNo++) {
                setPacketLength(packetNo, maxPacketSize);
                setPacketActualLength(packetNo, 0);
                setPacketStatus(packetNo, -1);
            }
            initialized = true;
        }

        public int submit() throws IOException {
            if (!initialized || queued) {
                throw new IllegalStateException();
            }
            initialized = false;
            int rc;
            try {
                rc = Libc.INSTANCE.ioctl(fileDescriptor, USBDEVFS_SUBMITURB, getNativeUrbAddr());
                if (rc != 0) {
                    Log.d(TAG, "ioctl(USBDEVFS_SUBMITURB) failed, rc=" + rc + ".");
                    return rc;
                }
            } catch (Exception e) {
                Log.e(TAG, "ioctl(USBDEVFS_SUBMITURB) exception", e);
                return -1;
            }
            queued = true;
            return 0;
        }

        public void cancel() throws IOException {
            int rc;
            try {
                rc = Libc.INSTANCE.ioctl(fileDescriptor, USBDEVFS_DISCARDURB, getNativeUrbAddr());
            } catch (LastErrorException e) {
                if (e.getErrorCode() == EINVAL) {
                    // Request already completed.
                    return;
                }
                throw e;
            }
            if (rc != 0) {
                throw new IOException("ioctl(USBDEVFS_DISCARDURB) failed, rc=" + rc);
            }
        }

        @Override
        public void setNumberOfPackets(int n) {
            if (n < 1 || n > maxPacketsPerRequest) {
                throw new IllegalArgumentException();
            }
            super.setNumberOfPackets(n);
        }

        @Override
        public void setPacketLength(int packetNo, int length) {
            if (length < 0 || length > maxPacketSize) {
                throw new IllegalArgumentException();
            }
            super.setPacketLength(packetNo, length);
        }

        public void setPacketData(int packetNo, byte[] buf, int len) {
            if (packetNo < 0 || packetNo >= maxPacketsPerRequest || len > maxPacketSize) {
                throw new IllegalArgumentException();
            }
            buffer.write(packetNo * maxPacketSize, buf, 0, len);
        }

        public void getPacketData(int packetNo, byte[] buf, int len) {
            if (packetNo < 0 || packetNo >= maxPacketsPerRequest || len > maxPacketSize) {
                throw new IllegalArgumentException();
            }
            buffer.read(packetNo * maxPacketSize, buf, 0, len);
        }
    }
}
