import java.net.*;

import java.util.*;



public class Receiver {



    // --- Paramètres adaptatifs ---

    static volatile int bufferMax = 32;      // fenêtre annoncée (dynamique, 16..255)

    static volatile int bufferUsed = 0;      // occupation (en "segments")

    static final int SEQ_MOD = 65536;



    // Consommation "app" (~50 segments/s)

    static final int CONSUME_INTERVAL_MS = 20;



    // ACK périodiques pour éviter les blocages (dupACKs réguliers)

    static final int ACK_PERIOD_MS = 100;



    // Adresse/port du dernier expéditeur (pour ACK périodiques)

    static volatile InetAddress lastAddr = null;

    static volatile int lastPort = -1;



    // Prochain numéro de séquence attendu

    static volatile int expectedSeq;



    static int seqNext(int seq) {

        return (seq + 1) % SEQ_MOD;

    }



    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);



        byte[] buffer = new byte[2048];



        System.out.println("Receiver en écoute...");



        /******************** HANDSHAKE ********************/

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);



        lastAddr = dp.getAddress();  // mémorise l'expéditeur

        lastPort = dp.getPort();



        Packet syn = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));



        Packet synAck = new Packet();

        synAck.seq = 0;

        synAck.ack = seqNext(syn.seq);

        synAck.flags = (byte) (Packet.FLAG_SYN | Packet.FLAG_ACK);

        synAck.data = new byte[]{ (byte) bufferMax }; // annonce fenêtre initiale



        byte[] synAckRaw = PacketEncoder.encode(synAck);

        socket.send(new DatagramPacket(synAckRaw, synAckRaw.length, lastAddr, lastPort));



        expectedSeq = seqNext(syn.seq);



        System.out.println("Connexion établie");



        /******************** Thread "application" : consommation + adaptation ********************/

        Thread consumer = new Thread(() -> {

            while (true) {

                try {

                    Thread.sleep(CONSUME_INTERVAL_MS);

                } catch (InterruptedException ignored) {}



                // Simule la consommation de l'app

                if (bufferUsed > 0) bufferUsed--;



                // Adaptation simple (bornes : 16..255 car rwnd tient sur 1 octet)

                if (bufferUsed < bufferMax / 4 && bufferMax < 255) {

                    bufferMax++;

                } else if (bufferUsed > (3 * bufferMax) / 4 && bufferMax > 16) {

                    bufferMax--;

                }

            }

        });

        consumer.setDaemon(true);

        consumer.start();



        /******************** Timer d'ACK périodiques ********************/

        Timer ackTimer = new Timer(true);

        ackTimer.scheduleAtFixedRate(new TimerTask() {

            @Override

            public void run() {

                // On ne peut rien envoyer tant qu'on n'a pas l'adresse/port de l'émetteur

                if (lastAddr == null || lastPort == -1) return;



                int rwnd = Math.max(0, bufferMax - bufferUsed);



                try {

                    Packet ack = new Packet();

                    ack.flags = Packet.FLAG_ACK;

                    ack.ack  = expectedSeq;                // prochain attendu (cum ACK)

                    ack.data = new byte[]{ (byte) rwnd };  // fenêtre annoncée (0..255)



                    byte[] raw = PacketEncoder.encode(ack);

                    DatagramPacket dpAck = new DatagramPacket(raw, raw.length, lastAddr, lastPort);

                    socket.send(dpAck);



                    // (Log optionnel) System.out.println("[ACK périodique] ack=" + ack.ack + " | rwnd=" + rwnd);

                } catch (Exception ignored) {}

            }

        }, ACK_PERIOD_MS, ACK_PERIOD_MS);



        /******************** BOUCLE PRINCIPALE ********************/

        while (true) {

            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);

            socket.receive(dpData);



            // Mémorise à chaque paquet (utile si l'IP/port change, ou au démarrage)

            lastAddr = dpData.getAddress();

            lastPort = dpData.getPort();



            Packet p = PacketEncoder.decode(Arrays.copyOf(dpData.getData(), dpData.getLength()));



            // Go-Back-N strict : on n'accepte que le prochain attendu

            if (p.seq == expectedSeq) {

                expectedSeq = seqNext(expectedSeq);

                bufferUsed++;

            }

            // sinon : on jette, mais on renverra l'ACK "expectedSeq" (dupACK) juste après



            // Calcule la fenêtre restante et envoie l'ACK immédiatement

            int rwnd = Math.max(0, bufferMax - bufferUsed);



            Packet ack = new Packet();

            ack.flags = Packet.FLAG_ACK;

            ack.ack = expectedSeq;                 // ACK cumulatif = prochain attendu

            ack.data = new byte[]{ (byte) rwnd };  // fenêtre annoncée



            byte[] ackRaw = PacketEncoder.encode(ack);

            DatagramPacket dpAck = new DatagramPacket(ackRaw, ackRaw.length, lastAddr, lastPort);

            socket.send(dpAck);



            System.out.println("ACK envoyé | ack=" + ack.ack +

                    " | rwnd=" + rwnd +

                    " | bufUsed=" + bufferUsed +

                    " | bufMax=" + bufferMax);

        }

    }

}