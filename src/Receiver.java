import java.net.*;

import java.util.*;



public class Receiver {



    /* =======================================================

       PARAMÈTRES GÉNÉRAUX

       ======================================================= */



    static final int SEQ_MOD = 65536;



    // --- Buffer réseau (tampon kernel simulé)

    static final int RX_BUFFER_BYTES = 512 * 1024; // 512 KB réseau

    static int rxUsed = 0; // EN OCTETS



    // --- Buffer application (ne limite PAS rwnd)

    // Simulation d'une app lente/variable

    static int appBufferUsed = 0;

    static final int APP_CONSUME_MS = 3;

    static final int APP_CONSUME_AMOUNT = 4;



    // --- ACK périodiques (pour maintenir fast retransmit)

    static final int ACK_PERIOD_MS = 20;



    // Adresse source

    static volatile InetAddress lastAddr = null;

    static volatile int lastPort = -1;



    // Suivi de séquence

    static volatile int expectedSeq;



    // Hors‑ordre : stockage des segments reçus mais non consécutifs

    static final TreeMap<Integer, byte[]> outOfOrder = new TreeMap<>();



    /* =======================================================

       UTILITAIRES POUR LES SÉQUENCES (modulo)

       ======================================================= */



    static int seqNext(int s) { return (s + 1) % SEQ_MOD; }



    static boolean seqLess(int a, int b) {

        return ((b - a + SEQ_MOD) % SEQ_MOD) < (SEQ_MOD / 2);

    }



    static boolean seqGreater(int a, int b) {

        return seqLess(b, a);

    }



    static int seqDist(int a, int b) {

        return (a - b + SEQ_MOD) % SEQ_MOD;

    }



    /* =======================================================

       SACK : construire la liste des blocs hors‑ordre

       Format :

       ack.data = [ rwnd_hi, rwnd_lo, <sack_hi, sack_lo>*2*N ]

       Chaque bloc = (start, end) inclusif

       ======================================================= */



    static byte[] buildAckData(int rwndBytes, List<int[]> sackBlocks) {



        int totalBytes = 2 + sackBlocks.size() * 4;

        byte[] data = new byte[totalBytes];



        data[0] = (byte)((rwndBytes >> 8) & 0xFF);

        data[1] = (byte)(rwndBytes & 0xFF);



        int idx = 2;

        for (int[] blk : sackBlocks) {

            int s = blk[0];

            int e = blk[1];

            data[idx++] = (byte)((s >> 8) & 0xFF);

            data[idx++] = (byte)(s & 0xFF);

            data[idx++] = (byte)((e >> 8) & 0xFF);

            data[idx++] = (byte)(e & 0xFF);

        }

        return data;

    }



    /* =======================================================

       MAIN

       ======================================================= */

    public static void main(String[] args) throws Exception {



        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);

        socket.setReceiveBufferSize(4 * 1024 * 1024);



        byte[] buffer = new byte[2048];



        System.out.println("Receiver avancé en écoute...");



        /* ================== HANDSHAKE ===================== */

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);



        lastAddr = dp.getAddress();

        lastPort = dp.getPort();



        Packet syn = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));



        expectedSeq = seqNext(syn.seq);



        Packet synAck = new Packet();

        synAck.seq = 0;

        synAck.ack = expectedSeq;

        synAck.flags = (byte)(Packet.FLAG_SYN | Packet.FLAG_ACK);



        int rwndBytes = RX_BUFFER_BYTES - rxUsed;

        synAck.data = new byte[]{

                (byte)((rwndBytes >> 8) & 0xFF),

                (byte)(rwndBytes & 0xFF)

        };



        socket.send(new DatagramPacket(

                PacketEncoder.encode(synAck),

                PacketEncoder.encode(synAck).length,

                lastAddr, lastPort));



        System.out.println("Connexion établie");



        /* ================== THREAD APPLICATION ===================== */

        Thread appConsumer = new Thread(() -> {

            while (true) {

                try { Thread.sleep(APP_CONSUME_MS); } catch (Exception ignored) {}



                int n = APP_CONSUME_AMOUNT;

                while (n-- > 0 && appBufferUsed > 0) {

                    appBufferUsed--;

                }

            }

        });

        appConsumer.setDaemon(true);

        appConsumer.start();



        /* ================== ACK PÉRIODIQUE ===================== */

        Timer ackTimer = new Timer(true);

        ackTimer.scheduleAtFixedRate(new TimerTask() {

            @Override

            public void run() {

                if (lastAddr == null) return;



                int rwndBytes = Math.max(0, RX_BUFFER_BYTES - rxUsed);



                Packet ack = new Packet();

                ack.flags = Packet.FLAG_ACK;

                ack.ack = expectedSeq;



                List<int[]> sack = computeSackBlocks();

                ack.data = buildAckData(rwndBytes, sack);



                try {

                    byte[] raw = PacketEncoder.encode(ack);

                    socket.send(new DatagramPacket(raw, raw.length, lastAddr, lastPort));

                } catch (Exception ignored) {}

            }

        }, ACK_PERIOD_MS, ACK_PERIOD_MS);



        /* ================== BOUCLE PRINCIPALE ===================== */

        while (true) {



            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);

            socket.receive(dpData);



            lastAddr = dpData.getAddress();

            lastPort = dpData.getPort();



            Packet p = PacketEncoder.decode(Arrays.copyOf(dpData.getData(), dpData.getLength()));



            int size = p.data.length;



            // Écriture dans le buffer réseau (rxUsed)

            if (rxUsed + size > RX_BUFFER_BYTES) {

                // Buffer réseau plein → ignorer (flow control fera son job)

                continue;

            }



            // Cas 1 : paquet attendu → délivrer immédiatement

            if (p.seq == expectedSeq) {

                deliverPacket(p);

                expectedSeq = seqNext(expectedSeq);



                // vider tout l’hors‑ordre consécutif

                while (outOfOrder.containsKey(expectedSeq)) {

                    byte[] chunk = outOfOrder.remove(expectedSeq);

                    deliverData(chunk);

                    expectedSeq = seqNext(expectedSeq);

                }

            }

            // Cas 2 : hors‑ordre futur → stocker

            else if (seqGreater(p.seq, expectedSeq)) {

                outOfOrder.put(p.seq, p.data);

                rxUsed += size;

            }

            // Cas 3 : déjà reçu → ignorer

            else {

                // nothing

            }



            // ACK immédiat avec SACK

            sendAck(socket);

        }

    }



    /* =======================================================

       RÉASSEMBLAGE & LIVRAISON

       ======================================================= */



    static void deliverPacket(Packet p) {

        deliverData(p.data);

    }



    static void deliverData(byte[] data) {

        // Décrément le buffer réseau

        rxUsed -= data.length;

        if (rxUsed < 0) rxUsed = 0;



        // Buffer “application”

        appBufferUsed += 1;

        if (appBufferUsed > 1000000) appBufferUsed = 1000000; // limite de sécurité

    }



    /* =======================================================

       CALCUL DES BLOCS SACK

       ======================================================= */



    static List<int[]> computeSackBlocks() {

        List<int[]> blocks = new ArrayList<>();



        if (outOfOrder.isEmpty()) return blocks;



        Integer prevStart = null;

        Integer prevEnd = null;



        for (Integer seq : outOfOrder.keySet()) {



            if (prevStart == null) {

                prevStart = seq;

                prevEnd = seq;

            }

            else if (seq == seqNext(prevEnd)) {

                prevEnd = seq;

            }

            else {

                blocks.add(new int[]{prevStart, prevEnd});

                prevStart = seq;

                prevEnd = seq;

            }

        }



        blocks.add(new int[]{prevStart, prevEnd});



        return blocks;

    }



    /* =======================================================

       ENVOI D’UN ACK IMMÉDIAT

       ======================================================= */



    static void sendAck(DatagramSocket socket) throws Exception {



        int rwndBytes = Math.max(0, RX_BUFFER_BYTES - rxUsed);



        Packet ack = new Packet();

        ack.flags = Packet.FLAG_ACK;

        ack.ack = expectedSeq;



        List<int[]> sack = computeSackBlocks();

        ack.data = buildAckData(rwndBytes, sack);



        byte[] raw = PacketEncoder.encode(ack);

        socket.send(new DatagramPacket(raw, raw.length, lastAddr, lastPort));

    }

}