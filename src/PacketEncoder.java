import java.util.*;



public class PacketEncoder {

    /** Encode un Packet en tableau d’octets (v3 - header fixe + payload variable) */

    public static byte[] encode(Packet p) {

        if (p.data == null) p.data = new byte[0];

        int dataLen = p.data.length;

        int total = 8 + dataLen; // 8 = header v3

        byte[] out = new byte[total];

        // seq (2 bytes)

        out[0] = (byte)((p.seq >> 8) & 0xFF);

        out[1] = (byte)(p.seq & 0xFF);

        // ack (2 bytes)

        out[2] = (byte)((p.ack >> 8) & 0xFF);

        out[3] = (byte)(p.ack & 0xFF);

        // flags (1 byte)

        out[4] = p.flags;

        // reserved (1 byte) = 0

        out[5] = 0;

        // dataLen (2 bytes)

        out[6] = (byte)((dataLen >> 8) & 0xFF);
        out[7] = (byte)(dataLen & 0xFF);

        // payload
        System.arraycopy(p.data, 0, out, 8, dataLen);
        return out;
    }


    /** Decode un tableau d’octets en Packet (v3) */

    public static Packet decode(byte[] buf) {

        if (buf == null || buf.length < 8) throw new IllegalArgumentException("Paquet trop petit pour Packet v3");

        Packet p = new Packet();

        // seq

        p.seq = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);

        // ack

        p.ack = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);

        // flags

        p.flags = buf[4];

        // reserved = buf[5], ignoré

        // dataLen

        int dataLen = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);

        if (dataLen < 0 || dataLen > buf.length - 8) {

            throw new IllegalArgumentException("dataLen incohérent : " + dataLen);

        }

        p.data = Arrays.copyOfRange(buf, 8, 8 + dataLen);

        return p;
    }
}