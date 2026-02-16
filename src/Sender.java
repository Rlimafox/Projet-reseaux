import java.net.*;

import java.nio.file.*;

import java.util.*;



public class Sender {



    static final int MAX_DATA = 1024;

    static final int SEQ_MOD = 65536;



    // --- CONFIG de la rafale Go-Back-N ---

    // Nombre max de segments à retransmettre d'affilée lors d'une perte.

    static final int MAX_RETX_BURST = 8;



    /****************** Utils séquences ******************/

    static boolean seqLess(int a, int b) {

        return ((b - a + SEQ_MOD) % SEQ_MOD) < (SEQ_MOD / 2);

    }



    static int distFromBase(int base, int seq) {

        return (seq - base + SEQ_MOD) % SEQ_MOD;

    }



    static int findOldestRelativeToBase(Set<Integer> keys, int base) {

        int best = -1, bestDist = SEQ_MOD;

        for (int k : keys) {

            int d = distFromBase(base, k);

            if (d > 0 && d < bestDist) {

                bestDist = d;

                best = k;

            }

        }

        return best;

    }



    static void retransmitIfPresent(DatagramSocket socket, InetAddress addr, int port,

                                    Map<Integer, byte[]> inFlight, int seq) throws Exception {

        byte[] raw = inFlight.get(seq);

        if (raw != null) {

            socket.send(new DatagramPacket(raw, raw.length, addr, port));

            System.out.println("[RETX] seq=" + seq);

        }

    }



    /*** 🔥 Retransmission en rafale depuis 'base' (lastAck) : Go-Back-N limité ***/

    static void retransmitBurstFrom(DatagramSocket socket, InetAddress addr, int port,

                                    Map<Integer, byte[]> inFlight, int base, int maxCount) throws Exception {

        // On parcourt les clés de inFlight dans l'ordre "modulo" à partir de 'base'

        // et on tente d'en renvoyer jusqu'à maxCount.

        if (inFlight.isEmpty()) return;



        // 1) Construire une liste triée par distance modulo depuis 'base'

        List<Integer> keys = new ArrayList<>(inFlight.keySet());

        keys.sort(Comparator.comparingInt(k -> distFromBase(base, k)));



        int sent = 0;

        for (int k : keys) {

            int d = distFromBase(base, k);

            if (d <= 0) continue;               // on ne renvoie pas les anciens déjà ACKés

            retransmitIfPresent(socket, addr, port, inFlight, k);

            sent++;

            if (sent >= maxCount) break;

        }

        if (sent > 0) {

            System.out.println("[RETX-BURST] from=" + base + " count=" + sent);

        }

    }



    /****************** Logs fenêtre ******************/

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



        Packet synAck = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));



        int nextSeq = (baseSeq + 1) % SEQ_MOD;

        int offset = 0;



        System.out.println("Connexion établie");



        // ===== BOUCLE =====

        while (offset < fileData.length || !inFlight.isEmpty()) {



            int window = Math.min(cwnd, rwnd);



            // Zero-window probe : cibler le prochain attendu

            if (rwnd == 0 && !inFlight.isEmpty()) {

                int target = inFlight.containsKey(lastAck) ? lastAck

                        : findOldestRelativeToBase(inFlight.keySet(), lastAck);

                if (target != -1) {

                    retransmitIfPresent(socket, addr, port, inFlight, target);

                    System.out.println("[PROBE] seq=" + target);

                }

            }



            // Envoi tant qu'il reste de la place dans la fenêtre effective

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

                nextSeq = (nextSeq + 1) % SEQ_MOD;

            }



            try {

                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);

                socket.receive(dpAck);



                Packet ack = PacketEncoder.decode(Arrays.copyOf(dpAck.getData(), dpAck.getLength()));

                if ((ack.flags & Packet.FLAG_ACK) == 0) continue;



                int ackSeq = ack.ack;

                if (ack.data != null && ack.data.length > 0)

                    rwnd = ack.data[0] & 0xFF;



                if (ackSeq == lastAck) dupAckCount++; else dupAckCount = 0;

                lastAck = ackSeq;



                // Nettoyage inFlight (ACK cumulatif)

                int removed = 0;

                Iterator<Integer> it = inFlight.keySet().iterator();

                while (it.hasNext()) {

                    int seq = it.next();

                    if (seqLess(seq, ackSeq)) {

                        it.remove();

                        removed++;

                    }

                }



                // Fast retransmit: 3 dupACK → rafale Go-Back-N

                if (dupAckCount == 3) {

                    int target = inFlight.containsKey(lastAck) ? lastAck

                            : findOldestRelativeToBase(inFlight.keySet(), lastAck);

                    if (target != -1) {

                        // on renvoie d'abord le "next expected"

                        retransmitIfPresent(socket, addr, port, inFlight, target);

                        // 🔥 puis on renvoie quelques suivants (car le RX a jeté tout l'hors-ordre)

                        retransmitBurstFrom(socket, addr, port, inFlight, lastAck, MAX_RETX_BURST - 1);

                    }

                    ssthresh = Math.max(2, cwnd / 2);

                    cwnd = ssthresh;

                }

                // Evolution de cwnd

                else if (removed > 0) {

                    if (cwnd < ssthresh) cwnd += removed; else cwnd += 1;

                }



                printWindow("ACK", cwnd, ssthresh, rwnd, inFlight.size());

            }

            catch (SocketTimeoutException e) {

                // TIMEOUT → cwnd reset + rafale Go-Back-N

                ssthresh = Math.max(2, cwnd / 2);

                cwnd = 1;

                dupAckCount = 0;



                printWindow("TIMEOUT", cwnd, ssthresh, rwnd, inFlight.size());



                if (!inFlight.isEmpty()) {

                    int target = inFlight.containsKey(lastAck) ? lastAck

                            : findOldestRelativeToBase(inFlight.keySet(), lastAck);

                    if (target != -1) {

                        // idem : renvoyer le "next expected" puis une petite rafale derrière

                        retransmitIfPresent(socket, addr, port, inFlight, target);

                        retransmitBurstFrom(socket, addr, port, inFlight, lastAck, MAX_RETX_BURST - 1);

                    }

                }

            }

        }



        socket.close();

        System.out.println("Transfert terminé");

    }

}