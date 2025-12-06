package Serveur;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

public class ServeurUDP extends JFrame {

    private final JPanel zoneAffichage = new JPanel();
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private final Map<String, Reassembly> reassemblies = new ConcurrentHashMap<>();
    private final Map<String, ReassemblyFile> reassembliesFiles = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ServeurUDP() {
        super("Serveur UDP – Chat + Images + Fichiers + Audio");
        zoneAffichage.setLayout(new BoxLayout(zoneAffichage, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(zoneAffichage);
        add(scroll, BorderLayout.CENTER);
        setSize(800, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setVisible(true);

        scheduler.scheduleAtFixedRate(this::purgeOld, 60, 60, TimeUnit.SECONDS);
        new Thread(this::lancerServeur, "UDP-Server-Thread").start();
    }

    private void append(String s) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = new JLabel(s);
            label.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            zoneAffichage.add(label);
            zoneAffichage.revalidate();
            zoneAffichage.repaint();
        });
    }

    private void lancerServeur() {
        final int PORT = 5000;
        append("Serveur en écoute sur le port " + PORT + "...");

        try (DatagramSocket socket = new DatagramSocket(PORT)) {
            socket.setReuseAddress(true);

            while (true) {
                byte[] buffer = new byte[65507];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                InetAddress addr = packet.getAddress();
                int port = packet.getPort();
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

                ClientInfo client = findClientByAddress(addr, port);

                // Nouveau client → pseudo (message initial)
                if (client == null) {
                    String pseudo = new String(data, StandardCharsets.UTF_8).trim();
                    if (pseudo.isEmpty()) pseudo = "Client";
                    String uniquePseudo = pseudo;
                    int i = 1;
                    while (clients.containsKey(uniquePseudo)) uniquePseudo = pseudo + "_" + i++;
                    client = new ClientInfo(addr, port, uniquePseudo);
                    clients.put(uniquePseudo, client);
                    append("Nouveau client connecté : " + uniquePseudo);
                    envoyerListeClients(socket);
                    continue;
                }

                // Détecter TYPE (IMG|FILE|AUDIO) — on lit d'abord le préfix
                String prefix = new String(data, 0, Math.min(6, data.length), StandardCharsets.UTF_8);
                if (prefix.startsWith("IMG|") || prefix.startsWith("FILE|") || prefix.startsWith("AUDIO|")) {
                    handleDataPacket(socket, client, data);
                    continue;
                }

                // Message texte
                String message = new String(data, StandardCharsets.UTF_8);
                if (message.startsWith("PUB|")) {
                    String txt = message.substring(4);
                    append(client.pseudo + " : " + txt);
                    broadcastText(socket, client.pseudo + " : " + txt);
                } else if (message.startsWith("PRIV|")) {
                    String[] parts = message.split("\\|", 3);
                    if (parts.length >= 3) {
                        String destName = parts[1];
                        String txt = parts[2];
                        ClientInfo dest = clients.get(destName);
                        if (dest != null) {
                            String finalMsg = "(Privé) " + client.pseudo + " : " + txt;
                            sendBytes(socket, finalMsg.getBytes(StandardCharsets.UTF_8), dest.adresse, dest.port);
                            append("(Privé) " + client.pseudo + " -> " + destName + " : " + txt);
                        } else {
                            append("Utilisateur introuvable : " + destName);
                            String err = "ERREUR|Utilisateur introuvable: " + destName;
                            sendBytes(socket, err.getBytes(StandardCharsets.UTF_8), client.adresse, client.port);
                        }
                    }
                } else {
                    append(client.pseudo + " : " + message);
                    broadcastText(socket, client.pseudo + " : " + message);
                }
            }

        } catch (Exception e) {
            append("Erreur serveur : " + e.getMessage());
            e.printStackTrace();
        } finally {
            scheduler.shutdownNow();
        }
    }

    private void handleDataPacket(DatagramSocket socket, ClientInfo sender, byte[] data) {
        try {
            // Determine type by reading prefix
            String possible = new String(data, 0, Math.min(7, data.length), StandardCharsets.UTF_8);
            String headerType;
            if (possible.startsWith("FILE|")) headerType = "FILE";
            else if (possible.startsWith("AUDIO|")) headerType = "AUDIO";
            else headerType = "IMG"; // default

            int idx = headerType.equals("FILE") ? 5 : headerType.equals("AUDIO") ? 6 : 4; // positions after "FILE|" "AUDIO|" "IMG|"

            // read fields using robust helper that returns next index
            FieldResult fr = readNextField(data, data.length, idx);
            String next = fr.field;
            idx = fr.nextIdx;

            boolean isPrivate = false;
            String destName = null;

            if ("DEST".equals(next)) {
                isPrivate = true;
                fr = readNextField(data, data.length, idx);
                destName = fr.field;
                idx = fr.nextIdx;
                // then read id...
                fr = readNextField(data, data.length, idx);
            }

            String id = fr.field;
            idx = fr.nextIdx;
            fr = readNextField(data, data.length, idx);
            String filename = fr.field;
            idx = fr.nextIdx;
            fr = readNextField(data, data.length, idx);
            String seqS = fr.field;
            idx = fr.nextIdx;
            fr = readNextField(data, data.length, idx);
            String totalS = fr.field;
            idx = fr.nextIdx;

            int seq = Integer.parseInt(seqS);
            int total = Integer.parseInt(totalS);
            byte[] chunk = Arrays.copyOfRange(data, idx, data.length);

            if (headerType.equals("IMG")) {
                append("Image reçue frag " + (seq + 1) + "/" + total + " (" + filename + ")");
                Reassembly re = reassemblies.computeIfAbsent(id, k -> new Reassembly(filename, total));
                re.put(seq, chunk);

                if (re.isComplete()) {
                    byte[] fullImg = re.assemble();
                    Path out = Paths.get("received_" + filename);
                    Files.write(out, fullImg);
                    append("Image complète sauvegardée : " + out.toAbsolutePath());

                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(fullImg));
                    if (img != null) {
                        SwingUtilities.invokeLater(() -> {
                            JPanel panelImage = new JPanel(new BorderLayout());
                            panelImage.setBorder(BorderFactory.createLineBorder(Color.BLACK));

                            JLabel label = new JLabel(new ImageIcon(img));
                            JLabel labelNom = new JLabel(filename, JLabel.CENTER);
                            labelNom.setFont(new Font("Arial", Font.BOLD, 12));

                            panelImage.add(labelNom, BorderLayout.NORTH);
                            panelImage.add(label, BorderLayout.CENTER);

                            zoneAffichage.add(panelImage);
                            zoneAffichage.revalidate();
                            zoneAffichage.repaint();
                        });
                    }

                    // send reassembled to destination(s)
                    if (isPrivate && destName != null) {
                        ClientInfo dest = clients.get(destName);
                        if (dest != null) sendDataToClient(socket, id, filename, fullImg, "IMG", dest);
                    } else {
                        sendDataToAll(socket, id, filename, fullImg, "IMG");
                    }

                    reassemblies.remove(id);
                }
            } else { // FILE or AUDIO
                append((headerType.equals("AUDIO") ? "Audio" : "Fichier") + " reçu frag " + (seq + 1) + "/" + total + " (" + filename + ")");
                ReassemblyFile re = reassembliesFiles.computeIfAbsent(id, k -> new ReassemblyFile(filename, total));
                re.put(seq, chunk);

                if (re.isComplete()) {
                    byte[] fullFile = re.assemble();
                    Path out = Paths.get("received_" + filename);
                    Files.write(out, fullFile);
                    append((headerType.equals("AUDIO") ? "Audio" : "Fichier") + " complet sauvegardé : " + out.toAbsolutePath());

                    // affichage / bouton utile côté serveur
                    SwingUtilities.invokeLater(() -> ajouterLien(filename, fullFile));

                    if (isPrivate && destName != null) {
                        ClientInfo dest = clients.get(destName);
                        if (dest != null) sendDataToClient(socket, id, filename, fullFile, headerType, dest);
                    } else {
                        sendDataToAll(socket, id, filename, fullFile, headerType);
                    }

                    reassembliesFiles.remove(id);
                }
            }

        } catch (Exception e) {
            append("Erreur réception données : " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void ajouterLien(String filename, byte[] fileBytes) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JLabel link = new JLabel("<html><a href=''>" + filename + "</a></html>");
        link.setFont(new Font("Arial", Font.BOLD, 12));
        link.setCursor(new Cursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                try {
                    File tempFile = new File(System.getProperty("java.io.tmpdir"), filename);
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        fos.write(fileBytes);
                    }
                    Desktop.getDesktop().open(tempFile);
                } catch (IOException ex) {
                    append("Impossible d'ouvrir le fichier : " + ex.getMessage());
                }
            }
        });

        panel.add(link, BorderLayout.CENTER);
        zoneAffichage.add(panel);
        zoneAffichage.revalidate();
        zoneAffichage.repaint();
    }

    private void sendDataToClient(DatagramSocket socket, String id, String filename, byte[] data, String type, ClientInfo dest) throws IOException {
        final int CHUNK = 60000;
        int total = (data.length + CHUNK - 1) / CHUNK;
        for (int seq = 0; seq < total; seq++) {
            int start = seq * CHUNK;
            int end = Math.min(start + CHUNK, data.length);
            byte[] part = Arrays.copyOfRange(data, start, end);
            String header = type + "|" + id + "|" + filename + "|" + seq + "|" + total + "|";
            byte[] head = header.getBytes(StandardCharsets.UTF_8);
            byte[] send = new byte[head.length + part.length];
            System.arraycopy(head, 0, send, 0, head.length);
            System.arraycopy(part, 0, send, head.length, part.length);
            sendBytes(socket, send, dest.adresse, dest.port);
        }
    }

    private void sendDataToAll(DatagramSocket socket, String id, String filename, byte[] data, String type) throws IOException {
        for (ClientInfo c : clients.values()) sendDataToClient(socket, id, filename, data, type, c);
    }

    // Helper returns both field and next index
    private FieldResult readNextField(byte[] data, int len, int idx) {
        if (idx >= len) return new FieldResult("", len);
        int i = idx;
        while (i < len && data[i] != '|') i++;
        String field = new String(data, idx, i - idx, StandardCharsets.UTF_8).trim();
        int nextIdx = (i < len && data[i] == '|') ? (i + 1) : i;
        return new FieldResult(field, nextIdx);
    }

    private void sendBytes(DatagramSocket socket, byte[] b, InetAddress addr, int port) {
        try {
            socket.send(new DatagramPacket(b, b.length, addr, port));
        } catch (Exception e) {
            append("Erreur envoi bytes : " + e.getMessage());
        }
    }

    private ClientInfo findClientByAddress(InetAddress addr, int port) {
        for (ClientInfo c : clients.values())
            if (c.adresse.equals(addr) && c.port == port) return c;
        return null;
    }

    private void envoyerListeClients(DatagramSocket socket) {
        StringBuilder sb = new StringBuilder();
        for (String p : clients.keySet()) sb.append(p).append(",");
        byte[] data = ("#LISTE#" + sb.toString()).getBytes(StandardCharsets.UTF_8);
        for (ClientInfo c : clients.values()) sendBytes(socket, data, c.adresse, c.port);
    }

    private void broadcastText(DatagramSocket socket, String msg) {
        byte[] outData = msg.getBytes(StandardCharsets.UTF_8);
        for (ClientInfo c : clients.values()) sendBytes(socket, outData, c.adresse, c.port);
    }

    private void purgeOld() {
        long now = System.currentTimeMillis();
        reassemblies.entrySet().removeIf(e -> now - e.getValue().createdAt > 120_000);
        reassembliesFiles.entrySet().removeIf(e -> now - e.getValue().createdAt > 120_000);
    }

    private static class ClientInfo {
        InetAddress adresse;
        int port;
        String pseudo;
        ClientInfo(InetAddress a, int p, String pseudo) { this.adresse = a; this.port = p; this.pseudo = pseudo; }
    }

    private static class Reassembly {
        final String filename;
        final int total;
        final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();
        final long createdAt = System.currentTimeMillis();
        Reassembly(String filename, int total) { this.filename = filename; this.total = total; }
        void put(int seq, byte[] data) { parts.put(seq, data); }
        boolean isComplete() { return parts.size() == total; }
        byte[] assemble() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < total; i++) {
                byte[] p = parts.get(i);
                if (p == null) throw new IOException("Fragment manquant: " + i + " pour " + filename);
                out.write(p);
            }
            return out.toByteArray();
        }
    }

    private static class ReassemblyFile {
        final String filename;
        final int total;
        final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();
        final long createdAt = System.currentTimeMillis();
        ReassemblyFile(String filename, int total) { this.filename = filename; this.total = total; }
        void put(int seq, byte[] data) { parts.put(seq, data); }
        boolean isComplete() { return parts.size() == total; }
        byte[] assemble() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < total; i++) {
                byte[] p = parts.get(i);
                if (p == null) throw new IOException("Fragment manquant: " + i + " pour " + filename);
                out.write(p);
            }
            return out.toByteArray();
        }
    }

    // Small helper to return field + next index
    private static class FieldResult {
        final String field;
        final int nextIdx;
        FieldResult(String f, int n) { this.field = f; this.nextIdx = n; }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ServeurUDP::new);
    }
}
