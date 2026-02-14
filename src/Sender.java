import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Sender {

    static final int MAX_DATA = 1024;

    public static void main(String[] args) throws Exception {

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];

        byte[] fileData = Files.readAllBytes(Path.of(filename));

        InetAddress addr = InetAddress.getByName(ip);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(500);

        int baseSeq = new Random().nextInt(65536);
        int nextSeq = (baseSeq + 1) % 65536;

        int cwnd = 1;
        int ssthresh = 32;
        int rwnd = 32;

        int lastAck = -1;
        int dupAckCount = 0;

        TreeMap<Integer, byte[]> inFlight = new TreeMap<>();
        int offset = 0;
        byte[] buffer = new byte[2048];

        // ===== HANDSHAKE =====
        Packet syn = new Packet();
        syn.seq = baseSeq;
        syn.flags = Packet.FLAG_SYN;
        syn.data = new byte[0];
        byte[] synRaw = PacketEncoder.encode(syn);
        socket.send(new DatagramPacket(synRaw, synRaw.length, addr, port));

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);
        System.out.println("Connexion établie");

        // ===== TRANSMISSION =====
        while (offset < fileData.length || !inFlight.isEmpty()) {

            int win = Math.min(cwnd, rwnd);

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
                nextSeq = (nextSeq + 1) % 65536;
            }

            try {
                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);
                Packet ack = PacketEncoder.decode(dpAck.getData());

                if ((ack.flags & Packet.FLAG_ACK) == 0) continue;

                int ackSeq = ack.ack;
                rwnd = ack.data[0] & 0xFF;

                if (ackSeq == lastAck) dupAckCount++;
                else dupAckCount = 0;

                lastAck = ackSeq;

                // Fast retransmit
                if (dupAckCount == 3) {
                    ssthresh = Math.max(2, cwnd / 2);
                    cwnd = ssthresh;

                    int missing = (ackSeq + 1) % 65536;
                    if (inFlight.containsKey(missing)) {
                        socket.send(new DatagramPacket(inFlight.get(missing), inFlight.get(missing).length, addr, port));
                        System.out.println("[FAST RETRANSMIT] seq=" + missing);
                    }
                }

                // Suppression cumulative sécurisée
                List<Integer> toRemove = new ArrayList<>();
                for (int s : inFlight.keySet()) {
                    int diff = (ackSeq - s + 65536) % 65536;
                    if (diff < 32768) toRemove.add(s);
                }
                for (int s : toRemove) inFlight.remove(s);

                if (!toRemove.isEmpty()) {
                    if (cwnd < ssthresh) cwnd *= 2;
                    else cwnd += 1;
                }

            } catch (SocketTimeoutException e) {
                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                dupAckCount = 0;

                if (!inFlight.isEmpty()) {
                    int s = inFlight.keySet().iterator().next();
                    socket.send(new DatagramPacket(inFlight.get(s), inFlight.get(s).length, addr, port));
                    System.out.println("[RETRANSMIT TIMEOUT] seq=" + s);
                }
            }
        }

        socket.close();
        System.out.println("Transfert terminé");
    }
}
