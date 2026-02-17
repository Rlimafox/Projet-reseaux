import java.net.*;

import java.nio.file.*;

import java.util.*;



public class Sender {



    static final int MAX_DATA = 1400;

    static final int SEQ_MOD = 65536;



    // --- comparaison modulo correcte ---

    static boolean seqLess(int a, int b) {
        return a < b;
    }



    static void printWindow(String event, int cwnd, int ssthresh, int rwnd, int inFlight) {

        int effective = Math.min(cwnd, rwnd);

        System.out.println("[WINDOW] " + event +

                " | cwnd=" + cwnd +

                " | ssthresh=" + ssthresh +

                " | rwnd=" + rwnd +

                " | effective=" + effective +

                " | inFlight=" + inFlight);

    }



    public static void main(String[] args) throws Exception {



        String ip = args[0];

        int port = Integer.parseInt(args[1]);

        String filename = args[2];



        byte[] fileData = Files.readAllBytes(Path.of(filename));



        InetAddress addr = InetAddress.getByName(ip);

        DatagramSocket socket = new DatagramSocket();
        socket.setSendBufferSize(1 << 20);
        socket.setReceiveBufferSize(1 << 20);
        socket.setSoTimeout(500);



        int cwnd = 1;

        int ssthresh = 32;

        int rwnd = 32;



        int lastAck = -1;

        int dupAckCount = 0;
        int ackPrintCounter = 0;



        // packets en vol : seq -> raw

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



        Packet synAck = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));



        int nextSeq = baseSeq + 1;

        int offset = 0;



        System.out.println("Connexion établie");



        // ===== BOUCLE PRINCIPALE =====

        while (offset < fileData.length || !inFlight.isEmpty()) {



            int window = Math.min(cwnd, rwnd);



            // zero-window probe

            if (rwnd == 0 && !inFlight.isEmpty()) {

                int first = inFlight.firstKey();

                socket.send(new DatagramPacket(

                        inFlight.get(first),

                        inFlight.get(first).length,

                        addr, port));

                System.out.println("[PROBE] seq=" + first);

            }



            // ENVOI des paquets dans la fenêtre

            while (offset < fileData.length && inFlight.size() < window) {



                int size = Math.min(MAX_DATA, fileData.length - offset);

                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);



                Packet p = new Packet();

                p.seq = nextSeq;

                p.data = chunk;



                byte[] raw = PacketEncoder.encode(p);

                socket.send(new DatagramPacket(raw, raw.length, addr, port));



                inFlight.put(nextSeq, raw);



                offset += size;

                nextSeq = nextSeq + 1;

            }



            // RÉCEPTION ACKS

            try {

                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);

                socket.receive(dpAck);



                Packet ack = PacketEncoder.decode(Arrays.copyOf(dpAck.getData(), dpAck.getLength()));



                if ((ack.flags & Packet.FLAG_ACK) == 0)
                    continue;

                if (PacketEncoder.computeChecksum(ack) != ack.checksum)
                    continue;



                int ackSeq = ack.ack;

                rwnd = ack.data[0] & 0xFF;



                if (ackSeq == lastAck)

                    dupAckCount++;

                else

                    dupAckCount = 0;



                lastAck = ackSeq;



                // --- suppression des paquets confirmés ---

                int removed = 0;

                Iterator<Integer> it = inFlight.keySet().iterator();

                while (it.hasNext()) {

                    int seq = it.next();

                    if (seqLess(seq, ackSeq)) {

                        it.remove();

                        removed++;    // <<< FIX CRITIQUE

                    }

                }



                // --- contrôle de congestion ---

                if (dupAckCount == 3) {

                    // simple fast recovery

                    ssthresh = Math.max(2, cwnd / 2);

                    cwnd = ssthresh;

                }

                else if (removed > 0) {

                    if (cwnd < ssthresh)

                        cwnd += removed; // slow start

                    else

                        cwnd += 1;       // congestion avoidance

                }



                ackPrintCounter++;
                if (ackPrintCounter % 50 == 0)
                    printWindow("ACK", cwnd, ssthresh, rwnd, inFlight.size());



            } catch (SocketTimeoutException e) {



                // --- TIMEOUT ---

                ssthresh = Math.max(2, cwnd / 2);

                cwnd = 1;

                dupAckCount = 0;



                printWindow("TIMEOUT", cwnd, ssthresh, rwnd, inFlight.size());



                for (byte[] raw : inFlight.values()) {
                    socket.send(new DatagramPacket(
                            raw,
                            raw.length,
                            addr, port));
                }

            }

        }



        // ===== FIN =====
        Packet fin = new Packet();
        fin.seq = nextSeq;
        fin.flags = Packet.FLAG_FIN;
        fin.data = new byte[0];
        byte[] finRaw = PacketEncoder.encode(fin);

        while (true) {
            socket.send(new DatagramPacket(finRaw, finRaw.length, addr, port));
            try {
                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);
                Packet ack = PacketEncoder.decode(Arrays.copyOf(dpAck.getData(), dpAck.getLength()));
                if ((ack.flags & Packet.FLAG_ACK) == 0)
                    continue;
                if (PacketEncoder.computeChecksum(ack) != ack.checksum)
                    continue;
                if (ack.ack == fin.seq + 1)
                    break;
            } catch (SocketTimeoutException e) {
                // retransmit FIN
            }
        }

        socket.close();

        System.out.println("Transfert terminé");

    }

}
