import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Sender {

    static final int MAX_DATA = 1024;
    static final int SEQ_MOD = 65536;

    public static void main(String[] args) throws Exception {

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];

        byte[] fileData = Files.readAllBytes(Path.of(filename));

        InetAddress addr = InetAddress.getByName(ip);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(500);

        int cwnd = 1;
        int ssthresh = 32;
        int rwnd = 32;

        int lastAck = -1;
        int dupAckCount = 0;

        TreeMap<Integer, byte[]> inFlight = new TreeMap<>();

        byte[] buffer = new byte[2048];

        // ===== HANDSHAKE =====
        int baseSeq = new Random().nextInt(SEQ_MOD);

        Packet syn = new Packet();
        syn.seq = baseSeq;
        syn.flags = Packet.FLAG_SYN;
        syn.data = new byte[0];

        byte[] synRaw = PacketEncoder.encode(syn);
        socket.send(new DatagramPacket(synRaw, synRaw.length, addr, port));

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);

        Packet synAck = PacketEncoder.decode(dp.getData());

        // 🔧 CORRECTION : premier seq = baseSeq + 1
        int nextSeq = (baseSeq + 1) % SEQ_MOD;
        int offset = 0;

        System.out.println("Connexion établie, first data seq=" + nextSeq);

        // ===== TRANSMISSION =====
        while (offset < fileData.length || !inFlight.isEmpty()) {

            int window = Math.min(cwnd, rwnd);

            while (offset < fileData.length && inFlight.size() < window) {

                int size = Math.min(MAX_DATA, fileData.length - offset);
                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);

                Packet p = new Packet();
                p.seq = nextSeq;
                p.data = chunk;

                byte[] raw = PacketEncoder.encode(p);
                socket.send(new DatagramPacket(raw, raw.length, addr, port));

                inFlight.put(nextSeq, raw);

                System.out.println("[SEND] seq=" + nextSeq + " cwnd=" + cwnd);

                offset += size;
                nextSeq = (nextSeq + 1) % SEQ_MOD;
            }

            try {
                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);

                Packet ack = PacketEncoder.decode(dpAck.getData());

                if ((ack.flags & Packet.FLAG_ACK) == 0) continue;

                int ackSeq = ack.ack;
                rwnd = ack.data[0] & 0xFF;

                if (ackSeq == lastAck)
                    dupAckCount++;
                else
                    dupAckCount = 0;

                lastAck = ackSeq;

                // 🔧 suppression cumulative simple
                Iterator<Integer> it = inFlight.keySet().iterator();
                while (it.hasNext()) {
                    int seq = it.next();
                    if (seq == ackSeq)
                        it.remove();
                }

                // 🔧 congestion control simple
                if (dupAckCount == 3) {
                    ssthresh = Math.max(2, cwnd / 2);
                    cwnd = ssthresh;
                } else {
                    if (cwnd < ssthresh)
                        cwnd *= 2;
                    else
                        cwnd++;
                }

            } catch (SocketTimeoutException e) {

                System.out.println("[TIMEOUT]");

                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                dupAckCount = 0;

                if (!inFlight.isEmpty()) {
                    int first = inFlight.firstKey();
                    socket.send(new DatagramPacket(
                            inFlight.get(first),
                            inFlight.get(first).length,
                            addr,
                            port));
                }
            }
        }

        socket.close();
        System.out.println("Transfert terminé");
    }
}
