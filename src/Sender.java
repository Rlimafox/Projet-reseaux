import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Sender {

    static final int MAX_DATA = 1024;
    static final int SEQ_MOD = 65536;
    static final int HALF_SEQ = 32768;

    public static void main(String[] args) throws Exception {

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];

        byte[] fileData = Files.readAllBytes(Path.of(filename));

        InetAddress addr = InetAddress.getByName(ip);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(800);

        int baseSeq = new Random().nextInt(SEQ_MOD);
        int nextSeq = (baseSeq + 1) % SEQ_MOD;

        int cwnd = 1;
        int ssthresh = 32;
        int rwnd = 32;

        int lastAck = -1;
        int dupAckCount = 0;

        Map<Integer, byte[]> inFlight = new TreeMap<>();
        int offset = 0;
        byte[] buffer = new byte[2048];

        /* ===== HANDSHAKE ===== */
        Packet syn = new Packet();
        syn.seq = baseSeq;
        syn.flags = Packet.FLAG_SYN;
        syn.data = new byte[0];

        byte[] synRaw = PacketEncoder.encode(syn);
        socket.send(new DatagramPacket(synRaw, synRaw.length, addr, port));

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);

        System.out.println("Connexion établie");

        /* ===== TRANSFERT ===== */
        while (offset < fileData.length || !inFlight.isEmpty()) {

            int win = Math.min(cwnd, rwnd);

            /* ===== ENVOI ===== */
            while (offset < fileData.length && inFlight.size() < win) {

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
                if ((ack.flags & Packet.FLAG_ACK) == 0)
                    continue;

                int ackSeq = ack.ack;
                rwnd = ack.data[0] & 0xFF;

                /* ===== DUP ACK DETECTION ===== */
                if (ackSeq == lastAck) {
                    dupAckCount++;
                } else {
                    dupAckCount = 0;
                }
                lastAck = ackSeq;

                /* ===== FAST RETRANSMIT ===== */
                if (dupAckCount >= 3) {

                    ssthresh = Math.max(2, cwnd / 2);
                    cwnd = ssthresh;

                    int missing = (ackSeq + 1) % SEQ_MOD;
                    byte[] lost = inFlight.get(missing);

                    if (lost != null) {
                        socket.send(new DatagramPacket(
                                lost,
                                lost.length,
                                addr,
                                port
                        ));
                        System.out.println("[FAST RETRANSMIT] seq=" + missing);
                    }
                }

                /* ===== REMOVE ACKED ===== */
                boolean newAck = hasNewAck(inFlight, ackSeq);
                removeAcked(inFlight, ackSeq);

                /* ===== CONGESTION CONTROL ===== */
                if (newAck) {
                    if (cwnd < ssthresh) {
                        cwnd *= 2; // Slow Start
                    } else {
                        cwnd += 1; // Congestion Avoidance
                    }
                }

            } catch (SocketTimeoutException e) {

                System.out.println("[TIMEOUT]");

                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                dupAckCount = 0;

                /* ===== WINDOW PROBE ===== */
                if (rwnd == 0 && !inFlight.isEmpty()) {

                    int probe = inFlight.keySet().iterator().next();

                    socket.send(new DatagramPacket(
                            inFlight.get(probe),
                            inFlight.get(probe).length,
                            addr,
                            port
                    ));

                    System.out.println("[WINDOW PROBE] seq=" + probe);
                }

                /* ===== RETRANSMIT OLDEST ===== */
                else if (!inFlight.isEmpty()) {

                    int s = inFlight.keySet().iterator().next();

                    socket.send(new DatagramPacket(
                            inFlight.get(s),
                            inFlight.get(s).length,
                            addr,
                            port
                    ));

                    System.out.println("[RETRANSMIT TIMEOUT] seq=" + s);
                }
            }
        }

        socket.close();
        System.out.println("Transfert terminé");
    }

    /* ===== UTILS ===== */

    // a <= b en espace circulaire 16 bits
    static boolean seqLE(int a, int b) {
        return ((b - a + SEQ_MOD) % SEQ_MOD) < HALF_SEQ;
    }

    static void removeAcked(Map<Integer, byte[]> inFlight, int ackSeq) {
        Iterator<Integer> it = inFlight.keySet().iterator();

        while (it.hasNext()) {
            int seq = it.next();
            if (seqLE(seq, ackSeq)) {
                it.remove();
            }
        }
    }

    static boolean hasNewAck(Map<Integer, byte[]> inFlight, int ackSeq) {
        for (int seq : inFlight.keySet()) {
            if (seqLE(seq, ackSeq)) {
                return true;
            }
        }
        return false;
    }
}
