import java.nio.*;

public class PacketEncoder {

    // Header :
    // seq (4) | ack (4) | flags (1) | len (2) = 11 octets
    private static final int HEADER_SIZE = 11;

    public static byte[] encode(Packet p) {
        int dataLength = (p.data == null) ? 0 : p.data.length;
        int totalLength = HEADER_SIZE + dataLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // ----- HEADER -----
        buffer.putInt(p.seq);          // 4 octets
        buffer.putInt(p.ack);          // 4 octets
        buffer.put(p.flags);           // 1 octet
        buffer.putShort((short) dataLength); // 2 octets

        // ----- DATA -----
        if (dataLength > 0) {
            buffer.put(p.data);
        }

        return buffer.array();
    }

    public static Packet decode(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        buffer.order(ByteOrder.BIG_ENDIAN);

        Packet p = new Packet();

        // ----- HEADER -----
        p.seq = buffer.getInt();
        p.ack = buffer.getInt();
        p.flags = buffer.get();
        int dataLength = Short.toUnsignedInt(buffer.getShort());

        // ----- DATA -----
        if (dataLength > 0) {
            p.data = new byte[dataLength];
            buffer.get(p.data);
        } else {
            p.data = new byte[0];
        }

        return p;
    }
}