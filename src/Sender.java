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
        socket.setSoTimeout(800);

        int baseSeq = new Random().nextInt(SEQ_MOD);
        int nextSeq = (baseSeq + 1) % SEQ_MOD;

        int cwnd = 1;
        int ssthresh = 32;
        int rwnd = 32;

        int lastAck = -1;
        int dupAckCount = 0;

        Map<Integer, byte[]> inFlight = new HashMap<>();

        int offset = 0;
        byte[] buffer = new byte[2048];

        /* ===== HANDSHAKE ===== */
        Packet syn = new Packet();
        syn.seq = baseSeq;
        syn.flags = Packet.FLAG_SYN;
        syn.data = new byte[0];

        socket.send(new DatagramPacket(
                PacketEncoder.encode(syn),
                PacketEncoder.encode(syn).length,
                addr, port));

        socket.receive(new DatagramPacket(buffer, buffer.length));
        System.out.println("Connexion établie");

        /* ===== MAIN LOOP ===== */
        while (offset < fileData.length || !inFlight.isEmpty()) {

            int win = Math.min(cwnd, rwnd);

            /* SEND */
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
                if ((ack.flags & Packet.FLAG_ACK) == 0) continue;

                int ackSeq = ack.ack;
                rwnd = ack.data[0] & 0xFF;

                /* DUP ACK */
                if (ackSeq == lastAck) dupAckCount++;
                else dupAckCount = 0;

                lastAck = ackSeq;

                /* FAST RETRANSMIT */
                if (dupAckCount >= 3) {

                    ssthresh = Math.max(2, cwnd / 2);
                    cwnd = ssthresh;

                    Integer missing = firstGreater(inFlight, ackSeq);

                    if (missing != null) {
                        retransmit(socket, addr, port, inFlight, missing);
                        System.out.println("[FAST RETRANSMIT] seq=" + missing);
                    }
                }

                boolean newAck = removeAcked(inFlight, ackSeq);

                if (newAck) {
                    if (cwnd < ssthresh) cwnd *= 2;
                    else cwnd++;
                }

            } catch (SocketTimeoutException e) {

                System.out.println("[TIMEOUT]");

                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                dupAckCount = 0;

                Integer missing = firstGreater(inFlight, lastAck);

                if (missing != null) {
                    retransmit(socket, addr, port, inFlight, missing);
                    System.out.println("[RETRANSMIT TIMEOUT] seq=" + missing);
                }
            }
        }

        socket.close();
        System.out.println("Transfert terminé");
    }

    /* ===== CORE LOGIC ===== */

    static boolean removeAcked(Map<Integer, byte[]> inFlight, int ackSeq) {

        boolean removed = false;

        Iterator<Integer> it = inFlight.keySet().iterator();

        while (it.hasNext()) {
            int seq = it.next();

            if (seq <= ackSeq) {
                it.remove();
                removed = true;
            }
        }

        return removed;
    }

    static Integer firstGreater(Map<Integer, byte[]> inFlight, int ackSeq) {

        Integer best = null;

        for (int seq : inFlight.keySet()) {

            if (seq > ackSeq) {

                if (best == null || seq < best)
                    best = seq;
            }
        }

        return best;
    }

    static void retransmit(DatagramSocket socket, InetAddress addr, int port,
                           Map<Integer, byte[]> inFlight, int seq) throws Exception {

        byte[] data = inFlight.get(seq);

        if (data != null)
            socket.send(new DatagramPacket(data, data.length, addr, port));
    }
}
