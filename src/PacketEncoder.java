import java.nio.*;

public class PacketEncoder {

    public static byte[] encode(Packet p) {
        int dataLength = (p.data == null) ? 0 : p.data.length;
        int totalLength = 5 + dataLength; // 2 bytes length + 2 bytes seq + 1 byte flags + data

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // --- header ---
        buffer.putShort((short) totalLength);
        buffer.putShort((short) p.seq);

        // --- flags sur 4 bits bas ---
        byte flags = 0;
        if (p.syn) flags |= 0b1000;
        if (p.ack) flags |= 0b0100;
        if (p.fin) flags |= 0b0010;
        if (p.rst) flags |= 0b0001;
        buffer.put(flags);

        // --- data ---
        if (p.data != null)
            buffer.put(p.data);

        return buffer.array();
    }

    public static Packet decode(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        buffer.order(ByteOrder.BIG_ENDIAN);

        Packet p = new Packet();

        // --- header ---
        p.length = Short.toUnsignedInt(buffer.getShort());
        p.seq = Short.toUnsignedInt(buffer.getShort());

        byte flags = buffer.get();
        p.syn = (flags & 0b1000) != 0;
        p.ack = (flags & 0b0100) != 0;
        p.fin = (flags & 0b0010) != 0;
        p.rst = (flags & 0b0001) != 0;

        // --- data ---
        int dataLength = p.length - 5;
        if (dataLength > 0) {
            p.data = new byte[dataLength];
            buffer.get(p.data);
        } else {
            p.data = new byte[0];
        }

        return p;
    }
}
