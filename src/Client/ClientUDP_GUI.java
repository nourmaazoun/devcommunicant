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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

public class ClientUDP_GUI extends JFrame {

    private JPanel panelChat;
    private JTextField champMessage;
    private JButton btnEnvoyer, btnEnvoyerImage, btnEnvoyerFichier;
    private JList<String> listeClients;
    private DefaultListModel<String> modelClients;
    private JScrollPane scrollChat;

    private DatagramSocket socket;
    private InetAddress serveurAdresse;
    private int portServeur = 5000;
    private String pseudo;

    private final Map<String, ReassemblyClient> reassemblies = new ConcurrentHashMap<>();
    private final Map<String, ReassemblyFile> reassembliesFiles = new ConcurrentHashMap<>();

    public ClientUDP_GUI() {
        super("Client UDP – Chat");

        pseudo = JOptionPane.showInputDialog(this, "Entrez votre pseudo :", "Pseudo", JOptionPane.PLAIN_MESSAGE);
        if (pseudo == null || pseudo.trim().isEmpty()) pseudo = "Inconnu";
        setTitle("Chat – " + pseudo);

        try {
            socket = new DatagramSocket();
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
        btnEnvoyerFichier = new JButton("📎 Fichier");

        modelClients = new DefaultListModel<>();
        listeClients = new JList<>(modelClients);
        listeClients.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollClients = new JScrollPane(listeClients);
        scrollClients.setPreferredSize(new Dimension(150, 0));
        scrollClients.setBorder(BorderFactory.createTitledBorder("Utilisateurs"));

        JPanel panelBoutons = new JPanel(new FlowLayout());
        panelBoutons.add(btnEnvoyer);
        panelBoutons.add(btnEnvoyerImage);
        panelBoutons.add(btnEnvoyerFichier);

        JPanel bas = new JPanel(new BorderLayout());
        bas.add(champMessage, BorderLayout.CENTER);
        bas.add(panelBoutons, BorderLayout.EAST);

        add(scrollChat, BorderLayout.CENTER);
        add(scrollClients, BorderLayout.EAST);
        add(bas, BorderLayout.SOUTH);

        btnEnvoyer.addActionListener(e -> envoyer());
        champMessage.addActionListener(e -> envoyer());
        btnEnvoyerImage.addActionListener(e -> envoyerImage());
        btnEnvoyerFichier.addActionListener(e -> envoyerFichier());

        new Thread(this::recevoir).start();

        setSize(900, 600);
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

            envoyerBytes("IMG", fichier.getName(), imageBytes);

            final ImageIcon icon = new ImageIcon(img);
            final String filename = fichier.getName();
            SwingUtilities.invokeLater(() -> ajouterImage(pseudo, filename, icon));

            ajouterMessage("Envoi d'image : " + fichier.getName(), true);
        } catch (Exception e) {
            ajouterMessageErreur("Erreur envoi image : " + e.getMessage());
        }
    }

    private void envoyerFichier() {
        try {
            JFileChooser chooser = new JFileChooser();
            int res = chooser.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            File fichier = chooser.getSelectedFile();
            byte[] fileBytes = Files.readAllBytes(fichier.toPath());

            envoyerBytes("FILE", fichier.getName(), fileBytes);

            final String displayName = "<html><a href=''>" + fichier.getName() + "</a></html>";
            SwingUtilities.invokeLater(() -> ajouterLien(pseudo, displayName, fichier.getName(), fileBytes));

            ajouterMessage("Envoi fichier : " + fichier.getName(), true);
        } catch (Exception e) {
            ajouterMessageErreur("Erreur envoi fichier : " + e.getMessage());
        }
    }

    private void envoyerBytes(String type, String filename, byte[] bytes) throws IOException {
        final int CHUNK = 60000;
        int total = (bytes.length + CHUNK - 1) / CHUNK;
        String id = pseudo + "_" + System.currentTimeMillis();

        String destinataire = listeClients.getSelectedValue();
        boolean isPrivate = destinataire != null && !destinataire.equals(pseudo);

        for (int seq = 0; seq < total; seq++) {
            int start = seq * CHUNK;
            int end = Math.min(start + CHUNK, bytes.length);
            byte[] part = Arrays.copyOfRange(bytes, start, end);

            String header;
            if (isPrivate) {
                header = type + "|DEST|" + destinataire + "|" + id + "|" + filename + "|" + seq + "|" + total + "|";
            } else {
                header = type + "|" + id + "|" + filename + "|" + seq + "|" + total + "|";
            }

            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[headerBytes.length + part.length];
            System.arraycopy(headerBytes, 0, data, 0, headerBytes.length);
            System.arraycopy(part, 0, data, headerBytes.length, part.length);

            DatagramPacket paquet = new DatagramPacket(data, data.length, serveurAdresse, portServeur);
            socket.send(paquet);
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

                if (headerStr.startsWith("IMG|") || headerStr.startsWith("FILE|")) {
                    traiterBytesRecus(data, headerStr.startsWith("FILE|"));
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
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> ajouterMessageErreur("Erreur réception : " + e.getMessage()));
        }
    }

    private void traiterBytesRecus(byte[] data, boolean isFile) {
        try {
            String header = new String(data, 0, Math.min(data.length, 400), StandardCharsets.UTF_8);
            String[] parts = header.split("\\|");

            int idxData = 0;
            String id, filename, destPseudo = null;
            int seq, total;

            if (parts.length > 1 && "DEST".equals(parts[1])) {
                destPseudo = parts[2];
                if (!pseudo.equals(destPseudo)) return;

                id = parts[3];
                filename = parts[4];
                seq = Integer.parseInt(parts[5]);
                total = Integer.parseInt(parts[6]);

                int count = 0;
                for (int i = 0; i < data.length; i++) {
                    if (data[i] == '|') count++;
                    idxData = i + 1;
                    if (count == 7) break;
                }
            } else {
                id = parts[1];
                filename = parts[2];
                seq = Integer.parseInt(parts[3]);
                total = Integer.parseInt(parts[4]);

                int count = 0;
                for (int i = 0; i < data.length; i++) {
                    if (data[i] == '|') count++;
                    idxData = i + 1;
                    if (count == 5) break;
                }
            }

            byte[] partBytes = Arrays.copyOfRange(data, idxData, data.length);

            // EXTRACTION EXPÉDITEUR
            final String expediteur = id.split("_")[0];

            // 🔥🔥🔥 CORRECTION : éviter double affichage chez l’expéditeur 🔥🔥🔥
            if (expediteur.equals(pseudo)) return;

            if (isFile) {
                ReassemblyFile re = reassembliesFiles.computeIfAbsent(id, k -> new ReassemblyFile(total, filename));
                if (re.parts.length != total) re.parts = new byte[total][];
                if (re.parts[seq] == null) re.received++;
                re.parts[seq] = partBytes;

                if (re.received == total) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (byte[] p : re.parts) baos.write(p);
                    byte[] fileBytes = baos.toByteArray();

                    final String displayName = "<html><a href=''>" + filename + "</a></html>";
                    SwingUtilities.invokeLater(() -> ajouterLien(expediteur, displayName, filename, fileBytes));
                    reassembliesFiles.remove(id);
                }
            } else {
                ReassemblyClient re = reassemblies.computeIfAbsent(id, k -> new ReassemblyClient(total));
                if (re.parts.length != total) re.parts = new byte[total][];
                if (re.parts[seq] == null) re.received++;
                re.parts[seq] = partBytes;

                if (re.received == total) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (byte[] p : re.parts) baos.write(p);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
                    if (img != null) {
                        SwingUtilities.invokeLater(() -> ajouterImage(expediteur, filename, new ImageIcon(img)));
                    }
                    reassemblies.remove(id);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void ajouterMessage(String message, boolean estMoi) {

        // Un simple label, sans panneau, sans couleur de fond
        JLabel label = new JLabel(message);
        label.setFont(new Font("Arial", Font.PLAIN, 14));

        // Alignement : à droite pour toi, à gauche pour les autres
        label.setAlignmentX(estMoi ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);

        panelChat.add(label);
        panelChat.add(Box.createVerticalStrut(5)); // Petit espace entre les messages

        panelChat.revalidate();
        panelChat.repaint();
    }


    private void ajouterImage(String expediteur, String filename, ImageIcon icon) {
        JPanel panelMessage = new JPanel();
        panelMessage.setLayout(new BoxLayout(panelMessage, BoxLayout.Y_AXIS));
        panelMessage.setBackground(new Color(245, 245, 245));
        panelMessage.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JLabel labelPseudo = new JLabel(expediteur);
        labelPseudo.setFont(new Font("Arial", Font.BOLD, 12));
        labelPseudo.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel labelImage = new JLabel(icon);
        labelImage.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        labelImage.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel labelNomFichier = new JLabel(filename);
        labelNomFichier.setFont(new Font("Arial", Font.PLAIN, 12));
        labelNomFichier.setAlignmentX(Component.LEFT_ALIGNMENT);

        panelMessage.add(labelPseudo);
        panelMessage.add(Box.createVerticalStrut(5));
        panelMessage.add(labelImage);
        panelMessage.add(Box.createVerticalStrut(5));
        panelMessage.add(labelNomFichier);

        panelChat.add(panelMessage);
        panelChat.add(Box.createVerticalStrut(10));
        panelChat.revalidate();
        panelChat.repaint();
    }

    private void ajouterLien(String expediteur, String displayName, String filename, byte[] fileBytes) {
        JPanel panelMessage = new JPanel();
        panelMessage.setLayout(new BoxLayout(panelMessage, BoxLayout.Y_AXIS));
        panelMessage.setBackground(new Color(245, 245, 245));
        panelMessage.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JLabel labelPseudo = new JLabel(expediteur);
        labelPseudo.setFont(new Font("Arial", Font.BOLD, 12));
        labelPseudo.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel link = new JLabel(displayName);
        link.setFont(new Font("Arial", Font.BOLD, 12));
        link.setCursor(new Cursor(Cursor.HAND_CURSOR));
        link.setAlignmentX(Component.LEFT_ALIGNMENT);
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                try {
                    File tempFile = new File(System.getProperty("java.io.tmpdir"), filename);
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        fos.write(fileBytes);
                    }
                    Desktop.getDesktop().open(tempFile);
                } catch (IOException ex) {
                    ajouterMessageErreur("Impossible d'ouvrir le fichier : " + ex.getMessage());
                }
            }
        });

        panelMessage.add(labelPseudo);
        panelMessage.add(Box.createVerticalStrut(5));
        panelMessage.add(link);

        panelChat.add(panelMessage);
        panelChat.add(Box.createVerticalStrut(10));
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

    private static class ReassemblyFile {
        byte[][] parts;
        int received = 0;
        String filename;
        public ReassemblyFile(int total, String filename) {
            parts = new byte[total][];
            this.filename = filename;
        }
    }
}
