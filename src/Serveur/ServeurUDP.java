package Serveur;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
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

public class ServeurUDP extends JFrame {

	private final JPanel zoneAffichage = new JPanel(); // au lieu de JTextArea

    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private final Map<String, Reassembly> reassemblies = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ServeurUDP() {
        super("Serveur UDP – Chat + Images");
        zoneAffichage.setLayout(new BoxLayout(zoneAffichage, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(zoneAffichage);
        add(scroll, BorderLayout.CENTER);
        setSize(600, 420);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setVisible(true);

        scheduler.scheduleAtFixedRate(this::purgeOld, 60, 60, TimeUnit.SECONDS);
        new Thread(this::lancerServeur, "UDP-Server-Thread").start();
    }

    private void append(String s) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = new JLabel(s);
            label.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));
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

                // Nouveau client → pseudo
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

                // Image reçue
                if (data.length > 4 && data[0] == 'I' && data[1] == 'M' && data[2] == 'G' && data[3] == '|') {
                    handleImagePacket(socket, client, data);
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
    private void handleImagePacket(DatagramSocket socket, ClientInfo sender, byte[] data) {
        try {
            int idx = 4; // après "IMG|"
            String next = readNextField(data, data.length, idx);
            boolean isPrivate = false;
            String destName = null;

            if ("DEST".equals(next)) {
                isPrivate = true;
                idx += next.getBytes(StandardCharsets.UTF_8).length + 1;
                destName = readNextField(data, data.length, idx);
                idx += destName.getBytes(StandardCharsets.UTF_8).length + 1;
            }

            String id = readNextField(data, data.length, idx);
            idx += id.getBytes(StandardCharsets.UTF_8).length + 1;
            String filename = readNextField(data, data.length, idx);
            idx += filename.getBytes(StandardCharsets.UTF_8).length + 1;
            String seqS = readNextField(data, data.length, idx);
            idx += seqS.getBytes(StandardCharsets.UTF_8).length + 1;
            String totalS = readNextField(data, data.length, idx);
            idx += totalS.getBytes(StandardCharsets.UTF_8).length + 1;

            int seq = Integer.parseInt(seqS);
            int total = Integer.parseInt(totalS);
            byte[] chunk = Arrays.copyOfRange(data, idx, data.length);

            append("Image reçue frag " + (seq + 1) + "/" + total + " (" + filename + ")");

            Reassembly re = reassemblies.computeIfAbsent(id, k -> new Reassembly(filename, total));
            re.put(seq, chunk);

            if (re.isComplete()) {
                append("Image complète : " + filename);
                byte[] fullImg = re.assemble();
                Path out = Paths.get("received_" + filename);
                Files.write(out, fullImg);
                append("Image sauvegardée : " + out.toAbsolutePath());

                // ⚡ Affichage sur le serveur
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

                // ⚡ Transmission aux clients
                if (isPrivate && destName != null) {
                    ClientInfo dest = clients.get(destName);
                    if (dest != null) sendImageToClient(socket, id, filename, fullImg, dest);
                } else {
                    sendImageToAll(socket, id, filename, fullImg);
                }

                reassemblies.remove(id);
            }

        } catch (Exception e) {
            append("Erreur image : " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void sendImageToClient(DatagramSocket socket, String id, String filename, byte[] imageBytes, ClientInfo dest) throws IOException {
        final int CHUNK = 60000;
        int total = (imageBytes.length + CHUNK - 1) / CHUNK;
        for (int seq = 0; seq < total; seq++) {
            int start = seq * CHUNK;
            int end = Math.min(start + CHUNK, imageBytes.length);
            byte[] part = Arrays.copyOfRange(imageBytes, start, end);
            String header = "IMG|" + id + "|" + filename + "|" + seq + "|" + total + "|";
            byte[] head = header.getBytes(StandardCharsets.UTF_8);
            byte[] send = new byte[head.length + part.length];
            System.arraycopy(head, 0, send, 0, head.length);
            System.arraycopy(part, 0, send, head.length, part.length);
            sendBytes(socket, send, dest.adresse, dest.port);
        }
    }

    private void sendImageToAll(DatagramSocket socket, String id, String filename, byte[] imageBytes) throws IOException {
        for (ClientInfo c : clients.values()) sendImageToClient(socket, id, filename, imageBytes, c);
    }

    private String readNextField(byte[] data, int len, int idx) {
        if (idx >= len) return "";
        int i = idx;
        while (i < len && data[i] != '|') i++;
        return new String(data, idx, i - idx, StandardCharsets.UTF_8).trim();
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
                if (p == null) throw new IOException("Part manquante : " + i);
                out.write(p);
            }
            return out.toByteArray();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ServeurUDP::new);
    }
}
