# Projet de Transfert Réseau

Projet d'implémentation d'un protocole personnalisé pour le transfert de fichiers via UDP avec contrôle de congestion.

## Prérequis

- **Java JDK** (version 8 ou supérieure)
- **Terminal/Invite de Commandes**

## Structure du projet

```
├── src/                    # Code source
│   ├── Packet.java
│   ├── PacketEncoder.java
│   ├── Sender.java        # Client (émetteur)
│   ├── Receiver.java      # Serveur (récepteur)
│   └── ...
├── bin/                   # Fichiers compilés (créé automatiquement)
├── compile.bat           # Script de compilation
├── run_receiver.bat      # Script pour lancer le serveur
├── run_sender.bat        # Script pour lancer le client
└── README.md
```

## Compilation

### Option 1 : Compilation automatique
Les scripts de lancement compilent automatiquement si nécessaire.

### Option 2 : Compilation manuelle
```cmd
compile.bat
```

## Utilisation

### Étape 1 : Lancer le Receiver (serveur)

```cmd
run_receiver.bat <port>
```

**Exemple :**
```cmd
run_receiver.bat 5000
```

### Étape 2 : Lancer le Sender (client)

Dans une autre invite de commandes :

```cmd
run_sender.bat <adresse_ip> <port> <fichier>
```

**Exemple :**
```cmd
run_sender.bat 127.0.0.1 5000 test.txt
```

## Cas d'usage complet

1. **Terminal 1 - Lancer le serveur :**
   ```cmd
   run_receiver.bat 5000
   ```
   Résultat attendu :
   ```
   Receiver en écoute...
   Connexion établie
   ACK envoye | ack=... | rwnd=...
   ...
   FIN recu, fermeture.
   ```

2. **Terminal 2 - Lancer le client :**
   ```cmd
   run_sender.bat 127.0.0.1 5000 test.txt
   ```
   Résultat attendu :
   ```
   Connexion établie
   [WINDOW] ACK | cwnd=... | ssthresh=... | rwnd=... | effective=... | inFlight=...
   ...
   Transfert terminé
   ```

## Compilation manuelle via javac

Si vous préférez compiler directement sans script :

```cmd
mkdir bin
javac -d bin src\*.java
```

Puis lancer :
```cmd
java -cp bin Receiver 5000
java -cp bin Sender 127.0.0.1 5000 test.txt
```

## Fichier de sortie

Le Receiver sauvegarde le fichier reçu dans : `src/test1go.txt`

## Résolution des problèmes

- **"javac is not recognized"** : Assurez-vous que Java JDK est installé et ajouté au PATH
- **"Port already in use"** : Changez le port utilisé (ex: 5001 au lieu de 5000)
- **"File not found"** : Vérifiez que le fichier à transférer existe et utilisez le chemin correct
