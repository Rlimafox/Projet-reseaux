public class Packet {
    int seq;
    int ack;
    byte flags;
    byte[] data;

    static final byte FLAG_SYN = 0x01;
    static final byte FLAG_ACK = 0x02;
    static final byte FLAG_FIN = 0x04;
}
