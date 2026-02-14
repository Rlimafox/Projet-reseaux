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
        socket.setSoTimeout(1000);

        int baseSeq = new Random().nextInt(65536);
        int nextSeq = (baseSeq + 1) % 65536;

        /* ===== TCP Reno Variables ===== */
        int cwnd = 1;
        int ssthresh = 32;
        int rwnd = 32;

        int lastAck = -1;
        int dupAckCount = 0;
        boolean inFastRecovery = false;

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

            /* ===== Zero Window Probe ===== */
            if (rwnd == 0) {
                System.out.println("[ZERO WINDOW] probing...");
                if (!inFlight.isEmpty()) {
                    int s = inFlight.keySet().iterator().next();
                    socket.send(new DatagramPacket(
                            inFlight.get(s),
                            inFlight.get(s).length,
                            addr,
                            port
                    ));
                }
                Thread.sleep(300);
                continue;
            }

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

                System.out.println("[SEND] seq=" + nextSeq +
                        " cwnd=" + cwnd +
                        " ssthresh=" + ssthresh +
                        " rwnd=" + rwnd);

                offset += size;
                nextSeq = (nextSeq + 1) % 65536;
            }

            try {

                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);

                Packet ack = PacketEncoder.decode(dpAck.getData());
                if ((ack.flags & Packet.FLAG_ACK) == 0)
                    continue;

                int ackSeq = ack.ack;
                rwnd = ack.data[0] & 0xFF;

                System.out.println("[ACK] ack=" + ackSeq +
                        " cwnd=" + cwnd +
                        " rwnd=" + rwnd);

                /* ===== DUPLICATE ACK detection ===== */
                if (ackSeq == lastAck) {
                    dupAckCount++;
                } else {
                    dupAckCount = 0;
                }

                lastAck = ackSeq;

                /* ===== FAST RETRANSMIT + FAST RECOVERY ===== */
                if (dupAckCount == 3) {

                    System.out.println("[FAST RETRANSMIT] seq=" + ((ackSeq + 1) % 65536));

                    ssthresh = Math.max(2, cwnd / 2);
                    cwnd = ssthresh + 3; // Fast Recovery
                    inFastRecovery = true;

                    int missingSeq = (ackSeq + 1) % 65536;

                    if (inFlight.containsKey(missingSeq)) {
                        socket.send(new DatagramPacket(
                                inFlight.get(missingSeq),
                                inFlight.get(missingSeq).length,
                                addr,
                                port
                        ));
                    }
                }

                boolean newAck = inFlight.containsKey(ackSeq);

                inFlight.keySet().removeIf(s ->
                        (s - ackSeq + 65536) % 65536 <= 0);

                /* ===== Congestion Control ===== */
                if (newAck) {

                    if (inFastRecovery) {
                        cwnd = ssthresh;
                        inFastRecovery = false;
                    } else {
                        if (cwnd < ssthresh) {
                            // Slow Start
                            cwnd *= 2;
                        } else {
                            // Congestion Avoidance
                            cwnd += 1;
                        }
                    }
                }

            } catch (SocketTimeoutException e) {

                System.out.println("[TIMEOUT] cwnd=" + cwnd);

                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                inFastRecovery = false;
                dupAckCount = 0;

                if (!inFlight.isEmpty()) {
                    int s = inFlight.keySet().iterator().next();
                    socket.send(new DatagramPacket(
                            inFlight.get(s),
                            inFlight.get(s).length,
                            addr,
                            port
                    ));
                    System.out.println("[RETX] seq=" + s);
                }
            }
        }

        /* ===== FIN ===== */
        Packet fin = new Packet();
        fin.seq = nextSeq;
        fin.flags = Packet.FLAG_FIN;
        fin.data = new byte[0];

        byte[] finRaw = PacketEncoder.encode(fin);
        socket.send(new DatagramPacket(finRaw, finRaw.length, addr, port));

        socket.close();
        System.out.println("Transfert terminé");
    }
}
