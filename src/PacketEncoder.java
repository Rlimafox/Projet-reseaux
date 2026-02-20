import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PacketEncoder {

    // Header (format protocole):
    // data_length (2) | seq (2) | flags (4 bits + 4 bits padding) = 5 octets
    private static final int HEADER_SIZE = 5;

    public static byte[] encode(Packet p) {
        int dataLength = (p.data == null) ? 0 : p.data.length;
        if (dataLength > 0xFFFF) {
            throw new IllegalArgumentException("Payload too large for 16-bit length: " + dataLength);
        }
        int totalLength = HEADER_SIZE + dataLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // ----- HEADER -----
        buffer.putShort((short) dataLength);         // 2 octets
        buffer.putShort((short) (p.seq & 0xFFFF));   // 2 octets
        buffer.put((byte) (p.flags & 0x0F));         // 4 bits utiles + padding

        // ----- DATA -----
        if (dataLength > 0) {
            buffer.put(p.data);
        }

        return buffer.array();
    }

    public static Packet decode(byte[] raw) {
        if (raw.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Packet too short: " + raw.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        buffer.order(ByteOrder.BIG_ENDIAN);

        Packet p = new Packet();

        // ----- HEADER -----
        int dataLength = Short.toUnsignedInt(buffer.getShort());
        p.seq = Short.toUnsignedInt(buffer.getShort());
        p.flags = (byte) (buffer.get() & 0x0F);

        if (raw.length != HEADER_SIZE + dataLength) {
            throw new IllegalArgumentException(
                    "Packet length mismatch. expected=" + (HEADER_SIZE + dataLength) + ", actual=" + raw.length
            );
        }

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
        // Le sujet précise que la corruption est couverte par le checksum UDP.
        return 0;
    }

    public static int computeChecksum(int seq, int ack, byte flags, short len, byte[] data) {
        // Signature conservée pour compatibilité avec le code existant.
        return 0;
    }
}
