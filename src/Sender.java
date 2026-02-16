import java.net.*;

import java.nio.file.*;

import java.util.*;



/**

 * Sender avancé :

 *  - cwnd/ssthresh en OCTETS (byte-based congestion control)

 *  - RTO adaptatif (SRTT/RTTVAR) + Karn

 *  - SACK : retransmission ciblée des trous

 *  - Fenêtre effective en OCTETS = min(cwndBytes, rwndBytes)

 *  - Pacing léger

 *  - Gestion wrap modulo 65536 pour les numéros de séquence

 */

public class Sender {



    // ===== Paramètres =====

    static final int MAX_DATA = 1400;      // MSS ~1400B (évite fragmentation sur MTU 1500)

    static final int SEQ_MOD  = 65536;     // seq 16 bits

    static final int INIT_CWND_BYTES = MAX_DATA;        // 1 MSS

    static final int INIT_SSTHRESH_BYTES = 64 * 1024;   // seuil de départ (~64KB)

    static final int MAX_RETX_BURST = 8;                // sécurité (rarement utilisé avec SACK)

    static final int PACE_EVERY = 64;                   // pacing léger : pause après N envois

    static final int PACE_SLEEP_MS = 1;



    // ===== RTO / RTT (RFC6298-like) =====

    static final int RTO_MIN_MS = 200;

    static final int RTO_MAX_MS = 3000;

    static final double ALPHA = 1.0 / 8.0;

    static final double BETA  = 1.0 / 4.0;



    // ===== Séquence utils (modulo) =====

    static boolean seqLess(int a, int b) {

        return ((b - a + SEQ_MOD) % SEQ_MOD) < (SEQ_MOD / 2);

    }

    static boolean seqLE(int a, int b) { return a == b || seqLess(a, b); }

    static boolean seqGreater(int a, int b) { return seqLess(b, a); }

    static boolean seqGE(int a, int b) { return a == b || seqGreater(a, b); }

    static int seqNext(int s) { return (s + 1) % SEQ_MOD; }



    // ===== Segment en vol =====

    static class Segment {

        final int seq;

        final byte[] raw;

        final int len;

        long sendTimeMs;

        boolean retransmitted;



        Segment(int seq, byte[] raw, int len, long sendTimeMs) {

            this.seq = seq; this.raw = raw; this.len = len;

            this.sendTimeMs = sendTimeMs; this.retransmitted = false;

        }

    }



    // ===== Impression fenêtre =====

    static void printWindow(String event, long cwndBytes, long ssthreshBytes, int rwndBytes, int inFlightSegs, int inFlightBytes) {

        long effective = Math.min(cwndBytes, (long)rwndBytes);

        System.out.println("[WINDOW] " + event +

                " | cwndB=" + cwndBytes +

                " | ssthreshB=" + ssthreshBytes +

                " | rwndB=" + rwndBytes +

                " | effB=" + effective +

                " | inFlightSegs=" + inFlightSegs +

                " | inFlightBytes=" + inFlightBytes);

    }



    // ===== Parse SACK blocks: ack.data = [rwndHi, rwndLo, (sHi,sLo,eHi,eLo)*] =====

    static class AckInfo {

        final int rwndBytes;

        final int ackSeq; // cumulatif (next expected)

        final List<int[]> sackBlocks; // [start,end] en seq 16 bits



        AckInfo(int ackSeq, int rwndBytes, List<int[]> sacks) {

            this.ackSeq = ackSeq; this.rwndBytes = rwndBytes; this.sackBlocks = sacks;

        }

    }



    static AckInfo parseAck(Packet ack) {

        int ackSeq = ack.ack;

        int rwndBytes = 0;

        List<int[]> sacks = new ArrayList<>();

        if (ack.data != null && ack.data.length >= 2) {

            rwndBytes = ((ack.data[0] & 0xFF) << 8) | (ack.data[1] & 0xFF);

            for (int i = 2; i + 3 < ack.data.length; i += 4) {

                int s = ((ack.data[i] & 0xFF) << 8) | (ack.data[i+1] & 0xFF);

                int e = ((ack.data[i+2] & 0xFF) << 8) | (ack.data[i+3] & 0xFF);

                sacks.add(new int[]{s, e});

            }

        }

        return new AckInfo(ackSeq, rwndBytes, sacks);

    }



    // sequence in [start..end] modulo (start/end d’un bloc SACK)

    static boolean seqInRange(int s, int start, int end) {

        if (start == end) return s == start;

        if (((end - start + SEQ_MOD) % SEQ_MOD) < (SEQ_MOD / 2)) {

            // intervalle "normal" (pas de wrap)

            return seqGE(s, start) && seqLE(s, end);

        } else {

            // intervalle qui wrap

            return seqGE(s, start) || seqLE(s, end);

        }

    }



    public static void main(String[] args) throws Exception {

        String ip = args[0];

        int port = Integer.parseInt(args[1]);

        String filename = args[2];



        byte[] fileData = Files.readAllBytes(Path.of(filename));



        InetAddress addr = InetAddress.getByName(ip);

        DatagramSocket socket = new DatagramSocket();

        socket.setSoTimeout(500);

        socket.setReceiveBufferSize(4 * 1024 * 1024);

        socket.setSendBufferSize(4 * 1024 * 1024);



        // ===== Contrôle de congestion (byte-based) =====

        long cwndBytes = INIT_CWND_BYTES;

        long ssthreshBytes = INIT_SSTHRESH_BYTES;



        // Fenêtre de réception annoncée (octets)

        int rwndBytes = 64 * 1024;



        // RTT/RTO

        boolean rttInit = false;

        double srtt = 0.0, rttvar = 0.0;

        int RTOms = 500;



        // DUPACK

        int lastCumAckSeq = -1;

        int dupAckCount = 0;

        boolean inFastRecovery = false;



        // In-flight : seq -> segment (TreeMap uniquement comme conteneur ; on utilise nos comparaisons modulo)

        final Map<Integer, Segment> inFlight = new HashMap<>();

        int inFlightBytes = 0;



        // Buffer pour réception d’ACKs

        byte[] rxBuf = new byte[2048];



        // ===== Handshake =====

        int baseSeq = new Random().nextInt(SEQ_MOD);



        Packet syn = new Packet();

        syn.seq = baseSeq;

        syn.flags = Packet.FLAG_SYN;

        syn.data = new byte[0];



        byte[] synRaw = PacketEncoder.encode(syn);

        socket.send(new DatagramPacket(synRaw, synRaw.length, addr, port));



        DatagramPacket dp = new DatagramPacket(rxBuf, rxBuf.length);

        socket.receive(dp);



        Packet synAck = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));

        AckInfo synAckInfo = parseAck(synAck);

        if (synAckInfo.rwndBytes > 0) rwndBytes = synAckInfo.rwndBytes;



        int nextSeq = (baseSeq + 1) % SEQ_MOD;

        int offset = 0;



        System.out.println("Connexion établie");



        long lastSendTs = System.currentTimeMillis();

        int sentSinceSleep = 0;



        while (offset < fileData.length || !inFlight.isEmpty()) {



            // ===== Fenêtre effective en OCTETS =====

            long windowBytes = Math.min(cwndBytes, (long) rwndBytes);



            // ===== Envoi tant qu'il reste de la place dans la fenêtre =====

            while (offset < fileData.length && (inFlightBytes + MAX_DATA) <= windowBytes) {



                int size = Math.min(MAX_DATA, fileData.length - offset);

                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);



                Packet p = new Packet();

                p.seq = nextSeq;

                p.data = chunk;



                byte[] raw = PacketEncoder.encode(p);

                long now = System.currentTimeMillis();

                socket.send(new DatagramPacket(raw, raw.length, addr, port));



                Segment seg = new Segment(nextSeq, raw, raw.length, now);

                inFlight.put(nextSeq, seg);

                inFlightBytes += seg.len;



                offset += size;

                nextSeq = seqNext(nextSeq);



                // pacing léger

                if (++sentSinceSleep >= PACE_EVERY) {

                    try { Thread.sleep(PACE_SLEEP_MS); } catch (InterruptedException ignored) {}

                    sentSinceSleep = 0;

                }

            }



            try {

                // Ajuste timeout dynamiquement

                socket.setSoTimeout(RTOms);



                DatagramPacket dpAck = new DatagramPacket(rxBuf, rxBuf.length);

                socket.receive(dpAck);



                Packet ack = PacketEncoder.decode(Arrays.copyOf(dpAck.getData(), dpAck.getLength()));

                if ((ack.flags & Packet.FLAG_ACK) == 0) continue;



                AckInfo info = parseAck(ack);

                int ackSeq = info.ackSeq;

                if (info.rwndBytes > 0) rwndBytes = info.rwndBytes;



                // DUPACK counting (basé sur ACK cumulatif)

                if (ackSeq == lastCumAckSeq) {

                    dupAckCount++;

                } else {

                    dupAckCount = 0;

                }

                lastCumAckSeq = ackSeq;



                // ===== Nettoyage par ACK cumulatif =====

                int ackedBytes = 0;

                List<Segment> freshlyAcked = new ArrayList<>();



                // Retire tout ce qui est strictement < ackSeq (modulo)

                List<Integer> toRemove = new ArrayList<>();

                for (Integer s : inFlight.keySet()) {

                    if (seqLess(s, ackSeq)) {

                        Segment seg = inFlight.get(s);

                        toRemove.add(s);

                        ackedBytes += seg.len;

                        freshlyAcked.add(seg);

                    }

                }

                for (Integer s : toRemove) {

                    inFlightBytes -= inFlight.get(s).len;

                    inFlight.remove(s);

                }



                // ===== Nettoyage par SACK =====

                if (!info.sackBlocks.isEmpty()) {

                    List<Integer> sackRemove = new ArrayList<>();

                    for (Map.Entry<Integer, Segment> e : inFlight.entrySet()) {

                        int s = e.getKey();

                        for (int[] blk : info.sackBlocks) {

                            if (seqInRange(s, blk[0], blk[1])) {

                                sackRemove.add(s);

                                ackedBytes += e.getValue().len;

                                freshlyAcked.add(e.getValue());

                                break;

                            }

                        }

                    }

                    for (Integer s : sackRemove) {

                        inFlightBytes -= inFlight.get(s).len;

                        inFlight.remove(s);

                    }

                }



                // ===== RTT sample (Karn) : seulement si segment NON retransmis =====

                long now = System.currentTimeMillis();

                for (Segment seg : freshlyAcked) {

                    if (!seg.retransmitted) {

                        int sample = (int) Math.max(1, now - seg.sendTimeMs);

                        if (!rttInit) {

                            srtt = sample;

                            rttvar = sample / 2.0;

                            rttInit = true;

                        } else {

                            double err = sample - srtt;

                            srtt   += ALPHA * err;

                            rttvar += BETA  * (Math.abs(err) - rttvar);

                        }

                        int rto = (int) (srtt + Math.max(10, 4 * rttvar));

                        RTOms = Math.max(RTO_MIN_MS, Math.min(RTO_MAX_MS, rto));

                        break; // un seul sample par ACK suffit

                    }

                }



                // ===== Fast retransmit / recovery =====

                if (dupAckCount >= 3 && inFlight.containsKey(ackSeq)) {

                    // Ssthresh = moitié, cwnd = ssthresh (+ optionnel 3*MSS), on reste simple

                    ssthreshBytes = Math.max(2 * MAX_DATA, (int)(cwndBytes / 2));

                    cwndBytes = ssthreshBytes;



                    // Retransmettre la perte présumée (ackSeq) puis quelques trous non SACKés proches

                    fastRetransmit(socket, addr, port, inFlight, ackSeq, info.sackBlocks);



                    dupAckCount = 0; // évite retriggers en boucle

                    inFastRecovery = true;

                } else {

                    inFastRecovery = false;

                }



                // ===== Évolution cwnd (AIMD byte-based) =====

                if (ackedBytes > 0) {

                    if (cwndBytes < ssthreshBytes) {

                        // Slow start : + ackedBytes

                        cwndBytes += ackedBytes;

                    } else {

                        // Congestion avoidance : + MSS^2 / cwnd (approximation par ACK)

                        long add = (long) Math.max(1, (MAX_DATA * (long)ackedBytes) / Math.max(MAX_DATA, cwndBytes));

                        cwndBytes += add;

                    }

                }



                printWindow("ACK", cwndBytes, ssthreshBytes, rwndBytes, inFlight.size(), inFlightBytes);



            } catch (SocketTimeoutException te) {

                // ===== TIMEOUT : RTO, cwnd reset, retx ciblée =====

                ssthreshBytes = Math.max(2 * MAX_DATA, (int)(cwndBytes / 2));

                cwndBytes = MAX_DATA; // 1 MSS

                dupAckCount = 0;

                inFastRecovery = false;



                printWindow("TIMEOUT", cwndBytes, ssthreshBytes, rwndBytes, inFlight.size(), inFlightBytes);



                // Retransmettre le "next expected" (lastCumAckSeq) + quelques trous non SACKés

                if (!inFlight.isEmpty() && lastCumAckSeq != -1) {

                    targetedRetransmitOnTimeout(socket, addr, port, inFlight, lastCumAckSeq);

                }

            }



            // ===== Zero-window probing ciblé =====

            if (rwndBytes == 0 && !inFlight.isEmpty() && lastCumAckSeq != -1) {

                Segment seg = inFlight.get(lastCumAckSeq);

                if (seg != null) {

                    seg.retransmitted = true;

                    seg.sendTimeMs = System.currentTimeMillis();

                    socket.send(new DatagramPacket(seg.raw, seg.raw.length, addr, port));

                    System.out.println("[PROBE] seq=" + seg.seq);

                }

            }

        }



        socket.close();

        System.out.println("Transfert terminé");

    }



    // ===== Fast retransmit ciblé avec SACK =====

    static void fastRetransmit(DatagramSocket socket, InetAddress addr, int port,

                               Map<Integer, Segment> inFlight, int ackSeq, List<int[]> sacks) throws Exception {



        // 1) retransmettre la perte présumée (ackSeq)

        Segment first = inFlight.get(ackSeq);

        int retxCount = 0;



        if (first != null) {

            first.retransmitted = true;

            first.sendTimeMs = System.currentTimeMillis();

            socket.send(new DatagramPacket(first.raw, first.raw.length, addr, port));

            System.out.println("[RETX] seq=" + first.seq);

            retxCount++;

        }



        // 2) retransmettre quelques trous non SACKés (à partir de ackSeq)

        // Construire la liste des séquences en vol en ordre croissant relatif à ackSeq

        List<Integer> keys = new ArrayList<>(inFlight.keySet());

        keys.sort(Comparator.comparingInt(k -> ((k - ackSeq + SEQ_MOD) % SEQ_MOD)));



        for (int s : keys) {

            if (s == ackSeq) continue; // déjà fait

            // si s est déjà SACKé, on saute

            if (isSacked(s, sacks)) continue;



            Segment seg = inFlight.get(s);

            if (seg == null) continue;



            seg.retransmitted = true;

            seg.sendTimeMs = System.currentTimeMillis();

            socket.send(new DatagramPacket(seg.raw, seg.raw.length, addr, port));

            System.out.println("[RETX] seq=" + seg.seq);



            if (++retxCount >= MAX_RETX_BURST) break;

        }



        if (retxCount > 1) {

            System.out.println("[RETX-BURST] de=" + ackSeq + " compte=" + retxCount);

        }

    }



    static boolean isSacked(int seq, List<int[]> sacks) {

        if (sacks == null || sacks.isEmpty()) return false;

        for (int[] blk : sacks) {

            if (seqInRange(seq, blk[0], blk[1])) return true;

        }

        return false;

    }



    // ===== Retransmission après TIMEOUT (ciblée autour du cumulatif) =====

    static void targetedRetransmitOnTimeout(DatagramSocket socket, InetAddress addr, int port,

                                            Map<Integer, Segment> inFlight, int baseSeq) throws Exception {

        // renvoyer d’abord le cumulatif (baseSeq), puis quelques suivants

        List<Integer> keys = new ArrayList<>(inFlight.keySet());

        keys.sort(Comparator.comparingInt(k -> ((k - baseSeq + SEQ_MOD) % SEQ_MOD)));



        int sent = 0;

        for (int s : keys) {

            Segment seg = inFlight.get(s);

            if (seg == null) continue;



            seg.retransmitted = true;

            seg.sendTimeMs = System.currentTimeMillis();

            socket.send(new DatagramPacket(seg.raw, seg.raw.length, addr, port));

            System.out.println("[RETX] seq=" + seg.seq);

            if (++sent >= MAX_RETX_BURST) break;

        }

        if (sent > 1) System.out.println("[RETX-BURST] de=" + baseSeq + " compte=" + sent);

    }

}