public class Packet {
    public int length;
    public int seq;

    public boolean syn;
    public boolean ack;
    public boolean fin;
    public boolean rst;

    public byte[] data;
}
