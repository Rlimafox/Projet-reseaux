public class TestPacket {
    public static void main(String[] args) {
        Packet p1 = new Packet();
        p1.seq = 123;
        p1.syn = true;
        p1.data = "Bonjour".getBytes();

        byte[] raw = PacketEncoder.encode(p1);
        Packet p2 = PacketEncoder.decode(raw);

        System.out.println(p2.seq);
        System.out.println(p2.syn);
        System.out.println(new String(p2.data));
    }
}
