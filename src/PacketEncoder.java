import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

public class PacketEncoder {

    // Header :
    // seq (4) | ack (4) | flags (1) | len (2) | checksum (4) = 15 octets
    private static final int HEADER_SIZE = 15;

    public static byte[] encode(Packet p) {
        int dataLength = (p.data == null) ? 0 : p.data.length;
        int totalLength = HEADER_SIZE + dataLength;
        short len = (short) dataLength;
        int checksum = computeChecksum(p.seq, p.ack, p.flags, len, p.data);
        p.checksum = checksum;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // ----- HEADER -----
        buffer.putInt(p.seq);          // 4 octets
        buffer.putInt(p.ack);          // 4 octets
        buffer.put(p.flags);           // 1 octet
        buffer.putShort(len); // 2 octets
        buffer.putInt(checksum);       // 4 octets

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
        p.checksum = buffer.getInt();

        // ----- DATA -----
        if (dataLength > 0) {
            p.data = new byte[dataLength];
            buffer.get(p.data);
        } else {
            p.data = new byte[0];
        }

        return p;
    }

    public static int computeChecksum(Packet p) {
        short len = (short) ((p.data == null) ? 0 : p.data.length);
        return computeChecksum(p.seq, p.ack, p.flags, len, p.data);
    }

    private static int computeChecksum(int seq, int ack, byte flags, short len, byte[] data) {
        CRC32 crc = new CRC32();
        ByteBuffer header = ByteBuffer.allocate(11);
        header.order(ByteOrder.BIG_ENDIAN);
        header.putInt(seq);
        header.putInt(ack);
        header.put(flags);
        header.putShort(len);
        crc.update(header.array());
        if (data != null && data.length > 0) {
            crc.update(data);
        }
        return (int) crc.getValue();
    }
}
