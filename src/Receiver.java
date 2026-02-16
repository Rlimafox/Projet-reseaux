import java.net.*;

import java.util.*;



public class Receiver {



    static final int SEQ_MOD = 65536;



    // fenêtre annoncée : dynamique (limitée à 255 car 1 octet dans les ACK)

    static volatile int bufferMax = 32;

    static volatile int bufferUsed = 0;



    // Hors‑ordre : stockage temporaire

    static final Map<Integer, Packet> outOfOrder = new HashMap<>();



    // Consommation simulée (~50 segments/s)

    static final int CONSUME_MS = 2;



    // ACK périodiques pour éviter blocages

    static final int ACK_PERIOD_MS = 20;



    static volatile int expectedSeq;

    static volatile InetAddress lastAddr = null;

    static volatile int lastPort = -1;



    /*************** Utils modulo séquences ***************/

    static int seqNext(int seq) { return (seq + 1) % SEQ_MOD; }



    static boolean seqGreater(int a, int b) {

        return ((a - b + SEQ_MOD) % SEQ_MOD) < (SEQ_MOD / 2);

    }



    public static void main(String[] args) throws Exception {



        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);



        byte[] buffer = new byte[2048];



        System.out.println("Receiver en écoute...");



        /**************** HANDSHAKE ****************/

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);



        lastAddr = dp.getAddress();

        lastPort = dp.getPort();



        Packet syn = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));



        Packet synAck = new Packet();

        synAck.seq = 0;

        synAck.ack = seqNext(syn.seq);

        synAck.flags = (byte)(Packet.FLAG_SYN | Packet.FLAG_ACK);

        synAck.data = new byte[]{ (byte) bufferMax };



        byte[] synAckRaw = PacketEncoder.encode(synAck);

        socket.send(new DatagramPacket(synAckRaw, synAckRaw.length, lastAddr, lastPort));



        expectedSeq = seqNext(syn.seq);



        System.out.println("Connexion établie");



        /**************** THREAD consommation + adaptation ****************/

        Thread consumer = new Thread(() -> {

            while (true) {

                try { Thread.sleep(CONSUME_MS); } catch (Exception ignored) {}

                // consommer plusieurs segments par tick

                int toConsume = 4; // ajuster 2..8 selon tes tests

                while (toConsume-- > 0 && bufferUsed > 0) bufferUsed--;



                // adaptation 16..255 inchangée

                if (bufferUsed < bufferMax / 4 && bufferMax < 255) bufferMax++;

                else if (bufferUsed > 3 * bufferMax / 4 && bufferMax > 16) bufferMax--;

            }

        });



        consumer.setDaemon(true);

        consumer.start();



        /**************** ACK périodiques ****************/

        Timer ackTimer = new Timer(true);

        ackTimer.scheduleAtFixedRate(new TimerTask() {

            @Override

            public void run() {

                if (lastAddr == null || lastPort == -1) return;



                int rwnd = Math.max(0, bufferMax - bufferUsed);



                try {

                    Packet ack = new Packet();

                    ack.flags = Packet.FLAG_ACK;

                    ack.ack = expectedSeq;

                    ack.data = new byte[]{ (byte) rwnd };

                    byte[] raw = PacketEncoder.encode(ack);



                    socket.send(new DatagramPacket(raw, raw.length, lastAddr, lastPort));

                    //System.out.println("[ACK périodique] ack=" + expectedSeq + " rwnd=" + rwnd);

                } catch (Exception ignored) {}

            }

        }, ACK_PERIOD_MS, ACK_PERIOD_MS);



        /**************** BOUCLE PRINCIPALE ****************/

        while (true) {



            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);

            socket.receive(dpData);



            lastAddr = dpData.getAddress();

            lastPort = dpData.getPort();



            Packet p = PacketEncoder.decode(Arrays.copyOf(dpData.getData(), dpData.getLength()));



            /**************** LOGIQUE HORS‑ORDRE ****************/

            if (p.seq == expectedSeq) {

                // bon paquet → consommer

                expectedSeq = seqNext(expectedSeq);

                bufferUsed++;



                // vider les paquets suivants en attente

                while (outOfOrder.containsKey(expectedSeq)) {

                    outOfOrder.remove(expectedSeq);

                    expectedSeq = seqNext(expectedSeq);

                    bufferUsed++;

                }

            }

            else if (seqGreater(p.seq, expectedSeq)) {

                // futur → stocker hors ordre

                outOfOrder.put(p.seq, p);

            }

            else {

                // paquet déjà reçu → rien

            }



            /**************** ENVOI DE L’ACK IMMÉDIAT ****************/

            int rwnd = Math.max(0, bufferMax - bufferUsed);



            Packet ack = new Packet();

            ack.flags = Packet.FLAG_ACK;

            ack.ack = expectedSeq;                // ACK cumulatif

            ack.data = new byte[]{ (byte) rwnd }; // fenêtre annoncée



            byte[] ackRaw = PacketEncoder.encode(ack);

            socket.send(new DatagramPacket(ackRaw, ackRaw.length, lastAddr, lastPort));



            System.out.println("ACK envoyé | ack=" + ack.ack +

                    " | rwnd=" + rwnd +

                    " | bufUsed=" + bufferUsed +

                    " | bufMax=" + bufferMax +

                    " | outOfOrder=" + outOfOrder.size());

        }

    }

}