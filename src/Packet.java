public class Packet {
    static final byte FLAG_SYN = 0x01;
    static final byte FLAG_ACK = 0x02;
    static final byte FLAG_FIN = 0x04;
    static final byte FLAG_RST = 0x08;
    int seq;
    byte flags;
    byte[] data;
}
