package Client;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

public class ClientUDP_GUI extends JFrame {

    private JPanel panelChat;
    private JTextField champMessage;
    private JButton btnEnvoyer, btnEnvoyerImage;
    private JList<String> listeClients;
    private DefaultListModel<String> modelClients;
    private JScrollPane scrollChat;

    private DatagramSocket socket;
    private InetAddress serveurAdresse;
    private int portServeur = 5000;
    private String pseudo;

    private final Map<String, ReassemblyClient> reassemblies = new ConcurrentHashMap<>();

    public ClientUDP_GUI() {
        super("Client UDP – Chat");

        pseudo = JOptionPane.showInputDialog(this, "Entrez votre pseudo :", "Pseudo", JOptionPane.PLAIN_MESSAGE);
        if (pseudo == null || pseudo.trim().isEmpty()) pseudo = "Inconnu";
        setTitle("Chat – " + pseudo);

        try {
            socket = new DatagramSocket();
            // timeout utile pour ne pas rester bloqué indéfiniment si besoin
            socket.setSoTimeout(0);
            serveurAdresse = InetAddress.getByName("localhost");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Erreur socket: " + e.getMessage());
            System.exit(1);
        }

        panelChat = new JPanel();
        panelChat.setLayout(new BoxLayout(panelChat, BoxLayout.Y_AXIS));
        panelChat.setBackground(Color.WHITE);

        scrollChat = new JScrollPane(panelChat);
        scrollChat.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        champMessage = new JTextField();
        btnEnvoyer = new JButton("Envoyer");
        btnEnvoyerImage = new JButton("📷 Image");

        modelClients = new DefaultListModel<>();
        listeClients = new JList<>(modelClients);
        listeClients.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollClients = new JScrollPane(listeClients);
        scrollClients.setPreferredSize(new Dimension(150, 0));
        scrollClients.setBorder(BorderFactory.createTitledBorder("Utilisateurs"));

        JPanel panelBoutons = new JPanel(new FlowLayout());
        panelBoutons.add(btnEnvoyer);
        panelBoutons.add(btnEnvoyerImage);

        JPanel bas = new JPanel(new BorderLayout());
        bas.add(champMessage, BorderLayout.CENTER);
        bas.add(panelBoutons, BorderLayout.EAST);

        add(scrollChat, BorderLayout.CENTER);
        add(scrollClients, BorderLayout.EAST);
        add(bas, BorderLayout.SOUTH);

        btnEnvoyer.addActionListener(e -> envoyer());
        champMessage.addActionListener(e -> envoyer());
        btnEnvoyerImage.addActionListener(e -> envoyerImage());

        new Thread(this::recevoir).start();

        setSize(800, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setVisible(true);

        envoyerPseudo();
    }

    private void envoyerPseudo() {
        try {
            byte[] data = pseudo.getBytes(StandardCharsets.UTF_8);
            DatagramPacket paquet = new DatagramPacket(data, data.length, serveurAdresse, portServeur);
            socket.send(paquet);
        } catch (Exception e) {
            ajouterMessageErreur("Erreur envoi pseudo : " + e.getMessage());
        }
    }

    private void envoyer() {
        try {
            String msg = champMessage.getText().trim();
            if (msg.isEmpty()) return;

            String destinataire = listeClients.getSelectedValue();
            String messageComplet;
            if (destinataire != null && !destinataire.equals(pseudo)) {
                messageComplet = "PRIV|" + destinataire + "|" + msg;
                ajouterMessage("(Privé à " + destinataire + ") Moi : " + msg, true);
            } else {
                messageComplet = "PUB|" + msg;
                ajouterMessage("Moi : " + msg, true);
            }

            byte[] data = messageComplet.getBytes(StandardCharsets.UTF_8);
            DatagramPacket paquet = new DatagramPacket(data, data.length, serveurAdresse, portServeur);
            socket.send(paquet);

            champMessage.setText("");
        } catch (Exception e) {
            ajouterMessageErreur("Erreur envoi : " + e.getMessage());
        }
    }

    private void envoyerImage() {
        try {
            JFileChooser chooser = new JFileChooser();
            int res = chooser.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            File fichier = chooser.getSelectedFile();
            BufferedImage img = ImageIO.read(fichier);
            if (img == null) return;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();

            final int CHUNK = 60000;
            int total = (imageBytes.length + CHUNK - 1) / CHUNK;
            String id = pseudo + "_" + System.currentTimeMillis();

            String destinataire = listeClients.getSelectedValue();
            boolean isPrivate = destinataire != null && !destinataire.equals(pseudo);

            for (int seq = 0; seq < total; seq++) {
                int start = seq * CHUNK;
                int end = Math.min(start + CHUNK, imageBytes.length);
                byte[] part = Arrays.copyOfRange(imageBytes, start, end);

                String header;
                if (isPrivate) {
                    // Format pour image privée : IMG|DEST|<dest>|<id>|<filename>|<seq>|<total>|
                    header = "IMG|DEST|" + destinataire + "|" + id + "|" + fichier.getName() + "|" + seq + "|" + total + "|";
                } else {
                    // Format pour image publique : IMG|<id>|<filename>|<seq>|<total>|
                    header = "IMG|" + id + "|" + fichier.getName() + "|" + seq + "|" + total + "|";
                }

                byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
                byte[] data = new byte[headerBytes.length + part.length];
                System.arraycopy(headerBytes, 0, data, 0, headerBytes.length);
                System.arraycopy(part, 0, data, headerBytes.length, part.length);

                DatagramPacket paquet = new DatagramPacket(data, data.length, serveurAdresse, portServeur);
                socket.send(paquet);
            }

            ajouterMessage("Envoi d'image : " + fichier.getName() + (isPrivate ? " (privée à " + destinataire + ")" : ""), true);

        } catch (Exception e) {
            ajouterMessageErreur("Erreur envoi image : " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void recevoir() {
        try {
            byte[] buffer = new byte[65507];
            while (true) {
                DatagramPacket rep = new DatagramPacket(buffer, buffer.length);
                socket.receive(rep);
                byte[] data = Arrays.copyOf(rep.getData(), rep.getLength());
                String headerStr = new String(data, 0, Math.min(200, data.length), StandardCharsets.UTF_8);

                if (headerStr.startsWith("IMG|")) {
                    traiterImageRecue(data);
                } else if (headerStr.startsWith("#LISTE#")) {
                    String[] pseudos = headerStr.substring(7).split(",");
                    SwingUtilities.invokeLater(() -> {
                        modelClients.clear();
                        for (String p : pseudos) if (!p.trim().isEmpty()) modelClients.addElement(p.trim());
                    });
                } else {
                    String message = new String(data, StandardCharsets.UTF_8);
                    SwingUtilities.invokeLater(() -> ajouterMessage(message, false));
                }
            }
        } catch (SocketTimeoutException ste) {
            // timeout si configuré; on peut relancer la boucle
            recevoir();
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> ajouterMessageErreur("Erreur réception : " + e.getMessage()));
        }
    }

    private void traiterImageRecue(byte[] data) {
        try {
            // on lit jusqu'à 10 séparateurs '|' pour récupérer les champs du header
            String header = new String(data, 0, Math.min(data.length, 400), StandardCharsets.UTF_8);
            String[] parts = header.split("\\|");

            int startIdx;
            String id, filename, destPseudo = null;
            int seq, total;
            boolean isPrivate = false;

            if (parts.length > 1 && "DEST".equals(parts[1])) {
                // IMG|DEST|dest|id|filename|seq|total|...
                if (parts.length < 7) return;

                destPseudo = parts[2];
                if (!pseudo.equals(destPseudo)) return; // Si ce n'est pas pour moi, ignorer

                id = parts[3];
                filename = parts[4];
                seq = Integer.parseInt(parts[5]);
                total = Integer.parseInt(parts[6]);
                isPrivate = true;

                // index octet où le header s'arrête (compter 7 séparateurs)
                int count = 0, idxData = 0;
                for (int i = 0; i < data.length; i++) {
                    if (data[i] == '|') count++;
                    idxData = i + 1;
                    if (count == 7) break;
                }

                byte[] imgPart = Arrays.copyOfRange(data, idxData, data.length);
                ReassemblyClient re = reassemblies.computeIfAbsent(id, k -> new ReassemblyClient(total));
                if (re.parts.length != total) re.parts = new byte[total][];
                if (re.parts[seq] == null) re.received++;
                re.parts[seq] = imgPart;

                if (re.received == total) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (byte[] p : re.parts) baos.write(p);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
                    if (img != null) {
                        final String displayName = "(privé) " + destPseudo + " : " + filename;
                        SwingUtilities.invokeLater(() -> ajouterImage(displayName, new ImageIcon(img)));
                    }
                    reassemblies.remove(id);
                }

            } else {
                // IMG|id|filename|seq|total|...
                if (parts.length < 5) return;
                id = parts[1];
                filename = parts[2];
                seq = Integer.parseInt(parts[3]);
                total = Integer.parseInt(parts[4]);

                // index octet où le header s'arrête (compter 5 séparateurs)
                int count = 0, idxData = 0;
                for (int i = 0; i < data.length; i++) {
                    if (data[i] == '|') count++;
                    idxData = i + 1;
                    if (count == 5) break;
                }

                byte[] imgPart = Arrays.copyOfRange(data, idxData, data.length);
                ReassemblyClient re = reassemblies.computeIfAbsent(id, k -> new ReassemblyClient(total));
                if (re.parts.length != total) re.parts = new byte[total][];
                if (re.parts[seq] == null) re.received++;
                re.parts[seq] = imgPart;

                if (re.received == total) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (byte[] p : re.parts) baos.write(p);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
                    if (img != null) {
                        final String displayName = filename;
                        SwingUtilities.invokeLater(() -> ajouterImage(displayName, new ImageIcon(img)));
                    }
                    reassemblies.remove(id);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void ajouterMessage(String message, boolean estMoi) {
        JPanel panelMessage = new JPanel(new BorderLayout());
        panelMessage.setBackground(estMoi ? new Color(220, 240, 255) : new Color(240, 240, 240));
        panelMessage.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        JLabel label = new JLabel(message);
        panelMessage.add(label, BorderLayout.CENTER);
        panelChat.add(panelMessage);
        panelChat.revalidate();
        panelChat.repaint();
    }

    private void ajouterImage(String prefix, ImageIcon icon) {
        JPanel panelMessage = new JPanel(new BorderLayout());
        panelMessage.setBackground(new Color(245,245,245));
        panelMessage.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JLabel labelPseudo = new JLabel(prefix);
        labelPseudo.setFont(new Font("Arial", Font.BOLD, 12));

        JLabel labelImage = new JLabel(icon);
        labelImage.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));

        panelMessage.add(labelPseudo, BorderLayout.NORTH);
        panelMessage.add(labelImage, BorderLayout.CENTER);

        panelChat.add(panelMessage);
        panelChat.revalidate();
        panelChat.repaint();
    }

    private void ajouterMessageErreur(String message) {
        JPanel panelMessage = new JPanel(new BorderLayout());
        panelMessage.setBackground(new Color(255,220,220));
        panelMessage.setBorder(BorderFactory.createLineBorder(Color.RED));
        JLabel label = new JLabel(message);
        label.setForeground(Color.RED);
        panelMessage.add(label, BorderLayout.CENTER);
        panelChat.add(panelMessage);
        panelChat.revalidate();
        panelChat.repaint();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ClientUDP_GUI::new);
    }

    private static class ReassemblyClient {
        byte[][] parts;
        int received = 0;
        public ReassemblyClient(int total) { parts = new byte[total][]; }
    }
}
