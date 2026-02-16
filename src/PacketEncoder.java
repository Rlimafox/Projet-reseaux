import java.util.*;



public class PacketEncoder {



    /**

     * Format simple :

     *  seq     (2 bytes)

     *  ack     (2 bytes)

     *  flags   (1 byte)

     *  length  (1 byte)  <-- longueur de data, max 255

     *  data[]  (length bytes)

     */

    public static byte[] encode(Packet p) {

        if (p.data == null) p.data = new byte[0];

        int len = p.data.length;

        if (len > 255) throw new IllegalArgumentException("data too large (max 255)");



        byte[] out = new byte[6 + len];



        out[0] = (byte)((p.seq >> 8) & 0xFF);

        out[1] = (byte)(p.seq & 0xFF);



        out[2] = (byte)((p.ack >> 8) & 0xFF);

        out[3] = (byte)(p.ack & 0xFF);



        out[4] = p.flags;

        out[5] = (byte)(len & 0xFF);



        System.arraycopy(p.data, 0, out, 6, len);



        return out;

    }



    public static Packet decode(byte[] raw) {

        if (raw.length < 6) throw new IllegalArgumentException("Invalid packet");



        Packet p = new Packet();



        p.seq = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);

        p.ack = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);



        p.flags = raw[4];

        int len = raw[5] & 0xFF;



        if (len > raw.length - 6) throw new IllegalArgumentException("Invalid length");



        p.data = Arrays.copyOfRange(raw, 6, 6 + len);



        return p;

    }

}