package com.driving.hud.usbcamera.camera.usbiso;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Linux usbfs (USBDEVFS) ioctl constants, structs and the raw {@code usbdevfs_urb}
 * byte-buffer writer.
 *
 * The {@code usbdevfs_urb} struct is layout-compatible with the kernel's
 * {@code struct usbdevfs_urb} on both 32-bit and 64-bit (arm64) ABIs, which is
 * what allows the isochronous URBs to be submitted/reaped directly from Java.
 *
 * Ported from Peter-St/Android-UVC-Camera (LGPL-2.1):
 * https://github.com/Peter-St/Android-UVC-Camera/blob/master/app/src/main/java/humer/UvcCamera/UsbIso64/usbdevice_fs.java
 */
public interface UsbDeviceFs {

    enum UsbdevfsUrbType {
        ISO((byte) 0),
        INTERRUPT((byte) 1),
        CONTROL((byte) 2),
        BULK((byte) 3);

        private final byte value;

        UsbdevfsUrbType(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }
    }

    enum UsbdevfsUrbFlag {
        SHORT_NOT_OK(0x01),
        ISO_ASAP(0x02),
        BULK_CONTINUATION(0x04),
        NO_FSBR(0x20),
        ZERO_PACKET(0x40),
        NO_INTERRUPT(0x80);

        private final int value;

        UsbdevfsUrbFlag(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    int USBDEVFS_MAXDRIVERNAME = 255;

    // IOCTL function codes ('U' type, ARM default encoding).
    int USBDEVFS_CONTROL = Ioctl._IOWR('U', 0, new usbdevfs_ctrltransfer().size());
    int USBDEVFS_BULK = Ioctl._IOWR('U', 2, new usbdevfs_bulktransfer().size());
    int USBDEVFS_RESETEP = Ioctl._IOR('U', 3, 4);
    int USBDEVFS_SETINTERFACE = Ioctl._IOR('U', 4, new usbdevfs_setinterface().size());
    int USBDEVFS_SETCONFIGURATION = Ioctl._IOR('U', 5, 4);
    int USBDEVFS_GETDRIVER = Ioctl._IOW('U', 8, new usbdevfs_getdriver().size());
    int USBDEVFS_SUBMITURB = Ioctl._IOR('U', 10, new usbdevfs_urb().size());
    int USBDEVFS_DISCARDURB = Ioctl._IO('U', 11);
    int USBDEVFS_REAPURB = Ioctl._IOW('U', 12, Native.POINTER_SIZE);
    int USBDEVFS_REAPURBNDELAY = Ioctl._IOW('U', 13, Native.POINTER_SIZE);
    int USBDEVFS_DISCSIGNAL = Ioctl._IOR('U', 14, new usbdevfs_disconnectsignal().size());
    int USBDEVFS_CLAIMINTERFACE = Ioctl._IOR('U', 15, 4);
    int USBDEVFS_RELEASEINTERFACE = Ioctl._IOR('U', 16, 4);
    int USBDEVFS_CONNECTINFO = Ioctl._IOR('U', 17, new usbdevfs_connectinfo().size());
    int USBDEVFS_IOCTL = Ioctl._IOWR('U', 18, new usbdevfs_ioctl().size());
    int USBDEVFS_HUB_PORTINFO = Ioctl._IOWR('U', 18, new usbdevfs_hub_portinfo().size());
    int USBDEVFS_RESET = Ioctl._IO('U', 20);
    int USBDEVFS_CLEAR_HALT = Ioctl._IOR('U', 21, 4);
    int USBDEVFS_DISCONNECT = Ioctl._IO('U', 22);
    int USBDEVFS_CONNECT = Ioctl._IO('U', 23);
    int USBDEVFS_CLAIM_PORT = Ioctl._IOR('U', 24, 4);
    int USBDEVFS_RELEASE_PORT = Ioctl._IOR('U', 25, 4);
    int USBDEVFS_GET_CAPABILITIES = Ioctl._IOR('U', 26, 4);
    int USBDEVFS_DISCONNECT_CLAIM = Ioctl._IOR('U', 27, new usbdevfs_disconnect_claim().size());
    int USBDEVFS_ALLOC_STREAMS = Ioctl._IOR('U', 28, new usbdevfs_streams().size());
    int USBDEVFS_FREE_STREAMS = Ioctl._IOR('U', 29, new usbdevfs_streams().size());
    int USBDEVFS_DROP_PRIVILEGES = Ioctl._IOW('U', 30, 4);
    int USBDEVFS_GET_SPEED = Ioctl._IO('U', 31);

    int USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER = 0x01;
    int USBDEVFS_DISCONNECT_CLAIM_EXCEPT_DRIVER = 0x02;

    @Structure.FieldOrder({"bRequestType", "bRequest", "wValue", "wIndex", "wLength", "timeout", "data"})
    class usbdevfs_ctrltransfer extends Structure {
        public byte bRequestType;
        public byte bRequest;
        public short wValue;
        public short wIndex;
        public short wLength;
        public int timeout; /* in ms */
        public Pointer data;
    }

    @Structure.FieldOrder({"ep", "len", "timeout", "data"})
    class usbdevfs_bulktransfer extends Structure {
        public int ep;
        public int len;
        public int timeout; /* in ms */
        public Pointer data;
    }

    @Structure.FieldOrder({"interfaceId", "altsetting"})
    class usbdevfs_setinterface extends Structure {
        public int interfaceId;
        public int altsetting;
    }

    @Structure.FieldOrder({"signr", "context"})
    class usbdevfs_disconnectsignal extends Structure {
        public int signr;
        public Pointer context;
    }

    @Structure.FieldOrder({"ifno", "driver"})
    class usbdevfs_getdriver extends Structure {
        public int ifno;
        public byte[] driver = new byte[USBDEVFS_MAXDRIVERNAME + 1];
    }

    @Structure.FieldOrder({"devnum", "slow"})
    class usbdevfs_connectinfo extends Structure {
        public int devnum;
        public byte slow;
    }

    @Structure.FieldOrder({"type", "endpoint", "status", "flags", "buffer",
            "buffer_length", "actual_length", "start_frame",
            "number_of_packets_stream_id", "error_count", "signr",
            "usercontext"})
    final class usbdevfs_urb extends Structure {
        public byte type;
        public byte endpoint;
        public int status;
        public int flags;
        public Pointer buffer;
        public int buffer_length;
        public int actual_length;
        public int start_frame;
        public int number_of_packets_stream_id; // this is a union
        public int error_count;
        public int signr;
        public Pointer usercontext;

        @Override
        protected int fieldOffset(String field) {
            return super.fieldOffset(field);
        }
    }

    @Structure.FieldOrder({"length", "actual_length", "status"})
    final class usbdevfs_iso_packet_desc extends Structure {
        public int length;
        public int actual_length;
        public int status;

        @Override
        protected int fieldOffset(String field) {
            return super.fieldOffset(field);
        }
    }

    @Structure.FieldOrder({"ifno", "ioctl_code", "data"})
    class usbdevfs_ioctl extends Structure {
        public int ifno;
        public int ioctl_code;
        public Pointer data;
    }

    @Structure.FieldOrder({"nports", "port"})
    class usbdevfs_hub_portinfo extends Structure {
        public byte nports;
        public byte[] port = new byte[127];
    }

    @Structure.FieldOrder({"iface", "flags", "driver"})
    class usbdevfs_disconnect_claim extends Structure {
        public int iface;
        public int flags;
        public byte[] driver = new byte[USBDEVFS_MAXDRIVERNAME + 1];
    }

    @Structure.FieldOrder({"num_streams", "num_eps", "eps"})
    class usbdevfs_streams extends Structure {
        public int num_streams;
        public int num_eps;
        public byte[] eps = new byte[1];
    }

    /**
     * Raw {@code struct usbdevfs_urb} (+ iso packet descriptors) as a ByteBuffer,
     * with byte-accurate field offsets for the current ABI.
     */
    class Urb {
        public static final int urbBaseSize;
        private static final int packetDescSize;
        public static final int usbdevfs_urb_type;
        public static final int usbdevfs_urb_endpoint;
        public static final int usbdevfs_urb_status;
        public static final int usbdevfs_urb_flags;
        public static final int usbdevfs_urb_buffer;
        public static final int usbdevfs_urb_buffer_length;
        public static final int usbdevfs_urb_actual_length;
        public static final int usbdevfs_urb_start_frame;
        public static final int usbdevfs_urb_number_of_packets_stream_id;
        public static final int usbdevfs_urb_error_count;
        public static final int usbdevfs_urb_signr;
        public static final int usbdevfs_urb_usercontext;
        public static final int usbdevfs_iso_packet_desc_length;
        public static final int usbdevfs_iso_packet_desc_actual_length;
        public static final int usbdevfs_iso_packet_desc_status;

        static {
            usbdevfs_urb urb = new usbdevfs_urb();
            usbdevfs_iso_packet_desc desc = new usbdevfs_iso_packet_desc();
            urbBaseSize = urb.size();
            packetDescSize = desc.size();
            usbdevfs_urb_type = urb.fieldOffset("type");
            usbdevfs_urb_endpoint = urb.fieldOffset("endpoint");
            usbdevfs_urb_status = urb.fieldOffset("status");
            usbdevfs_urb_flags = urb.fieldOffset("flags");
            usbdevfs_urb_buffer = urb.fieldOffset("buffer");
            usbdevfs_urb_buffer_length = urb.fieldOffset("buffer_length");
            usbdevfs_urb_actual_length = urb.fieldOffset("actual_length");
            usbdevfs_urb_start_frame = urb.fieldOffset("start_frame");
            usbdevfs_urb_number_of_packets_stream_id = urb.fieldOffset("number_of_packets_stream_id");
            usbdevfs_urb_error_count = urb.fieldOffset("error_count");
            usbdevfs_urb_signr = urb.fieldOffset("signr");
            usbdevfs_urb_usercontext = urb.fieldOffset("usercontext");
            usbdevfs_iso_packet_desc_length = desc.fieldOffset("length");
            usbdevfs_iso_packet_desc_actual_length = desc.fieldOffset("actual_length");
            usbdevfs_iso_packet_desc_status = desc.fieldOffset("status");
        }

        private ByteBuffer urbBuf;
        private final Pointer urbBufPointer;
        private int maxPackets;

        public Urb(int maxPackets) {
            this.maxPackets = maxPackets;
            int urbSize = urbBaseSize + maxPackets * packetDescSize;
            urbBuf = ByteBuffer.allocateDirect(urbSize);
            urbBuf.order(ByteOrder.nativeOrder());
            urbBufPointer = Native.getDirectBufferPointer(urbBuf);
        }

        public Urb(Pointer urbPointer) {
            int urbSize = urbBaseSize + maxPackets * packetDescSize;
            this.urbBufPointer = urbPointer;
            this.urbBuf = urbBufPointer.getByteBuffer(0, urbSize);
        }

        public Pointer getNativeUrbAddr() {
            return urbBufPointer;
        }

        public void setType(byte type) {
            urbBuf.put(usbdevfs_urb_type, type);
        }

        public void setEndpoint(byte endpoint) {
            urbBuf.put(usbdevfs_urb_endpoint, endpoint);
        }

        public int getStatus() {
            return urbBuf.getInt(usbdevfs_urb_status);
        }

        public void setStatus(int status) {
            urbBuf.putInt(usbdevfs_urb_status, status);
        }

        public void setFlags(int flags) {
            urbBuf.putInt(usbdevfs_urb_flags, flags);
        }

        public void setBuffer(Pointer buffer) {
            if (Native.POINTER_SIZE == 4) {
                urbBuf.putInt(usbdevfs_urb_buffer, (int) Pointer.nativeValue(buffer));
            } else if (Native.POINTER_SIZE == 8) {
                urbBuf.putLong(usbdevfs_urb_buffer, Pointer.nativeValue(buffer));
            } else {
                throw new IllegalStateException("Unhandled Pointer Size: " + Native.POINTER_SIZE);
            }
        }

        public void setBufferLength(int bufferLength) {
            urbBuf.putInt(usbdevfs_urb_buffer_length, bufferLength);
        }

        public void setActualLength(int actualLength) {
            urbBuf.putInt(usbdevfs_urb_actual_length, actualLength);
        }

        public void setStartFrame(int startFrame) {
            urbBuf.putInt(usbdevfs_urb_start_frame, startFrame);
        }

        public int getNumberOfPackets() {
            return urbBuf.getInt(usbdevfs_urb_number_of_packets_stream_id);
        }

        public void setNumberOfPackets(int numberOfPackets) {
            if (numberOfPackets < 0 || numberOfPackets > maxPackets) {
                throw new IllegalArgumentException();
            }
            urbBuf.putInt(usbdevfs_urb_number_of_packets_stream_id, numberOfPackets);
        }

        public void setErrorCount(int errorCount) {
            urbBuf.putInt(usbdevfs_urb_error_count, errorCount);
        }

        public void setSigNr(int signr) {
            urbBuf.putInt(usbdevfs_urb_signr, signr);
        }

        public int getUserContext() {
            if (Native.POINTER_SIZE == 4) {
                return urbBuf.getInt(usbdevfs_urb_usercontext);
            } else if (Native.POINTER_SIZE == 8) {
                return (int) urbBuf.getLong(usbdevfs_urb_usercontext);
            } else {
                throw new IllegalStateException("Unhandled Pointer Size: " + Native.POINTER_SIZE);
            }
        }

        public void setUserContext(int userContext) {
            if (Native.POINTER_SIZE == 4) {
                urbBuf.putInt(usbdevfs_urb_usercontext, userContext);
            } else if (Native.POINTER_SIZE == 8) {
                urbBuf.putLong(usbdevfs_urb_usercontext, userContext);
            } else {
                throw new IllegalStateException("Unhandled Pointer Size: " + Native.POINTER_SIZE);
            }
        }

        public void setPacketLength(int packetNo, int length) {
            if (packetNo < 0 || packetNo >= maxPackets) {
                throw new IllegalArgumentException();
            }
            urbBuf.putInt(urbBaseSize + packetNo * packetDescSize + usbdevfs_iso_packet_desc_length, length);
        }

        public int getPacketLength(int packetNo) {
            if (packetNo < 0 || packetNo >= maxPackets) {
                throw new IllegalArgumentException();
            }
            return urbBuf.getInt(urbBaseSize + packetNo * packetDescSize + usbdevfs_iso_packet_desc_length);
        }

        public void setPacketActualLength(int packetNo, int actualLength) {
            if (packetNo < 0 || packetNo >= maxPackets) {
                throw new IllegalArgumentException();
            }
            urbBuf.putInt(urbBaseSize + packetNo * packetDescSize + usbdevfs_iso_packet_desc_actual_length, actualLength);
        }

        public int getPacketActualLength(int packetNo) {
            if (packetNo < 0 || packetNo >= maxPackets) {
                throw new IllegalArgumentException();
            }
            return urbBuf.getInt(urbBaseSize + packetNo * packetDescSize + usbdevfs_iso_packet_desc_actual_length);
        }

        public void setPacketStatus(int packetNo, int status) {
            if (packetNo < 0 || packetNo >= maxPackets) {
                throw new IllegalArgumentException();
            }
            urbBuf.putInt(urbBaseSize + packetNo * packetDescSize + usbdevfs_iso_packet_desc_status, status);
        }

        public int getPacketStatus(int packetNo) {
            if (packetNo < 0 || packetNo >= maxPackets) {
                throw new IllegalArgumentException();
            }
            return urbBuf.getInt(urbBaseSize + packetNo * packetDescSize + usbdevfs_iso_packet_desc_status);
        }
    }
}
